package tech.fonia.feature.speaking

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.fonia.live.Clip
import tech.fonia.speaking.CleanResult
import tech.fonia.speaking.CleanStats
import tech.fonia.speaking.FlaggedSpan
import tech.fonia.speaking.ProsodicTranscript
import tech.fonia.speaking.SpeakingAnalysis
import tech.fonia.speaking.SpeakingAudio
import tech.fonia.speaking.SpeakingPipeline
import tech.fonia.speaking.SpeakingText
import tech.fonia.speaking.TimeMap

/** The steps of NO-UM (plan 01 §10). */
enum class NoUmStep { Prepare, Recording, Thinking, Transcript, SecondTake, SecondThinking, Result }

/** Which version plays: the take as it was, or the cleaned one. */
enum class Version { Original, Clean }

/** One analysed take, ready to draw. */
data class TakeView(
    val transcript: ProsodicTranscript,
    val spans: List<FlaggedSpan>,
    val cuts: Set<Int>,
    /** „3 umpluturi · 1 pornire falsă · 1 pauză de 2,1 s” */
    val headline: String,
    /** „Ai scos 4 umpluturi. 6,2 secunde mai scurt.” */
    val cleanedLine: String,
    val timeMap: TimeMap,
    val stats: CleanStats,
) {
    /** Fillers (and lengthenings) that are cut in this version: the number the result compares. */
    val fillersCut: Int get() = spans.count { it.id in cuts && (it.isFiller || it.kind == tech.fonia.speaking.SpanKind.LENGTHENING) }
}

data class NoUmUi(
    val step: NoUmStep = NoUmStep.Prepare,
    val topic: String? = null,
    /** „Mai spune puțin.” after a take under the minimum. */
    val message: String? = null,
    val take: TakeView? = null,
    /** What is playing now, null when quiet. */
    val playing: Version? = null,
    /** The A/B switch: which version "Ascultă" plays. */
    val version: Version = Version.Clean,
    /** Step 6: „Umpluturi: 3 → 0” and one measured line. */
    val comparison: String? = null,
    val measured: String? = null,
    val second: TakeView? = null,
    val busy: Boolean = false,
)

/** Where playback is, in both time bases; read once per frame by the transcript drawing. */
data class PlaybackPosition(val version: Version, val oldSeconds: Double, val newSeconds: Double)

/**
 * The NO-UM screen's state: plain Kotlin, testable on the JVM with a fake [SpeakingAudio]. Audio and recogniser come
 * in through the engine's ports; nothing is stored unless [keep] is called (plan 01 §10: takes vanish on exit).
 * Call [close] when the screen goes away.
 */
class NoUmState(
    private val scope: CoroutineScope,
    private val audio: SpeakingAudio,
    private val pipeline: SpeakingPipeline = SpeakingPipeline(),
    topic: String? = null,
    private val maxSeconds: Double = 60.0,
    private val minSpeakingSeconds: Double = 10.0,
    silenceStopSeconds: Double = 3.0,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val onKeep: ((original: Clip, clean: CleanResult) -> Unit)? = null,
) {
    private val _ui = MutableStateFlow(NoUmUi(topic = topic))
    val ui: StateFlow<NoUmUi> = _ui.asStateFlow()

    private val recorder = TakeRecorder(audio, maxSeconds, silenceStopSeconds)

    /** Live levels for the recording ring (read in the draw phase). */
    val levels: LevelHistory get() = recorder.levels
    val maxRecordSeconds: Double get() = maxSeconds

    private var clip: Clip? = null
    private var analysis: SpeakingAnalysis? = null
    private var clean: CleanResult? = null
    private var job: Job? = null
    private var playJob: Job? = null

    val canKeep: Boolean get() = onKeep != null && clean != null

    /** Prepare → Recording. */
    fun start() {
        if (_ui.value.step != NoUmStep.Prepare && _ui.value.step != NoUmStep.Result) return
        stopPlayback()
        _ui.update { NoUmUi(step = NoUmStep.Recording, topic = it.topic) }
        job = scope.launch { firstTake() }
    }

    /** „Gata” while recording. */
    fun finishRecording() = recorder.stop()

    private suspend fun firstTake() {
        val c = recorder.record()
        _ui.update { it.copy(step = NoUmStep.Thinking) }
        val a = withContext(worker) { pipeline.analyze(c) }
        if (a.transcript.summary.speakingSeconds < minSpeakingSeconds) {
            _ui.update { NoUmUi(step = NoUmStep.Prepare, topic = it.topic, message = SpeakingStrings.SAY_MORE) }
            return
        }
        val r = withContext(worker) { pipeline.clean(c, a) }
        clip = c
        analysis = a
        clean = r
        _ui.update { it.copy(step = NoUmStep.Transcript, take = view(a, r), message = null) }
    }

    /** A tap on a flagged word: cut it or keep it, then re-render the cleaned take. */
    fun toggleCut(spanId: Int) {
        val a = analysis ?: return
        val c = clip ?: return
        val current = _ui.value.take ?: return
        if (a.span(spanId) == null) return
        val cuts = if (spanId in current.cuts) current.cuts - spanId else current.cuts + spanId
        stopPlayback()
        _ui.update { it.copy(take = current.copy(cuts = cuts), busy = true) }
        job = scope.launch {
            val r = withContext(worker) { pipeline.clean(c, a, cuts) }
            clean = r
            _ui.update { it.copy(take = view(a, r, cuts), busy = false) }
        }
    }

    /** Plays the version on the A/B switch (or [version]), from the start. */
    fun listen(version: Version = _ui.value.version) {
        val c = clip ?: return
        val r = clean ?: return
        stopPlayback()
        _ui.update { it.copy(version = version, playing = version) }
        playJob = scope.launch { play(version, c, r, 0.0) }
    }

    /** The A/B switch. While playing, it jumps to the same moment of the other version. */
    fun setVersion(version: Version) {
        val state = _ui.value
        if (state.version == version) return
        val c = clip
        val r = clean
        val playing = state.playing
        if (playing == null || c == null || r == null) { _ui.update { it.copy(version = version) }; return }
        val at = audio.playbackSeconds().takeUnless { it.isNaN() } ?: 0.0
        val from = if (version == Version.Original) r.timeMap.toOld(at) else r.timeMap.toNew(at)
        playJob?.cancel()
        audio.stopPlayback()
        _ui.update { it.copy(version = version, playing = version) }
        playJob = scope.launch { play(version, c, r, from) }
    }

    private suspend fun play(version: Version, c: Clip, r: CleanResult, from: Double) {
        try {
            if (version == Version.Original) audio.play(c.samples, c.sampleRate, from) else audio.play(r.samples, r.sampleRate, from)
        } finally {
            _ui.update { if (it.playing == version) it.copy(playing = null) else it }
        }
    }

    fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        audio.stopPlayback()
        _ui.update { it.copy(playing = null) }
    }

    /** Polled each frame while something plays; null when quiet. */
    fun position(): PlaybackPosition? {
        val v = _ui.value.playing ?: return null
        val r = clean ?: return null
        val t = audio.playbackSeconds()
        if (t.isNaN()) return null
        return if (v == Version.Clean) PlaybackPosition(v, r.timeMap.toOld(t), t) else PlaybackPosition(v, t, r.timeMap.toNew(t))
    }

    /** „Acum spune-o tu așa”: the second take, then the comparison. */
    fun sayItAgain() {
        if (_ui.value.step != NoUmStep.Transcript) return
        stopPlayback()
        _ui.update { it.copy(step = NoUmStep.SecondTake) }
        job = scope.launch {
            val c = recorder.record()
            _ui.update { it.copy(step = NoUmStep.SecondThinking) }
            val a = withContext(worker) { pipeline.analyze(c) }
            val r = withContext(worker) { pipeline.clean(c, a) }
            val before = _ui.value.take?.fillersCut ?: 0
            val second = view(a, r)
            val after = second.fillersCut
            _ui.update {
                it.copy(
                    step = NoUmStep.Result, second = second,
                    comparison = SpeakingText.comparison(before, after),
                    measured = SpeakingText.worked(a.transcript.summary),
                )
            }
        }
    }

    /** „Încă o dată”: a fresh first take. */
    fun again() {
        stopPlayback()
        clip = null; analysis = null; clean = null
        _ui.update { NoUmUi(topic = it.topic) }
        start()
    }

    /** „Păstrează”: hands the take and its cleaned version to the app (the only way anything is kept). */
    fun keep() {
        val c = clip ?: return
        val r = clean ?: return
        onKeep?.invoke(c, r)
    }

    fun close() {
        job?.cancel()
        recorder.stop()
        stopPlayback()
        clip = null; analysis = null; clean = null
    }

    private fun view(a: SpeakingAnalysis, r: CleanResult, cuts: Set<Int> = a.defaultCuts) = TakeView(
        transcript = a.transcript, spans = a.spans, cuts = cuts, headline = SpeakingText.headline(a, cuts),
        cleanedLine = SpeakingText.cleaned(r.stats), timeMap = r.timeMap, stats = r.stats,
    )
}
