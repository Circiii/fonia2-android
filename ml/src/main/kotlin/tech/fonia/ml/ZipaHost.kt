package tech.fonia.ml

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import tech.fonia.judge.JudgeRequest
import tech.fonia.judge.Level
import tech.fonia.judge.PhoneEvidence
import tech.fonia.judge.PhoneScorerPort
import tech.fonia.judge.Position
import tech.fonia.live.Clip
import tech.fonia.model.SoundId
import tech.fonia.phones.AcousticModel
import tech.fonia.phones.ZipaModel
import tech.fonia.phones.ZipaPhoneScorer
import java.util.concurrent.Executors

/** One take for ZIPA: the samples at their own rate (resampled to 16 kHz on the host's thread) and what was asked. */
class ZipaInput(val samples: FloatArray, val sampleRate: Double, val request: JudgeRequest) {
    constructor(clip: Clip, request: JudgeRequest) : this(clip.samples, clip.sampleRate, request)
}

/** Where the time of the last take went, milliseconds (the debug screen of plan 03 §5 reads it). */
data class ZipaTimings(val loadMs: Double = 0.0, val resampleMs: Double = 0.0, val modelMs: Double = 0.0, val scoringMs: Double = 0.0) {
    val totalMs: Double get() = resampleMs + modelMs + scoringMs
}

data class ZipaHostConfig(
    /** ONNX Runtime intra-op threads: half the cores, 2 to 4 (plan 03 §5 (a)). */
    val intraOpThreads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4),
    /** The full head's budget after the take ends (N6: ≤ 600 ms); past it the fast head stands. */
    val timeoutMs: Long = 600,
    val scorer: ZipaPhoneScorer.Config = ZipaPhoneScorer.Config(),
)

/**
 * ZIPA on onnxruntime-android behind the judge's [PhoneScorerPort] (plan 02 §6 `ZipaHost`, plan 03 §5).
 *
 * - One thread, `ml-zipa`, owns the session; takes queue behind each other (capacity 1 in practice: one take at a time).
 * - Lazy: nothing is loaded until [warmUp] or the first [run]. Load = map the asset ([AssetModelFile]) + create the
 *   session off the main thread; warm-up = one inference on half a second of near-silence.
 * - [trim] closes the session on the host thread after any running take; the next take loads again (≈ 1–3 s).
 * - [score] returns null on timeout or failure, so the caller keeps the fast head: the take is never blocked.
 *
 * `loader` builds the acoustic model; [create] wires the real one from the APK's assets.
 */
class ZipaHost(
    override val version: ModelVersion,
    private val config: ZipaHostConfig = ZipaHostConfig(),
    private val loader: (threads: Int) -> Pair<AcousticModel, AutoCloseable>,
) : ModelHost<ZipaInput, PhoneEvidence?> {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ml-zipa").apply { isDaemon = true } }
    private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(HostState.Cold)
    override val state: StateFlow<HostState> = _state.asStateFlow()

    // Touched only on the host thread.
    private var scorer: ZipaPhoneScorer? = null
    private var acoustic: AcousticModel? = null
    private var closeable: AutoCloseable? = null

    @Volatile var lastTimings = ZipaTimings()
        private set

    private fun ensureLoaded(): ZipaPhoneScorer {
        scorer?.let { return it }
        _state.value = HostState.Loading
        try {
            val t0 = System.nanoTime()
            val (model, handle) = loader(config.intraOpThreads)
            closeable = handle
            acoustic = model
            val s = ZipaPhoneScorer(model, config.scorer, modelName = version.tag)
            scorer = s
            lastTimings = lastTimings.copy(loadMs = (System.nanoTime() - t0) / 1e6)
            _state.value = HostState.Warm
            return s
        } catch (e: Throwable) {
            _state.value = HostState.Failed
            throw e
        }
    }

    override suspend fun warmUp() {
        scope.async {
            val s = ensureLoaded()
            val quiet = FloatArray(8_000) { ((it * 7919 % 200) - 100) * 1e-6f }
            s.score(quiet, JudgeRequest(SoundId.S, Level.Word, "sare", Position.Initial))
            Unit
        }.await()
    }

    /**
     * The vowels the phone model hears in [samples] (any rate), as seconds from the start: one per syllable. This is
     * the speech coach's speaking rate. Null when the model is missing or fails. Runs on the host thread.
     */
    suspend fun vowelOnsets(samples: FloatArray, sampleRate: Double): DoubleArray? = try {
        scope.async {
            ensureLoaded()
            val model = acoustic ?: return@async null
            val samples16k = if (sampleRate == 16_000.0) samples else ZipaPhoneScorer.to16k(Clip(samples, sampleRate))
            val posteriors = model.posteriors(samples16k)
            if (posteriors.frames == 0) return@async DoubleArray(0)
            val onsets = IntArray(posteriors.frames)
            val n = tech.fonia.phones.VowelNuclei.onsets(posteriors, onsets)
            val secondsPerFrame = samples16k.size / 16_000.0 / posteriors.frames
            DoubleArray(n) { onsets[it] * secondsPerFrame }
        }.await()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    override suspend fun run(input: ZipaInput): PhoneEvidence? = scope.async { score(input) }.await()

    /** On the host thread. */
    private fun score(input: ZipaInput): PhoneEvidence? {
        if (input.samples.isEmpty() || input.sampleRate <= 0) return null
        val s = ensureLoaded()
        val t0 = System.nanoTime()
        val samples16k = ZipaPhoneScorer.to16k(Clip(input.samples, input.sampleRate))
        val t1 = System.nanoTime()
        val evidence = s.score(samples16k, input.request)
        lastTimings = lastTimings.copy(
            resampleMs = (t1 - t0) / 1e6, modelMs = s.lastModelMicros / 1e3, scoringMs = s.lastScoringMicros / 1e3,
        )
        return evidence
    }

    /**
     * The full head's evidence for one take within [timeoutMs], or null (timeout, no model, failure): the caller then
     * keeps the fast verdict. Cancelling the caller only stops the wait.
     */
    suspend fun score(clip: Clip, request: JudgeRequest, timeoutMs: Long = config.timeoutMs): PhoneEvidence? = try {
        withTimeoutOrNull(timeoutMs) { run(ZipaInput(clip, request)) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    /** A blocking [PhoneScorerPort] (16 kHz in, as the port says), with the same timeout; for callers without coroutines. */
    fun port(timeoutMs: Long = config.timeoutMs): PhoneScorerPort = PhoneScorerPort { samples16k, request ->
        runBlocking {
            try {
                withTimeoutOrNull(timeoutMs) { run(ZipaInput(samples16k, ZipaPhoneScorer.RATE, request)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
        }
    }

    override fun trim() {
        scope.launch {
            closeable?.close()
            closeable = null
            scorer = null
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
         * The real host: ZIPA from the APK's assets (`models/manifest.json` → `models/zipa-small-crctc-500k/model.int8.onnx`),
         * mapped, not copied. Cheap: nothing is read until [warmUp] or the first take.
         */
        fun create(context: Context, config: ZipaHostConfig = ZipaHostConfig(), registry: ModelRegistry = ModelRegistry.load(context.assets)): ZipaHost {
            val app = context.applicationContext
            val entry = registry.entry(ModelRegistry.ZIPA)
            return ZipaHost(entry.modelVersion, config) { threads ->
                val opened = AssetModelFile.open(app, entry)
                val model = ZipaModel.load(opened.buffer, threads)
                model to model
            }
        }
    }
}
