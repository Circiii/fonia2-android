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
import tech.fonia.speaking.FlaggedSpan
import tech.fonia.speaking.ProsodicTranscript
import tech.fonia.speaking.SpeakingAudio
import tech.fonia.speaking.SpeakingPipeline
import tech.fonia.speaking.SpeakingText

enum class MinuteStep { Prepare, Recording, Thinking, SelfCheck, Result }

/** „Cum ți s-a părut?” — the speaker's own view first (plan 01 §10, MINUTE), never scored. */
enum class SelfView { Hard, SoSo, Easy }

data class MinuteUi(
    val step: MinuteStep = MinuteStep.Prepare,
    /** 0-based place on the 7-topic ladder. */
    val rung: Int = 0,
    val topic: String = "",
    val topics: Int = 7,
    val message: String? = null,
    val selfView: SelfView? = null,
    val transcript: ProsodicTranscript? = null,
    val spans: List<FlaggedSpan> = emptyList(),
    /** The measured numbers, one per line. */
    val lines: List<String> = emptyList(),
    val worked: String? = null,
    val toTry: String? = null,
    val spoken: String? = null,
    val playing: Boolean = false,
)

/**
 * The MINUTE screen's state ("Un minut pe zi"): a topic from a 7-step ladder (personal → public), one take of up to
 * 60 s, the speaker's own view, then the prosodic transcript with neutral numbers. No score anywhere.
 */
class MinuteState(
    private val scope: CoroutineScope,
    private val audio: SpeakingAudio,
    private val pipeline: SpeakingPipeline = SpeakingPipeline(),
    private val topics: List<String> = SpeakingStrings.TOPICS,
    startRung: Int = 0,
    private val maxSeconds: Double = 60.0,
    private val minSpeakingSeconds: Double = 10.0,
    silenceStopSeconds: Double = 3.0,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val onSelfView: ((rung: Int, view: SelfView) -> Unit)? = null,
) {
    private val _ui = MutableStateFlow(MinuteUi(rung = startRung.coerceIn(0, topics.size - 1), topic = topics[startRung.coerceIn(0, topics.size - 1)], topics = topics.size))
    val ui: StateFlow<MinuteUi> = _ui.asStateFlow()
    private val recorder = TakeRecorder(audio, maxSeconds, silenceStopSeconds)
    val levels: LevelHistory get() = recorder.levels
    val maxRecordSeconds: Double get() = maxSeconds
    private var clip: Clip? = null
    private var job: Job? = null
    private var playJob: Job? = null

    /** „Un pas înapoi” / the next rung. */
    fun moveRung(delta: Int) {
        if (_ui.value.step != MinuteStep.Prepare) return
        _ui.update { val r = (it.rung + delta).coerceIn(0, topics.size - 1); it.copy(rung = r, topic = topics[r], message = null) }
    }

    fun start() {
        val s = _ui.value.step
        if (s != MinuteStep.Prepare && s != MinuteStep.Result) return
        stopPlayback()
        _ui.update { MinuteUi(step = MinuteStep.Recording, rung = it.rung, topic = it.topic, topics = it.topics) }
        job = scope.launch {
            val c = recorder.record()
            _ui.update { it.copy(step = MinuteStep.Thinking) }
            val a = withContext(worker) { pipeline.analyze(c) }
            val s0 = a.transcript.summary
            if (s0.speakingSeconds < minSpeakingSeconds) {
                _ui.update { it.copy(step = MinuteStep.Prepare, message = SpeakingStrings.SAY_MORE) }
                return@launch
            }
            clip = c
            _ui.update {
                it.copy(
                    step = MinuteStep.SelfCheck, transcript = a.transcript, spans = a.spans, lines = SpeakingText.lines(s0),
                    worked = SpeakingText.worked(s0), toTry = SpeakingText.toTry(s0), spoken = SpeakingText.spoken(s0),
                )
            }
        }
    }

    fun finishRecording() = recorder.stop()

    fun answer(view: SelfView) {
        if (_ui.value.step != MinuteStep.SelfCheck) return
        onSelfView?.invoke(_ui.value.rung, view)
        _ui.update { it.copy(step = MinuteStep.Result, selfView = view) }
    }

    /** Plays the take back with the transcript following. */
    fun listen() {
        val c = clip ?: return
        stopPlayback()
        _ui.update { it.copy(playing = true) }
        playJob = scope.launch {
            try { audio.play(c.samples, c.sampleRate) } finally { _ui.update { it.copy(playing = false) } }
        }
    }

    fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        audio.stopPlayback()
        _ui.update { it.copy(playing = false) }
    }

    fun position(): PlaybackPosition? {
        if (!_ui.value.playing) return null
        val t = audio.playbackSeconds()
        return if (t.isNaN()) null else PlaybackPosition(Version.Original, t, t)
    }

    /** „Încă o dată, mai scurt”. */
    fun again() {
        stopPlayback()
        clip = null
        _ui.update { MinuteUi(rung = it.rung, topic = it.topic, topics = it.topics) }
        start()
    }

    fun close() {
        job?.cancel()
        recorder.stop()
        stopPlayback()
        clip = null
    }
}
