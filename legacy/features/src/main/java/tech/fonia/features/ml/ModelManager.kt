package tech.fonia.features.ml

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.fonia.phones.ZipaModel
import tech.fonia.speech.SpeechModelFetcher
import tech.fonia.speech.SpeechModelFile
import tech.fonia.speech.SpeechModelManifest
import tech.fonia.speech.SpeechModelStore
import java.io.File
import java.net.URI

/**
 * One model the app fetches after install (docs/PLAN.md §6): pinned files, the revision they come from, and the file
 * its runtime opens. Nothing here is in the APK.
 */
data class ModelEntry(
    /** Stable name: the model's folder under the manager's root. */
    val id: String,
    /** The pinned revision. Another one replaces this folder; [ModelManager.sweep] removes the old one. */
    val version: String,
    val license: String,
    /** The file the runtime opens, inside the model folder. */
    val mainFile: String,
    val files: List<SpeechModelFile>,
) {
    /** What the download weighs, known before anything is fetched. */
    val byteCount: Long get() = files.sumOf { it.byteCount }

    /** Decimal megabytes rounded up, as the button that offers the download names them. */
    val megabytes: Long get() = (byteCount + 999_999L) / 1_000_000L

    /** `<id>@<version>`: what the pilot log records as the model of a score. */
    val label: String get() = "$id@$version"

    /** The Whisper store's manifest, named so that its folders land in `<id>/<version>`. */
    internal val manifest: SpeechModelManifest get() = SpeechModelManifest(name = "$id/$version", files = files)
}

/** The models the app knows. A model leaves the phone when it leaves this list ([ModelManager.sweep]). */
object ModelCatalog {
    /**
     * ZIPA-small CR-CTC 500k int8, the phone recogniser of :phones: the bytes and hash pinned in `ZipaModel.INT8`
     * (`phones/MODELS.md`), from Hugging Face at the pinned commit until Fonia serves the same file from its own server
     * ([baseUrl]). Only the network: the 127 tokens are built into `ZipaInventory`.
     */
    fun zipa(baseUrl: URI? = null): ModelEntry {
        val spec = ZipaModel.INT8
        val url = baseUrl?.let { URI(it.toString().trimEnd('/') + "/" + spec.fileName) } ?: URI(spec.url)
        return ModelEntry(
            id = "zipa-small-crctc-500k-int8",
            version = spec.url.substringAfter("/resolve/").substringBefore("/").take(12),
            license = spec.license,
            mainFile = spec.fileName,
            files = listOf(SpeechModelFile(url = url, path = spec.fileName, byteCount = spec.bytes, sha256 = spec.sha256)),
        )
    }

    val ZIPA: ModelEntry = zipa()

    val all: List<ModelEntry> = listOf(ZIPA)
}

/**
 * Keeps the models that come after install, each in `<root>/<id>/<version>`. It is the Whisper store
 * (`SpeechModelStore`) for a catalog of models, and reuses it for each one, so the rules are the same:
 *
 * - fetched only on unmetered networks (Wi-Fi, Ethernet): there is no switch for mobile data;
 * - every file checked against its pinned size and SHA-256 before it counts, then moved in whole;
 * - a download that stops resumes where it stopped (the fetcher keeps the bytes in the model's `.partial` folder);
 * - each model can be deleted from the settings, and nothing is left of it.
 *
 * In the app [root] is `noBackupFilesDir/FoniaModels`, which Android never backs up, like the Whisper folder;
 * [fetcher] is `HttpsModelFetcher` (plain GETs, no cookies, a neutral User-Agent). Nothing about the user is sent.
 */
class ModelManager(
    val root: File,
    val catalog: List<ModelEntry> = ModelCatalog.all,
    /** The network piece for one model, given the folder its unfinished bytes wait in. */
    private val fetcher: (partialFolder: File) -> SpeechModelFetcher,
) {
    private val locks = HashMap<String, Mutex>()

    private fun lock(entry: ModelEntry): Mutex = synchronized(locks) { locks.getOrPut(entry.id) { Mutex() } }

    /** Where the finished model lives. */
    fun folder(entry: ModelEntry): File = File(File(root, entry.id), entry.version)

    private fun partialFolder(entry: ModelEntry): File = File(File(root, entry.id), entry.version + ".partial")

    private fun store(entry: ModelEntry): SpeechModelStore =
        SpeechModelStore(manifest = entry.manifest, root = root, fetcher = fetcher(File(partialFolder(entry), ".fetch")))

    /** File sizes only (the hashes were checked when the files arrived), so a screen can ask on its way in. */
    fun isReady(entry: ModelEntry): Boolean = store(entry).isReady

    /** The file the runtime opens, or null while the model isn't all there. */
    fun mainFile(entry: ModelEntry): File? = if (isReady(entry)) File(folder(entry), entry.mainFile) else null

    /**
     * Fetches whatever is missing, on an unmetered network only. [progress] gets 0...1. Throws when it can't
     * (no allowed network, a bad answer, a hash that doesn't match); the next call resumes. Cancel to stop.
     */
    suspend fun download(entry: ModelEntry, progress: (Double) -> Unit = {}) {
        lock(entry).withLock {
            store(entry).download(allowsCellular = false) { progress(it.fraction) }
        }
    }

    /** Removes the model, finished or half-fetched. Whatever has it open must close it first. */
    suspend fun delete(entry: ModelEntry) {
        lock(entry).withLock {
            store(entry).delete()
            val modelRoot = File(root, entry.id)
            if (modelRoot.list()?.isEmpty() == true) modelRoot.delete()
        }
    }

    /** Bytes of [entry] on disk, finished or not. */
    fun bytesOnDisk(entry: ModelEntry): Long =
        listOf(folder(entry), partialFolder(entry)).sumOf { folder -> folder.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    /**
     * Removes what the catalog no longer names: older versions of a model and models that left the catalog, with
     * their unfinished downloads. Returns what it removed. Run it at start, before anything opens a model.
     */
    fun sweep(): List<File> {
        val removed = ArrayList<File>()
        val known = catalog.associateBy { it.id }
        for (item in root.listFiles().orEmpty()) {
            val entry = known[item.name]
            if (entry == null || !item.isDirectory) {
                if (item.deleteRecursively()) removed += item
                continue
            }
            for (version in item.listFiles().orEmpty()) {
                if (version.name == entry.version || version.name == entry.version + ".partial") continue
                if (version.deleteRecursively()) removed += version
            }
            if (item.list()?.isEmpty() == true) item.delete()
        }
        return removed
    }
}
