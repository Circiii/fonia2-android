package tech.fonia.features.grownups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.Rung
import tech.fonia.features.app.index
import tech.fonia.features.session.WeakJudgeGate
import tech.fonia.store.Attempt
import tech.fonia.store.Profile
import tech.fonia.store.Recording
import tech.fonia.store.SessionRecord
import tech.fonia.store.SoundProgress
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

/**
 * The week page for the adult (iOS `WeekReportTests`): what one week adds up to per sound, which kept takes make the
 * short list, and what the adult's two answers write.
 */
class WeekReportTests {
    private val utc: ZoneId = ZoneOffset.UTC
    private val profileID = UUID.randomUUID()
    private val sessionID = UUID.randomUUID()
    private val monday = LocalDate.of(2026, 11, 16)

    private fun moment(day: Int, hour: Int = 17, minute: Int = 0, month: Int = 11): Instant =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, utc).toInstant()

    private fun session(day: Int, hour: Int = 17, minutes: Double = 10.0, sound: String? = "s", finished: Boolean = true): SessionRecord {
        val start = moment(day, hour)
        return SessionRecord(
            profileId = profileID,
            track = Profile.Track.Ro,
            sound = sound,
            startedAt = start,
            endedAt = if (finished) start.plusMillis((minutes * 60_000).toLong()) else null,
            attempts = 1,
            timeZone = utc,
        )
    }

    private fun attempt(
        day: Int,
        hour: Int = 17,
        minute: Int = 0,
        sound: String = "s",
        word: String = "sare",
        machine: Attempt.Outcome = Attempt.Outcome.Good,
        adult: Attempt.Outcome? = null,
        kept: Boolean = false,
    ): Pair<Attempt, Recording?> {
        val ts = moment(day, hour, minute)
        val recording = if (kept) Recording(profileId = profileID, ts = ts, label = word, sound = sound, duration = 1.0) else null
        val attempt = Attempt(
            sessionId = sessionID,
            profileId = profileID,
            ts = ts,
            exerciseKind = "repeat",
            sound = sound,
            target = word,
            machineOutcome = machine,
            adultOutcome = adult,
            recordingId = recording?.id,
            timeZone = utc,
        )
        return attempt to recording
    }

    private fun take(
        day: Int,
        hour: Int = 17,
        minute: Int = 0,
        word: String = "sare",
        machine: Attempt.Outcome = Attempt.Outcome.Good,
        adult: Attempt.Outcome? = null,
    ): WeekReport.Take {
        val (attempt, recording) = attempt(day, hour, minute, word = word, machine = machine, adult = adult, kept = true)
        return WeekReport.Take(attempt, recording!!)
    }

    private fun build(
        sessions: List<SessionRecord> = emptyList(),
        pairs: List<Pair<Attempt, Recording?>> = emptyList(),
        progress: List<SoundProgress> = emptyList(),
        order: List<String> = listOf("s", "r"),
    ): WeekReport = WeekReport.build(
        monday = monday,
        sessions = sessions,
        attempts = pairs.map { it.first },
        recordings = pairs.mapNotNull { it.second },
        progress = progress,
        order = order,
        timeZone = utc,
    )

    private fun block(rung: Rung, good: Int, day: Int) = SoundProgress.Block(rung = rung.index, good = good, total = 6, endedAt = moment(day))

    // The week's sums

    @Test
    fun weekSums() {
        val report = build(
            sessions = listOf(
                session(15, minutes = 30.0),
                session(16, minutes = 10.0),
                session(16, 19, minutes = 4.5, sound = "r"),
                session(18, minutes = 12.0),
                session(20, finished = false),
                session(22, 23, minutes = 5.0),
                session(23, minutes = 30.0),
            ),
            pairs = listOf(
                attempt(15),
                attempt(16), attempt(16, 17, 1), attempt(16, 19, sound = "r", word = "rac"),
                attempt(18), attempt(20), attempt(22, 23),
                attempt(23),
            ),
        )
        assertEquals(4, report.days)
        // 10 + 4.5 + 12 + 5 minutes, rounded up once.
        assertEquals(32, report.minutes)
        assertEquals(listOf("s", "r"), report.sounds.map { it.sound })

        val s = report.sounds[0]
        assertEquals(4, s.days)
        assertEquals(27, s.minutes)
        assertEquals(5, s.attempts)
        assertEquals(1, report.sounds[1].days)
        assertEquals(5, report.sounds[1].minutes)
        assertEquals(1, report.sounds[1].attempts)
    }

    @Test
    fun emptyWeek() {
        val empty = build(sessions = listOf(SessionRecord(profileId = profileID, track = Profile.Track.Ro, sound = "s", startedAt = moment(17), timeZone = utc)))
        assertTrue(empty.isEmpty)
        assertEquals(0, empty.minutes)
        assertEquals("Nicio zi cu exerciții.", WeekCopy.summary(empty))
    }

    @Test
    fun soundOrder() {
        val report = build(
            pairs = listOf(
                attempt(16, sound = "z", word = "zar"),
                attempt(16, sound = "r", word = "rac"),
                attempt(17, sound = "ș", word = "șase"),
                attempt(17, sound = "s"),
            ),
            order = listOf("r", "s"),
        )
        assertEquals(listOf("r", "s", "z", "ș"), report.sounds.map { it.sound })
    }

    @Test
    fun keptTakes() {
        val (gone, _) = attempt(16, kept = true)
        val report = build(pairs = listOf(gone to null, attempt(17, kept = true), attempt(18)))
        assertEquals(3, report.sounds[0].attempts)
        assertEquals(1, report.sounds[0].kept.size)
        assertEquals(1, report.sounds[0].waiting)
        assertEquals(1, report.waiting)
    }

    // Where the lessons stood

    @Test
    fun rungsFromBlocks() {
        val progress = SoundProgress(
            profileId = profileID,
            sound = "s",
            rung = Rung.Retell.index,
            blockHistory = listOf(block(Rung.Clusters, 5, 12), block(Rung.Pairs, 3, 17), block(Rung.Pairs, 6, 19), block(Rung.Sentences, 4, 24)),
        )
        val s = build(pairs = listOf(attempt(17)), progress = listOf(progress)).sounds[0]
        assertEquals(Rung.Pairs, s.rungBefore)
        assertEquals(Rung.Sentences, s.rungAfter)
        assertTrue(s.moved)
        assertTrue(s.changedStage)
        assertEquals("A trecut de la „Cuvinte care seamănă” la „Propoziții cu S”. A ajuns la etapa Automatizare.", WeekCopy.moved(s, "S"))
    }

    @Test
    fun rungsWithoutLaterBlocks() {
        val moved = WeekReport.rungs(
            SoundProgress(profileId = profileID, sound = "s", rung = Rung.WordsMedial.index, blockHistory = listOf(block(Rung.WordsInitial, 6, 18))),
            "2026-11-16",
            "2026-11-22",
            utc,
        )
        assertEquals(Rung.WordsInitial to Rung.WordsMedial, moved)

        val still = WeekReport.rungs(SoundProgress(profileId = profileID, sound = "s", rung = Rung.WordsMedial.index), "2026-11-16", "2026-11-22", utc)
        assertEquals(Rung.WordsMedial to Rung.WordsMedial, still)
    }

    @Test
    fun movedLines() {
        val progress = SoundProgress(profileId = profileID, sound = "s", rung = Rung.WordsMedial.index, blockHistory = listOf(block(Rung.WordsInitial, 6, 18)))
        val up = build(pairs = listOf(attempt(18)), progress = listOf(progress)).sounds[0]
        assertFalse(up.changedStage)
        assertTrue(WeekCopy.moved(up, "S")!!.startsWith("A trecut de la „"))
        assertFalse(WeekCopy.moved(up, "S")!!.contains("etapa"))

        val back = SoundProgress(profileId = profileID, sound = "s", rung = Rung.WordsInitial.index, blockHistory = listOf(block(Rung.WordsMedial, 1, 18)))
        val down = build(pairs = listOf(attempt(18)), progress = listOf(back)).sounds[0]
        assertTrue(WeekCopy.moved(down, "S")!!.startsWith("Lecțiile au revenit la „"))

        val same = build(pairs = listOf(attempt(18))).sounds[0]
        assertNull(WeekCopy.moved(same, "S"))
    }

    // The short list

    @Test
    fun pairFirst() {
        val takes = listOf(
            take(16, 17, 0, word = "Sara sare sus."),
            take(16, 17, 1, word = "sare"),
            take(18, 17, 0, word = "sare"),
            take(21, 18, 0, word = "Sara sare sus."),
        )
        val picked = WeekReport.pick(takes, limit = 2)
        assertEquals(listOf(takes[0].id, takes[3].id), picked.map { it.id })
        assertEquals(listOf(WeekReport.Take.Why.FirstOnWord, WeekReport.Take.Why.LatestOnWord), picked.map { it.why })
    }

    @Test
    fun pairByWord() {
        val takes = listOf(take(16, word = "Sare"), take(17, word = "sare"), take(18, word = "cos"))
        assertEquals(listOf(takes[0].id, takes[1].id), WeekReport.pick(takes, limit = 2).map { it.id })
        assertTrue(WeekReport.pick(listOf(take(16, word = "sare"), take(17, word = "cos")), limit = 2).none { it.why == WeekReport.Take.Why.FirstOnWord })
    }

    @Test
    fun unsureThenUnheard() {
        val takes = listOf(
            take(16, 17, 0, word = "sare", machine = Attempt.Outcome.NotYet),
            take(16, 17, 1, word = "cos", machine = Attempt.Outcome.Almost),
            take(17, 17, 0, word = "sută", machine = Attempt.Outcome.Good),
            take(18, 17, 0, word = "sac", machine = Attempt.Outcome.NotJudged),
            take(19, 17, 0, word = "sanie", machine = Attempt.Outcome.Almost, adult = Attempt.Outcome.Good),
            take(20, 17, 0, word = "soare", machine = Attempt.Outcome.Good),
            take(21, 17, 0, word = "sare", machine = Attempt.Outcome.Good),
        )
        val picked = WeekReport.pick(takes)
        assertEquals(WeekReport.TAKES_PER_SOUND, picked.size)
        // Pair: sare Monday and Saturday. Unsure: sac, cos. Not heard: soare (newest).
        assertEquals(setOf("sare", "sac", "cos", "soare"), picked.map { it.attempt.target }.toSet())
        assertEquals(WeekReport.Take.Why.PhoneUnsure, picked.first { it.attempt.target == "sac" }.why)
        assertEquals(WeekReport.Take.Why.NotHeard, picked.first { it.attempt.target == "soare" }.why)
        // The answered "sanie" and the older "sută" wait for a longer list.
        assertTrue(picked.none { it.attempt.target == "sanie" || it.attempt.target == "sută" })
        // Oldest first on the page.
        assertEquals(picked.map { it.attempt.ts }.sorted(), picked.map { it.attempt.ts })
    }

    @Test
    fun fewTakes() {
        val takes = listOf(
            take(16, word = "sare", adult = Attempt.Outcome.Good),
            take(18, word = "cos", machine = Attempt.Outcome.NotYet, adult = Attempt.Outcome.NotYet),
        )
        val picked = WeekReport.pick(takes)
        assertEquals(takes.map { it.id }, picked.map { it.id })
        assertTrue(picked.all { it.why == WeekReport.Take.Why.Other })
        assertTrue(WeekReport.pick(emptyList()).isEmpty())
        assertTrue(WeekReport.pick(takes, limit = 0).isEmpty())
    }

    @Test
    fun limitOne() {
        val takes = listOf(take(16, word = "sare"), take(17, word = "cos", machine = Attempt.Outcome.Almost), take(18, word = "sare"))
        assertEquals(listOf(takes[1].id), WeekReport.pick(takes, limit = 1).map { it.id })
    }

    @Test
    fun noRepeats() {
        val takes = (0 until 12).map { take(16 + it / 3, 17, it, word = if (it % 2 == 0) "sare" else "cos", machine = Attempt.Outcome.Almost) }
        val picked = WeekReport.pick(takes)
        assertEquals(WeekReport.TAKES_PER_SOUND, picked.size)
        assertEquals(picked.size, picked.map { it.id }.toSet().size)
    }

    // The adult's answers

    @Test
    fun earToOutcome() {
        assertEquals(Attempt.Outcome.Good, AdultEar.SoundsGood.outcome(null))
        assertEquals(Attempt.Outcome.Good, AdultEar.SoundsGood.outcome(Attempt.Outcome.NotYet))
        assertEquals(Attempt.Outcome.NotYet, AdultEar.KeepWorking.outcome(null))
        assertEquals(Attempt.Outcome.NotYet, AdultEar.KeepWorking.outcome(Attempt.Outcome.Good))
        assertEquals(Attempt.Outcome.Almost, AdultEar.KeepWorking.outcome(Attempt.Outcome.Almost))
        assertEquals(Attempt.Outcome.NotYet, AdultEar.KeepWorking.outcome(Attempt.Outcome.NotYet))

        assertEquals(AdultEar.SoundsGood, AdultEar.of(Attempt.Outcome.Good))
        assertEquals(AdultEar.KeepWorking, AdultEar.of(Attempt.Outcome.Almost))
        assertEquals(AdultEar.KeepWorking, AdultEar.of(Attempt.Outcome.NotYet))
        assertNull(AdultEar.of(null))
        assertNull(AdultEar.of(Attempt.Outcome.Unclear))
        assertNull(AdultEar.of(Attempt.Outcome.NotJudged))
    }

    @Test
    fun markingKeepsTheList() {
        val report = build(
            pairs = listOf(
                attempt(16, word = "sare", machine = Attempt.Outcome.NotYet, kept = true),
                attempt(17, word = "cos", machine = Attempt.Outcome.Almost, kept = true),
                attempt(19, word = "sare", kept = true),
            ),
        )
        val before = report.sounds[0]
        val id = before.picked[1].id
        val after = report.marking(id, Attempt.Outcome.Good).sounds[0]
        assertEquals(before.picked.map { it.id }, after.picked.map { it.id })
        assertEquals(before.picked.map { it.why }, after.picked.map { it.why })
        assertEquals(AdultEar.SoundsGood, after.picked[1].ear)
        assertEquals(Attempt.Outcome.Good, after.kept.first { it.id == id }.attempt.adultOutcome)
        assertEquals(before.waiting - 1, after.waiting)
        // Rungs are not part of an answer.
        assertEquals(before.rungBefore, after.rungBefore)
        assertEquals(before.rungAfter, after.rungAfter)
    }

    // Weeks and words

    @Test
    fun weekBounds() {
        assertEquals(LocalDate.of(2026, 11, 22), build().sunday)
        assertEquals(LocalDate.of(2026, 11, 9), WeekReport.firstMonday(listOf(session(20), session(11))))
        assertEquals(LocalDate.of(2027, 1, 4), PracticeReport.monday(LocalDate.of(2027, 1, 10)))
        assertNull(WeekReport.firstMonday(emptyList()))
    }

    @Test
    fun headings() {
        assertEquals("Săptămâna asta", WeekCopy.heading(monday, monday))
        assertEquals("Săptămâna trecută", WeekCopy.heading(monday.minusDays(7), monday))
        assertEquals("Săptămâna de la 2 noiembrie", WeekCopy.heading(monday.minusDays(14), monday))
        assertEquals("16 – 22 noiembrie", WeekCopy.range(monday, 2026))
        assertEquals("26 octombrie – 1 noiembrie", WeekCopy.range(LocalDate.of(2026, 10, 26), 2026))
        assertEquals("29 decembrie 2025 – 4 ianuarie", WeekCopy.range(LocalDate.of(2025, 12, 29), 2026))
        assertEquals("16 – 22 noiembrie 2026", WeekCopy.range(monday, 2027))
    }

    @Test
    fun counts() {
        val report = build(
            sessions = listOf(session(16, minutes = 10.0), session(18, minutes = 25.0)),
            pairs = (0 until 21).map { attempt(16, 17, it, kept = it < 6) },
        )
        assertEquals("2 zile cu exerciții, 35 de minute.", WeekCopy.summary(report))
        assertEquals("2 zile, 35 de minute, 21 de încercări.", WeekCopy.facts(report.sounds[0]))
        assertEquals("Am ales 5 din cele 6 înregistrări păstrate. La 6 nu ai spus încă ce ai auzit.", WeekCopy.takesLead(report.sounds[0]))
        assertEquals("o înregistrare", WeekCopy.recordings(1))
        assertEquals("19 înregistrări", WeekCopy.recordings(19))
        assertEquals("20 de înregistrări", WeekCopy.recordings(20))
        assertEquals("101 înregistrări", WeekCopy.recordings(101))

        val one = build(pairs = listOf(attempt(16, kept = true)))
        assertEquals("O singură înregistrare păstrată. La una nu ai spus încă ce ai auzit.", WeekCopy.takesLead(one.sounds[0]))
        val two = build(pairs = listOf(attempt(16, adult = Attempt.Outcome.Good, kept = true), attempt(17, adult = Attempt.Outcome.NotYet, kept = true)))
        assertEquals("Toate cele 2 înregistrări păstrate.", WeekCopy.takesLead(two.sounds[0]))
    }

    @Test
    fun theWeakSoundLineShowsOnlyUnderTheirTakes() {
        // J, GE, CE and Ț: the phone may not hear a good take as good, so the line says the adult's ear counts.
        for (sound in WeakJudgeGate.sounds) {
            val week = build(pairs = listOf(attempt(16, sound = sound.rawValue, kept = true)), order = listOf(sound.rawValue))
            assertTrue(sound.rawValue, WeekCopy.showsWeakSoundMarks(week.sounds, isChild = true))
            assertFalse(sound.rawValue, WeekCopy.showsWeakSoundMarks(week.sounds, isChild = false))
            assertTrue(sound.rawValue, WeekCopy.WEAK_SOUND_MARKS.startsWith("La CE, GE, J și Ț "))
        }
        assertFalse(WeekCopy.showsWeakSoundMarks(build(pairs = listOf(attempt(16, kept = true))).sounds, isChild = true))
        // Practice with nothing to hear: no line.
        assertFalse(WeekCopy.showsWeakSoundMarks(build(pairs = listOf(attempt(16, sound = "j")), order = listOf("j")).sounds, isChild = true))
        assertTrue(WeekCopy.WEAK_SOUND_MARKS in WeekCopy.everyLine)
    }

    @Test
    fun wording() {
        for (line in WeekCopy.everyLine) {
            for (word in listOf("diagnostic", "terapi", "tratează", "tratament", "vârst", "normal", "trist", "fericit", "supărat")) {
                assertFalse(line, line.contains(word, ignoreCase = true))
            }
            assertFalse(line, line.any { it in "ŞşŢţ" })
            assertFalse(line, line.contains("—"))
        }
        assertTrue(WeekCopy.KEEPING_OFF.contains("Păstrează înregistrările copiilor"))
        assertTrue(WeekCopy.STAYS_HERE.contains("nu pleacă de pe telefon"))
        // The first tries at new words stay pinned, as on iOS, so the line says so.
        assertTrue(WeekCopy.STAYS_HERE.endsWith("La fiecare sunet rămân ultimele trei și primele încercări la cuvinte noi."))
    }
}
