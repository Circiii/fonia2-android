package tech.fonia.ml

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tech.fonia.live.Clip
import tech.fonia.phones.ZipaPhoneScorer
import tech.fonia.words.Heard
import tech.fonia.words.RoAsrModel
import java.util.concurrent.Executors

/** One clip for the word recogniser: the samples at their own rate. */
class RoAsrInput(val samples: FloatArray, val sampleRate: Double)

/** Where the time of the last clip went, milliseconds. */
data class RoAsrTimings(val loadMs: Double = 0.0, val featureMs: Double = 0.0, val modelMs: Double = 0.0, val audioSeconds: Double = 0.0) {
    /** Processing time over audio time: under 1 is faster than real time. */
    val realTimeFactor: Double get() = if (audioSeconds > 0) (featureMs + modelMs) / 1000.0 / audioSeconds else Double.NaN
}

/**
 * The Romanian word recogniser (Parakeet-Ro 110M, CTC, int8) on onnxruntime-android, beside [ZipaHost].
 *
 * It tells WHICH WORDS were said, with their times. It never judges a sound: in noise it writes the word it expects
 * (an S for a Ș), so the sound judges keep that job.
 *
 * - One thread, `ml-roasr`, owns the session. It is used after a take or a talk has ended, never while the
 *   microphone is live, so it does not compete with the audio thread.
 * - Lazy: nothing is loaded until the first clip (about 130 MB mapped from the APK). [trim] frees it.
 * - [hear] returns null on timeout, failure or a build without the model: every caller must go on without it.
 */
class RoAsrHost(
    override val version: ModelVersion,
    private val threads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4),
    private val loader: (threads: Int) -> RoAsrModel,
) : ModelHost<RoAsrInput, Heard?> {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ml-roasr").apply { isDaemon = true } }
    private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(HostState.Cold)
    override val state: StateFlow<HostState> = _state.asStateFlow()

    // Touched only on the host thread.
    private var model: RoAsrModel? = null

    @Volatile var lastTimings = RoAsrTimings()
        private set

    private fun ensureLoaded(): RoAsrModel {
        model?.let { return it }
        _state.value = HostState.Loading
        try {
            val t0 = System.nanoTime()
            val loaded = loader(threads)
            model = loaded
            lastTimings = lastTimings.copy(loadMs = (System.nanoTime() - t0) / 1e6)
            _state.value = HostState.Warm
            return loaded
        } catch (e: Throwable) {
            _state.value = HostState.Failed
            throw e
        }
    }

    override suspend fun warmUp() {
        scope.async {
            ensureLoaded().hear(FloatArray(8_000) { ((it * 7919 % 200) - 100) * 1e-6f })
            Unit
        }.await()
    }

    override suspend fun run(input: RoAsrInput): Heard? = scope.async {
        if (input.samples.isEmpty() || input.sampleRate <= 0) return@async null
        val loaded = ensureLoaded()
        val samples16k = if (input.sampleRate == 16_000.0) input.samples else ZipaPhoneScorer.to16k(Clip(input.samples, input.sampleRate))
        val heard = loaded.hear(samples16k)
        lastTimings = lastTimings.copy(
            featureMs = loaded.lastFeatureNanos / 1e6, modelMs = loaded.lastModelNanos / 1e6, audioSeconds = samples16k.size / 16_000.0,
        )
        heard
    }.await()

    /**
     * The words in [samples] within [timeoutMs], or null (timeout, no model, failure). Cancelling the caller only
     * stops the wait.
     */
    suspend fun hear(samples: FloatArray, sampleRate: Double, timeoutMs: Long): Heard? = try {
        withTimeoutOrNull(timeoutMs) { run(RoAsrInput(samples, sampleRate)) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    override fun trim() {
        scope.launch {
            model?.close()
            model = null
            if (_state.value != HostState.Failed) _state.value = HostState.Cold
        }
    }

    override fun close() {
        trim()
        scope.launch { scope.cancel() }
        executor.shutdown()
    }

    companion object {
        /**
         * The real host, or null when this APK was built without the recogniser (`-Pfonia.roasr=false`, or the model
         * was not in the PC cache). Cheap: nothing is read until the first clip.
         */
        fun create(context: Context, registry: ModelRegistry = ModelRegistry.load(context.assets)): RoAsrHost? {
            val app = context.applicationContext
            val entry = registry.find(ModelRegistry.ROASR) ?: return null
            return RoAsrHost(entry.modelVersion) { threads -> RoAsrModel.load(AssetModelFile.open(app, entry).buffer, threads) }
        }
    }
}
