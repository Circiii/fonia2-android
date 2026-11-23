package tech.fonia.whisper

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tech.fonia.speech.FetchedFile
import tech.fonia.speech.LocalFileFetcher
import tech.fonia.speech.SpeechModelFetcher
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** No network the download may use: none at all, or only metered ones when cellular is not allowed. */
class NoAllowedNetworkException(val allowsCellular: Boolean) :
    IOException(if (allowsCellular) "no network" else "no unmetered network (Wi-Fi or Ethernet)")

/** Chooses the network a download runs on, or null when none is allowed. */
fun interface NetworkPicker {
    fun pick(allowsCellular: Boolean): Network?
}

/**
 * The store's network piece on Android: plain HTTPS GETs for the manifest's files and nothing else.
 *
 * - Like iOS's `allowsCellularAccess = false`, `allowsCellular = false` (the default everywhere) means
 *   unmetered networks only: Wi-Fi or Ethernet, never mobile data or a metered hotspot. The connection
 *   is bound to the network checked, so it cannot slide onto mobile data mid-file.
 * - No cache, no compression (byte offsets must be the file's), and a neutral User-Agent instead of
 *   Android's, which names the device model. HttpURLConnection sends cookies only if the app installs
 *   a CookieHandler, which Fonia does not.
 * - Resumes: bytes already fetched stay in [partialFolder] under a name derived from the URL (pinned
 *   revisions never change), and the next attempt asks only for the rest with a Range request. The
 *   store still checks every finished file's SHA-256 before installing it.
 *
 * @param partialFolder where unfinished files wait; [FoniaWhisper.modelStore] puts it inside the store's
 *   own `.partial` folder so that `SpeechModelStore.delete()` removes it too.
 */
class HttpsModelFetcher(
    private val partialFolder: File,
    private val networks: NetworkPicker,
) : SpeechModelFetcher {
    constructor(context: Context, partialFolder: File) : this(partialFolder, ConnectivityNetworkPicker(context))

    override suspend fun fetch(url: URI, allowsCellular: Boolean, onWrite: (Long) -> Unit): FetchedFile {
        // A model served from local files, as the store tests do.
        if (url.scheme == "file") return LocalFileFetcher.fetch(url, allowsCellular, onWrite)
        if (url.scheme != "https") throw IOException("refusing to fetch $url: https only")
        return withContext(Dispatchers.IO) {
            val network = networks.pick(allowsCellular) ?: throw NoAllowedNetworkException(allowsCellular)
            download(url, network, onWrite)
        }
    }

    private suspend fun download(url: URI, network: Network, onWrite: (Long) -> Unit): FetchedFile {
        Files.createDirectories(partialFolder.toPath())
        val partial = partialFile(partialFolder, url)
        var restarted = false
        while (true) {
            val have = if (partial.isFile) partial.length() else 0L
            val connection = network.openConnection(URL(url.toString())) as HttpURLConnection
            try {
                connection.useCaches = false
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "Fonia")
                if (have > 0) connection.setRequestProperty("Range", "bytes=$have-")
                val status = connection.responseCode
                val append = when {
                    status == HttpURLConnection.HTTP_PARTIAL && have > 0 && rangeStart(connection) == have -> true
                    status == HttpURLConnection.HTTP_OK -> false
                    // The part on disk is as long as the file or longer, or the server lost track: start over once.
                    status == RANGE_NOT_SATISFIABLE && have > 0 && !restarted -> {
                        partial.delete()
                        restarted = true
                        continue
                    }
                    // The store turns any status but 200 into SpeechModelStoreError.BadResponse.
                    else -> return FetchedFile(emptyFile(), status)
                }
                copyBody(connection, partial, append, onWrite)
                return FetchedFile(handOver(partial), HttpURLConnection.HTTP_OK)
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Appends the body to [partial], reporting the file's bytes so far; an interrupted body stays for the next attempt. */
    private suspend fun copyBody(connection: HttpURLConnection, partial: File, append: Boolean, onWrite: (Long) -> Unit) {
        var written = if (append) partial.length() else 0L
        var received = 0L
        onWrite(written)
        var reported = written
        connection.inputStream.use { input ->
            FileOutputStream(partial, append).use { output ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    written += n
                    received += n
                    if (written - reported >= REPORT_EVERY) {
                        onWrite(written)
                        reported = written
                    }
                }
                output.fd.sync()
            }
        }
        if (reported != written) onWrite(written)
        val expected = connection.getHeaderField("Content-Length")?.toLongOrNull()
        if (expected != null && received != expected) throw IOException("connection closed after $received of $expected bytes")
    }

    /** Moves the finished part to a fresh temporary file the caller owns, and drops the folder once empty. */
    private fun handOver(partial: File): File {
        val handed = File.createTempFile("fonia-speech-", ".download", partialFolder.parentFile ?: partialFolder)
        Files.move(partial.toPath(), handed.toPath(), StandardCopyOption.REPLACE_EXISTING)
        partialFolder.delete()
        return handed
    }

    private fun emptyFile(): File {
        val parent = partialFolder.parentFile ?: partialFolder
        Files.createDirectories(parent.toPath())
        return File.createTempFile("fonia-speech-", ".download", parent)
    }

    private fun rangeStart(connection: HttpURLConnection): Long? =
        connection.getHeaderField("Content-Range")
            ?.let { CONTENT_RANGE.find(it) }
            ?.groupValues?.get(1)?.toLongOrNull()

    companion object {
        private const val RANGE_NOT_SATISFIABLE = 416
        private const val REPORT_EVERY = 256L * 1024L
        private val CONTENT_RANGE = Regex("""bytes (\d+)-\d+/(\d+|\*)""")

        /** Where the unfinished bytes of [url] wait between attempts. */
        internal fun partialFile(folder: File, url: URI): File = File(folder, key(url) + ".part")

        private fun key(url: URI): String =
            MessageDigest.getInstance("SHA-256").digest(url.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.take(32)
    }
}

/** The default network if it is allowed, otherwise any connected network that is. */
class ConnectivityNetworkPicker(context: Context) : NetworkPicker {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun pick(allowsCellular: Boolean): Network? {
        fun allowed(network: Network): Boolean {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
            return allowsCellular || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        connectivity.activeNetwork?.takeIf(::allowed)?.let { return it }
        @Suppress("DEPRECATION")
        return connectivity.allNetworks.firstOrNull(::allowed)
    }
}
