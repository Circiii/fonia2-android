package tech.fonia.features.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.fonia.speech.SpeechModelStore
import tech.fonia.speech.StubTranscriber
import tech.fonia.speech.Transcriber
import tech.fonia.speech.WhisperEngine

/**
 * What [SpeechHub] needs from the speech model: on iOS a `SpeechModelStore` and the `WhisperEngine` it makes.
 * [StoreSpeechModel] wraps the real store of :whisper; [FakeSpeechModel] stands in for it with `foniaFakeSpeech`.
 */
interface SpeechModelPort {
    /** File sizes only: a fast check, on the way to a screen. */
    val isReady: Boolean

    /** What the download weighs, in decimal megabytes, for the sentence that offers it. */
    val megabytes: Long

    /** Fetches whatever is missing; [progress] gets 0...1. Throws when it can't. */
    suspend fun download(progress: (Double) -> Unit)

    suspend fun delete()

    /** Only called when [isReady]. */
    fun makeTranscriber(): Transcriber

    /** Frees the model's memory before its files go. */
    suspend fun unload(transcriber: Transcriber)
}

/**
 * The real store: `StoreSpeechModel(FoniaWhisper.modelStore(context))`, in `noBackupFilesDir/FoniaSpeech`, fetched
 * over HTTPS on Wi-Fi only and run by whisper.cpp. [megabytes] is `WhisperCppModel.megabytes()`.
 */
class StoreSpeechModel(private val store: SpeechModelStore, override val megabytes: Long) : SpeechModelPort {
    override val isReady: Boolean get() = store.isReady

    override suspend fun download(progress: (Double) -> Unit) = withContext(Dispatchers.IO) {
        store.download(allowsCellular = false) { progress(it.fraction) }
    }

    override suspend fun delete() = withContext(Dispatchers.IO) { store.delete() }

    /** Cheap: the engine loads the model on its first request. */
    override fun makeTranscriber(): Transcriber = store.makeEngine()

    override suspend fun unload(transcriber: Transcriber) {
        (transcriber as? WhisperEngine)?.unload()
    }
}

/** A model that downloads in a couple of seconds and answers with canned results (`StubTranscriber`). */
class FakeSpeechModel(ready: Boolean = false) : SpeechModelPort {
    @Volatile private var installed = ready

    override val isReady: Boolean get() = installed

    /** The size iOS names. */
    override val megabytes: Long = 147

    override suspend fun download(progress: (Double) -> Unit) {
        for (step in 1..20) {
            delay(100)
            progress(step / 20.0)
        }
        installed = true
    }

    override suspend fun delete() {
        installed = false
    }

    override fun makeTranscriber(): Transcriber = StubTranscriber()

    override suspend fun unload(transcriber: Transcriber) = Unit
}

/** The speech model is one optional download, so everything asks here whether it is ready. Main thread only. */
class SpeechHub(private val model: SpeechModelPort?, private val scope: CoroutineScope) {
    sealed interface State {
        data object Absent : State

        data class Downloading(val fraction: Double) : State

        data object Ready : State

        data object Failed : State
    }

    var state: State by mutableStateOf(if (model?.isReady == true) State.Ready else State.Absent)
        private set

    /** The size of the download; iOS's 147 MB when there is no model at all. */
    val megabytes: Long get() = model?.megabytes ?: 147

    private var engine: Transcriber? = null

    val transcriber: Transcriber?
        get() {
            val model = model
            if (state != State.Ready || model == null) return null
            engine?.let { return it }
            val made = model.makeTranscriber()
            engine = made
            return made
        }

    fun download() {
        val model = model ?: return
        state = State.Downloading(0.0)
        scope.launch {
            try {
                model.download { fraction -> scope.launch(Dispatchers.Main) { if (state is State.Downloading) state = State.Downloading(fraction) } }
                state = State.Ready
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state = State.Failed
            }
        }
    }

    fun remove() {
        val model = model ?: return
        val engine = engine
        this.engine = null
        state = State.Absent
        scope.launch {
            engine?.let { model.unload(it) }
            runCatching { model.delete() }
        }
    }
}
