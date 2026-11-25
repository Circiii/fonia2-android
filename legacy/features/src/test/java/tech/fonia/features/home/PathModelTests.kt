package tech.fonia.features.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.ContentSource
import tech.fonia.domain.CourseUnit
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.Rung
import tech.fonia.domain.SessionFocus
import tech.fonia.domain.SoundID
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.AudioHub
import tech.fonia.features.app.NoAudioBackend
import tech.fonia.features.app.SpeechHub
import tech.fonia.features.app.index
import tech.fonia.features.session.key
import tech.fonia.store.FoniaDatabase
import tech.fonia.store.FoniaRepository
import tech.fonia.store.Profile
import tech.fonia.store.SessionRecord
import tech.fonia.store.SoundProgress
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * `PathModelTests.swift` (suite "PathModel"), test for test, with the same inputs and expectations. The model runs on
 * a real database in memory, as on iOS; the content comes from the `content/` submodule.
 */
class PathModelTests {
    private fun makeApp(): AppModel {
        val folder = Files.createTempDirectory("fonia-path").toFile()
        // No main thread on the JVM: the app's own scope runs where it's called.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return AppModel(
            content = content,
            repository = FoniaRepository(FoniaDatabase.inMemory(recordingsDirectory = File(folder, "Recordings"))),
            audio = AudioHub(voices = null, backend = NoAudioBackend(null), usesFixtures = true),
            speech = SpeechHub(model = null, scope = scope),
            scope = scope,
        )
    }

    private fun child(confirmed: List<String> = emptyList(), targets: List<String> = listOf("s")): Profile =
        Profile(kind = Profile.Kind.Child, ageBand = Profile.AgeBand.SixToSeven, voiceGroup = Profile.VoiceGroup.Child, color = "cold", targetSounds = targets, confirmedSounds = confirmed, track = Profile.Track.Ro)

    private suspend fun finish(focus: SessionFocus, profile: Profile, app: AppModel, at: Instant = Instant.now()) {
        val session = SessionRecord(profileId = profile.id, track = Profile.Track.Ro, focus = focus.key, startedAt = at, endedAt = at.plusSeconds(120), attempts = 4)
        app.repository.addSession(session)
    }

    private suspend fun path(profile: Profile, app: AppModel): PathModel {
        app.repository.saveProfile(profile)
        val model = PathModel(profile, app.content)
        model.reload(app.repository)
        return model
    }

    private fun soundUnit(model: PathModel): CourseUnit = model.catalog.unit(model.content.sound(SoundID.S))

    /** someone new starts at the first thing in their first sound, not at the warm-up */
    @Test
    fun newChildStarts() = runBlocking {
        val app = makeApp()
        val model = path(child(), app)
        assertEquals("s-meet", model.continuing?.lesson?.id)
        assertFalse(model.hasStarted)
    }

    /** a finished lesson is done, and the row moves on to the next one */
    @Test
    fun movesOn() = runBlocking {
        val app = makeApp()
        val profile = child()
        app.repository.saveProfile(profile)
        finish(SessionFocus.Meet(SoundID.S), profile, app)
        val model = path(profile, app)
        assertEquals("s-isolated", model.continuing?.lesson?.id)
        assertTrue(model.hasStarted)
        val unit = soundUnit(model)
        val meet = unit.lessons.first()
        assertEquals(LessonState.done, model.state(meet, unit))
    }

    /** a rung stays where it is until the learner has passed it */
    @Test
    fun staysOnTheRung() = runBlocking {
        val app = makeApp()
        val profile = child()
        app.repository.saveProfile(profile)
        finish(SessionFocus.Meet(SoundID.S), profile, app, at = Instant.now().minusSeconds(60))
        finish(SessionFocus.Sound(SoundID.S, Rung.Isolated), profile, app)
        val model = path(profile, app)
        assertEquals("s-isolated", model.continuing?.lesson?.id)
    }

    /**
     * v2-flow decision (differs from iOS): with chosen sounds, the big button keeps to today's sound, the first chosen
     * one with lessons left, one sound at a time, even after a lesson of another sound was opened by hand.
     */
    @Test
    fun keepsToTodaysSound() = runBlocking {
        val app = makeApp()
        val profile = child(targets = listOf("s", "r"))
        app.repository.saveProfile(profile)
        finish(SessionFocus.Meet(SoundID.S), profile, app, at = Instant.now().minusSeconds(60))
        finish(SessionFocus.Meet(SoundID.R), profile, app)
        val model = path(profile, app)
        assertEquals("s-isolated", model.continuing?.lesson?.id)
    }

    // Today's sound with two sounds chosen

    private val zone: ZoneId = ZoneId.of("Europe/Bucharest")

    private fun day(day: Int, hour: Int = 10): Instant = LocalDateTime.of(2026, 11, day, hour, 0).atZone(zone).toInstant()

    private suspend fun save(sound: String, rung: Rung, profile: Profile, app: AppModel, history: List<SoundProgress.Block> = emptyList()) {
        app.repository.saveSoundProgress(SoundProgress(profileId = profile.id, sound = sound, rung = rung.index, blockHistory = history))
    }

    /** Today's sound on each of the given days. */
    private fun sounds(model: PathModel, days: List<Instant>): List<SoundID?> = days.map { date ->
        model.today = { date }
        model.zone = zone
        model.continuing?.unit?.sound
    }

    /** A lesson on [sound] practised on that day. */
    private suspend fun practise(sound: String, on: Instant, profile: Profile, app: AppModel, model: PathModel) {
        val session = SessionRecord(
            profileId = profile.id,
            track = Profile.Track.Ro,
            sound = sound,
            focus = "turn-$sound-${on.epochSecond}",
            startedAt = on,
            endedAt = on.plusSeconds(120),
            attempts = 4,
            timeZone = zone,
        )
        app.repository.addSession(session)
        model.reload(app.repository)
    }

    /** the everyday lesson can never be done, so a sound there held the button for good */
    @Test
    fun everydayIsNeverDone() = runBlocking {
        val app = makeApp()
        val profile = child(confirmed = listOf("s"))
        app.repository.saveProfile(profile)
        save("s", Rung.Everyday, profile, app)
        val model = path(profile, app)
        val unit = soundUnit(model)
        val everyday = unit.lessons.first { it.rung == Rung.Everyday }
        assertNotEquals(LessonState.done, model.state(everyday, unit))
        assertTrue(model.isAutomating(SoundID.S))
    }

    /** a sound in automatizare no longer holds the button: the next chosen sound comes up */
    @Test
    fun automatingSoundLetsTheNextOneIn() = runBlocking {
        val app = makeApp()
        val profile = child(confirmed = listOf("s"), targets = listOf("s", "r"))
        app.repository.saveProfile(profile)
        save("s", Rung.Everyday, profile, app)
        val model = path(profile, app)
        assertEquals(listOf(SoundID.R, SoundID.R, SoundID.R), sounds(model, listOf(day(20), day(21), day(22))))
    }

    /** from sentences on, the two sounds take turns: the one practised last rests on the next day of practice */
    @Test
    fun alternatesFromSentences() = runBlocking {
        val app = makeApp()
        val profile = child(confirmed = listOf("s"), targets = listOf("s", "r"))
        app.repository.saveProfile(profile)
        save("s", Rung.Sentences, profile, app)
        val model = path(profile, app)
        assertEquals("the first chosen sound opens", listOf(SoundID.S), sounds(model, listOf(day(20, hour = 7))))
        practise("s", day(20, hour = 8), profile, app, model)
        assertEquals("the same all day", listOf(SoundID.S), sounds(model, listOf(day(20, hour = 22))))
        assertEquals("a day off does not skip a turn", listOf(SoundID.R, SoundID.R), sounds(model, listOf(day(21), day(23))))
        practise("r", day(23, hour = 8), profile, app, model)
        assertEquals("the same all day", listOf(SoundID.R), sounds(model, listOf(day(23, hour = 22))))
        assertEquals(listOf(SoundID.S, SoundID.S), sounds(model, listOf(day(24), day(26))))
    }

    /** practising every other day still reaches both sounds */
    @Test
    fun everyOtherDayReachesBoth() = runBlocking {
        val app = makeApp()
        val profile = child(confirmed = listOf("s"), targets = listOf("s", "r"))
        app.repository.saveProfile(profile)
        save("s", Rung.Sentences, profile, app)
        val model = path(profile, app)
        val seen = mutableListOf<SoundID>()
        for (date in listOf(16, 18, 20, 22)) {
            val sound = sounds(model, listOf(day(date)))[0]
            assertNotNull(sound)
            seen += sound!!
            practise(sound.rawValue, day(date), profile, app, model)
        }
        assertEquals(listOf(SoundID.S, SoundID.R, SoundID.S, SoundID.R), seen)
    }

    /** while the first sound waits for the adult, the second gets its days; the lock stays on the first */
    @Test
    fun alternatesWhileWaiting() = runBlocking {
        val app = makeApp()
        val profile = child(targets = listOf("s", "r"))
        app.repository.saveProfile(profile)
        val blocks = List(2) { SoundProgress.Block(rung = 0, good = 6, total = 6, endedAt = day(18)) }
        save("s", Rung.Isolated, profile, app, history = blocks)
        val model = path(profile, app)
        assertTrue(model.isWaitingForAdult(SoundID.S))
        assertFalse(model.isWaitingForAdult(SoundID.R))
        practise("s", day(18), profile, app, model)
        assertEquals(listOf(SoundID.R), sounds(model, listOf(day(20))))
        practise("r", day(20), profile, app, model)
        assertEquals(listOf(SoundID.S), sounds(model, listOf(day(21))))
    }

    /** below sentences the first sound keeps the button every day */
    @Test
    fun staysOnTheFirstBelowSentences() = runBlocking {
        val app = makeApp()
        val profile = child(confirmed = listOf("s"), targets = listOf("s", "r"))
        app.repository.saveProfile(profile)
        save("s", Rung.Pairs, profile, app)
        val model = path(profile, app)
        assertEquals(listOf(SoundID.S, SoundID.S), sounds(model, listOf(day(20), day(21))))
    }

    /** with one sound chosen nothing changes, in automatizare or not */
    @Test
    fun oneSoundUnchanged() = runBlocking {
        val app = makeApp()
        val profile = child(confirmed = listOf("s"))
        app.repository.saveProfile(profile)
        save("s", Rung.Sentences, profile, app)
        val model = path(profile, app)
        assertEquals(listOf(SoundID.S, SoundID.S), sounds(model, listOf(day(20), day(21))))
        save("s", Rung.Everyday, profile, app)
        model.reload(app.repository)
        assertEquals(listOf(SoundID.S, SoundID.S), sounds(model, listOf(day(20), day(21))))
    }

    /** with no sound chosen, the row follows what was worked on last, as on iOS */
    @Test
    fun followsTheLastUnitWithNoChosenSound() = runBlocking {
        val app = makeApp()
        val profile = child(targets = emptyList())
        app.repository.saveProfile(profile)
        finish(SessionFocus.Meet(SoundID.S), profile, app, at = Instant.now().minusSeconds(60))
        finish(SessionFocus.Meet(SoundID.R), profile, app)
        val model = path(profile, app)
        assertEquals("r-isolated", model.continuing?.lesson?.id)
    }

    /** when the last unit is finished, the row goes back to the track */
    @Test
    fun backToTheTrack() = runBlocking {
        val app = makeApp()
        val profile = child()
        app.repository.saveProfile(profile)
        finish(SessionFocus.Voicing, profile, app, at = Instant.now().minusSeconds(60))
        finish(SessionFocus.VoicingSay, profile, app)
        val model = path(profile, app)
        assertEquals("s-meet", model.continuing?.lesson?.id)
    }

    /** syllables stay shut until an adult has listened, and what stands after them too */
    @Test
    fun adultLock() = runBlocking {
        val app = makeApp()
        val locked = path(child(), app)
        val unit = soundUnit(locked)
        val syllables = unit.lessons.first { it.rung == Rung.Syllables }
        val back = unit.lessons.first { it.id == "s-back" }
        assertEquals(LessonState.locked, locked.state(syllables, unit))
        assertFalse(locked.canOpen(back, unit))

        val open = path(child(confirmed = listOf("s")), app)
        assertNotEquals(LessonState.locked, open.state(syllables, unit))
        assertFalse("a rung not reached yet stays shut even when unlocked", open.canOpen(syllables, unit))
    }

    /** rungs behind the learner count as done */
    @Test
    fun rungsBehind() = runBlocking {
        val app = makeApp()
        val profile = child(confirmed = listOf("s"))
        app.repository.saveProfile(profile)
        val saved = SoundProgress(profileId = profile.id, sound = "s").copy(rung = Rung.WordsInitial.index)
        app.repository.saveSoundProgress(saved)
        val model = path(profile, app)
        val unit = soundUnit(model)
        val isolated = unit.lessons.first { it.rung == Rung.Isolated }
        val syllables = unit.lessons.first { it.rung == Rung.Syllables }
        val words = unit.lessons.first { it.rung == Rung.WordsInitial }
        assertEquals(LessonState.done, model.state(isolated, unit))
        assertEquals(LessonState.done, model.state(syllables, unit))
        assertTrue(model.canOpen(words, unit))
    }

    /** an adult starts on their own track */
    @Test
    fun adultStart() = runBlocking {
        val app = makeApp()
        val profile = Profile(kind = Profile.Kind.Adult, ageBand = Profile.AgeBand.Adult, voiceGroup = Profile.VoiceGroup.Female, color = "cold", requireConfirmation = false, openPath = true, track = Profile.Track.Dictie)
        val model = path(profile, app)
        assertEquals("d-rhythm", model.continuing?.unit?.id)
    }

    /** the week starts on Monday and shows what was done */
    @Test
    fun week() = runBlocking {
        val app = makeApp()
        val profile = child()
        app.repository.saveProfile(profile)
        finish(SessionFocus.Voicing, profile, app)
        val model = path(profile, app)
        assertEquals(listOf("L", "M", "M", "J", "V", "S", "D"), model.week.map { it.letter })
        assertEquals(1, model.week.count { it.isToday })
        assertEquals(4, model.week.first { it.isToday }.attempts)
        assertEquals(true, model.week.first { it.isToday }.hasPractice)
        assertEquals(1, model.totalLessons)
        assertEquals(1, model.totalDays)
    }

    /** every unit can be found again by its id */
    @Test
    fun unitsAreFound() = runBlocking {
        val app = makeApp()
        val model = path(child(), app)
        for (unit in model.catalog.allUnits(model.soundOrder)) {
            assertEquals(unit.id, model.unit(unit.id)?.id)
        }
        for (unit in model.catalog.playUnits + listOf(model.catalog.melodyUnit)) {
            assertNotNull(unit.id, model.unit(unit.id))
        }
    }

    private companion object {
        val content: FoniaContent by lazy {
            val root = System.getProperty("fonia.content") ?: error("fonia.content is not set")
            FoniaContent.load(ContentSource.directory(File(root)))
        }
    }
}
