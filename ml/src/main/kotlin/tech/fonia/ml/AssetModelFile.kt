package tech.fonia.ml

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * A model file inside the APK, opened without a copy on disk (plan 02 §6): the asset is stored uncompressed
 * (`noCompress += "onnx"`), so `openFd` gives its offset in the APK and `FileChannel.map` gives a direct buffer ONNX
 * Runtime builds the session from. The mapping is file-backed: the kernel may drop its pages under pressure and read
 * them again, they never count as dirty memory.
 *
 * Fallback (a build that compressed the asset, or a runtime that cannot map it): copied once to
 * `noBackupFilesDir/models/<id>/<version>/<file>`, SHA-256 checked, then mapped from there.
 */
object AssetModelFile {
    class Opened(val buffer: ByteBuffer, val mapped: Boolean, val source: String)

    fun open(context: Context, entry: ModelEntry): Opened {
        try {
            context.assets.openFd(entry.asset).use { fd ->
                FileInputStream(fd.fileDescriptor).use { input ->
                    val buffer = input.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                    check(fd.declaredLength == entry.bytes) { "${entry.asset}: ${fd.declaredLength} bytes in the APK, the manifest says ${entry.bytes}" }
                    return Opened(buffer, mapped = true, source = "apk:${entry.asset}")
                }
            }
        } catch (compressed: IOException) {
            // openFd throws FileNotFoundException ("probably compressed") for a compressed asset: fall back to a copy.
        }
        val file = File(context.noBackupFilesDir, "models/${entry.id}/${entry.version}/${entry.asset.substringAfterLast('/')}")
        if (!(file.isFile && file.length() == entry.bytes && sha256(file) == entry.sha256)) {
            file.parentFile?.mkdirs()
            val partial = File(file.parentFile, file.name + ".part")
            context.assets.open(entry.asset).use { input -> partial.outputStream().use { input.copyTo(it, 1 shl 20) } }
            check(sha256(partial) == entry.sha256) { partial.delete(); "${entry.asset}: the copied bytes do not match the manifest's SHA-256" }
            if (!partial.renameTo(file)) error("cannot move $partial to $file")
        }
        RandomAccessFile(file, "r").use { raf ->
            return Opened(raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()), mapped = false, source = "copy:${file.path}")
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
