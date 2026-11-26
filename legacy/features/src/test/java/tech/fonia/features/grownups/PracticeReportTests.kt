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
import tech.fonia.features.journal.MissionFollowUp
import tech.fonia.store.Attempt
import tech.fonia.store.FoniaDatabase
import tech.fonia.store.FoniaRepository
import tech.fonia.store.MissionCheck
import tech.fonia.store.MoodlessNote
import tech.fonia.store.Profile
import tech.fonia.store.Recording
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

    private fun moment(day: Int, month: Int = 11, hour: Int = 17) = ZonedDateTime.of(2026, month, day, hour, 0, 0, 0, ZoneOffset.UTC).toInstant()

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
        // The same line as „Săptămâna pe scurt” and „Cum merge”.
        assertTrue(report.text.contains(AdultCards.REPORT_DISCLAIMER))
        assertTrue(PracticeReport.HOW_TO_READ.none { it.contains("concluzii") && it != AdultCards.REPORT_DISCLAIMER })
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

    /** what the adult said about a mission is one plain line: the day, the sound, the answer */
    @Test
    fun missionAnswersInTheReport() = runBlocking {
        val ana = seeded()
        val session = SessionRecord(profileId = ana.id, track = Profile.Track.Ro, sound = "r", startedAt = moment(16), timeZone = ZoneOffset.UTC)
        repository.addSession(session)
        val mission = MissionCheck(profileId = ana.id, sessionId = session.id, sound = "r", givenAt = moment(16), timeZone = ZoneOffset.UTC)
        repository.addMission(mission)
        repository.answerMission(mission.id, MissionCheck.Answer.Sometimes, at = moment(17))
        val skipped = SessionRecord(profileId = ana.id, track = Profile.Track.Ro, sound = "s", startedAt = moment(17), timeZone = ZoneOffset.UTC)
        repository.addSession(skipped)
        val unanswered = MissionCheck(profileId = ana.id, sessionId = skipped.id, sound = "s", givenAt = moment(17), timeZone = ZoneOffset.UTC)
        repository.addMission(unanswered)
        repository.answerMission(unanswered.id, null, at = moment(18))

        val report = report(ana)
        assertEquals(listOf("16 noiembrie 2026", "12 noiembrie 2026", "5 noiembrie 2026"), report.notes.map { it.day })
        assertEquals("R: A folosit sunetul peste zi? Uneori.", report.notes.first().text)
        assertTrue(report.text.contains("16 noiembrie 2026\nR: A folosit sunetul peste zi? Uneori."))
        assertFalse(report.text.contains("S: A folosit"))
    }

    /** A kept take on [day], written the way a lesson writes it: a session, the recording and the attempt. */
    private suspend fun keptTake(profile: Profile, day: Int, hour: Int = 17, sound: String = "s", word: String, adult: Attempt.Outcome?): Attempt {
        val ts = moment(day, hour = hour)
        val session = SessionRecord(
            profileId = profile.id, track = Profile.Track.Ro, sound = sound, startedAt = ts, endedAt = ts.plusSeconds(60), attempts = 1,
            timeZone = ZoneOffset.UTC,
        )
        repository.addSession(session)
        val recording = Recording(profileId = profile.id, ts = ts, label = word, sound = sound, duration = 1.0)
        repository.recordingFileURL(recording).writeBytes(byteArrayOf(1, 2, 3))
        repository.addRecording(recording, now = ts)
        val attempt = Attempt(
            sessionId = session.id, profileId = profile.id, ts = ts, exerciseKind = "repeat", sound = sound, target = word,
            machineOutcome = Attempt.Outcome.Almost, adultOutcome = adult, recordingId = recording.id, timeZone = ZoneOffset.UTC,
        )
        repository.addAttempt(attempt)
        return attempt
    }

    /** the report says how many takes on this week's lists wait for the adult, and where to hear them */
    @Test
    fun takesWaitingToBeHeard() = runBlocking {
        val ana = child()
        // Last week's take doesn't count; this week's lists are the week page's own (`WeekReport.pick`).
        keptTake(ana, day = 10, sound = "r", word = "rac", adult = null)
        val first = keptTake(ana, day = 16, word = "sare", adult = null)
        keptTake(ana, day = 17, word = "sare", adult = Attempt.Outcome.Good)
        val latest = keptTake(ana, day = 17, hour = 18, word = "soare", adult = null)

        val report = report(ana)
        assertEquals(2, report.waitingToHear)
        val line = "2 rostiri așteaptă să fie ascultate: Setări › Săptămâna pe scurt."
        assertEquals(line, report.waitingLine)
        assertTrue(report.text.contains("${report.summary}\n$line"))

        // Once the adult has answered both, the line goes.
        repository.setAdultOutcome(first.id, Attempt.Outcome.Good)
        repository.setAdultOutcome(latest.id, Attempt.Outcome.NotYet)
        val heard = report(ana)
        assertEquals(0, heard.waitingToHear)
        assertNull(heard.waitingLine)
        assertFalse(heard.text.contains("așteaptă"))
    }

    /** the waiting line counts in plain Romanian, and an adult practising alone never gets it */
    @Test
    fun waitingLineWording() = runBlocking {
        val ana = child()
        practise(ana, day = 17, tries = listOf(Attempt.Outcome.Good to null))
        val report = report(ana)
        assertNull(report.waitingLine)
        assertEquals("O rostire așteaptă să fie ascultată: Setări › Săptămâna pe scurt.", report.copy(waitingToHear = 1).waitingLine)
        assertEquals("19 rostiri așteaptă să fie ascultate: Setări › Săptămâna pe scurt.", report.copy(waitingToHear = 19).waitingLine)
        assertEquals("20 de rostiri așteaptă să fie ascultate: Setări › Săptămâna pe scurt.", report.copy(waitingToHear = 20).waitingLine)
        assertEquals("101 rostiri așteaptă să fie ascultate: Setări › Săptămâna pe scurt.", report.copy(waitingToHear = 101).waitingLine)

        val adult = Profile(
            kind = Profile.Kind.Adult, ageBand = Profile.AgeBand.Adult, voiceGroup = Profile.VoiceGroup.Female, color = "cold",
            targetSounds = listOf("s"), track = Profile.Track.Ro,
        )
        repository.saveProfile(adult)
        keptTake(adult, day = 17, word = "sare", adult = null)
        assertEquals(0, report(adult).waitingToHear)
    }

    /** the journal and the report write the same line */
    @Test
    fun missionLine() {
        assertEquals("S: A folosit sunetul peste zi? Da.", MissionFollowUp.line("S", MissionCheck.Answer.Yes))
        assertEquals("Ș: A folosit sunetul peste zi? Încă nu.", MissionFollowUp.line("Ș", MissionCheck.Answer.NotYet))
        assertEquals("Pentru adult", MissionFollowUp.title(isChild = true))
        assertEquals("Azi, peste zi", MissionFollowUp.title(isChild = false))
    }

    /** the pace is tries per minute over the sessions that ended with tries, and absent when there is too little */
    @Test
    fun pace() = runBlocking {
        val ana = child()
        practise(ana, day = 4, minutes = 6.0, tries = List(30) { Attempt.Outcome.Good to null })
        practise(ana, day = 5, minutes = 4.0, tries = List(20) { Attempt.Outcome.Almost to null })
        val report = report(ana)
        assertEquals(5, report.perMinute)
        assertEquals("În medie, 5 încercări pe minut de exersare.", report.pace)
        assertTrue(report.text.contains("În medie, 5 încercări pe minut de exersare."))

        // 7 tries in 18 minutes rounds to none a minute: no line rather than a zero.
        assertNull(report(seeded()).pace)
    }

    /** the report says how often the phone didn't hear clearly, and what helps when it is more than half */
    @Test
    fun hearing() = runBlocking {
        val ana = child()
        practise(ana, day = 4, tries = List(5) { Attempt.Outcome.Unclear to null } + List(3) { Attempt.Outcome.Good to null })
        practise(
            ana, day = 5, sound = "r",
            tries = List(2) { Attempt.Outcome.Unclear to null } + List(6) { Attempt.Outcome.NotYet to null } +
                List(9) { Attempt.Outcome.NotJudged to null },
        )
        val report = report(ana)

        val s = report.sounds.first { it.letter == "S" }
        assertEquals(listOf("Telefonul n-a auzit clar 5 din 8 încercări.", PracticeReport.CAPTURE_TIP), s.hearing)
        // Tries the phone doesn't judge at all are not tries it missed.
        val r = report.sounds.first { it.letter == "R" }
        assertEquals(8, r.listened)
        assertEquals(listOf("Telefonul n-a auzit clar 2 din 8 încercări."), r.hearing)
        assertTrue(report.text.contains(PracticeReport.CAPTURE_TIP))

        // Under one block the phone listened to, nothing is said.
        val few = report(seeded())
        assertEquals(listOf("Telefonul n-a auzit clar 1 din 6 încercări."), few.sounds.first().hearing)
        assertEquals(emptyList<String>(), few.sounds.last().hearing)
    }

    /** the report says when the lesson last changed and what changed it */
    @Test
    fun lastMove() = runBlocking {
        val ana = child()
        var progress = SoundProgress(profileId = ana.id, sound = "s", rung = 2)
            .move(to = 3, at = moment(12, hour = 12), by = SoundProgress.Move.Source.Blocks)
        repository.saveSoundProgress(progress)
        practise(ana, day = 12, tries = listOf(Attempt.Outcome.Good to null))
        var report = report(ana)
        val s = report.sounds.first()
        assertEquals("Ultima schimbare: 12 noiembrie 2026, de la lecția 3 la lecția 4, după blocuri bune.", s.moveLine)
        assertTrue(report.text.contains(s.moveLine!!))

        progress = progress.move(to = 4, at = moment(14, hour = 12), by = SoundProgress.Move.Source.Adult)
        repository.saveSoundProgress(progress)
        report = report(ana)
        assertEquals(
            "Ultima schimbare: 14 noiembrie 2026, de la lecția 4 la lecția 5, după ce adultul a ascultat și a spus că e bine.",
            report.sounds.first().moveLine,
        )

        // Progress from before moves were kept says nothing.
        assertTrue(report(seeded()).sounds.all { it.moveLine == null })
    }

    /** a child's report says only an adult takes a step back; an adult's doesn't */
    @Test
    fun onlyAdultsGoBack() = runBlocking {
        val ana = child()
        practise(ana, day = 17, tries = listOf(Attempt.Outcome.Good to null))
        val childReport = report(ana)
        assertEquals(PracticeReport.ONLY_ADULTS_GO_BACK, childReport.readingNotes.last())
        assertEquals(PracticeReport.HOW_TO_READ, childReport.readingNotes.dropLast(1))

        val adult = Profile(
            kind = Profile.Kind.Adult, ageBand = Profile.AgeBand.Adult, voiceGroup = Profile.VoiceGroup.Female,
            color = "cold", targetSounds = listOf("s"), track = Profile.Track.Ro,
        )
        repository.saveProfile(adult)
        practise(adult, day = 17, tries = listOf(Attempt.Outcome.Good to null))
        assertEquals(PracticeReport.HOW_TO_READ, report(adult).readingNotes)
    }
}
