package tech.fonia.features.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.design.Rating
import tech.fonia.domain.AttemptsSummary
import tech.fonia.domain.ContentSource
import tech.fonia.domain.EnglishFocus
import tech.fonia.domain.Exercise
import tech.fonia.domain.FeedbackLevel
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.Rung
import tech.fonia.domain.SessionBlock
import tech.fonia.domain.SessionFocus
import tech.fonia.domain.SoundID
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.AudioHub
import tech.fonia.features.app.NoAudioBackend
import tech.fonia.features.app.SessionTicket
import tech.fonia.features.app.SpeechHub
import tech.fonia.features.app.calibration
import tech.fonia.features.app.index
import tech.fonia.features.app.progressionState
import tech.fonia.store.Attempt
import tech.fonia.store.FoniaDatabase
import tech.fonia.store.FoniaRepository
import tech.fonia.store.Profile
import tech.fonia.store.SoundProgress
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * `SessionRunTests.swift` (suites "SessionRun" and "Calibration bridge"), test for test, with the same inputs and
 * expectations. The run talks to a real database in memory, as on iOS; the audio is the JVM stand-in, which plays
 * nothing, as iOS's `AudioHub(voices: nil, effectsDirectory: nil, usesFixtures: true)` has nothing to play.
 */
class SessionRunTests {
    private fun makeApp(): AppModel {
        val folder = Files.createTempDirectory("fonia-run").toFile()
        // No main thread on the JVM: the app's own scope runs where it's called, saves included.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return AppModel(
            content = content,
            repository = FoniaRepository(FoniaDatabase.inMemory(recordingsDirectory = File(folder, "Recordings"))),
            audio = AudioHub(voices = null, backend = NoAudioBackend(null), usesFixtures = true),
            speech = SpeechHub(model = null, scope = scope),
            scope = scope,
        )
    }

    private fun child(band: Profile.AgeBand = Profile.AgeBand.SixToSeven, confirmed: List<String> = listOf("s"), sound: String = "s"): Profile =
        Profile(
            kind = Profile.Kind.Child,
            ageBand = band,
            voiceGroup = Profile.VoiceGroup.Child,
            color = "cold",
            targetSounds = listOf(sound),
            confirmedSounds = confirmed,
            track = Profile.Track.Ro,
        )

    private val zone: ZoneId = ZoneId.of("Europe/Bucharest")

    private fun day(day: Int, hour: Int = 10): Instant = LocalDateTime.of(2026, 11, day, hour, 0).atZone(zone).toInstant()

    /** A good full block on [rung] that ended on an earlier day than the sessions below. */
    private fun goodBlockYesterday(profile: Profile, sound: String = "s", rung: Rung): SoundProgress =
        SoundProgress(
            profileId = profile.id,
            sound = sound,
            rung = rung.index,
            blockHistory = listOf(SoundProgress.Block(rung = rung.index, good = 6, total = 6, endedAt = day(19))),
        )

    /** Runs one whole session of [sound] at [rung] on the given day, with what the store holds, and returns the saved rung. */
    private suspend fun session(
        app: AppModel,
        profile: Profile,
        sound: SoundID = SoundID.S,
        rung: Rung,
        on: Instant,
        machine: Attempt.Outcome,
        adult: Attempt.Outcome? = null,
        selfRating: Rating? = null,
    ): Int {
        val saved = app.repository.soundProgress(profile.id).firstOrNull { it.sound == sound.rawValue }
        val ticket = SessionTicket(focus = SessionFocus.Sound(sound, rung), title = "test", sound = sound)
        val run = SessionRun(ticket, profile, app, progress = saved, history = AttemptsSummary(), now = { on }, zone = zone)
        play(run, machine, adult = adult, selfRating = selfRating)
        assertEquals(SessionRun.Phase.Finished, run.phase)
        delay(50)
        run.awaitSaves()
        return app.repository.soundProgress(profile.id).firstOrNull { it.sound == sound.rawValue }?.rung ?: -1
    }

    private fun adultProfile(sound: String): Profile = Profile(
        kind = Profile.Kind.Adult,
        ageBand = Profile.AgeBand.Adult,
        voiceGroup = Profile.VoiceGroup.Female,
        color = "cold",
        targetSounds = listOf(sound),
        confirmedSounds = listOf(sound),
        track = Profile.Track.Ro,
    )

    private fun makeRun(app: AppModel, profile: Profile, rung: Rung, focus: SessionFocus? = null): SessionRun {
        val ticket = SessionTicket(focus = focus ?: SessionFocus.Sound(SoundID.S, rung), title = "test", sound = if (focus == null) SoundID.S else null)
        return SessionRun(ticket, profile, app, progress = null, history = AttemptsSummary())
    }

    /** Plays every trial with the same answer. Returns how many trials were seen. */
    private fun play(
        run: SessionRun,
        machine: Attempt.Outcome,
        adult: Attempt.Outcome? = null,
        selfRating: Rating? = null,
        retryFirst: Boolean = false,
    ): Int {
        var seen = 0
        var retried = false
        while (run.phase == SessionRun.Phase.Trial && seen < 500) {
            val trial = run.current ?: break
            seen += 1
            if (trial.countsAsAttempt) {
                if (retryFirst && !retried && trial.block.kind == SessionBlock.Kind.TargetA) {
                    retried = true
                    run.finishAttempt(trial.exercise, machine = Attempt.Outcome.Almost, adult = null, take = null, retry = true)
                }
                run.finishAttempt(trial.exercise, machine = machine, adult = adult, selfRating = selfRating, take = null)
            } else {
                run.advance()
            }
        }
        return seen
    }

    /** iOS waits 50 ms for the saves; here the run also says when the saves it started are done. */
    private suspend fun savedRung(app: AppModel, profile: Profile, run: SessionRun): Int {
        delay(50)
        run.awaitSaves()
        return app.repository.soundProgress(profile.id).firstOrNull { it.sound == "s" }?.rung ?: -1
    }

    /** at automatizare a take whose verdict stays hidden is still judged, stored and counted */
    @Test
    fun hiddenVerdictsStillCount() = runBlocking {
        val app = makeApp()
        val profile = child()
        app.repository.saveProfile(profile)
        val saved = goodBlockYesterday(profile, rung = Rung.Sentences)
        app.repository.saveSoundProgress(saved)
        val ticket = SessionTicket(focus = SessionFocus.Sound(SoundID.S, Rung.Sentences), title = "test", sound = SoundID.S)
        val run = SessionRun(ticket, profile, app, progress = saved, history = AttemptsSummary(), now = { day(20) }, zone = zone)
        assertTrue(run.isAutomatizare)
        run.start()
        var played = 0
        var hidden = 0
        var seen = 0
        while (run.phase == SessionRun.Phase.Trial && seen < 500) {
            val trial = run.current ?: break
            seen += 1
            if (trial.countsAsAttempt) {
                played += 1
                if (trial.feedback == FeedbackLevel.Silent) hidden += 1
                // What `SpeakExercise` does with a hidden take: the phone's outcome, no adult, no retry.
                run.finishAttempt(trial.exercise, machine = Attempt.Outcome.Good, adult = null, take = null)
            } else {
                run.advance()
            }
        }
        assertEquals(SessionRun.Phase.Finished, run.phase)
        assertTrue(hidden > 0)
        assertTrue(hidden * 2 <= played + 1)
        assertEquals(played, run.attempts)
        // So the two-good-days rule still moves the child on.
        assertEquals(Rung.Retell.index, savedRung(app, profile, run))
        assertEquals(played, app.repository.attempts(run.sessionID).size)
        // A block of six counts all six, the three answered and the three only heard.
        val progress = app.repository.soundProgress(profile.id).first { it.sound == "s" }
        val today = progress.blockHistory.filter { it.endedAt > day(19) }
        assertTrue(today.isNotEmpty())
        assertTrue(today.all { it.total == 6 && it.good == 6 })
    }

    /** only a sound at sentences, retelling or everyday speech is at automatizare */
    @Test
    fun automatizareRuns() {
        val app = makeApp()
        for (rung in Rung.entries) {
            assertEquals(rung in listOf(Rung.Sentences, Rung.Retell, Rung.Everyday), makeRun(app, child(), rung).isAutomatizare)
        }
        assertFalse(makeRun(app, child(), Rung.Sentences, focus = SessionFocus.Diction(tech.fonia.domain.DictionAreaID.Pitch)).isAutomatizare)
    }

    /** a whole good session moves one rung, not one per block */
    @Test
    fun oneRungPerSession() = runBlocking {
        val app = makeApp()
        val profile = child()
        app.repository.saveProfile(profile)
        val saved = goodBlockYesterday(profile, rung = Rung.Syllables)
        app.repository.saveSoundProgress(saved)
        val ticket = SessionTicket(focus = SessionFocus.Sound(SoundID.S, Rung.Syllables), title = "test", sound = SoundID.S)
        val run = SessionRun(ticket, profile, app, progress = saved, history = AttemptsSummary(), now = { day(20) }, zone = zone)
        play(run, Attempt.Outcome.Good)
        assertEquals(SessionRun.Phase.Finished, run.phase)
        assertEquals(Rung.WordsInitial.index, savedRung(app, profile, run))
    }

    /** one good session moves a child nowhere yet */
    @Test
    fun oneGoodDayIsNotEnough() = runBlocking {
        val app = makeApp()
        val profile = child()
        app.repository.saveProfile(profile)
        app.repository.saveSoundProgress(SoundProgress(profileId = profile.id, sound = "s", rung = Rung.Syllables.index))
        val rung = session(app, profile, rung = Rung.Syllables, on = day(20), machine = Attempt.Outcome.Good)
        assertEquals(Rung.Syllables.index, rung)
    }

    /** good sessions on two days move a child one rung */
    @Test
    fun twoGoodDaysMoveUp() = runBlocking {
        val app = makeApp()
        val profile = child()
        app.repository.saveProfile(profile)
        app.repository.saveSoundProgress(SoundProgress(profileId = profile.id, sound = "s", rung = Rung.Syllables.index))
        assertEquals(Rung.Syllables.index, session(app, profile, rung = Rung.Syllables, on = day(19), machine = Attempt.Outcome.Good))
        assertEquals(Rung.WordsInitial.index, session(app, profile, rung = Rung.Syllables, on = day(20), machine = Attempt.Outcome.Good))
    }

    /** two good sessions on the same day don't move a child */
    @Test
    fun twoSessionsOneDayStay() = runBlocking {
        val app = makeApp()
        val profile = child()
        app.repository.saveProfile(profile)
        app.repository.saveSoundProgress(SoundProgress(profileId = profile.id, sound = "s", rung = Rung.Syllables.index))
        session(app, profile, rung = Rung.Syllables, on = day(20, hour = 8), machine = Attempt.Outcome.Good)
        assertEquals(Rung.Syllables.index, session(app, profile, rung = Rung.Syllables, on = day(20, hour = 19), machine = Attempt.Outcome.Good))
    }

    /** an adult still moves up after one good session */
    @Test
    fun adultPacesThemselves() = runBlocking {
        val app = makeApp()
        val adult = adultProfile("s")
        app.repository.saveProfile(adult)
        app.repository.saveSoundProgress(SoundProgress(profileId = adult.id, sound = "s", rung = Rung.Syllables.index))
        assertEquals(Rung.WordsInitial.index, session(app, adult, rung = Rung.Syllables, on = day(20), machine = Attempt.Outcome.Good))
    }

    /** an adult's own rating still counts when the phone abstains */
    @Test
    fun adultSelfRatingCounts() = runBlocking {
        val app = makeApp()
        val adult = adultProfile("l")
        app.repository.saveProfile(adult)
        val rung = session(app, adult, sound = SoundID.L, rung = Rung.Syllables, on = day(20), machine = Attempt.Outcome.NotJudged, selfRating = Rating.yes)
        assertEquals(Rung.WordsInitial.index, rung)
    }

    /** a child's own ratings on L never move a rung, over any number of days */
    @Test
    fun childSelfRatingNeverAdvances() = runBlocking {
        val app = makeApp()
        val profile = child(confirmed = listOf("l"), sound = "l")
        app.repository.saveProfile(profile)
        for (date in listOf(day(18), day(19), day(20))) {
            val rung = session(app, profile, sound = SoundID.L, rung = Rung.Syllables, on = date, machine = Attempt.Outcome.NotJudged, selfRating = Rating.yes)
            assertTrue("rung $rung", rung == -1 || rung == Rung.Syllables.index)
        }
    }

    /** a child's own rating is still praised as a good take */
    @Test
    fun childSelfRatingStillCountsAsPractice() {
        val app = makeApp()
        val profile = child(confirmed = listOf("l"), sound = "l")
        val run = makeRun(app, profile, Rung.Syllables, focus = SessionFocus.Sound(SoundID.L, Rung.Syllables))
        while (true) {
            val trial = run.current ?: break
            if (trial.countsAsAttempt) break
            run.advance()
        }
        val trial = assertNotNullAndGet(run.current)
        run.finishAttempt(trial.exercise, machine = Attempt.Outcome.NotJudged, adult = null, selfRating = Rating.yes, take = null)
        assertEquals(1, run.attempts)
        assertEquals(1, run.goodAttempts)
        assertFalse(SessionRun.countsTowardBlock(isChild = true, machineJudged = false, adult = null))
        assertTrue(SessionRun.countsTowardBlock(isChild = true, machineJudged = false, adult = Attempt.Outcome.Good))
        assertTrue(SessionRun.countsTowardBlock(isChild = false, machineJudged = false, adult = null))
    }

    /** an adult who listens to L on two days still moves the child up */
    @Test
    fun adultRatingsOnLStillAdvance() = runBlocking {
        val app = makeApp()
        val profile = child(confirmed = listOf("l"), sound = "l")
        app.repository.saveProfile(profile)
        session(app, profile, sound = SoundID.L, rung = Rung.Syllables, on = day(19), machine = Attempt.Outcome.NotJudged, adult = Attempt.Outcome.Good, selfRating = Rating.yes)
        val rung = session(app, profile, sound = SoundID.L, rung = Rung.Syllables, on = day(20), machine = Attempt.Outcome.NotJudged, adult = Attempt.Outcome.Good)
        assertEquals(Rung.WordsInitial.index, rung)
    }

    /** progress saved before the two-day rule still loads where it was */
    @Test
    fun earlierProgressStays() = runBlocking {
        val app = makeApp()
        val profile = child()
        app.repository.saveProfile(profile)
        val saved = SoundProgress(
            profileId = profile.id,
            sound = "s",
            rung = Rung.Clusters.index,
            blockHistory = listOf(SoundProgress.Block(rung = Rung.WordsFinal.index, good = 5, total = 6, endedAt = day(1))),
        )
        app.repository.saveSoundProgress(saved)
        val loaded = app.repository.soundProgress(profile.id).firstOrNull { it.sound == "s" }
        assertEquals(Rung.Clusters.index, loaded?.rung)
        assertEquals(Rung.Clusters, profile.progressionState(SoundID.S, loaded).rung)
    }

    /** going back over an earlier rung moves nothing */
    @Test
    fun reviewStaysPut() = runBlocking {
        val app = makeApp()
        val profile = child()
        app.repository.saveProfile(profile)
        val saved = SoundProgress(profileId = profile.id, sound = "s", rung = Rung.WordsInitial.index)
        app.repository.saveSoundProgress(saved)
        val ticket = SessionTicket(focus = SessionFocus.Sound(SoundID.S, Rung.Isolated), title = "test", sound = SoundID.S)
        val run = SessionRun(ticket, profile, app, progress = saved, history = AttemptsSummary())
        play(run, Attempt.Outcome.NotYet)
        delay(100)
        assertEquals(Rung.WordsInitial.index, savedRung(app, profile, run))
    }

    /** a retried take doesn't void the block */
    @Test
    fun retryKeepsBlock() = runBlocking {
        val app = makeApp()
        val profile = child()
        app.repository.saveProfile(profile)
        val saved = goodBlockYesterday(profile, rung = Rung.Syllables)
        app.repository.saveSoundProgress(saved)
        val ticket = SessionTicket(focus = SessionFocus.Sound(SoundID.S, Rung.Syllables), title = "test", sound = SoundID.S)
        val run = SessionRun(ticket, profile, app, progress = saved, history = AttemptsSummary(), now = { day(20) }, zone = zone)
        play(run, Attempt.Outcome.Good, retryFirst = true)
        assertEquals(Rung.WordsInitial.index, savedRung(app, profile, run))
    }

    /** takes nobody could judge don't count and don't drop a rung */
    @Test
    fun abstentionsDecideNothing() = runBlocking {
        val app = makeApp()
        val profile = child()
        app.repository.saveProfile(profile)
        val run = makeRun(app, profile, Rung.WordsInitial)
        play(run, Attempt.Outcome.Unclear)
        assertEquals(0, run.attempts)
        val rung = savedRung(app, profile, run)
        assertTrue("rung $rung", rung == -1 || rung == Rung.WordsInitial.index)
    }

    /** real misses drop one rung, once */
    @Test
    fun missesDropOnce() = runBlocking {
        val app = makeApp()
        val profile = child()
        app.repository.saveProfile(profile)
        val run = makeRun(app, profile, Rung.WordsInitial)
        play(run, Attempt.Outcome.NotYet)
        assertEquals(Rung.Syllables.index, savedRung(app, profile, run))
    }

    /** a second finish for the same trial is ignored */
    @Test
    fun oneFinishPerTrial() {
        val app = makeApp()
        val run = makeRun(app, child(), Rung.Syllables)
        while (true) {
            val trial = run.current ?: break
            if (trial.countsAsAttempt) break
            run.advance()
        }
        val start = run.index
        val trial = assertNotNullAndGet(run.current)
        run.finishAttempt(trial.exercise, machine = Attempt.Outcome.Good, adult = null, take = null)
        assertEquals(start + 1, run.index)
        assertEquals(1, run.attempts)
    }

    /** nothing is recorded after leaving */
    @Test
    fun closedRunIgnoresEverything() = runBlocking {
        val app = makeApp()
        val run = makeRun(app, child(), Rung.Syllables)
        while (true) {
            val trial = run.current ?: break
            if (trial.countsAsAttempt) break
            run.advance()
        }
        val trial = assertNotNullAndGet(run.current)
        run.leaveEarly()
        run.finishAttempt(trial.exercise, machine = Attempt.Outcome.Good, adult = null, take = null)
        assertEquals(0, run.attempts)
    }

    /** a mission agreed to is asked about at the next session, on a later day */
    @Test
    fun missionIsNoted() = runBlocking {
        val app = makeApp()
        val profile = child()
        app.repository.saveProfile(profile)
        val ticket = SessionTicket(focus = SessionFocus.Sound(SoundID.S, Rung.Everyday), title = "test", sound = SoundID.S)
        val run = SessionRun(ticket, profile, app, progress = null, history = AttemptsSummary(), now = { day(20, hour = 18) }, zone = zone)
        // Starting stores the session's row, which the mission belongs to.
        run.start()
        play(run, machine = Attempt.Outcome.Good)
        assertEquals(SessionRun.Phase.Finished, run.phase)
        delay(50)
        run.awaitSaves()
        val pending = app.repository.pendingMission(profile.id, today = "2026-11-21")
        assertEquals("s", pending?.sound)
        assertEquals(tech.fonia.store.localDay(day(20, hour = 18), zone), pending?.localDay)
    }

    /** a session without a mission leaves nothing to ask */
    @Test
    fun noMissionNoQuestion() = runBlocking {
        val app = makeApp()
        val profile = child()
        app.repository.saveProfile(profile)
        val run = makeRun(app, profile, Rung.Syllables)
        play(run, machine = Attempt.Outcome.Good)
        delay(50)
        run.awaitSaves()
        assertEquals(null, app.repository.pendingMission(profile.id, today = "2100-01-01"))
    }

    /** the session on everyday practice still reaches its mission */
    @Test
    fun missionIsReached() {
        val app = makeApp()
        val run = makeRun(app, child(), Rung.Everyday)
        var sawMission = false
        var seen = 0
        while (run.phase == SessionRun.Phase.Trial && seen < 500) {
            val trial = run.current ?: break
            seen += 1
            if (trial.exercise is Exercise.Mission) sawMission = true
            if (trial.countsAsAttempt) {
                run.finishAttempt(trial.exercise, machine = Attempt.Outcome.Good, adult = null, take = null)
            } else {
                run.advance()
            }
        }
        assertTrue(sawMission)
    }

    /** adults don't get the hero story */
    @Test
    fun adultsSkipIntro() {
        val app = makeApp()
        val adult = Profile(kind = Profile.Kind.Adult, ageBand = Profile.AgeBand.Adult, voiceGroup = Profile.VoiceGroup.Female, color = "cold", track = Profile.Track.Ro)
        val run = makeRun(app, adult, Rung.Isolated)
        val hasIntro = run.session.trials.any { it.exercise is Exercise.Intro }
        assertFalse(hasIntro)
    }

    /** a retest reports identification without counting it as speech */
    @Test
    fun retestCounts() {
        val app = makeApp()
        val adult = Profile(kind = Profile.Kind.Adult, ageBand = Profile.AgeBand.Adult, voiceGroup = Profile.VoiceGroup.Female, color = "cold", track = Profile.Track.En)
        val run = makeRun(app, adult, Rung.Isolated, focus = SessionFocus.English(EnglishFocus.Retest(contrast = "ship-sheep")))
        play(run, Attempt.Outcome.Good)
        assertTrue(run.identified > 0)
        assertEquals(run.identified, run.identifiedRight)
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    // The easy closing item and the chapter (port-android.md §2.4, §2.9; SessionRunTests.swift).

    private fun walk(run: SessionRun, retryEveryTarget: Boolean = false): List<tech.fonia.domain.Trial> {
        val shown = mutableListOf<tech.fonia.domain.Trial>()
        while (run.phase == SessionRun.Phase.Trial && shown.size < 500) {
            val trial = run.current ?: break
            shown += trial
            if (trial.countsAsAttempt) {
                if (retryEveryTarget && trial.block.kind == SessionBlock.Kind.TargetA) {
                    run.finishAttempt(trial.exercise, machine = Attempt.Outcome.Almost, adult = null, take = null, retry = true)
                }
                run.finishAttempt(trial.exercise, machine = Attempt.Outcome.Good, adult = null, take = null)
            } else {
                run.advance()
            }
        }
        return shown
    }

    /** the last item is one the child already said well in this session */
    @Test
    fun closingIsASureWin() {
        val run = makeRun(makeApp(), child(), Rung.WordsMedial)
        val shown = walk(run)
        assertEquals(SessionRun.Phase.Finished, run.phase)
        val last = shown.last()
        assertEquals(SessionBlock.Kind.Closing, last.block.kind)
        assertTrue(shown.dropLast(1).filter { it.countsAsAttempt }.map { it.exercise }.contains(last.exercise))
    }

    /** a session that reaches its dose early still ends on the easy item */
    @Test
    fun earlyStopGoesToClosing() {
        val run = makeRun(makeApp(), child(), Rung.WordsMedial)
        val total = run.session.trials.size
        val shown = walk(run, retryEveryTarget = true)
        assertTrue(shown.size < total)
        assertEquals(SessionBlock.Kind.Closing, shown.last().block.kind)
        assertFalse(shown.dropLast(1).any { it.block.kind == SessionBlock.Kind.Closing })
        assertEquals(1.0, run.progressValue, 0.0)
    }

    /** a session cut short still ends on the easy item, then the mission */
    @Test
    fun closingThenMission() {
        val run = makeRun(makeApp(), child(), Rung.Everyday)
        val kinds = walk(run, retryEveryTarget = true).map { it.block.kind }
        assertEquals(listOf(SessionBlock.Kind.Closing, SessionBlock.Kind.Mission), kinds.takeLast(2))
    }

    /** a child's lesson carries its hero's chapter; an adult's does not */
    @Test
    fun chapterIsForChildren() {
        val app = makeApp()
        val run = makeRun(app, child(), Rung.Isolated)
        assertEquals(content.sound(SoundID.S).hero.chapter("isolated"), run.chapter)
        assertNotNull(run.chapter)
        val adult = Profile(kind = Profile.Kind.Adult, ageBand = Profile.AgeBand.Adult, voiceGroup = Profile.VoiceGroup.Male, color = "cold", track = Profile.Track.Ro)
        assertNull(makeRun(app, adult, Rung.Isolated).chapter)
    }

    /** a child is told a part is done, never a count; the end counts effort with dată/ori */
    @Test
    fun childSeesNoNumbersOfRightAnswers() {
        assertEquals("Încă o parte gata. Bravo!", SessionRun.blockNote(2, 6, isChild = true))
        assertEquals("2 din 6 au sunat bine.", SessionRun.blockNote(2, 6, isChild = false))
        assertEquals("Ai încercat o dată.", effortLine(1, onlyListening = false))
        assertEquals("Ai încercat de 12 ori.", effortLine(12, onlyListening = false))
        assertEquals("Ai ascultat de 20 de ori.", effortLine(20, onlyListening = true))
        assertNull(effortLine(0, onlyListening = false))
    }

    /** "aproape" and "încă nu" become one soft line for a child; praise and the unjudged stay */
    @Test
    fun verdictsAreSoftenedForAChild() {
        val nearly = TakeVerdict(rating = Rating.nearly, outcome = Attempt.Outcome.Almost, title = "Aproape. A sunat ca Ș", tip = "Zâmbește.")
        val soft = nearly.softenedForChild
        assertEquals("Hai să mai încercăm", soft.title)
        assertNull(soft.tip)
        assertEquals(Attempt.Outcome.Almost, soft.outcome)
        val good = TakeVerdict(rating = Rating.yes, outcome = Attempt.Outcome.Good, title = "Bravo!")
        assertEquals(good, good.softenedForChild)
    }

    companion object {
        val content: FoniaContent by lazy {
            FoniaContent.load(ContentSource.directory(File(System.getProperty("fonia.content") ?: "../content")))
        }
    }
}

/** Suite "Calibration bridge". */
class CalibrationBridgeTests {
    /** children get an age prior before any anchors exist */
    @Test
    fun childPrior() {
        val child = Profile(kind = Profile.Kind.Child, ageBand = Profile.AgeBand.FourToFive, voiceGroup = Profile.VoiceGroup.Child, color = "cold", track = Profile.Track.Ro)
        assertEquals(5, child.calibration?.ageYears)
        val adult = Profile(kind = Profile.Kind.Adult, ageBand = Profile.AgeBand.Adult, voiceGroup = Profile.VoiceGroup.Male, color = "cold", track = Profile.Track.En)
        assertNull(adult.calibration)
    }

}
