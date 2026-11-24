package tech.fonia.features.grownups

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.ContentSource
import tech.fonia.domain.FoniaContent
import tech.fonia.features.guide.AdultCards
import tech.fonia.store.Attempt
import tech.fonia.store.FoniaDatabase
import tech.fonia.store.FoniaRepository
import tech.fonia.store.MoodlessNote
import tech.fonia.store.Profile
import tech.fonia.store.SessionRecord
import tech.fonia.store.SoundProgress
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The PDF report for the logoped (iOS `PracticeReportTests`): built from the store's pseudonymous copy, so no name
 * gets in; the words and counts; and the empty case. The drawing itself runs on a device (`PracticeReportPdfTest`).
 */
class PracticeReportTests {
    private val today = LocalDate.of(2026, 11, 18)
    private val content = FoniaContent.load(ContentSource.directory(File(System.getProperty("fonia.content") ?: "../content")))
    private val folder: File = Files.createTempDirectory("fonia-report-tests").toFile()
    private val database = FoniaDatabase.inMemory(recordingsDirectory = File(folder, "Recordings"))
    private val repository = FoniaRepository(database)

    @After
    fun tearDown() {
        database.close()
        folder.deleteRecursively()
    }

    private fun moment(day: Int, month: Int = 11) = ZonedDateTime.of(2026, month, day, 17, 0, 0, 0, ZoneOffset.UTC).toInstant()

    private suspend fun child(name: String? = "Ana"): Profile {
        val profile = Profile(
            name = name,
            kind = Profile.Kind.Child,
            ageBand = Profile.AgeBand.SixToSeven,
            voiceGroup = Profile.VoiceGroup.Child,
            color = "cold",
            targetSounds = listOf("s", "r"),
            confirmedSounds = listOf("s"),
            track = Profile.Track.Ro,
        )
        repository.saveProfile(profile)
        return profile
    }

    /** A session of [minutes] on [day], with one try per answer pair. */
    private suspend fun practise(
        profile: Profile,
        day: Int,
        month: Int = 11,
        sound: String = "s",
        minutes: Double = 6.0,
        tries: List<Pair<Attempt.Outcome, Attempt.Outcome?>>,
    ) {
        val start = moment(day, month)
        val session = SessionRecord(
            profileId = profile.id,
            track = Profile.Track.Ro,
            sound = sound,
            startedAt = start,
            endedAt = start.plusMillis((minutes * 60_000).toLong()),
            attempts = tries.size,
            timeZone = ZoneOffset.UTC,
        )
        repository.addSession(session)
        tries.forEachIndexed { index, (machine, adult) ->
            repository.addAttempt(
                Attempt(
                    sessionId = session.id,
                    profileId = profile.id,
                    ts = start.plusSeconds(index * 10L),
                    exerciseKind = "repeat",
                    sound = sound,
                    target = "Ana și sare",
                    machineOutcome = machine,
                    adultOutcome = adult,
                    timeZone = ZoneOffset.UTC,
                ),
            )
        }
    }

    private suspend fun seeded(): Profile {
        val ana = child()
        repository.saveSoundProgress(SoundProgress(profileId = ana.id, sound = "s", rung = 3))
        practise(
            ana, day = 4,
            tries = listOf(
                Attempt.Outcome.Good to Attempt.Outcome.Good,
                Attempt.Outcome.Almost to Attempt.Outcome.Good,
                Attempt.Outcome.NotYet to null,
                Attempt.Outcome.Unclear to Attempt.Outcome.Almost,
            ),
        )
        practise(ana, day = 11, tries = listOf(Attempt.Outcome.Good to Attempt.Outcome.Good, Attempt.Outcome.Good to null))
        practise(ana, day = 17, sound = "r", tries = listOf(Attempt.Outcome.NotYet to Attempt.Outcome.NotYet))
        repository.saveNote(MoodlessNote(profileId = ana.id, localDay = "2026-11-05", text = "Ana a spus S mai ușor după încălzire."))
        repository.saveNote(MoodlessNote(profileId = ana.id, localDay = "2026-11-12", text = "A obosit la propoziții. ANA a cerut pauză."))
        return ana
    }

    private suspend fun report(profile: Profile) = PracticeReportPdf.report(repository, content, profile.id, today)

    @Test
    fun theReportHoldsWhatWasPractisedWithoutTheName() = runBlocking {
        val report = report(seeded())

        assertFalse(report.text.contains("Ana"))
        assertFalse(report.text.contains("ANA"))
        assertTrue(report.text.contains("[nume] a spus S mai ușor"))
        assertEquals("Profil" to "fără nume", report.facts.first())
        assertEquals("6-7 ani", report.age)
        assertEquals("4 noiembrie 2026 – 17 noiembrie 2026", report.period)
        assertEquals(3, report.days)
        assertEquals(3, report.sessions)
        assertEquals(18, report.minutes)
        assertEquals(7, report.attempts)
        assertEquals("3 zile cu exerciții, 3 ședințe, 18 minute și 7 încercări.", report.summary)
        assertEquals("fonia-raport-2026-11-18.pdf", report.fileName)
        // Newest first, dated.
        assertEquals(listOf("12 noiembrie 2026", "5 noiembrie 2026"), report.notes.map { it.day })

        // S first, as the adult chose, then R.
        assertEquals(listOf("S", "R"), report.sounds.map { it.letter })
        val s = report.sounds.first()
        assertEquals(4, s.rung)
        assertEquals(10, s.rungs)
        assertEquals("Treapta 4 din 10: „În mijlocul cuvântului” (cuvinte cu S la mijloc).", s.ladder)
        // The words in the middle are still Consolidare; the stage is a place in the course, not a grade.
        assertEquals("Etapa: Consolidare.", s.stageLine)
        assertTrue(s.confirmed != null)
        assertEquals("6 încercări în 2 zile.", s.tries)
        assertEquals(PracticeReport.Tally(good = 3, almost = 1, notYet = 1, none = 1), s.phone)
        assertEquals(PracticeReport.Tally(good = 3, almost = 1, notYet = 0, none = 2), s.adult)
        // The phone said good and almost where the adult said good twice; "unclear" isn't compared.
        assertEquals(3, s.compared)
        assertEquals(2, s.agreed)
        assertEquals("Din 3 încercări la care au răspuns amândoi, telefonul și adultul au spus același lucru de 2 ori.", s.agreement)
        assertEquals(listOf(4, 2, 0), s.weeks.map { it.attempts })
        assertEquals(listOf("2026-11-02", "2026-11-09", "2026-11-16"), s.weeks.map { it.monday.toString() })

        val r = report.sounds.last()
        assertNull(r.confirmed)
        assertTrue(r.ladder, r.ladder.startsWith("Treapta 1 din 10: „Doar sunetul” ("))
        assertEquals("Etapa: Emitere.", r.stageLine)
        // The text has the stage under the ladder, and the box that says how to read it explains the names.
        assertTrue(report.text.contains("${s.ladder}\n${s.stageLine}"))
        assertTrue(report.text.contains(AdultCards.STAGE_REPORT_NOTE))
        assertEquals("La singura încercare la care au răspuns amândoi, telefonul și adultul au spus același lucru.", r.agreement)
    }

    @Test
    fun countsTakeTheRomanianPluralForms() = runBlocking {
        val ana = child()
        practise(ana, day = 17, minutes = 0.5, tries = listOf(Attempt.Outcome.Good to null))
        val one = report(ana)
        assertEquals("1 zi cu exerciții, 1 ședință, 1 minut și 1 încercare.", one.summary)
        assertEquals("1 încercare în 1 zi.", one.sounds.first().tries)
        assertEquals("Adultul nu a ales încă nimic la încercările cu S.", one.sounds.first().agreement)
        assertEquals("17 noiembrie 2026", one.period)

        practise(ana, day = 18, minutes = 19.5, tries = List(20) { Attempt.Outcome.Good to Attempt.Outcome.Good })
        val many = report(ana)
        assertEquals("2 zile cu exerciții, 2 ședințe, 20 de minute și 21 de încercări.", many.summary)
        assertTrue(many.sounds.first().agreement.endsWith("același lucru de 20 de ori."))
    }

    @Test
    fun withNothingPractisedThereIsNoReport() = runBlocking {
        val ana = child()
        assertFalse(PracticeReportPdf.hasPractice(repository, ana.id))
        val report = report(ana)
        assertTrue(report.isEmpty)
        assertEquals("nicio zi încă", report.period)

        practise(ana, day = 17, tries = listOf(Attempt.Outcome.Good to null))
        assertTrue(PracticeReportPdf.hasPractice(repository, ana.id))
    }

    @Test
    fun longNotesAreCutAndTheWordsStayPlain() = runBlocking {
        val ana = child()
        practise(ana, day = 3, tries = listOf(Attempt.Outcome.Good to Attempt.Outcome.Almost))
        repository.saveNote(MoodlessNote(profileId = ana.id, localDay = "2026-11-03", text = "Am lucrat la sunete. ".repeat(60)))
        val report = report(ana)
        assertTrue(report.notes.all { it.text.length <= PracticeReport.NOTE_LENGTH + 1 })
        val lower = report.text.lowercase()
        for (word in listOf("diagnostic", "terapie", "tratează", "risc", "severitate", "tulburare", "greșit", "scor")) {
            assertFalse(word, lower.contains(word))
        }
    }
}
