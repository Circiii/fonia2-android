package tech.fonia.speaking.coach

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** The five things the report scores. [title] is what the screen writes under each living picture. */
enum class Area(val key: String, val title: String) {
    Pace("pace", "Ritm"),
    Voice("voice", "Voce"),
    Gaze("gaze", "Privire"),
    Calm("calm", "Calm"),
    Lively("lively", "Viu"),
}

/** 0–100 per area; null when it could not be measured (no camera, too little speech). */
data class CoachScores(val values: Map<Area, Int?>) {
    operator fun get(area: Area): Int? = values[area]

    companion object {
        val empty = CoachScores(Area.entries.associateWith { null })
    }
}

/** What happened at a moment of the talk, for the timeline. [label] is short, calm and never a verdict. */
enum class MomentKind(val key: String, val label: String) {
    TooFast("fast", "prea repede"),
    TooSlow("slow", "prea rar"),
    Quiet("quiet", "prea încet"),
    LookedAway("away", "privirea în altă parte"),
    Moving("moving", "mișcare multă"),
    Flat("flat", "pe același ton"),
    LongPause("pause", "pauză lungă"),
    Filler("filler", "„ăăă”"),
}

/** [word]: for a filler, the filler word a recogniser wrote down („deci”); null for a sound („ăăă”) and the rest. */
data class Moment(val kind: MomentKind, val start: Double, val end: Double, val word: String? = null)

/**
 * A filler of the talk, for the report: a „ăăă” the sound detector heard ([word] null) or a filler word a recogniser
 * wrote down ([word] = what it wrote).
 */
data class FillerMark(val start: Double, val end: Double, val word: String? = null)

/** The one thing to work on next: the area, a short title and a tip of at most 8 words. */
data class Focus(val area: Area?, val title: String, val tip: String, val fillers: Boolean = false)

data class CoachReport(
    val seconds: Double,
    val scores: CoachScores,
    val moments: List<Moment>,
    val focus: Focus,
    /** Score minus the previous attempt's, per area where both exist. */
    val changes: Map<Area, Int>,
    /** One sentence about the biggest change worth mentioning, or null. */
    val changeLine: String?,
    /** Syllables per second of speech over the whole talk; NaN when too little. */
    val rate: Double,
    /** Share of the camera time he looked at the lens; null without a camera. */
    val eyeContact: Double?,
    /** Share of the camera's time the picture was good enough to measure; null without a camera. */
    val cameraCoverage: Double? = null,
    /** What went best, said first; null when nothing scored high enough to praise honestly. */
    val best: Area? = null,
    /** Words per minute over the whole talk, pauses included, estimated from the syllables; NaN when unknown. */
    val wordsPerMinute: Double = Double.NaN,
    /** The silences inside the talk by kind (short, medium, long); null when there was no audio. */
    val pauses: PauseCounts? = null,
    /** p10 to p90 of the melody over the talk, in semitones; NaN when too little voice. */
    val pitchRangeSt: Double = Double.NaN,
    /** This talk against his own habit (pace, pitch range); null before he has enough talks. */
    val usual: AgainstUsual? = null,
    val fillers: Int,
    val fillersPerMinute: Double,
    val specVersion: String,
)

/** Turns an attempt's timeline into the report. Pure. */
object CoachReportBuilder {
    fun build(
        ticks: List<CoachTick>,
        seconds: Double,
        audio: AudioSummary?,
        fillers: List<FillerMark>,
        previous: CoachScores?,
        spec: CoachSpec = CoachSpec.v1,
        /** His habit from his earlier talks, or null: then nothing is said about it. */
        baseline: SpeakerBaseline? = null,
    ): CoachReport {
        val speakingMinutes = max(audio?.speechSeconds ?: seconds, 1.0) / 60.0
        val fillersPerMinute = fillers.size / max(speakingMinutes, 0.25)
        val fillerPenalty = minOf(spec.report.maxFillerPenalty, fillersPerMinute * spec.report.fillerPenaltyPerMinute)

        val pace = spec.pace
        val paceTicks = ticks.filter { it.speaking && !it.rate.isNaN() }
        val paceScore = if (paceTicks.size >= 4) {
            val mean = paceTicks.map { tick ->
                val d = when {
                    tick.rate > pace.bandHigh -> tick.rate - pace.bandHigh
                    tick.rate < pace.bandLow -> pace.bandLow - tick.rate
                    else -> 0.0
                }
                (1.0 - d).coerceIn(0.0, 1.0)
            }.average()
            (mean * 100 - fillerPenalty).roundToInt().coerceIn(0, 100)
        } else {
            null
        }
        val speakingTicks = ticks.filter { it.speaking }
        val voiceScore = if (speakingTicks.size >= 4) ((1.0 - speakingTicks.count { it.quiet }.toDouble() / speakingTicks.size) * 100).roundToInt() else null
        val gazeTicks = ticks.filter { it.looking != null }
        // Coverage: the share of the camera's time that could be measured. Under the minimum there is no score.
        val cameraTicks = ticks.count { it.camera }
        val cameraCoverage = if (cameraTicks >= 4) gazeTicks.size.toDouble() / cameraTicks else null
        val enough = cameraCoverage == null || cameraCoverage >= spec.quality.minCoverage
        val eyeContact = if (gazeTicks.size >= 4 && enough) gazeTicks.count { it.looking == true }.toDouble() / gazeTicks.size else null
        val gazeScore = eyeContact?.let { (it * 100).roundToInt() }
        val motionTicks = ticks.filter { !it.motion.isNaN() }
        val calmScore = if (motionTicks.size >= 4) {
            val span = spec.motion.gaugeMaxIndex - 0.5
            (motionTicks.map { (1.0 - max(0.0, it.motion - 0.5) / span).coerceIn(0.0, 1.0) }.average() * 100).roundToInt()
        } else {
            null
        }
        val livelyTicks = ticks.filter { it.speaking && !it.liveliness.isNaN() }
        val livelyScore = if (livelyTicks.size >= 4) ((livelyTicks.map { it.liveliness }.average() / 0.6).coerceIn(0.0, 1.0) * 100).roundToInt() else null
        val scores = CoachScores(
            mapOf(Area.Pace to paceScore, Area.Voice to voiceScore, Area.Gaze to gazeScore, Area.Calm to calmScore, Area.Lively to livelyScore),
        )

        val moments = ArrayList<Moment>()
        fun runs(kind: MomentKind, test: (CoachTick) -> Boolean) {
            var start = Double.NaN
            var last = Double.NaN
            for (tick in ticks) {
                if (test(tick)) {
                    if (start.isNaN() || tick.t - last > 0.6) {
                        if (!start.isNaN() && last - start + spec.report.tickSeconds >= spec.report.minMomentSeconds) moments += Moment(kind, start, last + spec.report.tickSeconds)
                        start = tick.t
                    }
                    last = tick.t
                }
            }
            if (!start.isNaN() && last - start + spec.report.tickSeconds >= spec.report.minMomentSeconds) moments += Moment(kind, start, last + spec.report.tickSeconds)
        }
        runs(MomentKind.TooFast) { it.speaking && !it.rate.isNaN() && it.rate > pace.bandHigh }
        runs(MomentKind.TooSlow) { it.speaking && !it.rate.isNaN() && it.rate < pace.bandLow }
        runs(MomentKind.Quiet) { it.quiet }
        runs(MomentKind.LookedAway) { it.looking == false }
        runs(MomentKind.Moving) { !it.motion.isNaN() && it.motion > spec.motion.cueIndex }
        runs(MomentKind.Flat) { it.speaking && !it.liveliness.isNaN() && it.liveliness < spec.liveliness.cueBelow }
        audio?.longPauses?.forEach { moments += Moment(MomentKind.LongPause, it.start, it.end) }
        fillers.forEach { moments += Moment(MomentKind.Filler, it.start, it.end, it.word) }
        moments.sortBy { it.start }

        val focus = focus(scores, paceTicks.map { it.rate }, fillersPerMinute, spec)
        val changes = HashMap<Area, Int>()
        if (previous != null) {
            for (area in Area.entries) {
                val now = scores[area] ?: continue
                val before = previous[area] ?: continue
                changes[area] = now - before
            }
        }
        val worth = spec.report.changeWorthMentioning
        val best = changes.entries.filter { it.value >= worth }.maxByOrNull { it.value }
        val changeLine = when {
            best != null -> CoachText.better(best.key, best.value)
            previous != null && changes.isNotEmpty() && changes.values.all { abs(it) < worth } -> CoachText.SAME
            else -> null
        }
        return CoachReport(
            seconds = seconds,
            scores = scores,
            moments = moments,
            focus = focus,
            changes = changes,
            changeLine = changeLine,
            rate = audio?.articulationRate ?: Double.NaN,
            eyeContact = eyeContact,
            cameraCoverage = cameraCoverage,
            best = scores.values.filterValues { it != null && it >= spec.report.praiseFrom }.maxByOrNull { it.value!! }?.key,
            pauses = audio?.pauses,
            pitchRangeSt = audio?.pitchRangeSt ?: Double.NaN,
            usual = AgainstUsual.of(audio?.articulationRate ?: Double.NaN, audio?.pitchRangeSt ?: Double.NaN, baseline, spec),
            wordsPerMinute = if (audio != null && audio.nuclei > 0 && seconds >= 10.0) audio.nuclei / spec.report.syllablesPerWord / (seconds / 60.0) else Double.NaN,
            fillers = fillers.size,
            fillersPerMinute = fillersPerMinute,
            specVersion = spec.version,
        )
    }

    /** The lowest score under [CoachSpec.Report.focusBelow] (ties: the order of [Area]); else "keep it". */
    fun focus(scores: CoachScores, rates: List<Double>, fillersPerMinute: Double, spec: CoachSpec = CoachSpec.v1): Focus {
        val lowest = Area.entries.mapNotNull { a -> scores[a]?.let { a to it } }.minByOrNull { it.second }
        if (lowest == null || lowest.second >= spec.report.focusBelow) return CoachText.keep
        return when (lowest.first) {
            Area.Pace -> {
                val median = rates.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }
                when {
                    !median.isNaN() && median > spec.pace.bandHigh -> CoachText.tooFast
                    !median.isNaN() && median < spec.pace.bandLow -> CoachText.tooSlow
                    fillersPerMinute >= 2.0 -> CoachText.fillers
                    !median.isNaN() && median > (spec.pace.bandLow + spec.pace.bandHigh) / 2 -> CoachText.tooFast
                    else -> CoachText.tooSlow
                }
            }
            Area.Voice -> CoachText.voice
            Area.Gaze -> CoachText.gaze
            Area.Calm -> CoachText.calm
            Area.Lively -> CoachText.lively
        }
    }
}

/** Every sentence of the coach. Tips are at most 8 words; nothing says "greșit"; feelings are never named. */
object CoachText {
    val tooFast = Focus(Area.Pace, "Ritmul: mai rar", "Fă o pauză după fiecare idee.")
    val tooSlow = Focus(Area.Pace, "Ritmul: mai legat", "Leagă cuvintele, ține ritmul până la punct.")
    val fillers = Focus(Area.Pace, "Fără „ăăă”", "În loc de „ăăă”, taci o clipă.", fillers = true)
    val voice = Focus(Area.Voice, "Vocea: mai tare", "Vorbește pentru ultimul rând din sală.")
    val gaze = Focus(Area.Gaze, "Privirea: în lentilă", "Privește lentila la finalul fiecărei fraze.")
    val calm = Focus(Area.Calm, "Corpul: mai liniștit", "Picioarele fixe, capul liniștit, mâinile libere.")
    val lively = Focus(Area.Lively, "Vocea: mai vie", "Urcă vocea pe cuvântul cel mai important.")
    val keep = Focus(null, "Ține-o tot așa", "Păstrează asta. Încearcă un subiect nou.")

    const val SAME = "Cam la fel ca data trecută."

    fun better(area: Area, delta: Int): String = "${area.title}: +$delta față de data trecută."

    /** Every tip, for the length test. */
    val allFocus: List<Focus> = listOf(tooFast, tooSlow, fillers, voice, gaze, calm, lively, keep)

    /**
     * The 30-second drill's tip for [area]. For the pace it goes by his own habit: someone who usually speaks slowly
     * is not told to slow down. Without a habit yet, the more common advice („mai rar”).
     */
    fun drill(area: Area, baseline: SpeakerBaseline?, spec: CoachSpec = CoachSpec.v1): Focus = when (area) {
        Area.Pace -> {
            val usual = baseline?.pace ?: Double.NaN
            if (usual.isFinite() && usual < (spec.pace.bandLow + spec.pace.bandHigh) / 2) tooSlow else tooFast
        }
        Area.Voice -> voice
        Area.Gaze -> gaze
        Area.Calm -> calm
        Area.Lively -> lively
    }

    /** What the drill card says about his habit for [area], or null when there is nothing to say. */
    fun drillHabit(area: Area, baseline: SpeakerBaseline?): String? {
        val b = baseline ?: return null
        return when (area) {
            Area.Pace -> if (b.pace.isFinite()) "De obicei: ${tech.fonia.speaking.SpeakingText.decimal(b.pace)} silabe pe secundă." else null
            Area.Lively -> if (b.pitchRangeSt.isFinite()) {
                "De obicei, tonul urcă și coboară pe ${tech.fonia.speaking.SpeakingText.plural(b.pitchRangeSt.roundToInt(), "semiton", "semitonuri")}."
            } else {
                null
            }
            else -> null
        }
    }
}
