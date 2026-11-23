package tech.fonia.features.ml

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tech.fonia.audio.CapturedClip
import tech.fonia.features.pilot.PilotMath
import tech.fonia.features.pilot.ShadowContext
import tech.fonia.features.pilot.ShadowRecord
import tech.fonia.features.pilot.ShadowStatus
import tech.fonia.features.pilot.SoundPosition
import tech.fonia.phones.PhoneScorer
import tech.fonia.phones.ScoringPolicy
import tech.fonia.phones.TargetSound
import tech.fonia.phones.WordScore
import tech.fonia.phones.ZipaModel
import tech.fonia.speech.SpeechResampler
import tech.fonia.store.Attempt
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.log10
import kotlin.math.roundToInt

/** The phone scorer as shadow mode uses it: one take in, one score out. The tests put a fake here. */
fun interface PhoneScoring {
    fun score(samples16k: FloatArray, text: String, sound: TargetSound): WordScore
}

/** Opens the scorer when a take needs it and closes it when nobody practises. Used on the shadow thread only. */
interface PhoneScoringSource {
    /** The scorer, loading its model if needed; null when the model isn't on the phone. */
    fun open(): PhoneScoring?

    /** Frees the model's memory. */
    fun close()

    /** What the log records as the model and the thresholds of a score. */
    val model: String?
    val policy: String?
}

/**
 * ZIPA-small int8 through `PhoneScorer`, loaded from the model manager's file on first use. One ONNX Runtime thread:
 * the scorer shares the phone with the capture and the live analysis, and nobody waits for it.
 */
class ZipaScoringSource(
    private val modelFile: () -> File?,
    private val scoringPolicy: ScoringPolicy = ScoringPolicy(),
    private val threads: Int = 1,
    override val model: String = ModelCatalog.ZIPA.label,
) : PhoneScoringSource {
    private var zipa: ZipaModel? = null
    private var scorer: PhoneScorer? = null

    override val policy: String = scoringPolicy.label

    override fun open(): PhoneScoring? {
        val ready = scorer ?: run {
            val file = modelFile() ?: return null
            val loaded = ZipaModel.load(file, intraOpThreads = threads)
            zipa = loaded
            PhoneScorer(loaded, scoringPolicy).also { scorer = it }
        }
        return PhoneScoring { samples, text, sound -> ready.score(samples, text, sound) }
    }

    override fun close() {
        runCatching { zipa?.close() }
        zipa = null
        scorer = null
    }
}

/** The thresholds a score was decided with, in one line. */
val ScoringPolicy.label: String
    get() = "correct lpr>=$acceptLpr, tryAgain lpr<=$rejectLpr, fit>=$minFitPerLabel, frames>=$minFrames, tap ${if (tapAccepted) "correct" else "alternative"}"

/**
 * One take for the shadow scorer: the audio TakeJudge just judged and what the child was told. The samples are held only
 * until the take is scored, and never written anywhere.
 */
class ShadowTake(
    val profileId: UUID,
    val sessionId: UUID?,
    val take: String,
    val context: ShadowContext,
    /** The item as the content writes it ("sare", "sa"). */
    val text: String,
    val sound: String,
    val target: TargetSound,
    val position: SoundPosition,
    val level: String?,
    val feedback: String?,
    /** TakeJudge's verdict, as the session records it. */
    val shown: Attempt.Outcome,
    val clip: CapturedClip,
    val narrowBand: Boolean,
    val input: String,
    val dsp: String,
    val at: Instant = Instant.now(),
    internal val submittedNanos: Long = System.nanoTime(),
)

/** What the developer badge shows: the verdicts compared so far, as 03 §4.2 reads agreement (decisions only). */
data class ShadowTally(
    val compared: Int = 0,
    val agreed: Int = 0,
    /** TakeJudge said "not heard" or left it to a person. */
    val phoneAbstained: Int = 0,
    /** ZIPA said "nu am auzit clar". */
    val zipaAbstained: Int = 0,
    /** Dropped, failed or no model. */
    val notScored: Int = 0,
) {
    operator fun minus(start: ShadowTally) = ShadowTally(
        compared - start.compared, agreed - start.agreed, phoneAbstained - start.phoneAbstained,
        zipaAbstained - start.zipaAbstained, notScored - start.notScored,
    )
}

/**
 * Shadow mode (03 §1.2 P13, docs/PLAN.md §6): after each production take TakeJudge has judged, the same audio is scored
 * again with ZIPA on a thread of its own, and both verdicts go to the pilot log ([sink]). It is built so that it can't
 * change what the child sees or hears:
 *
 * - [submit] only queues and returns: no waiting, no lock held by the scorer, nothing thrown;
 * - the verdict is TakeJudge's, decided before the take gets here, and nothing here can reach it;
 * - the queue is short ([capacity]): when takes come faster than the scorer, the oldest waiting one is dropped (and
 *   logged as dropped), so the memory held stays a couple of takes;
 * - the scorer runs at the lowest thread priority, one ONNX Runtime thread, and is closed after [idleMillis] without
 *   takes, to give its memory back;
 * - the samples are dropped once scored, and never written.
 */
class ShadowMode(
    private val source: PhoneScoringSource,
    private val sink: (ShadowRecord) -> Unit,
    private val dispatcher: CoroutineDispatcher = newShadowThread(),
    private val capacity: Int = 2,
    private val idleMillis: Long = 30_000,
) : AutoCloseable {
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error -> log.log(Level.WARNING, "shadow mode", error) },
    )
    private val pending = ArrayDeque<ShadowTake>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val busy = AtomicInteger(0)

    private val latestRecord = MutableStateFlow<ShadowRecord?>(null)

    /** The last take scored, for the developer badge. */
    val latest: StateFlow<ShadowRecord?> = latestRecord.asStateFlow()

    private val tallyState = MutableStateFlow(ShadowTally())

    /** Agreement since the app started. */
    val tally: StateFlow<ShadowTally> = tallyState.asStateFlow()

    init {
        scope.launch { work() }
    }

    /**
     * Queues [take] for scoring and returns at once. False when the queue was full and an older take was dropped to
     * make room. Never throws.
     */
    fun submit(take: ShadowTake): Boolean {
        val dropped = try {
            synchronized(pending) {
                val oldest = if (pending.size >= capacity) pending.removeFirst() else null
                pending.addLast(take)
                oldest
            }
        } catch (_: Throwable) {
            return false
        }
        wake.trySend(Unit)
        // On the caller's thread, so nothing that reads the samples: the dropped take's row has no SNR.
        if (dropped != null) deliver(dropped.record(ShadowStatus.Dropped, waitMs = elapsedMs(dropped), measure = false))
        return dropped == null
    }

    private suspend fun work() {
        while (true) {
            val next = synchronized(pending) { pending.removeFirstOrNull()?.also { busy.incrementAndGet() } }
            if (next == null) {
                if (withTimeoutOrNull(idleMillis) { wake.receive() } == null) {
                    // Nobody has practised for a while: give the model's memory back. It loads again for the next take.
                    runCatching { source.close() }
                    wake.receive()
                }
                continue
            }
            try {
                deliver(score(next))
            } finally {
                busy.decrementAndGet()
            }
        }
    }

    private fun score(take: ShadowTake): ShadowRecord {
        val waitMs = elapsedMs(take)
        return try {
            val opening = System.nanoTime()
            val scorer = source.open() ?: return take.record(ShadowStatus.Unavailable, waitMs)
            // Loading the model, on the first take after a pause, is timed apart from scoring.
            val loadMs = (System.nanoTime() - opening) / 1_000_000
            val started = System.nanoTime()
            val samples = SpeechResampler.resample(take.clip.samples, take.clip.sampleRate, SpeechResampler.WHISPER_RATE)
            val score = scorer.score(samples, take.text, take.target)
            take.record(ShadowStatus.Scored, waitMs, score, (System.nanoTime() - started) / 1_000_000, source.model, source.policy, loadMs = loadMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: LinkageError) {
            // No native runtime for this phone's processor.
            take.record(ShadowStatus.Unavailable, waitMs)
        } catch (error: Throwable) {
            log.log(Level.WARNING, "shadow score failed", error)
            // Out of memory or a model that won't run: close it, so the next take starts clean.
            runCatching { source.close() }
            take.record(ShadowStatus.Failed, waitMs)
        }
    }

    private fun deliver(record: ShadowRecord) {
        try {
            sink(record)
        } catch (_: Throwable) {
        }
        if (record.status == ShadowStatus.Scored) latestRecord.value = record
        tallyState.update { tally ->
            val phone = PilotMath.shownAsVerdict(record.shown.rawValue)
            val zipa = record.verdict?.let(PilotMath::zipaAsVerdict)
            when {
                record.status != ShadowStatus.Scored -> tally.copy(notScored = tally.notScored + 1)
                phone == null -> tally.copy(phoneAbstained = tally.phoneAbstained + 1)
                zipa == null -> tally.copy(zipaAbstained = tally.zipaAbstained + 1)
                else -> tally.copy(compared = tally.compared + 1, agreed = tally.agreed + if (phone == zipa) 1 else 0)
            }
        }
    }

    /** Closes the model after the take being scored, if any: before its files are deleted. */
    suspend fun unload() {
        withContext(dispatcher) { runCatching { source.close() } }
    }

    /** Waits until every take queued so far has been scored or dropped (tests, the instrumented check). */
    suspend fun awaitIdle() {
        while (synchronized(pending) { pending.isNotEmpty() } || busy.get() > 0) delay(5)
    }

    override fun close() {
        scope.cancel()
        val executor = dispatcher as? ExecutorCoroutineDispatcher
        if (executor != null) {
            executor.executor.execute { runCatching { source.close() } }
            executor.close()
        }
    }

    companion object {
        private val log: Logger = Logger.getLogger("tech.fonia.shadow")

        private fun elapsedMs(take: ShadowTake): Long = (System.nanoTime() - take.submittedNanos) / 1_000_000

        /** One thread at the lowest priority: the scorer never competes with the screen or the capture. */
        fun newShadowThread(): ExecutorCoroutineDispatcher =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "fonia-shadow").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }.asCoroutineDispatcher()
    }
}

/** The take as the log keeps it: no samples, only what they were (rate, length, an SNR estimate) and the score. */
internal fun ShadowTake.record(
    status: ShadowStatus,
    waitMs: Long,
    score: WordScore? = null,
    scoreMs: Long? = null,
    model: String? = null,
    policy: String? = null,
    /** False on the caller's thread: the SNR reads every sample. */
    measure: Boolean = true,
    loadMs: Long? = null,
): ShadowRecord {
    val worst = score?.targets?.minByOrNull { it.lpr }
    return ShadowRecord(
        profileId = profileId,
        sessionId = sessionId,
        at = at,
        take = take,
        context = context,
        sound = sound,
        position = position,
        level = level,
        feedback = feedback,
        shown = shown,
        status = status,
        verdict = score?.verdict?.name?.replaceFirstChar { it.lowercaseChar() },
        abstention = score?.abstention?.name?.replaceFirstChar { it.lowercaseChar() },
        confidence = score?.confidence,
        lpr = worst?.lpr,
        heardAs = worst?.let { runCatching { it.heardAs.id }.getOrNull() },
        fit = score?.fitPerLabel?.takeIf { it.isFinite() },
        frames = score?.frames,
        targets = score?.let { it.pronunciation.occurrences(it.sound).size },
        scoreMs = scoreMs,
        loadMs = loadMs,
        waitMs = waitMs,
        sampleRate = clip.sampleRate.roundToInt(),
        durationMs = (clip.duration * 1000).roundToInt(),
        droppedSamples = clip.dropped,
        snrDb = if (measure) estimateSnrDb(clip.samples, clip.sampleRate) else null,
        narrowBand = narrowBand,
        input = input,
        model = model,
        policy = policy,
        dsp = dsp,
    )
}

/**
 * A rough signal-to-noise ratio of a take, in dB: the loud end of its 20 ms frame levels (95th percentile) over the
 * quiet end (10th). Enough to sort takes into quiet and noisy rooms; not a measurement. Null under 100 ms.
 */
internal fun estimateSnrDb(samples: FloatArray, sampleRate: Double): Double? {
    val frame = (sampleRate * 0.02).toInt()
    if (frame <= 0) return null
    val count = samples.size / frame
    if (count < 5) return null
    val levels = DoubleArray(count) { index ->
        var sum = 0.0
        for (k in index * frame until (index + 1) * frame) sum += samples[k].toDouble() * samples[k]
        10 * log10(sum / frame + 1e-12)
    }
    levels.sort()
    val snr = levels[minOf(count - 1, (count * 0.95).toInt())] - levels[(count * 0.1).toInt()]
    return (snr * 10).roundToInt() / 10.0
}
