package tech.fonia.features.adaptive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.AttemptsSummary
import tech.fonia.domain.ContentSource
import tech.fonia.domain.FeedbackLevel
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.Rung
import tech.fonia.domain.SessionBlock
import tech.fonia.domain.SessionFocus
import tech.fonia.domain.SoundID
import tech.fonia.domain.Trial
import tech.fonia.domain.adaptive.AdaptiveCodec
import tech.fonia.domain.adaptive.AdaptiveDecision
import tech.fonia.domain.adaptive.AdaptiveMaterial
import tech.fonia.domain.adaptive.AdaptiveProfile
import tech.fonia.domain.adaptive.ItemPosition
import tech.fonia.domain.adaptive.KeyMastery
import tech.fonia.domain.adaptive.ModeSetting
import tech.fonia.domain.adaptive.PracticeMode
import tech.fonia.domain.adaptive.SoundAdaptive
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.AudioHub
import tech.fonia.features.app.NoAudioBackend
import tech.fonia.features.app.SessionTicket
import tech.fonia.features.app.SpeechHub
import tech.fonia.features.app.index
import tech.fonia.features.grownups.estimateWords
import tech.fonia.features.grownups.keyTitle
import tech.fonia.features.pilot.AdaptiveRecord
import tech.fonia.features.pilot.PilotLog
import tech.fonia.features.session.SessionRun
import tech.fonia.store.Attempt
import tech.fonia.store.FoniaDatabase
import tech.fonia.store.FoniaRepository
import tech.fonia.store.Profile
import tech.fonia.store.SoundProgress
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The adaptive pilot inside the app (docs/PLAN.md §7.4): off, a session is iOS's to the trial; on, the engine plans the
 * blocks, every decision reaches the pilot log, the state is saved beside FoniaStore, goes with the profile, and is in
 * the logoped's summary.
 */
class AdaptivePilotTests {
    private val pilot = PilotLog.inMemory(zone = ZoneId.systemDefault())
    private val folder: File = Files.createTempDirectory("fonia-adaptive").toFile()
    private val store = AdaptiveStore(File(folder, "Adaptive"))

    @After
    fun close() {
        pilot.close()
        folder.deleteRecursively()
    }

    private fun makeApp(adaptive: AdaptiveStore? = store): AppModel {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return AppModel(
            content = content,
            repository = FoniaRepository(FoniaDatabase.inMemory(recordingsDirectory = File(folder, "Recordings"))),
            audio = AudioHub(voices = null, backend = NoAudioBackend(null), usesFixtures = true),
            speech = SpeechHub(model = null, scope = scope),
            scope = scope,
            pilot = pilot,
            adaptive = adaptive,
        )
    }

    private val child = Profile(
        kind = Profile.Kind.Child,
        ageBand = Profile.AgeBand.SixToSeven,
        voiceGroup = Profile.VoiceGroup.Child,
        color = "cold",
        targetSounds = listOf("s"),
        confirmedSounds = listOf("s"),
        track = Profile.Track.Ro,
    )

    private suspend fun setUp(app: AppModel, rung: Rung): SoundProgress {
        app.repository.saveProfile(child)
        pilot.profiles(listOf(child))
        val progress = SoundProgress(profileId = child.id, sound = "s", rung = rung.index)
        app.repository.saveSoundProgress(progress)
        return progress
    }

    private fun makeRun(app: AppModel, rung: Rung, progress: SoundProgress?) =
        SessionRun(SessionTicket(focus = SessionFocus.Sound(SoundID.S, rung), title = "test", sound = SoundID.S), child, app, progress, AttemptsSummary())

    /** Plays the whole session, every production judged by [outcome]. */
    private fun play(run: SessionRun, outcome: (Trial) -> Attempt.Outcome) {
        var guard = 0
        while (run.phase == SessionRun.Phase.Trial && guard++ < 500) {
            val trial = run.current ?: break
            if (trial.countsAsAttempt) run.finishAttempt(trial.exercise, machine = outcome(trial), adult = null, take = null) else run.advance()
        }
    }

    private suspend fun savedRung(app: AppModel, run: SessionRun): Int {
        delay(50)
        run.awaitSaves()
        return app.repository.soundProgress(child.id).first { it.sound == "s" }.rung
    }

    @Test
    fun offTheRunIsIOSs() = runBlocking {
        for (saved in listOf(null, AdaptiveProfile(enabled = false, sounds = mapOf(SoundID.S to SoundAdaptive(setting = ModeSetting.Generalization))))) {
            val app = makeApp()
            val progress = setUp(app, Rung.Syllables)
            if (saved != null) store.update(child.id) { saved }
            val run = makeRun(app, Rung.Syllables, progress)
            val built = run.session
            run.start()
            // start() leaves iOS's session as the builder made it.
            assertSame(built, run.session)
            play(run) { Attempt.Outcome.Good }
            // iOS's progression: a good session moves one rung.
            assertEquals(Rung.WordsInitial.index, savedRung(app, run))
            pilot.flush()
            assertEquals(0, pilot.snapshot().rows.getValue("adaptive"))
        }
        // A profile never switched on gets no file.
        store.keepOnly(emptySet())
        val app = makeApp()
        val run = makeRun(app, Rung.Syllables, setUp(app, Rung.Syllables))
        run.start()
        play(run) { Attempt.Outcome.Good }
        run.awaitSaves()
        assertFalse(store.exists(child.id))
    }

    @Test
    fun withoutAStoreTheRunIsIOSs() = runBlocking {
        val app = makeApp(adaptive = null)
        val run = makeRun(app, Rung.Syllables, setUp(app, Rung.Syllables))
        val built = run.session
        run.start()
        assertSame(built, run.session)
    }

    @Test
    fun onTheEnginePlansTheBlocksAndLogsEveryDecision() = runBlocking {
        val app = makeApp()
        val progress = setUp(app, Rung.WordsMedial)
        store.update(child.id) { it.copy(enabled = true) }
        val run = makeRun(app, Rung.WordsMedial, progress)
        val built = run.session
        run.start()
        val planned = run.session
        val head = built.trials.takeWhile { it.block.kind == SessionBlock.Kind.Opening || it.block.kind == SessionBlock.Kind.Listening }
        assertEquals(head, planned.trials.take(head.size))
        assertEquals(built.targetAttempts, planned.targetAttempts)
        val blocks = planned.trials.drop(head.size).groupBy { it.block }
        assertEquals(built.targetAttempts / 6, blocks.size)
        // A new sound: acquisition, the live view on every take.
        assertTrue(blocks.values.flatten().all { it.feedback == FeedbackLevel.Teach })

        play(run) { Attempt.Outcome.Good }
        assertEquals(SessionRun.Phase.Finished, run.phase)
        // All good, but one day: the rung waits for a second day (iOS would have moved it mid-session).
        assertEquals(Rung.WordsMedial.index, savedRung(app, run))
        pilot.flush()
        val rows = pilot.snapshot().adaptive.getValue(child.id.toString())
        assertEquals("mode", rows.first()["kind"])
        assertEquals("acquisition" to "new", rows.first()["choice"] to rows.first()["reason"])
        val blockRows = rows.filter { it["kind"] == "block" }
        assertEquals(1 + blocks.size, blockRows.size)
        assertTrue(blockRows.drop(1).any { it["choice"] == "harder" })
        assertTrue(rows.any { it["kind"] == "mastery" })
        assertEquals("stay" to "notMastered", rows.last().let { it["choice"] to it["reason"] })
        assertTrue(rows.all { it["sessionId"] == run.sessionID.toString() })

        val saved = store.load(child.id).sound(SoundID.S)
        assertEquals(PracticeMode.Acquisition, saved.mode)
        assertEquals(built.targetAttempts, saved.sessions.single().judged)
        assertEquals(Rung.WordsMedial, saved.challenge?.ceiling)
        val key = saved.key(ItemPosition.Medial, Rung.WordsMedial)
        assertTrue(key.takes > 0)
        assertEquals(listOf(LocalDate.now().toString()), key.masteredOn)
        // The blocks joined the sound's history under the rung they practised.
        val history = app.repository.soundProgress(child.id).first { it.sound == "s" }.blockHistory
        assertEquals(blocks.size, history.size)
    }

    @Test
    fun aBadBlockMovesTheRestOfTheSessionOneRungDown() = runBlocking {
        val app = makeApp()
        val progress = setUp(app, Rung.WordsMedial)
        store.update(child.id) { it.copy(enabled = true) }
        val run = makeRun(app, Rung.WordsMedial, progress)
        run.start()
        val firstBlock = run.session.trials.first { it.block.kind == SessionBlock.Kind.TargetA }.block
        var guard = 0
        while (run.current?.block != SessionBlock(SessionBlock.Kind.TargetA, 1) && guard++ < 100) {
            val trial = run.current ?: break
            if (trial.countsAsAttempt) {
                run.finishAttempt(trial.exercise, machine = if (trial.block == firstBlock) Attempt.Outcome.NotYet else Attempt.Outcome.Good, adult = null, take = null)
            } else {
                run.advance()
            }
        }
        val initialWords = AdaptiveMaterial(content).primary(SoundID.S, Rung.WordsInitial, emptySet()).exercises.mapNotNull { it.itemText }.toSet()
        val next = run.session.trials.filter { it.block == SessionBlock(SessionBlock.Kind.TargetA, 1) }
        assertEquals(6, next.size)
        assertTrue(next.all { it.exercise.itemText in initialWords && it.feedback == FeedbackLevel.Teach })
        pilot.flush()
        val decision = pilot.snapshot().adaptive.getValue(child.id.toString()).first { it["kind"] == "block" && it["block"] == 1 }
        assertEquals(listOf("easier", "below50", "wordsInitial", true), listOf(decision["choice"], decision["reason"], decision["rung"], decision["kp"]))
    }

    @Test
    fun theRungMovesOnAfterNinetyOnTwoDifferentDays() = runBlocking {
        val app = makeApp()
        val progress = setUp(app, Rung.WordsMedial)
        val yesterday = LocalDate.now().minusDays(1).toString()
        store.update(child.id) {
            AdaptiveProfile(
                enabled = true,
                sounds = mapOf(SoundID.S to SoundAdaptive().withKey(KeyMastery(ItemPosition.Medial, Rung.WordsMedial, pKnown = 0.95, takes = 40, masteredOn = listOf(yesterday)))),
            )
        }
        val run = makeRun(app, Rung.WordsMedial, progress)
        run.start()
        play(run) { Attempt.Outcome.Good }
        assertNull(run.needsAdultNext)
        assertEquals(Rung.WordsFinal.index, savedRung(app, run))
        pilot.flush()
        val last = pilot.snapshot().adaptive.getValue(child.id.toString()).last()
        assertEquals(listOf("rung", "advance", "mastered2days"), listOf(last["kind"], last["choice"], last["reason"]))
    }

    @Test
    fun theLockAfterTheIsolatedSoundOpensByHisOwnSuccess() = runBlocking {
        val app = makeApp()
        val locked = child.copy(confirmedSounds = emptyList())
        app.repository.saveProfile(locked)
        pilot.profiles(listOf(locked))
        val yesterday = LocalDate.now().minusDays(1).toString()
        store.update(locked.id) {
            AdaptiveProfile(true, mapOf(SoundID.S to SoundAdaptive().withKey(KeyMastery(ItemPosition.Isolated, Rung.Isolated, 0.95, 40, listOf(yesterday)))))
        }
        val run = SessionRun(SessionTicket(focus = SessionFocus.Sound(SoundID.S, Rung.Isolated), title = "test", sound = SoundID.S), locked, app, null, AttemptsSummary())
        run.start()
        play(run) { Attempt.Outcome.Good }
        // v2-flow: no adult in the loop; the rung the engine would have held for an adult opens.
        assertEquals(null, run.needsAdultNext)
        assertEquals(Rung.Syllables, run.movedUpTo)
    }

    @Test
    fun deletingTheProfileTakesItsAdaptiveStateAndRows() = runBlocking {
        val app = makeApp()
        val job = app.scope.launch { app.start() }
        app.repository.saveProfile(child)
        // The app follows FoniaStore's profiles: wait until it and the pilot log have seen this one.
        assertTrue(eventually { app.profiles.any { it.id == child.id } && pilot.snapshot().profiles.any { it.profileId == child.id.toString() } })
        store.update(child.id) { it.copy(enabled = true) }
        pilot.adaptive(AdaptiveRecord(child.id, null, Instant.now(), AdaptiveDecision(AdaptiveDecision.Kind.Setting, null, "on", "adult")))
        pilot.flush()
        assertEquals(1, pilot.snapshot().rows.getValue("adaptive"))
        app.repository.deleteProfile(child.id)
        assertTrue(eventually { !store.exists(child.id) })
        assertTrue(eventually { pilot.snapshot().rows.getValue("adaptive") == 0 })
        job.cancel()
        app.scope.cancel()
    }

    @Test
    fun theLogopedsSummaryEndsWithTheAdaptiveSectionOnlyForThePilot() = runBlocking {
        val app = makeApp()
        setUp(app, Rung.WordsMedial)
        val without = Json.parseToJsonElement(pilot.export(child, dspThresholdsVersion = 1, adaptive = store.summary(child.id)).second.decodeToString()).jsonObject
        assertFalse(without.containsKey("adaptive"))

        store.update(child.id) { AdaptiveProfile(true, mapOf(SoundID.S to SoundAdaptive(setting = ModeSetting.Acquisition, mastery = listOf(KeyMastery(ItemPosition.Medial, Rung.WordsMedial, 0.42, 12))))) }
        pilot.adaptive(AdaptiveRecord(child.id, null, Instant.now(), AdaptiveDecision(AdaptiveDecision.Kind.Setting, SoundID.S, "acquisition", "adult")))
        val text = pilot.export(child, dspThresholdsVersion = 1, adaptive = store.summary(child.id)).second.decodeToString()
        val document = Json.parseToJsonElement(text).jsonObject
        assertEquals(without.keys.toList() + "adaptive", document.keys.toList())
        val adaptive = document.getValue("adaptive").jsonObject
        assertEquals(listOf("enabled", "rules", "sounds", "decisions"), adaptive.keys.toList())
        val sound = adaptive.getValue("sounds").jsonArray.single().jsonObject
        assertEquals("acquisition", sound.getValue("setting").jsonPrimitive.content)
        assertEquals("0.42", sound.getValue("mastery").jsonArray.single().jsonObject.getValue("p").jsonPrimitive.content)
        assertEquals("acquisition", adaptive.getValue("decisions").jsonArray.single().jsonObject.getValue("choice").jsonPrimitive.content)
        assertFalse(text.contains(child.id.toString()))
    }

    @Test
    fun theStoreKeepsOneFilePerProfileAndADamagedOneIsOff() = runBlocking {
        val profile = AdaptiveProfile(true, mapOf(SoundID.R to SoundAdaptive(setting = ModeSetting.Generalization)))
        store.update(child.id) { profile }
        assertEquals(profile, store.load(child.id))
        assertEquals(AdaptiveCodec.encode(profile), File(folder, "Adaptive/${child.id}.json").readText())
        File(folder, "Adaptive/${child.id}.json").writeText("{not json")
        assertEquals(AdaptiveProfile(), store.load(child.id))
        store.keepOnly(emptySet())
        assertFalse(store.exists(child.id))
        assertNull(store.summary(child.id))
    }

    @Test
    fun theParentSeesWordsNotNumbers() {
        val s = content.sound(SoundID.S)
        assertEquals("Încă îl exersăm", estimateWords(KeyMastery(ItemPosition.Medial, Rung.WordsMedial, 0.2, 10)))
        assertEquals("Iese tot mai des", estimateWords(KeyMastery(ItemPosition.Medial, Rung.WordsMedial, 0.7, 10)))
        assertTrue(estimateWords(KeyMastery(ItemPosition.Medial, Rung.WordsMedial, 0.93, 10, listOf("2026-11-05"))).contains("altă zi"))
        assertEquals("Cuvinte cu S la mijloc", keyTitle(s, KeyMastery(ItemPosition.Medial, Rung.WordsMedial)))
        assertEquals("Silabe cu S (la sfârșit)", keyTitle(s, KeyMastery(ItemPosition.Final, Rung.Syllables)))
        val all = listOf(0.1, 0.6, 0.95).map { estimateWords(KeyMastery(ItemPosition.Initial, Rung.WordsInitial, it, 10)) }
        assertTrue(all.none { Regex("risc|diagnostic|severitate|tulburare|%|\\d").containsMatchIn(it) })
    }

    /** Waits up to two seconds for [condition], which the app's scope makes true on its own time. */
    private suspend fun eventually(condition: suspend () -> Boolean): Boolean {
        repeat(100) {
            if (condition()) return true
            delay(20)
        }
        return condition()
    }

    companion object {
        val content: FoniaContent by lazy {
            FoniaContent.load(ContentSource.directory(File(System.getProperty("fonia.content") ?: "../content")))
        }
    }
}
