package tech.fonia.features.speaking

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.fonia.audio.CapturedClip
import tech.fonia.design.Field
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.Foni
import tech.fonia.design.FoniMood
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaLabel
import tech.fonia.design.FoniaText
import tech.fonia.design.PaceChart
import tech.fonia.design.Dock
import tech.fonia.design.Palette
import tech.fonia.design.PillButtonStyle
import tech.fonia.design.PressableStyle
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.WrapLayout
import tech.fonia.design.fBody
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fCaption
import tech.fonia.design.fHeadline
import tech.fonia.design.ui
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.Language
import tech.fonia.dsp.TimeSpan
import tech.fonia.dsp.heardSpeech
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.calibration
import tech.fonia.features.app.dspGroup
import tech.fonia.features.home.ModelNotice
import tech.fonia.features.home.NextStep
import tech.fonia.features.session.TakeJudge
import tech.fonia.features.session.romanianDecimal
import tech.fonia.speech.FillerHit
import tech.fonia.speech.FillerLexicon
import tech.fonia.speech.SpeechJudge
import tech.fonia.speech.SpeechLanguage
import tech.fonia.speech.SpeechReport
import tech.fonia.speech.SpeechResampler
import tech.fonia.speech.Transcriber
import tech.fonia.speech.speechReport
import tech.fonia.designsystem.motion.staggeredEntrance
import tech.fonia.store.Profile
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

@Composable
internal fun ReportView(
    report: TalkReport?,
    previous: TalkReport?,
    feeling: Int?,
    takes: Int,
    openingOnly: Boolean,
    clip: CapturedClip?,
    app: AppModel,
    noUm: NoUmTake?,
    cleanView: Boolean,
    playing: Boolean,
    onView: (Boolean) -> Unit,
    onPlay: () -> Unit,
    ignoredFillers: Set<Int>,
    drill: NextStep?,
    onClose: () -> Unit,
    onAgain: () -> Unit,
    onToggleFiller: (Int) -> Unit,
    onDrill: (NextStep) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        CloseBar(onClose)
        if (report == null) return@Column
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Foni(Modifier.size(84.dp), mood = if (feeling == 1) FoniMood.idle else FoniMood.happy, reactsToTap = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // What worked first, then the one thing to change.
                report.strength?.let { FoniaText(it, style = fBodyStrong, color = Palette.accentText) }
                FoniaText(report.headline, style = ui(22, UIWeight.bold, TextRole.title2), color = Palette.ink)
                val change = previous?.let { report.change(it) }
                if (change != null) FoniaText(change, style = fBodyStrong, color = Palette.accentText)
            }
        }

        if (noUm != null && noUm.worthPlaying) {
            NoUmBlock(noUm, cleanView, playing, onView, onPlay, Modifier.staggeredEntrance(1))
        }

        Column(Modifier.staggeredEntrance(2), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FoniaText("Ritmul", style = fHeadline, color = Palette.ink)
            // Too little speech for a pace: the sentence says so, and an empty chart would only repeat it.
            if (report.hasPace) PaceChart(
                samples = report.paceTimeline,
                zone = report.paceZone,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .semantics { contentDescription = report.paceSentence },
                maximum = report.paceMax,
                line = Palette.ink,
                zoneColor = Palette.accentSoft,
            )
            FoniaText(report.paceSentence, style = fBody, color = Palette.inkSoft)
        }

        val fillers = report.speech?.fillers ?: emptyList()
        if (fillers.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FoniaText("Ce am auzit în plus", style = fHeadline, color = Palette.ink)
                FoniaText("Atinge ce trebuia să rămână.", style = fCaption, color = Palette.inkSoft)
                WrapLayout(spacing = 8.dp, lineSpacing = 8.dp) {
                    fillers.forEachIndexed { index, hit -> FillerChip(hit, index in ignoredFillers) { onToggleFiller(index) } }
                }
                FoniaText(app.content.diction.fillerNote, style = fCaption, color = Palette.inkSoft)
            }
        }

        for (detail in report.details) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FoniaText(detail.title, style = fHeadline, color = Palette.ink)
                FoniaText(detail.sentence, style = fBody, color = Palette.inkSoft)
            }
        }

        val transcript = report.transcript
        val marked = noUm?.markedTranscript(cleanView)
        if (marked != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FoniaText("Ce ai spus", style = fHeadline, color = Palette.ink)
                NoUmTranscript(marked)
            }
        } else if (!transcript.isNullOrEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FoniaText("Ce ai spus", style = fHeadline, color = Palette.ink)
                FoniaText(transcript, style = fBody, color = Palette.ink)
            }
        } else if (app.speech.state != tech.fonia.features.app.SpeechHub.State.Ready) {
            ModelNotice(app.speech)
        }
    }
    // The one thing to do next waits under the report, where the reading ends.
    if (report != null) {
        Dock(Modifier.background(Palette.paper)) {
            // One main thing to do next: the same talk again, shorter. The rest stays quiet.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StyledButton(onClick = onAgain, style = FoniButtonStyle(field = Field.paper, size = FoniButtonStyle.Size.regular)) {
                    FoniaText(if (openingOnly) "Încă o dată" else if (takes == 0) "Încă o dată, mai scurt" else "Mai încerc o dată")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (clip != null && noUm?.worthPlaying != true) {
                        StyledButton(onClick = { app.scope.launch { app.audio.play(clip) } }, style = PillButtonStyle(), modifier = Modifier.weight(1f)) {
                            FoniaLabel("Ascultă-te", FoniaIcon.play, iconSize = 16f)
                        }
                    }
                    if (drill != null) {
                        StyledButton(onClick = { onDrill(drill) }, style = PillButtonStyle(), modifier = Modifier.weight(1f)) {
                            FoniaLabel("Exersează asta", FoniaIcon.arrow, iconSize = 16f)
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun FillerChip(hit: FillerHit, ignored: Boolean, onToggle: () -> Unit) {
    StyledButton(onClick = onToggle, style = PressableStyle(), onClickLabel = if (ignored) "Îl pune la loc" else "Îl scoate din numărătoare") {
        Box(
            Modifier
                .heightIn(min = 44.dp)
                .background(Palette.sheet, RoundedCornerShape(50))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            FoniaText(
                hit.word,
                style = fBodyStrong.copy(textDecoration = if (ignored) TextDecoration.LineThrough else null),
                color = if (ignored) Palette.inkSoft else Palette.ink,
            )
        }
    }
}

/** What a take most needs next, in the order it should be said. Only ever one. */
enum class TalkFocus { NotHeard, Time, Hesitations, TooFast, TooSlow, PhrasesShort, PhrasesLong, FlatPitch, None }

/**
 * One take as a report. One sentence, one thing to try, and no score: scores have nothing behind them, and a number in
 * the middle of a scale only confuses. The ranges are guides, not verdicts.
 */
data class TalkReport(
    val seconds: Double,
    /** Syllables per second over the whole time spent speaking, pauses included. */
    val rate: Double,
    val focus: TalkFocus,
    /** One thing that went well, said before the one thing to change. Null only when nothing was heard. */
    val strength: String?,
    val headline: String,
    val paceTimeline: List<Double>,
    val paceZone: ClosedFloatingPointRange<Double>,
    val paceMax: Double,
    val paceSentence: String,
    val details: List<Detail>,
    /** The take's hesitations and the seconds of speech they were counted in. */
    val fillers: FillerSample,
    /** How they compare with the person's own earlier talks. */
    val fillerTrend: FillerTrend,
    val medianPhrase: Int?,
    val pitchSpread: Double,
    val transcript: String?,
    val speech: SpeechReport?,
    /** Whisper's verbatim words, kept for „Ascultă-te fără ăăă” so the take is not transcribed twice. */
    val rawTranscript: tech.fonia.speech.Transcript? = null,
    /** Whether there was speech enough for the pace to mean anything, and so for its chart. */
    val hasPace: Boolean = true,
) {
    data class Detail(val title: String, val sentence: String)

    /** What went well, in the words the report says it. Only measured things, or the plain fact of the effort. */
    object Strength {
        const val PACE = "Ai vorbit într-un ritm ușor de urmărit."
        const val PITCH = "Vocea a urcat și a coborât, ca într-o discuție vie."
        const val FEWER_FILLERS = "Ai spus „ăă” mai rar decât de obicei."
        const val HELD_TIME = "Ai intrat în timpul pe care ți l-ai propus."
        const val FINISHED = "Ai vorbit până la capăt."
    }

    /** Only changes bigger than the noise between two takes get mentioned. */
    fun change(earlier: TalkReport): String? {
        if (FillerTrend.change(earlier.fillers, fillers) == FillerTrend.Direction.Lower) return "Mai puține „ăă” decât data trecută."
        if (pitchSpread - earlier.pitchSpread >= 0.5) return "Vocea a urcat și a coborât mai mult decât data trecută."
        if (earlier.rate > 0 && abs(rate - earlier.rate) / earlier.rate >= 0.1) {
            return if (rate < earlier.rate) "Ai vorbit mai rar decât data trecută." else "Ai vorbit mai repede decât data trecută."
        }
        if (seconds < earlier.seconds * 0.9) return "Ai spus-o mai scurt decât data trecută."
        return null
    }

    companion object {
        /** Syllables a speech needs before its pace means anything, about fifteen seconds' worth. */
        private const val MINIMUM_SYLLABLES = 60

        suspend fun make(
            clip: CapturedClip,
            language: Language,
            profile: Profile,
            content: FoniaContent,
            transcriber: Transcriber?,
            target: Int,
            earlierFillers: List<FillerSample> = emptyList(),
        ): TalkReport {
            val summary = TakeJudge.summarise(clip, profile.dspGroup, profile.calibration)
            var speechReport: SpeechReport? = null
            var heardText: String? = null
            var raw: tech.fonia.speech.Transcript? = null
            if (transcriber != null) {
                try {
                    withContext(Dispatchers.Default) {
                        val samples = SpeechResampler.resample(clip.samples, clip.sampleRate)
                        val lang = if (language == Language.Ro) SpeechLanguage.RO else SpeechLanguage.EN
                        val transcript = transcriber.transcribe(samples, lang, verbatim = true)
                        heardText = transcript.text
                        raw = transcript
                        val list = if (language == Language.Ro) content.diction.fillers.ro else content.diction.fillers.en
                        val lexicon = FillerLexicon(
                            hesitationLabel = list.hesitations.firstOrNull() ?: "ăă",
                            fillers = list.hesitations + list.discourseMarkers,
                            ambiguous = list.discourseMarkers,
                        )
                        speechReport = SpeechJudge.speechReport(
                            transcript,
                            language = lang,
                            lexicon = lexicon,
                            hesitations = summary.hesitations.map { tech.fonia.speech.TimeSpan(it.start, it.end) },
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // `try?`: no transcript, the rest of the report still stands.
                }
            }

            val zone = if (language == Language.Ro) 3.2..5.0 else 2.75..4.25
            val rate = summary.speechRate.toDouble()
            val span = max(summary.speechEnd - summary.speechStart, 0.1)
            val hesitationCount = summary.hesitations.size
            val fillers = FillerSample(hesitationCount, span)
            val fillerTrend = FillerTrend.compare(hesitationCount, span, earlierFillers)
            val runs = phraseLengths(summary.syllables, summary.pauses)
            val median = if (runs.size >= 8) runs.sorted()[runs.size / 2] else null
            val pitchSpread = summary.pvq.toDouble() * 17.3
            val longPauses = summary.pauses.count { it.duration >= 3 }
            val enoughForPace = summary.syllables.size >= MINIMUM_SYLLABLES
            val shown = romanianDecimal(rate)

            val focus: TalkFocus
            val headline: String
            if (!heardSpeech(summary)) {
                focus = TalkFocus.NotHeard
                headline = "Nu te-am auzit. Ține telefonul mai aproape și mai încearcă."
            } else if (target >= 60 && abs(span - target.toDouble()) > target.toDouble() * 0.1) {
                focus = TalkFocus.Time
                headline = "Ai ținut ${clock(span.toInt())} din ${clock(target)}. Alege o idee pe care o scurtezi sau o lungești, ca să intri fix în timp."
            } else if (fillerTrend is FillerTrend.More) {
                // Only against the person's own earlier talks: no fixed number of "ăă" is too many.
                focus = TalkFocus.Hesitations
                headline = "Ai spus „ăă” mai des decât de obicei. Când simți că vine unul, taci o clipă: pauza sună mai sigur."
            } else if (enoughForPace && rate > zone.endInclusive) {
                focus = TalkFocus.TooFast
                headline = "Ai vorbit repede. Fă o pauză scurtă după fiecare idee, ca să te poată urmări cine te ascultă."
            } else if (enoughForPace && rate < zone.start) {
                focus = TalkFocus.TooSlow
                headline = "Ai vorbit rar. Spune dintr-o suflare cuvintele care țin de aceeași idee."
            } else if (median != null && median < 6) {
                focus = TalkFocus.PhrasesShort
                headline = "Frazele au ieșit tăiate. Spune fiecare idee dintr-o suflare, apoi respiră."
            } else if (median != null && median > 20) {
                focus = TalkFocus.PhrasesLong
                headline = "Frazele au fost lungi. Pune punct după fiecare idee și respiră."
            } else if (summary.speechSeconds >= 30 && pitchSpread < 2) {
                focus = TalkFocus.FlatPitch
                headline = "Vocea a stat mai mult pe loc. În fiecare frază, alege un cuvânt și apasă pe el."
            } else {
                focus = TalkFocus.None
                headline = "A curs bine. Acum spune același lucru în mai puțin timp."
            }

            val paceSentence = if (!enoughForPace) {
                "E prea puțin ca să spun ceva despre ritm."
            } else if (rate in zone) {
                "Un ritm obișnuit, $shown silabe pe secundă. Zona verde e doar un reper."
            } else {
                "$shown silabe pe secundă. Zona verde e doar un reper."
            }

            val heldTime = target >= 60 && focus != TalkFocus.Time
            val strengthLine = strength(
                focus = focus,
                paceInZone = enoughForPace && rate in zone,
                pitchMoved = summary.speechSeconds >= 30 && pitchSpread >= 2.8,
                fewerFillers = fillerTrend.isFewer,
                heldTime = heldTime,
                // „Până la capăt” only for a take that came close to the time it had.
                reachedEnd = clip.duration >= (if (target > 0) target else OPENING_SECONDS).toDouble() * 0.8,
            )

            val details = mutableListOf<Detail>()
            if (longPauses > 0) {
                details += Detail("Pauze lungi", if (longPauses == 1) "O pauză de peste 3 secunde." else "$longPauses pauze de peste 3 secunde.")
            }
            fillerSentence(hesitationCount, fillerTrend, fillers.perMinute)?.let { details += Detail("„Ăă”", it) }
            if (summary.speechSeconds >= 30 && pitchSpread >= 2.8 && strengthLine != Strength.PITCH) {
                details += Detail("Vocea", Strength.PITCH)
            }

            return TalkReport(
                seconds = clip.duration,
                rate = rate,
                focus = focus,
                strength = strengthLine,
                headline = headline,
                paceTimeline = paceLine(summary.syllables, summary.speechStart, summary.speechEnd),
                paceZone = zone,
                paceMax = zone.endInclusive * 1.6,
                paceSentence = paceSentence,
                details = details,
                fillers = fillers,
                fillerTrend = fillerTrend,
                medianPhrase = median,
                pitchSpread = pitchSpread,
                transcript = heardText,
                rawTranscript = raw,
                speech = speechReport,
                hasPace = enoughForPace,
            )
        }

        /**
         * The first thing that went well and doesn't clash with the one thing to change. When nothing measured stands
         * out, the effort itself, when the take went on to near its time. Never invented praise. Null when nothing was
         * heard, or when nothing measured stands out in a take that stopped well short of its time.
         */
        fun strength(focus: TalkFocus, paceInZone: Boolean, pitchMoved: Boolean, fewerFillers: Boolean, heldTime: Boolean, reachedEnd: Boolean = true): String? {
            if (focus == TalkFocus.NotHeard) return null
            if (paceInZone && focus != TalkFocus.TooFast && focus != TalkFocus.TooSlow) return Strength.PACE
            if (pitchMoved && focus != TalkFocus.FlatPitch) return Strength.PITCH
            if (fewerFillers && focus != TalkFocus.Hesitations) return Strength.FEWER_FILLERS
            if (heldTime) return Strength.HELD_TIME
            return if (reachedEnd) Strength.FINISHED else null
        }

        /**
         * What the report says about "ăă", or null when there were none. The count alone when the take was too short for
         * a rate; the rate and, with enough history, how it compares with the person's usual. Never a limit.
         */
        fun fillerSentence(count: Int, trend: FillerTrend, rate: Double?): String? {
            if (count <= 0) return null
            val rateText = rate?.let { "Cam ${roundedHalfAway(it)} pe minut." } ?: ""
            val counted = "Am numărat $count."
            return when (trend) {
                FillerTrend.TooShort -> "$counted E prea puțin timp ca să le compar cu alte dăți."
                FillerTrend.FirstTalks -> "${rateText.ifEmpty { counted }} După câteva încercări îți arăt cum se schimbă la tine."
                is FillerTrend.Fewer -> "$rateText Mai rar decât de obicei la tine, cam ${roundedHalfAway(trend.usual)} pe minut."
                is FillerTrend.Same -> "$rateText Cam cât de obicei la tine, cam ${roundedHalfAway(trend.usual)} pe minut."
                is FillerTrend.More -> "$rateText Mai des decât de obicei la tine, cam ${roundedHalfAway(trend.usual)} pe minut."
            }
        }

        /** `String(format: "%d:%02d", ...)`. */
        private fun clock(seconds: Int): String = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)

        /** Swift's `rounded()`: halves away from zero. */
        private fun roundedHalfAway(value: Double): Int = (if (value < 0) -Math.floor(-value + 0.5) else Math.floor(value + 0.5)).toInt()

        internal fun phraseLengths(syllables: List<Double>, pauses: List<TimeSpan>): List<Int> {
            val breaks = pauses.filter { it.duration >= 0.25 }.map { it.start }.sorted()
            val counts = IntArray(breaks.size + 1)
            for (time in syllables) {
                val found = breaks.indexOfFirst { time < it }
                counts[if (found < 0) breaks.size else found] += 1
            }
            return counts.filter { it > 0 }
        }

        /** Pace in a fifteen second window moving five seconds at a time, so the line doesn't jump about. */
        internal fun paceLine(syllables: List<Double>, start: Double, end: Double): List<Double> {
            val window = 15.0
            val span = end - start
            if (!(span > 0)) return listOf(0.0, 0.0)
            if (!(span > window)) return listOf(syllables.size / span, syllables.size / span)
            // `stride(from:through:by:)` on Double: each value is start + n × 5, through the last one not past the end.
            val result = mutableListOf<Double>()
            var n = 0
            while (true) {
                val from = start + n * 5.0
                if (from > end - window) break
                result += syllables.count { it >= from && it < from + window }.toDouble() / window
                n += 1
            }
            return result
        }
    }
}
