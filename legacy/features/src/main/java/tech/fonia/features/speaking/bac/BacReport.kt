package tech.fonia.features.speaking.bac

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.fonia.dsp.ClipSummary
import tech.fonia.dsp.EmphasisAnalysis
import tech.fonia.dsp.EmphasisState
import tech.fonia.dsp.analyzeEmphasis
import tech.fonia.dsp.heardSpeech
import tech.fonia.features.session.romanianDecimal
import tech.fonia.features.speaking.PauseCheck
import tech.fonia.features.speaking.ReadAloud
import tech.fonia.features.speaking.ReadAloudCheck
import tech.fonia.features.speaking.ReadStatus
import tech.fonia.features.speaking.clockText
import tech.fonia.speech.FillerHit
import tech.fonia.speech.FillerLexicon
import tech.fonia.speech.SpeechJudge
import tech.fonia.speech.SpeechLanguage
import tech.fonia.speech.SpeechReport
import tech.fonia.speech.Transcriber
import tech.fonia.speech.Transcript
import tech.fonia.speech.speechReport
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToInt

// The report of one simulation: indicators only (V6). No score, no level, no estimate of a grade: the exam's level
// is the teachers' to give (AI Act Annex III 3(b) would apply to anything that graded). Each indicator stands with
// the reference it is read against, a starting value from the research ([S] in 03 §1.5 and H F7), not the official grid.

/** The four things recorded in a simulation. */
enum class BacPart(val title: String) {
    Reading("Citirea"),
    Task1("Cerința 1"),
    Task2("Cerința 2"),
    Opinion("Cerința 3"),
    ;

    /** 1, 2 or 3 for the tasks; 0 for the reading. */
    val task: Int get() = ordinal
}

/** One recorded part, kept small: the clip at 16 kHz for the recogniser and the clip analysis of the original. */
class BacRecording(val part: BacPart, val samples16k: FloatArray, val summary: ClipSummary, val seconds: Double)

data class ReadingResult(
    val seconds: Double,
    val heard: Boolean,
    val check: ReadAloudCheck?,
    /** Syllable nuclei per second over the speaking span (the clip analysis, as in the talk report). */
    val syllablesPerSecond: Float,
    val pauses: PauseCheck?,
    val pvq: Float,
    /** P5 to P95 of the pitch, semitones. */
    val pitchRangeSt: Float,
    val emphasis: EmphasisAnalysis?,
)

data class SpokenAnswer(
    val part: BacPart,
    val seconds: Double,
    val heard: Boolean,
    val transcript: Transcript?,
    val report: SpeechReport?,
    val syllablesPerSecond: Float,
)

data class BacReport(
    /** With the key words the reader chose. */
    val ticket: BacTicket,
    val reading: ReadingResult?,
    val answers: List<SpokenAnswer>,
    /** From the opinion's transcript; null when it was not recorded or not heard. */
    val arguments: ArgumentCount?,
    /** Syllables per second the content gives as an orientative band for Romanian (`diction.json` rateGuide). */
    val paceBand: ClosedFloatingPointRange<Double>,
) {
    val opinion: SpokenAnswer? get() = answers.firstOrNull { it.part == BacPart.Opinion }

    fun answer(part: BacPart): SpokenAnswer? = answers.firstOrNull { it.part == part }

    companion object {
        /** References ([S]): reading accuracy, pauses at punctuation, pauses elsewhere, PVQ, key words, hesitations. */
        const val READING_ACCURACY = 0.95f
        const val PUNCTUATION_PAUSES = 0.80f
        const val OTHER_PAUSES_PER_MINUTE = 2.0
        const val LIVELY_PVQ = 0.15f
        const val FLAT_PVQ = 0.12f
        const val KEY_WORDS = 0.80f
        const val HESITATIONS_PER_100_WORDS = 2.5

        /**
         * Runs everything after the last part: the reading against the ticket's text (transcript, then teacher forcing
         * where needed), the pauses and the key words over the reading, and a verbatim transcript of each answer.
         * [progress] gets a line for the waiting screen. Nothing leaves the phone.
         */
        suspend fun make(
            ticket: BacTicket,
            recordings: Map<BacPart, BacRecording>,
            transcriber: Transcriber,
            lexicon: FillerLexicon,
            paceBand: ClosedFloatingPointRange<Double>,
            progress: (String) -> Unit = {},
        ): BacReport {
            val reading = recordings[BacPart.Reading]?.let { recording ->
                val summary = recording.summary
                if (!heardSpeech(summary)) {
                    ReadingResult(recording.seconds, false, null, 0f, null, summary.pvq, summary.pitch.rangeSt, null)
                } else {
                    progress("Ascult citirea")
                    val check = ReadAloud.check(ticket.text, recording.samples16k, transcriber, SpeechLanguage.RO) { sentence, total ->
                        progress("Compar citirea cu textul: propoziția ${sentence + 1} din $total")
                    }
                    withContext(Dispatchers.Default) {
                        ReadingResult(
                            seconds = recording.seconds,
                            heard = true,
                            check = check,
                            syllablesPerSecond = summary.speechRate,
                            pauses = PauseCheck.measure(ticket.text.words, check.placed, summary.pauses),
                            pvq = summary.pvq,
                            pitchRangeSt = summary.pitch.rangeSt,
                            emphasis = analyzeEmphasis(summary, check.spans, ticket.text.keyIndices),
                        )
                    }
                }
            }
            val answers = listOf(BacPart.Task1, BacPart.Task2, BacPart.Opinion).mapNotNull { part ->
                val recording = recordings[part] ?: return@mapNotNull null
                val summary = recording.summary
                if (!heardSpeech(summary)) return@mapNotNull SpokenAnswer(part, recording.seconds, false, null, null, 0f)
                progress("Ascult răspunsul la ${part.title.lowercase()}")
                val transcript = try {
                    transcriber.transcribe(recording.samples16k, SpeechLanguage.RO, verbatim = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                val report = transcript?.let {
                    SpeechJudge.speechReport(
                        it,
                        language = SpeechLanguage.RO,
                        lexicon = lexicon,
                        hesitations = summary.hesitations.map { h -> tech.fonia.speech.TimeSpan(h.start, h.end) },
                    )
                }
                SpokenAnswer(part, recording.seconds, true, transcript, report, summary.speechRate)
            }
            val opinion = answers.firstOrNull { it.part == BacPart.Opinion }?.transcript
            return BacReport(ticket, reading, answers, opinion?.let { Connectors.find(it) }, paceBand)
        }
    }
}

/** A shown indicator: what it is, the measured value, and the reference it is read against. */
data class Indicator(val title: String, val value: String, val reference: String?, val note: String? = null)

/** The report's sentences. Physical and countable things only: no state of the speaker, no grade. */
object BacReportText {
    const val GRADE_NOTE = "Nota și nivelul le dau profesorii din comisie. Aici vezi doar ce a măsurat telefonul, ca să știi ce să exersezi."
    const val REFERENCE_NOTE = "Reperele vin din cercetare și sunt provizorii. Nu sunt grila examenului."

    fun percent(share: Float): String = "${(share * 100).roundToInt()} %"

    /** Two decimals with a comma, as PVQ is written: 0,18. */
    fun twoDecimals(value: Float): String =
        BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_EVEN).toPlainString().replace('.', ',')

    fun readingIndicators(report: BacReport): List<Indicator> {
        val reading = report.reading ?: return emptyList()
        if (!reading.heard) return listOf(Indicator("Citirea", "Nu am auzit citirea.", null, "Ține telefonul mai aproape data viitoare."))
        val out = mutableListOf<Indicator>()
        reading.check?.let { check ->
            val unsure = check.words.count { it.status == ReadStatus.UNSURE }
            out += Indicator(
                "Cuvinte citite ca în text",
                "${check.readAsWritten} din ${check.words.size} (${percent(check.accuracy)})",
                "Reper pentru o citire corectă: cel puțin ${percent(BacReport.READING_ACCURACY)}.",
                when (unsure) {
                    0 -> null
                    1 -> "La un cuvânt nu am fost sigur: ascultă-l mai jos."
                    else -> "La ${plural(unsure, "un cuvânt", "cuvinte")} nu am fost sigur: ascultă-le mai jos."
                },
            )
        }
        out += Indicator(
            "Ritmul",
            "${romanianDecimal(reading.syllablesPerSecond.toDouble())} silabe pe secundă",
            "Reper provizoriu: între ${romanianDecimal(report.paceBand.start)} și ${romanianDecimal(report.paceBand.endInclusive)}.",
        )
        reading.pauses?.let { pauses ->
            pauses.markShare?.let { share ->
                out += Indicator(
                    "Pauze la semnele de punctuație",
                    "${pauses.pausedMarks} din ${pauses.marks} (${percent(share)})",
                    "Reper: cel puțin ${percent(BacReport.PUNCTUATION_PAUSES)}, cu pauze de cel puțin un sfert de secundă.",
                )
            }
            pauses.otherPausesPerMinute?.let { perMinute ->
                out += Indicator(
                    "Pauze fără semn de punctuație",
                    if (pauses.otherPauses == 0) "niciuna" else "${romanianDecimal(perMinute)} pe minut (${pauses.otherPauses} în total)",
                    "Reper: cel mult 2 pe minut.",
                )
            }
        }
        out += Indicator(
            "Tonul",
            "PVQ ${twoDecimals(reading.pvq)}. Vocea a urcat și a coborât pe ${romanianDecimal(reading.pitchRangeSt.toDouble())} semitonuri.",
            "Reper: PVQ de cel puțin ${twoDecimals(BacReport.LIVELY_PVQ)}. Sub ${twoDecimals(BacReport.FLAT_PVQ)}, vocea sună plat.",
        )
        reading.emphasis?.let { emphasis ->
            val tally = emphasis.tally
            val share = tally.keyShare
            if (tally.keyWords > 0) {
                out += Indicator(
                    "Cuvintele-cheie",
                    if (share == null) "Nu le-am putut măsura." else "${tally.stressedKeyWords} din ${tally.measuredKeyWords} au ieșit în față (${percent(share)})",
                    "Reper: cel puțin ${percent(BacReport.KEY_WORDS)} dintre cuvintele-cheie, mai lungi, mai tari sau mai sus decât vecinii.",
                    if (tally.everythingStressed) "Au ieșit în față ${percent(tally.stressedShare ?: 0f)} din toate cuvintele. Peste 40 %, cele importante nu se mai deosebesc." else null,
                )
            }
        }
        return out
    }

    fun answerIndicators(answer: SpokenAnswer): List<Indicator> {
        if (!answer.heard) return listOf(Indicator("Durata", clockText(answer.seconds.roundToInt()), null, "Nu am auzit răspunsul."))
        val out = mutableListOf(Indicator("Durata", clockText(answer.seconds.roundToInt()), null))
        out += Indicator("Ritmul", "${romanianDecimal(answer.syllablesPerSecond.toDouble())} silabe pe secundă", null)
        answer.report?.let { report ->
            if (report.words > 0) {
                out += Indicator(
                    "„Ăă” la 100 de cuvinte",
                    romanianDecimal(report.hesitationsPer100Words),
                    "Reper: cel mult ${romanianDecimal(BacReport.HESITATIONS_PER_100_WORDS)}.",
                )
            }
            markers(report)?.let { out += Indicator("Cuvinte de umplutură auzite", it, null, "Doar ca informație: uneori chiar își au locul.") }
        }
        return out
    }

    /** "deci ×3, practic ×1"; null when there were none. */
    fun markers(report: SpeechReport): String? {
        val counts = LinkedHashMap<String, Int>()
        for (hit in report.fillers) if (hit.kind == FillerHit.Kind.DISCOURSE_MARKER) counts[hit.word] = (counts[hit.word] ?: 0) + 1
        if (counts.isEmpty()) return null
        return counts.entries.joinToString(", ") { "${it.key} ×${it.value}" }
    }

    /** [marked] is the reader's own count, when they changed it. */
    fun argumentIndicator(count: ArgumentCount, marked: Int?): Indicator {
        val value = if (marked != null) {
            "${plural(marked, "un argument", "argumente")}, " + if (marked == 1) "numărat de tine" else "numărate de tine"
        } else {
            "${plural(count.arguments, "un argument", "argumente")}, după cuvintele de legătură"
        }
        return Indicator("Argumente", value, "Reper: cel puțin ${Connectors.TARGET_ARGUMENTS} argumente.")
    }

    fun structureLines(count: ArgumentCount): List<String> = listOf(
        count.phrases(ConnectorRole.Thesis).firstOrNull()?.let { "Punctul de vedere: l-ai spus cu „$it”." }
            ?: "Punctul de vedere: nu am auzit „cred că”, „în opinia mea” sau ceva asemănător.",
        count.phrases(ConnectorRole.Conclusion).firstOrNull()?.let { "Încheierea: ai folosit „$it”." }
            ?: "Încheierea: nu am auzit „în concluzie”, „așadar” sau ceva asemănător.",
    )

    fun roleTitle(role: ConnectorRole): String = when (role) {
        ConnectorRole.Thesis -> "Punct de vedere"
        ConnectorRole.Argument -> "Argumente"
        ConnectorRole.Reason -> "Motive"
        ConnectorRole.Example -> "Exemple"
        ConnectorRole.Conclusion -> "Încheiere"
    }

    /** The key words of the reading with what each one did, for the list under the indicator. */
    fun keyWordLines(report: BacReport): List<Pair<String, String>> {
        val emphasis = report.reading?.emphasis ?: return emptyList()
        return emphasis.keyWords.map { word ->
            val name = report.ticket.text.words[word.index].bare
            name to when (word.state) {
                EmphasisState.STRESSED -> "a ieșit în față"
                EmphasisState.CLOSE -> "aproape"
                EmphasisState.LEVEL -> "ca vecinii lui"
                EmphasisState.NOT_MEASURED -> "nemăsurat"
            }
        }
    }

    /** "un cuvânt", "3 cuvinte", "20 de cuvinte". */
    fun plural(count: Int, one: String, many: String): String = when {
        count == 1 -> one
        count == 0 || count % 100 in 1..19 -> "$count $many"
        else -> "$count de $many"
    }
}
