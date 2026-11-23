package tech.fonia.whisper

import android.content.Context
import tech.fonia.speech.SpeechModelManifest
import tech.fonia.speech.SpeechModelStore
import java.io.File

/**
 * What `:features` needs to give SpeechHub the model, as `SpeechModelStore()` and `WhisperEngine(modelFolder:)`
 * do on iOS:
 *
 * ```
 * val store = FoniaWhisper.modelStore(context)
 * store.isReady                                   // sizes only, cheap enough for a screen
 * WhisperCppModel.megabytes()                     // the size the button shows before anything is fetched
 * store.download { progress -> }                  // Wi-Fi or Ethernet only, resumable; cancel the coroutine to stop
 * val engine = store.makeEngine()                 // tech.fonia.speech.WhisperEngine, a Transcriber
 * store.delete()                                  // after engine.unload()
 * ```
 */
object FoniaWhisper {
    /** `Application Support/FoniaSpeech` on iOS: a folder Android's Auto Backup never copies, so nothing needs excluding. */
    fun defaultRoot(context: Context): File = File(context.noBackupFilesDir, "FoniaSpeech")

    /**
     * The model store over [root], fetching with [HttpsModelFetcher] and loading with [WhisperCppLoader].
     * The fetcher keeps unfinished files inside the store's own `.partial` folder, so `delete()` clears them.
     */
    fun modelStore(
        context: Context,
        manifest: SpeechModelManifest = WhisperCppModel.WHISPER_BASE_FROM_HUGGING_FACE,
        root: File = defaultRoot(context),
        loader: WhisperCppLoader = WhisperCppLoader(),
    ): SpeechModelStore {
        val partialFolder = File(File(root, manifest.name + ".partial"), ".fetch")
        return SpeechModelStore(manifest = manifest, root = root, fetcher = HttpsModelFetcher(context.applicationContext, partialFolder), loader = loader)
    }
}
