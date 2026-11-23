package tech.fonia.features.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** The report of C2, as words and numbers before it is drawn: Anexa 1's parts, the period, the wording. */
class ReadingReportTests {
    private val today: LocalDate = LocalDate.of(2026, 11, 6)

    private fun probe(kind: ProbeKind, day: String, read: Int, errors: Int, seconds: Double = 60.0) = ProbeRecord(
        takenAt = Instant.parse("${day}T09:00:00Z"),
        localDay = day,
        probeID = "A-${kind.rawValue}",
        kind = kind,
        set = "A",
        contentVersion = "ciorna-2026-11-06",
        preset = ReadingPreset.Standard,
        startedBy = StartSource.Tap,
        seconds = seconds,
        totalWords = 60,
        wordsRead = read,
        errorIndices = (0 until errors).toList(),
        syllablesRead = read,
        syllablesCorrect = read - errors,
    )

    private val data = ReadingProfileData(
        report = ReportDetails(pseudonym = "Elevul A", age = "", grade = SchoolGrade.First),
        probes = listOf(
            probe(ProbeKind.Mono, "2026-03-02", 30, 4), // more than six months ago: left out
            probe(ProbeKind.Mono, "2026-06-01", 34, 3),
            probe(ProbeKind.Mono, "2026-11-05", 41, 3),
            probe(ProbeKind.Text, "2026-09-15", 30, 2),
        ),
    )

    private val speech = listOf(SpeechLine("S", "Silabe cu S", good = 12, almost = 5, notYet = 3))

    private fun build(from: ReadingProfileData = data) =
        ReadingReport.build(from, ageBand = "6–7", today = today, speech = speech, contentVersion = "ciorna-2026-11-06", contentIsDraft = true)

    @Test
    fun theSixPartsOfAnexa1InTheirOrder() {
        val report = build()
        assertEquals(
            listOf("Identificare", "Citit", "Conștiința fonologică", "Denumirea rapidă și pseudocuvintele", "Scris", "Vorbire"),
            report.parts.map { it.title },
        )
        assertEquals((1..6).toList(), report.parts.map { it.number })
        // What the app can't measure yet is left for the adult, with lines to write on.
        for (part in report.parts.drop(2).dropLast(1)) {
            assertEquals(listOf(ReadingReport.NOT_MEASURED), part.lines)
            assertTrue(part.forTheAdult!!.startsWith("De completat: "))
        }
    }

    @Test
    fun itSaysWhereTheDiagnosisIsMade() {
        assertEquals("Orientativ; diagnosticul se pune doar prin evaluarea complexă (art. 10–11).", build().disclaimer)
        assertTrue("nu se folosesc pentru note" in build().purpose.lowercase())
    }

    @Test
    fun itCoversTheLastSixMonths() {
        val report = build()
        assertEquals(3, report.probes.size)
        assertEquals(LocalDate.of(2026, 6, 1), report.from)
        assertEquals("1 iunie 2026 – 6 noiembrie 2026", report.period)
        assertEquals(listOf(ProbeKind.Mono, ProbeKind.Text), report.kinds)
        assertEquals(listOf("2026-06-01", "2026-11-05"), report.curve(ProbeKind.Mono).map { it.localDay })
    }

    @Test
    fun theFirstPageNamesAPseudonymNeverTheChild() {
        val report = build()
        val first = report.identification.toMap()
        assertEquals("Elevul A", first["Pseudonim"])
        assertEquals("6–7 ani", first["Vârsta"]) // no age typed: the profile's age band
        assertEquals("clasa I", first["Clasa"])
        assertEquals("3", first["Probe de citire"])
        val typed = build(data.copy(report = ReportDetails("Elevul B", "7 ani", null))).identification.toMap()
        assertEquals("7 ani", typed["Vârsta"])
        assertEquals("nescrisă", typed["Clasa"])
    }

    @Test
    fun criterionIIIsNumbersOnly() {
        val report = build()
        assertEquals(
            listOf(
                "Cuvinte de o silabă: de la 31 (1.06.2026) la 38 (5.11.2026) cuvinte corecte pe minut, în 2 probe.",
                "Un text scurt: o singură probă (15.09.2026), 28 de cuvinte corecte pe minut.",
            ),
            report.progressLines,
        )
        assertEquals(listOf("Nicio probă de citire în perioadă."), build(ReadingProfileData()).progressLines)
    }

    @Test
    fun theBandIsABandWithItsSource() {
        val lines = build().bandLines
        assertEquals(
            "Cuvinte de o silabă (5.11.2026): 38 de cuvinte corecte pe minut, în banda raportată pentru final de clasa I " +
                "(26–40 de cuvinte corecte pe minut, la cuvintele de o silabă).",
            lines[0],
        )
        assertTrue(lines[1].contains("în banda raportată"))
        assertTrue("Bodea Hațegan" in build().bandSource)
    }

    @Test
    fun theTableHasEveryProbeOldestFirst() {
        val table = build().readingTable
        assertEquals(listOf("Data", "Proba (setul)", "Corecte pe minut", "Corectitudine", "Silabe pe secundă", "Citite", "Citite altfel", "Așezare"), table.first())
        assertEquals(listOf("1.06.2026", "o silabă (A)", "31", "91%", "0,5", "34", "3", "Standard"), table[1])
        assertEquals(4, table.size)
    }

    @Test
    fun theAnnexPageListsEveryItemOfTheForm() {
        val annex = build().annex
        assertEquals(
            listOf(
                "I.1 Exprimarea orală", "I.2 Înțelegerea orală", "I.3 a) Citit: fluența", "I.3 b) Citit: corectitudinea",
                "I.3 c) Citit: înțelegerea", "I.4 a) Scris: rapiditatea", "I.4 b) Scris: corectitudinea",
                "Criteriul II: progresul după 6 luni de instruire adecvată",
            ),
            annex.map { it.item },
        )
        assertEquals(ReadingReport.NOT_IN_FONIA, annex[1].fromFonia)
        assertTrue(annex[0].fromFonia.contains("S"))
        assertTrue(annex.last().fromFonia.contains("de la 31"))
    }

    @Test
    fun theWordingFollowsTheRules() {
        val report = build()
        val everything = listOf(report.title, report.subtitle, report.purpose, report.readingIntro, report.bandSource, report.annexTitle, report.annexIntro, report.madeBy) +
            report.identification.flatMap { listOf(it.first, it.second) } +
            report.parts.flatMap { it.lines + listOfNotNull(it.title, it.forTheAdult) } +
            report.annex.flatMap { listOf(it.item, it.fromFonia) } +
            report.readingTable.flatten() + report.progressLines + report.bandLines
        for (text in everything) {
            assertEquals(text, emptyList<String>(), CopyRules.problems(text))
            // Diagnosis is named once, in the sentence that says where it is made.
            assertFalse(text, "diagnostic" in text.lowercase())
        }
        assertEquals(emptyList<String>(), CopyRules.problems(report.disclaimer))
        assertTrue(report.madeBy.contains("ciornă"))
    }
}
