package tech.fonia.features.pilot

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.store.Attempt
import tech.fonia.store.Profile
import tech.fonia.store.SessionRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** The pilot event log: what it keeps, how it adds up, what retention and deletion leave, and the export's shape. */
class PilotLogTests {
    private val zone = ZoneId.of("Europe/Bucharest")
    private val log = PilotLog.inMemory(PilotEnvironment("0.1.0", "Test Phone", 35), zone)

    @After
    fun close() = log.close()

    /** 9:00 on [day] in Bucharest. */
    private fun at(day: LocalDate, hour: Int = 9): Instant = ZonedDateTime.of(day.atTime(hour, 0), zone).toInstant()

    private val today: LocalDate = LocalDate.of(2026, 11, 6)

    private fun child(name: String? = "Ana Popescu", createdOn: LocalDate = today.minusDays(20)) = Profile(
        name = name,
        kind = Profile.Kind.Child,
        ageBand = Profile.AgeBand.SixToSeven,
        voiceGroup = Profile.VoiceGroup.Child,
        color = "cold",
        createdAt = at(createdOn, 8),
        targetSounds = listOf("s", "r"),
        track = Profile.Track.Ro,
    )

    private fun attempt(
        profile: Profile,
        on: LocalDate,
        session: UUID? = null,
        take: String = "0.0",
        sound: String? = "s",
        position: SoundPosition = SoundPosition.Initial,
        mode: AttemptMode = AttemptMode.Production,
        machine: Attempt.Outcome = Attempt.Outcome.Good,
        adult: Attempt.Outcome? = null,
        decided: Attempt.Outcome = adult ?: machine,
        feedback: String? = "teach",
        rung: String? = "wordsInitial",
    ) = AttemptRecord(
        profileId = profile.id,
        sessionId = session,
        at = at(on),
        take = take,
        mode = mode,
        exercise = "repeat",
        level = "word",
        sound = sound,
        position = position,
        rung = rung,
        block = "targetA",
        feedback = feedback,
        machine = machine,
        adult = adult,
        self = null,
        decided = decided,
        counted = mode != AttemptMode.Mirror,
        retry = false,
    )

    private fun shadow(profile: Profile, on: LocalDate, shown: Attempt.Outcome, verdict: String?, status: ShadowStatus = ShadowStatus.Scored, abstention: String? = null) =
        ShadowRecord(
            profileId = profile.id,
            sessionId = null,
            at = at(on),
            take = "0.0",
            context = ShadowContext.Session,
            sound = "s",
            position = SoundPosition.Initial,
            level = "word",
            feedback = "teach",
            shown = shown,
            status = status,
            verdict = verdict,
            abstention = abstention,
            confidence = 0.9,
            lpr = 1.5,
            heardAs = "ʃ",
            fit = -0.4,
            frames = 49,
            targets = 1,
            scoreMs = 140,
            waitMs = 3,
            sampleRate = 48_000,
            durationMs = 1000,
            droppedSamples = 0,
            snrDb = 41.0,
            narrowBand = false,
            input = "microphone",
            model = "zipa-small-crctc-500k-int8@dfe87c976626",
            policy = "default",
            dsp = "v1",
        )

    private fun session(profile: Profile, on: LocalDate) = SessionRecord(
        profileId = profile.id,
        track = Profile.Track.Ro,
        sound = "s",
        focus = "sound:s:wordsInitial",
        startedAt = at(on),
        timeZone = zone,
    )

    @Test
    fun countsTheDosePerDayWeekAndSound() = runBlocking {
        val ana = child()
        log.profiles(listOf(ana))
        // Five days in a row of S from Monday 26 October, then a gap, then two days this week.
        val start = LocalDate.of(2026, 10, 26)
        for (offset in 0L until 5L) {
            repeat(10) { log.attempt(attempt(ana, start.plusDays(offset))) }
        }
        // Not the dose: an unclear take, a listening choice, a mirror take, an English attempt with no sound.
        log.attempt(attempt(ana, today, machine = Attempt.Outcome.Unclear))
        log.attempt(attempt(ana, today, mode = AttemptMode.Listening))
        log.attempt(attempt(ana, today, mode = AttemptMode.Mirror))
        log.attempt(attempt(ana, today, sound = null))
        repeat(7) { log.attempt(attempt(ana, today, sound = "r", position = SoundPosition.Final)) }
        repeat(3) { log.attempt(attempt(ana, today.minusDays(1), machine = Attempt.Outcome.NotYet)) }

        val dose = log.dose(ana.id, today, order = ana.targetSounds)
        assertEquals(listOf("s", "r"), dose.sounds.map { it.sound })
        val s = dose.sounds[0]
        assertEquals(10, s.on(start))
        assertEquals(0, s.on(today))
        assertEquals(3, s.on(today.minusDays(1)))
        assertEquals(53, s.total)
        assertEquals(WeekDose(start, 50, 5), s.week(start))
        assertEquals(WeekDose(LocalDate.of(2026, 11, 2), 3, 1), s.week(PilotMath.monday(today)))
        assertEquals(IntensiveWeek(start, daysWithPractice = 5, isOpen = false), s.intensiveWeek)
        assertTrue(s.intensiveWeek!!.isComplete)
        // R started today: its five days are still running.
        val r = dose.sounds[1]
        assertEquals(7, r.on(today))
        assertEquals(IntensiveWeek(today, daysWithPractice = 1, isOpen = true), r.intensiveWeek)
        assertFalse(r.intensiveWeek!!.isComplete)
        assertEquals(LocalDate.of(2026, 11, 2), dose.thisMonday)
    }

    @Test
    fun aGapInTheFirstFiveDaysIsAFactNotALoss() {
        val start = LocalDate.of(2026, 10, 1)
        val days = mapOf(start to 4, start.plusDays(1) to 2, start.plusDays(3) to 9, start.plusDays(4) to 1, start.plusDays(10) to 30)
        val week = PilotMath.intensiveWeek(days, today)!!
        assertEquals(4, week.daysWithPractice)
        assertFalse(week.isComplete)
        assertFalse(week.isOpen)
        assertEquals(start.plusDays(4), week.end)
        // Halfway through, only the days so far count.
        assertEquals(IntensiveWeek(start, 2, isOpen = true), PilotMath.intensiveWeek(days, start.plusDays(2)))
        assertNull(PilotMath.intensiveWeek(emptyMap(), today))
    }

    @Test
    fun retentionMarkersFollowTheSetupDay() {
        val setup = LocalDate.of(2026, 9, 1)
        val active = listOf(setup, setup.plusDays(1), setup.plusDays(8), setup.plusDays(29))
        val early = PilotMath.retention(setup, active, setup.plusDays(20))
        assertEquals(true, early.activeAt[7])
        assertEquals(true, early.activeAt[14])
        assertNull(early.activeAt[30])
        assertNull(early.activeAt[90])
        val late = PilotMath.retention(setup, active, setup.plusDays(95))
        assertEquals(true, late.activeAt[30])
        assertEquals(false, late.activeAt[90])
        assertEquals(setup.plusDays(29), late.lastActive)
        // Day 7's window is days 1..7: practice only on day 0 doesn't count for it.
        assertEquals(false, PilotMath.retention(setup, listOf(setup), setup.plusDays(7)).activeAt[7])
    }

    @Test
    fun positionsAreReadFromTheItem() {
        val s = listOf("s")
        assertEquals(SoundPosition.Initial, SoundPositions.of("sare", s, "word"))
        assertEquals(SoundPosition.Medial, SoundPositions.of("casă", s, "word"))
        assertEquals(SoundPosition.Final, SoundPositions.of("nas", s, "word"))
        assertEquals(SoundPosition.Initial, SoundPositions.of("Sa", s, "syllable"))
        assertEquals(SoundPosition.Isolated, SoundPositions.of("sss", s, "syllable"))
        assertEquals(SoundPosition.Isolated, SoundPositions.of(null, s, "isolated"))
        assertEquals(SoundPosition.Sentence, SoundPositions.of("Sare mare.", s, "word"))
        assertEquals(SoundPosition.Sentence, SoundPositions.of("x", s, "sentence"))
        assertEquals(SoundPosition.Unknown, SoundPositions.of("mare", s, "word"))
        val ce = listOf("ce", "ci")
        assertEquals(SoundPosition.Initial, SoundPositions.of("cer", ce, "word"))
        assertEquals(SoundPosition.Final, SoundPositions.of("pace", ce, "word"))
        assertEquals(SoundPosition.Medial, SoundPositions.of("vecin", ce, "word"))
        // A cedilla T counts as the comma-below one.
        assertEquals(SoundPosition.Initial, SoundPositions.of("ţară", listOf("ț"), "word"))
    }

    @Test
    fun theDetailPeriodEndsInDailyCounters() = runBlocking {
        val ana = child(createdOn = today.minusDays(200))
        log.profiles(listOf(ana))
        val old = today.minusDays(PilotLog.DETAIL_DAYS.toLong() + 10)
        val oldSession = session(ana, old)
        log.sessionStarted(oldSession, "wordsInitial")
        log.sessionEnded(oldSession.id, finished = true, at = at(old).plusSeconds(600))
        repeat(4) { log.attempt(attempt(ana, old, session = oldSession.id)) }
        log.attempt(attempt(ana, old, session = oldSession.id, machine = Attempt.Outcome.Almost))
        log.shadow(shadow(ana, old, Attempt.Outcome.Good, "correct"))
        log.shadow(shadow(ana, old, Attempt.Outcome.Almost, "correct"))
        // And something recent, which stays as it is.
        repeat(2) { log.attempt(attempt(ana, today)) }
        log.shadow(shadow(ana, today, Attempt.Outcome.Good, "correct"))
        val before = log.read { attemptCounters(ana.id.toString()) }

        log.start(at(today))
        val after = log.snapshot()
        assertEquals(2, after.attempts.getValue(ana.id.toString()).size)
        assertEquals(1, after.shadow.getValue(ana.id.toString()).size)
        assertEquals(0, after.rows.getValue("session"))
        assertEquals(1, after.rows.getValue("dailySession"))
        assertEquals(2, after.rows.getValue("dailyShadow"))
        // The counters say the same as the rows they replaced.
        assertEquals(before.sumOf { it.dose }, log.read { attemptCounters(ana.id.toString()) }.sumOf { it.dose })
        assertEquals(before.filter { it.day == old.toString() }, log.read { attemptCounters(ana.id.toString()) }.filter { it.day == old.toString() })
        assertEquals(SessionDay(old.toString(), 1, 1, 600), log.read { sessionDays(ana.id.toString()) }.single { it.day == old.toString() })
        assertEquals(3, log.read { shadowCounters(ana.id.toString()) }.sumOf { it.takes })
        // A second pass adds nothing twice.
        log.start(at(today))
        assertEquals(before.sumOf { it.dose }, log.read { attemptCounters(ana.id.toString()) }.sumOf { it.dose })
    }

    @Test
    fun deletingAProfileOrEverythingReachesEveryRow() = runBlocking {
        val ana = child()
        val dan = child(name = "Dan")
        log.start(at(today))
        log.profiles(listOf(ana, dan))
        for (profile in listOf(ana, dan)) {
            val record = session(profile, today)
            log.sessionStarted(record, "wordsInitial")
            log.attempt(attempt(profile, today, session = record.id))
            log.shadow(shadow(profile, today, Attempt.Outcome.Good, "correct"))
            log.exported(profile.id, at(today))
        }
        log.attempt(attempt(ana, today.minusDays(120)))
        log.start(at(today))

        log.deleteProfile(ana.id)
        val left = log.snapshot()
        assertEquals(listOf(dan.id.toString()), left.profiles.map { it.profileId })
        assertEquals(0, log.read { attemptCounters(ana.id.toString()) }.size)
        assertEquals(0, log.read { access(ana.id.toString()) }.size)

        // A profile that disappears from FoniaStore (wipeAll, or a delete elsewhere) loses its rows too.
        log.profiles(emptyList())
        log.flush()
        assertEquals(PilotSchema.profileTables.associateWith { 0 }, log.snapshot().rows - "install")

        // Rows for a profile the log no longer knows are not written.
        log.attempt(attempt(ana, today))
        log.shadow(shadow(ana, today, Attempt.Outcome.Good, "correct"))
        log.flush()
        assertEquals(0, log.snapshot().rows.getValue("attempt"))

        log.wipe()
        assertTrue(log.snapshot().rows.values.all { it == 0 })
        assertNull(log.snapshot().install)
    }

    @Test
    fun pseudonymsAreRandomAndStay() = runBlocking {
        val ana = child()
        log.profiles(listOf(ana))
        val first = log.snapshot().profiles.single().pseudonym
        assertTrue(first, Regex("P-[2-9A-HJKMNP-Z]{6}").matches(first))
        // Seen again, with a new age band: same pseudonym, new band.
        log.profiles(listOf(ana.copy(ageBand = Profile.AgeBand.EightToTen)))
        val again = log.snapshot().profiles.single()
        assertEquals(first, again.pseudonym)
        assertEquals("8-10", again.ageBand)
        assertEquals(today.minusDays(20).toString(), again.setupDay)
    }

    @Test
    fun theSummaryForTheLogopedIsAggregatedAndNamesNoOne() = runBlocking {
        val ana = child()
        log.start(at(today.minusDays(21)))
        log.profiles(listOf(ana))
        val day1 = today.minusDays(2)
        val record = session(ana, day1)
        log.sessionStarted(record, "wordsInitial")
        log.attempt(attempt(ana, day1, session = record.id, take = "0.0", adult = Attempt.Outcome.Good))
        log.attempt(attempt(ana, day1, session = record.id, take = "1.0", machine = Attempt.Outcome.Almost, adult = Attempt.Outcome.Good))
        log.attempt(attempt(ana, day1, session = record.id, take = "2.0", machine = Attempt.Outcome.Unclear, adult = Attempt.Outcome.NotYet, feedback = "quick"))
        log.attempt(attempt(ana, day1, session = record.id, take = "3.0", position = SoundPosition.Medial, rung = "wordsMedial"))
        log.attempt(attempt(ana, day1, mode = AttemptMode.Mirror))
        log.sessionEnded(record.id, finished = true, at = at(day1).plusSeconds(8 * 60))
        log.shadow(shadow(ana, day1, Attempt.Outcome.Good, "correct"))
        log.shadow(shadow(ana, day1, Attempt.Outcome.Almost, "correct"))
        log.shadow(shadow(ana, day1, Attempt.Outcome.Almost, "notHeard", abstention = "uncertain"))
        log.shadow(shadow(ana, day1, Attempt.Outcome.Unclear, "tryAgain"))
        log.shadow(shadow(ana, day1, Attempt.Outcome.Good, null, status = ShadowStatus.Dropped))
        log.exported(ana.id, at(today))

        val (name, bytes) = log.export(ana, dspThresholdsVersion = 1, today = today)
        val text = bytes.toString(Charsets.UTF_8)
        val document = Json.parseToJsonElement(text).jsonObject
        val pseudonym = document.getValue("pseudonym").jsonPrimitive.content
        assertEquals("fonia-exersare-$pseudonym.json", name)

        // Nothing typed or said: no name, no word, no UUID.
        for (absent in listOf("Ana", "Popescu", "sare", ana.id.toString(), record.id.toString())) assertFalse(absent, text.contains(absent))

        assertEquals(
            listOf(
                "format", "formatVersion", "generatedOn", "pseudonym", "profile", "period", "app", "phone", "activation", "retention",
                "days", "weeks", "sounds", "phoneVsAdult", "shadow", "accessLog",
            ),
            document.keys.toList(),
        )
        assertEquals(PilotExport.FORMAT, document.getValue("format").jsonPrimitive.content)
        assertEquals(1, document.getValue("formatVersion").jsonPrimitive.int)
        assertEquals("""{"kind":"child","ageBand":"6-7","targetSounds":["s","r"]}""", document.getValue("profile").toString())
        assertEquals("""{"version":"0.1.0","dspThresholds":1,"detailDays":90}""", document.getValue("app").toString())
        assertEquals("""{"model":"Test Phone","androidSdk":35}""", document.getValue("phone").toString())
        val activation = document.getValue("activation").jsonObject
        assertEquals(today.minusDays(21).toString(), activation.getValue("loggingSince").jsonPrimitive.content)
        assertEquals(today.minusDays(20).toString(), activation.getValue("setUpOn").jsonPrimitive.content)
        assertEquals(day1.toString(), activation.getValue("firstSessionFinishedOn").jsonPrimitive.content)
        // Set up on day 0, practised on day 18 only: not active in days 1..7 nor 8..14; day 30 isn't here yet.
        val retention = document.getValue("retention").jsonObject
        assertEquals(RetentionMarkers.DEFINITION, retention.getValue("definition").jsonPrimitive.content)
        assertEquals(day1.toString(), retention.getValue("lastActiveOn").jsonPrimitive.content)
        assertEquals("false", retention.getValue("activeAtDay7").jsonPrimitive.content)
        assertEquals("false", retention.getValue("activeAtDay14").jsonPrimitive.content)
        assertEquals(JsonNull, retention.getValue("activeAtDay30"))

        val day = document.getValue("days").jsonArray.single().jsonObject
        assertEquals(
            """{"date":"$day1","sessions":1,"sessionsFinished":1,"minutes":8,"productionAttempts":4,"doseAttempts":4,"listeningAttempts":0,"mirrorAttempts":1}""",
            day.toString(),
        )
        assertEquals("""[{"weekOf":"2026-11-02","activeDays":1,"minutes":8,"doseAttempts":4}]""", document.getValue("weeks").toString())

        val s = document.getValue("sounds").jsonArray.single().jsonObject
        assertEquals("s", s.getValue("sound").jsonPrimitive.content)
        assertEquals(4, s.getValue("doseAttempts").jsonPrimitive.int)
        assertEquals("""{"startedOn":"$day1","daysWithPractice":1,"complete":false,"open":true}""", s.getValue("intensiveWeek").toString())
        val items = s.getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(listOf("initial", "medial"), items.map { it.getValue("position").jsonPrimitive.content })
        // The adult's verdict decides: good, good, not yet (the phone abstained on it).
        assertEquals(
            """{"position":"initial","rung":"wordsInitial","attempts":3,"doseAttempts":3,"good":2,"almost":0,"notYet":1,"unjudged":0,"percentGood":66.7,"activeDays":1,"byFeedback":{"quick":1,"teach":2}}""",
            items[0].toString(),
        )

        assertEquals("""[{"sound":"s","compared":2,"agreed":1,"phoneAbstained":1}]""", document.getValue("phoneVsAdult").toString())

        val shadow = document.getValue("shadow").jsonObject
        assertEquals(5, shadow.getValue("takes").jsonPrimitive.int)
        assertEquals(4, shadow.getValue("scored").jsonPrimitive.int)
        assertEquals(1, shadow.getValue("dropped").jsonPrimitive.int)
        val bySound = (shadow.getValue("bySound") as JsonArray).single().jsonObject
        assertEquals(4, bySound.getValue("scored").jsonPrimitive.int)
        // good/correct agrees, almost/correct doesn't, almost/notHeard is ZIPA abstaining, unclear/tryAgain the phone.
        assertEquals(2, bySound.getValue("bothDecided").jsonPrimitive.int)
        assertEquals(1, bySound.getValue("agreed").jsonPrimitive.int)
        assertEquals(1, bySound.getValue("phoneAbstained").jsonPrimitive.int)
        assertEquals(1, bySound.getValue("zipaAbstained").jsonPrimitive.int)
        assertEquals("""{"almost":{"correct":1,"notHeard":1},"good":{"correct":1},"unclear":{"tryAgain":1}}""", bySound.getValue("matrix").toString())
        assertEquals("""{"uncertain":1}""", bySound.getValue("zipaAbstentions").toString())
        assertEquals(140, bySound.getValue("medianScoreMs").jsonPrimitive.int)
        assertEquals("""[{"on":"$today","action":"export"}]""", document.getValue("accessLog").toString())
    }

    @Test
    fun writesReturnAtOnceAndKeepTheirOrder() = runBlocking {
        val ana = child()
        log.profiles(listOf(ana))
        val started = System.nanoTime()
        repeat(2_000) { index -> log.attempt(attempt(ana, today, take = "$index.0")) }
        val queuedMs = (System.nanoTime() - started) / 1_000_000
        log.flush()
        val takes = log.snapshot().attempts.getValue(ana.id.toString()).map { it["take"] }
        assertEquals((0 until 2_000).map { "$it.0" }, takes)
        // Queuing 2,000 rows doesn't wait for SQLite (a generous bound, for a busy CI machine).
        assertTrue("queued in $queuedMs ms", queuedMs < 2_000)
        assertNotNull(log.snapshot().profiles.single())
    }
}
