package tech.fonia.features.speaking

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.dsp.VoiceGroup
import tech.fonia.dsp.analyzeClip
import tech.fonia.features.reading.CopyRules
import tech.fonia.features.speaking.bac.BacPart
import tech.fonia.features.speaking.bac.BacRecording
import tech.fonia.features.speaking.bac.BacReport
import tech.fonia.features.speaking.bac.BacReportText
import tech.fonia.features.speaking.bac.BacTicket
import tech.fonia.features.speaking.bac.Indicator
import tech.fonia.speech.FillerLexicon
import tech.fonia.speech.SpeechResampler

/**
 * The BAC simulation's report (V6): reading accuracy from the alignment, pauses at punctuation, the tone, the key words,
 * the answers and the arguments, each with its reference, and nothing that adds up to a score or a level. First a whole
 * simulation of synthetic speech for ticket 1, then a recorded clip with Whisper's real transcript.
 */
class BacReportTests {
    private val lexicon = FillerLexicon(
        hesitationLabel = "ăă",
        fillers = listOf("ăă", "ăăă", "îî", "mmm", "deci", "practic", "adică"),
        ambiguous = listOf("deci", "practic", "adică"),
    )
    private val paceBand = 3.2..4.8

    private fun recording(part: BacPart, spoken: SyntheticSpeech.Spoken) = BacRecording(
        part = part,
        samples16k = SpeechResampler.resample(spoken.samples, spoken.rate),
        summary = analyzeClip(spoken.samples, spoken.rate, VoiceGroup.FEMALE, null),
        seconds = spoken.seconds,
    )

    private fun answer(text: String) = SyntheticSpeech.say(text.split(' '), pauseAfter = { 0.08 }, seed = text.length)

    private class Simulation(val ticket: BacTicket, val report: BacReport, val marks: Int, val calls: List<String>)

    /** Ticket 1 read with two commas run through, one stop mid-sentence, one recogniser slip, one word misread, and the key word "regulat" said like the rest. */
    private val simulation: Simulation by lazy {
        val ticket = bacContent.ticket("b01")!!
        val words = ticket.text.words
        fun at(bare: String) = words.indexOfFirst { it.bare == bare }
        val commas = words.filter { it.mark == ',' }.map { it.index }.take(2).toSet()
        val reading = SyntheticSpeech.say(
            words.map { it.shown },
            stressed = ticket.text.keyIndices - at("regulat"),
            pauseAfter = SyntheticSpeech.readerPauses(words, skip = commas, extra = mapOf(at("schimbă") to 0.45)),
            heard = { i, word ->
                when (i) {
                    at("răcoroasă") -> "racoroasa"
                    at("adolescent") -> "adolescentul"
                    else -> word
                }
            },
        )
        val task1 = answer("Tema textului este somnul ăă la adolescenți. Textul spune că dormim prea puțin.")
        val task2 = answer("Textul se adresează, deci, elevilor și părinților. Se vede din exemple.")
        val opinion = answer(
            "În opinia mea, orele ar trebui să înceapă mai târziu. În primul rând, elevii ar dormi mai mult. " +
                "În al doilea rând, ar fi mai atenți. În concluzie, cred că ar fi mai bine.",
        )
        val recogniser = ScriptedTranscriber()
        recogniser.add(reading, readWrong = setOf("adolescent"))
        for (take in listOf(task1, task2, opinion)) recogniser.add(take)
        val report = runBlocking {
            BacReport.make(
                ticket = ticket,
                recordings = mapOf(
                    BacPart.Reading to recording(BacPart.Reading, reading),
                    BacPart.Task1 to recording(BacPart.Task1, task1),
                    BacPart.Task2 to recording(BacPart.Task2, task2),
                    BacPart.Opinion to recording(BacPart.Opinion, opinion),
                ),
                transcriber = recogniser,
                lexicon = lexicon,
                paceBand = paceBand,
            )
        }
        Simulation(ticket, report, words.dropLast(1).count { it.mark != null }, recogniser.calls.toList())
    }

    @Test
    fun theReadingIsCheckedAgainstTheTicket() {
        val reading = checkNotNull(simulation.report.reading)
        val check = checkNotNull(reading.check)
        assertEquals(121, check.words.size)
        assertEquals(120, check.readAsWritten)
        val words = simulation.ticket.text.words
        assertEquals(listOf("adolescent" to ReadStatus.DIFFERENT, "răcoroasă" to ReadStatus.UNSURE), check.toListen.map { words[it.index].bare to it.status })
        // Only the two sentences with a word written differently were scored again.
        assertEquals(2, check.scoredSentences)
        assertEquals(1 + 2 + 3, simulation.calls.size)
    }

    @Test
    fun pausesToneAndKeyWords() {
        val reading = simulation.report.reading!!
        val pauses = checkNotNull(reading.pauses)
        assertEquals(simulation.marks, pauses.marks)
        assertEquals(simulation.marks - 2, pauses.pausedMarks)
        assertEquals(1, pauses.otherPauses)
        val tally = reading.emphasis!!.tally
        assertEquals(5, tally.keyWords)
        assertEquals(5, tally.measuredKeyWords)
        assertEquals(4, tally.stressedKeyWords)
        assertFalse(tally.everythingStressed)
        assertTrue(reading.syllablesPerSecond > 0f)
        assertTrue(reading.pvq > 0f)
    }

    @Test
    fun theAnswersAndTheArguments() {
        val report = simulation.report
        assertEquals(listOf(BacPart.Task1, BacPart.Task2, BacPart.Opinion), report.answers.map { it.part })
        val first = report.answer(BacPart.Task1)!!
        assertTrue(first.report!!.hesitationsPer100Words > 0)
        assertEquals("deci ×1", BacReportText.markers(report.answer(BacPart.Task2)!!.report!!))
        val arguments = checkNotNull(report.arguments)
        assertEquals(2, arguments.arguments)
        assertTrue(arguments.hasThesis && arguments.hasConclusion)
        assertEquals(Indicator("Argumente", "2 argumente, după cuvintele de legătură", "Reper: cel puțin 2 argumente."), BacReportText.argumentIndicator(arguments, null))
        assertEquals("3 argumente, numărate de tine", BacReportText.argumentIndicator(arguments, 3).value)
        assertEquals("un argument, numărat de tine", BacReportText.argumentIndicator(arguments, 1).value)
        assertEquals(
            listOf("Punctul de vedere: l-ai spus cu „în opinia mea”.", "Încheierea: ai folosit „în concluzie”."),
            BacReportText.structureLines(arguments),
        )
    }

    @Test
    fun indicatorsComeWithTheirReferences() {
        val indicators = BacReportText.readingIndicators(simulation.report)
        assertEquals(
            listOf("Cuvinte citite ca în text", "Ritmul", "Pauze la semnele de punctuație", "Pauze fără semn de punctuație", "Tonul", "Cuvintele-cheie"),
            indicators.map { it.title },
        )
        val accuracy = indicators[0]
        assertEquals("120 din 121 (99 %)", accuracy.value)
        assertEquals("Reper pentru o citire corectă: cel puțin 95 %.", accuracy.reference)
        assertEquals("La un cuvânt nu am fost sigur: ascultă-l mai jos.", accuracy.note)
        assertEquals("Reper provizoriu: între 3,2 și 4,8.", indicators[1].reference)
        assertEquals("${simulation.marks - 2} din ${simulation.marks} (${BacReportText.percent((simulation.marks - 2f) / simulation.marks)})", indicators[2].value)
        assertEquals("Reper: cel puțin 80 %, cu pauze de cel puțin un sfert de secundă.", indicators[2].reference)
        assertEquals("Reper: cel mult 2 pe minut.", indicators[3].reference)
        assertTrue(indicators[4].value, indicators[4].value.startsWith("PVQ 0,"))
        assertEquals("4 din 5 au ieșit în față (80 %)", indicators[5].value)
        for (answer in simulation.report.answers) {
            val shown = BacReportText.answerIndicators(answer)
            assertEquals(listOf("Durata", "Ritmul", "„Ăă” la 100 de cuvinte"), shown.take(3).map { it.title })
        }
    }

    @Test
    fun noScoreNoLevelNoInferredState() {
        // The report has no field that adds the indicators up.
        val fields = (BacReport::class.java.declaredFields + tech.fonia.features.speaking.bac.ReadingResult::class.java.declaredFields).map { it.name.lowercase() }
        for (word in listOf("score", "grade", "level", "nota", "mark")) assertTrue("$word in $fields", fields.none { word in it })
        val texts = mutableListOf(BacReportText.GRADE_NOTE, BacReportText.REFERENCE_NOTE)
        val report = simulation.report
        for (indicator in BacReportText.readingIndicators(report) + report.answers.flatMap { BacReportText.answerIndicators(it) } +
            listOf(BacReportText.argumentIndicator(report.arguments!!, null))
        ) {
            texts += listOfNotNull(indicator.title, indicator.value, indicator.reference, indicator.note)
        }
        texts += BacReportText.structureLines(report.arguments!!)
        for (text in texts) {
            assertEquals(text, emptyList<String>(), CopyRules.problems(text))
            assertEquals(text, emptyList<String>(), statesIn(text))
            for (word in listOf("nota ta", "nivelul tău", "punctaj", "scor", "/10", "calificativ")) assertFalse("$word in $text", word in text.lowercase())
        }
        assertTrue("profesorii" in BacReportText.GRADE_NOTE)
    }

    @Test
    fun aSilentReadingSaysSo() {
        val ticket = bacContent.ticket("b02")!!
        val quiet = SyntheticSpeech.Spoken(FloatArray(16000 * 3), 16000.0, emptyList())
        val report = runBlocking {
            BacReport.make(ticket, mapOf(BacPart.Reading to recording(BacPart.Reading, quiet)), ScriptedTranscriber(), lexicon, paceBand)
        }
        assertFalse(report.reading!!.heard)
        assertNull(report.reading!!.check)
        assertEquals(listOf(Indicator("Citirea", "Nu am auzit citirea.", null, "Ține telefonul mai aproape data viitoare.")), BacReportText.readingIndicators(report))
        assertTrue(report.answers.isEmpty())
        assertNull(report.arguments)
    }

    @Test
    fun aRecordedTakeAsReadingAndAsOpinion() {
        // The ElevenLabs clip read from its script, with Whisper's real transcript of it. Without the model on the JVM
        // the teacher-forced second look can't run, so the four words Whisper wrote differently count as read differently.
        val take = Recorded.take("ro-speech")
        val ticket = BacTicket("rec", 0, "text nonliterar", "Somnul", MarkedText.parse(take.script), listOf("Tema?", "Cui?", "Opinia, cu argumente?"))
        val recogniser = ScriptedTranscriber().also { it.add(take.samples, take.words, forcedScores = false) }
        val recorded = BacRecording(BacPart.Reading, take.samples, take.summary, take.samples.size / take.rate)
        val report = runBlocking {
            BacReport.make(
                ticket = ticket,
                recordings = mapOf(BacPart.Reading to recorded, BacPart.Opinion to BacRecording(BacPart.Opinion, take.samples, take.summary, recorded.seconds)),
                transcriber = recogniser,
                lexicon = lexicon,
                paceBand = paceBand,
            )
        }
        val indicators = BacReportText.readingIndicators(report)
        assertEquals("25 din 29 (86 %)", indicators.first { it.title == "Cuvinte citite ca în text" }.value)
        assertEquals("1 din 5 (20 %)", indicators.first { it.title == "Pauze la semnele de punctuație" }.value)
        assertEquals("PVQ 0,17. Vocea a urcat și a coborât pe 6,6 semitonuri.", indicators.first { it.title == "Tonul" }.value)
        val opinion = report.opinion!!
        assertEquals("deci ×1, practic ×1", BacReportText.markers(opinion.report!!))
        assertEquals(0.0, opinion.report!!.hesitationsPer100Words, 0.0)
        assertEquals(0, report.arguments!!.arguments)
        assertEquals("0 argumente, după cuvintele de legătură", BacReportText.argumentIndicator(report.arguments!!, null).value)
    }
}
