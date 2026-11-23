package tech.fonia.features.ml

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.audio.CapturedClip
import tech.fonia.design.Rating
import tech.fonia.domain.AttemptsSummary
import tech.fonia.domain.ContentSource
import tech.fonia.domain.Exercise
import tech.fonia.domain.FeedbackLevel
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.Language
import tech.fonia.domain.RepeatPrompt
import tech.fonia.domain.Rung
import tech.fonia.domain.SessionBlock
import tech.fonia.domain.SessionFocus
import tech.fonia.domain.SoundID
import tech.fonia.domain.Trial
import tech.fonia.domain.WordItem
import tech.fonia.dsp.DSPThresholds
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.AudioHub
import tech.fonia.features.app.NoAudioBackend
import tech.fonia.features.app.SessionTicket
import tech.fonia.features.app.SpeechHub
import tech.fonia.features.app.dspGroup
import tech.fonia.features.pilot.PilotHooks
import tech.fonia.features.pilot.ShadowContext
import tech.fonia.features.pilot.ShadowRecord
import tech.fonia.features.pilot.ShadowStatus
import tech.fonia.features.pilot.SoundPosition
import tech.fonia.features.session.Praise
import tech.fonia.features.session.SessionRun
import tech.fonia.features.session.TakeJudge
import tech.fonia.features.session.TakeVerdict
import tech.fonia.phones.Abstention
import tech.fonia.phones.RomanianG2P
import tech.fonia.phones.TargetSound
import tech.fonia.phones.Verdict
import tech.fonia.phones.WordScore
import tech.fonia.store.Attempt
import tech.fonia.store.FoniaDatabase
import tech.fonia.store.FoniaRepository
import tech.fonia.store.Profile
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Shadow mode must not change what the child gets (03 §1.2 P13): TakeJudge's verdict, as it is and as soon as TakeJudge
 * has it, whatever the scorer does. The scorer here is a fake that waits on a gate the test opens only after the
 * verdict is back, so a verdict that waited for it could never arrive.
 */
class ShadowModeTests {
    /** A scorer that answers "correct" once [gate] opens, or throws when [fails]. */
    private class GatedScoring(private val gate: CountDownLatch, private val fails: Boolean = false) : PhoneScoring {
        val started = AtomicInteger(0)
        val finished = AtomicInteger(0)
        val seen = CopyOnWriteArrayList<Triple<Int, String, TargetSound>>()

        override fun score(samples16k: FloatArray, text: String, sound: TargetSound): WordScore {
            started.incrementAndGet()
            seen += Triple(samples16k.size, text, sound)
            check(gate.await(20, TimeUnit.SECONDS)) { "the test never opened the gate" }
            if (fails) throw IllegalStateException("model broke")
            finished.incrementAndGet()
            return WordScore(RomanianG2P.pronounce(text), sound, emptyList(), Verdict.Correct, 0.8, null, null, -0.5, 50)
        }
    }

    private class FakeSource(private val scoring: PhoneScoring?) : PhoneScoringSource {
        val opened = AtomicInteger(0)
        val closed = AtomicInteger(0)

        override fun open(): PhoneScoring? {
            opened.incrementAndGet()
            return scoring
        }

        override fun close() {
            closed.incrementAndGet()
        }

        override val model = "fake@1"
        override val policy = "fake"
    }

    private val records = CopyOnWriteArrayList<ShadowRecord>()
    private val closing = mutableListOf<AutoCloseable>()

    @After
    fun closeAll() = closing.forEach { it.close() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** An app whose model manager has a (tiny) model in place of ZIPA, so shadow mode runs. */
    private fun makeApp(source: PhoneScoringSource, ready: Boolean = true, capacity: Int = 2, idleMillis: Long = 30_000): AppModel {
        val folder = Files.createTempDirectory("fonia-shadow").toFile()
        val entry = LocalModels.entry(File(folder, "served"))
        val manager = LocalModels.manager(File(folder, "models"), entry)
        if (ready) runBlocking { manager.download(entry) }
        val shadow = ShadowMode(source, sink = { records += it }, capacity = capacity, idleMillis = idleMillis).also { closing += it }
        val ml = MlHub(ModelsHub(manager, scope, isSupported = true), shadow, zipa = entry)
        return AppModel(
            content = content,
            repository = FoniaRepository(FoniaDatabase.inMemory(recordingsDirectory = File(folder, "Recordings"))),
            audio = AudioHub(voices = null, backend = NoAudioBackend(null), usesFixtures = true),
            speech = SpeechHub(model = null, scope = scope),
            scope = scope,
            ml = ml,
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

    private fun makeRun(app: AppModel): SessionRun = SessionRun(
        SessionTicket(focus = SessionFocus.Sound(SoundID.S, Rung.WordsInitial), title = "test", sound = SoundID.S),
        child,
        app,
        progress = null,
        history = AttemptsSummary(),
    )

    private fun wordTrial(text: String = "sare", sound: SoundID = SoundID.S, language: Language = Language.Ro) = Trial(
        exercise = Exercise.Repeat(RepeatPrompt(text = text, language = language, sound = sound, level = RepeatPrompt.Level.Word)),
        feedback = FeedbackLevel.Teach,
        block = SessionBlock(SessionBlock.Kind.TargetA),
    )

    /** Half a second of quiet, a hiss and a vowel at 48 kHz: something for the DSP to judge. */
    private fun take(seed: Int = 1): CapturedClip {
        val rate = 48_000
        val random = Random(seed)
        val samples = FloatArray(rate) { index ->
            val t = index.toDouble() / rate
            when {
                t < 0.2 -> (random.nextFloat() - 0.5f) * 0.0004f
                t < 0.45 -> (random.nextFloat() - 0.5f) * 0.25f
                t < 0.75 -> (0.3 * sin(2 * PI * 220 * t) + 0.1 * sin(2 * PI * 660 * t)).toFloat()
                else -> (random.nextFloat() - 0.5f) * 0.0004f
            }
        }
        return CapturedClip(samples, rate.toDouble())
    }

    /** Everything a verdict says, its random praise aside ("Bravo!", "Da!"…), which is drawn anew on every judging. */
    private fun essence(verdict: TakeVerdict): TakeVerdict =
        verdict.copy(title = if (verdict.title in Praise.short) "praise" else verdict.title)

    private suspend fun awaitRecords(count: Int) = withTimeout(20_000) { while (records.size < count) delay(5) }

    @Test
    fun theVerdictIsTakeJudgesAndArrivesBeforeTheScorerFinishes() = runBlocking {
        val gate = CountDownLatch(1)
        val scoring = GatedScoring(gate)
        val app = makeApp(FakeSource(scoring))
        val run = makeRun(app)
        val trial = wordTrial()
        val clip = take()
        val judge = TakeJudge(content, child.dspGroup, calibration = null, transcriber = null, thresholds = DSPThresholds.v1)

        val plain = judge.judge(trial.exercise, clip)
        val shadowed = judge.judgeShadowed(app, run, trial, clip)
        // Back while the scorer is still held at its gate: the verdict didn't wait for it.
        assertEquals(0, scoring.finished.get())
        assertEquals(essence(plain), essence(shadowed))

        gate.countDown()
        app.ml!!.shadow.awaitIdle()
        awaitRecords(1)
        val record = records.single()
        assertEquals(ShadowStatus.Scored, record.status)
        assertEquals(shadowed.outcome, record.shown)
        assertEquals("correct", record.verdict)
        assertEquals(PilotHooks.takeKey(run.index, run.retryToken), record.take)
        assertEquals(run.sessionID, record.sessionId)
        assertEquals(ShadowContext.Session, record.context)
        assertEquals("s", record.sound)
        assertEquals(SoundPosition.Initial, record.position)
        assertEquals("teach", record.feedback)
        assertEquals(48_000, record.sampleRate)
        assertEquals(1000, record.durationMs)
        assertEquals("fixture", record.input)
        assertEquals("fake@1", record.model)
        assertEquals("v1", record.dsp)
        assertTrue(record.snrDb!! > 20)
        // The scorer got the same take at 16 kHz, with the item and its sound.
        assertEquals(Triple(16_000, "sare", TargetSound.S), scoring.seen.single())
    }

    @Test
    fun handingATakeOverTakesNoTimeEvenWhenTheScorerIsStuck() = runBlocking {
        val gate = CountDownLatch(1)
        val scoring = GatedScoring(gate)
        val app = makeApp(FakeSource(scoring))
        val run = makeRun(app)
        val trial = wordTrial()
        val clip = take()
        val verdict = TakeVerdict(rating = Rating.nearly, outcome = Attempt.Outcome.Almost, title = "Aproape")

        app.shadowSessionTake(run, trial, clip, verdict)
        withTimeout(10_000) { while (scoring.started.get() == 0) delay(5) }
        // The scorer is stuck on the first take. Nine more come: each hand-over returns at once, the queue keeps two.
        val durations = (1..9).map {
            val started = System.nanoTime()
            app.shadowSessionTake(run, trial, clip, verdict)
            (System.nanoTime() - started) / 1_000_000.0
        }
        assertTrue("hand-overs took $durations ms", durations.max() < 50.0)
        assertEquals(7, records.count { it.status == ShadowStatus.Dropped })
        assertTrue(records.filter { it.status == ShadowStatus.Dropped }.all { it.verdict == null && it.snrDb == null && it.shown == Attempt.Outcome.Almost })

        gate.countDown()
        awaitRecords(10)
        assertEquals(3, records.count { it.status == ShadowStatus.Scored })
        assertEquals(3, scoring.finished.get())
    }

    @Test
    fun aScorerThatBreaksIsLoggedAndChangesNothing() = runBlocking {
        val gate = CountDownLatch(0)
        val source = FakeSource(GatedScoring(gate, fails = true))
        val app = makeApp(source)
        val run = makeRun(app)
        val trial = wordTrial()
        val judge = TakeJudge(content, child.dspGroup, calibration = null, transcriber = null)
        val clip = take(seed = 3)

        val verdict = judge.judgeShadowed(app, run, trial, clip)
        assertEquals(essence(judge.judge(trial.exercise, clip)), essence(verdict))
        awaitRecords(1)
        assertEquals(ShadowStatus.Failed, records.single().status)
        // The broken model is closed, so the next take starts clean.
        assertTrue(source.closed.get() >= 1)
    }

    @Test
    fun withoutTheModelOrATargetNothingIsQueued() = runBlocking {
        val source = FakeSource(GatedScoring(CountDownLatch(0)))
        val absent = makeApp(source, ready = false)
        val run = makeRun(absent)
        val verdict = TakeVerdict(rating = Rating.yes, outcome = Attempt.Outcome.Good, title = "Bravo!")
        assertFalse(absent.ml!!.isShadowing)
        assertFalse(absent.ml!!.afterJudge(ShadowContext.Session, child.id, run.sessionID, "0.0", wordTrial().exercise, take(), verdict, null, content, DSPThresholds.v1, false))

        val present = makeApp(source)
        val ml = present.ml!!
        assertTrue(ml.isShadowing)
        fun queued(exercise: Exercise) = ml.afterJudge(ShadowContext.Session, child.id, run.sessionID, "0.0", exercise, take(), verdict, null, content, DSPThresholds.v1, false)
        // English, a word without the sound, and exercises with no target sound are not for ZIPA.
        assertFalse(queued(wordTrial(text = "sheep", language = Language.En).exercise))
        assertFalse(queued(wordTrial(text = "mare").exercise))
        assertFalse(queued(Exercise.Volume))
        assertFalse(queued(Exercise.Repeat(RepeatPrompt(text = "Bună ziua", language = Language.Ro, level = RepeatPrompt.Level.Sentence))))
        // A picture to name is a word with its sound: that one is scored.
        assertTrue(queued(Exercise.Name(SoundID.S, WordItem(text = "sanie"))))
        ml.shadow.awaitIdle()
        awaitRecords(1)
        assertEquals(listOf(ShadowStatus.Scored), records.map { it.status })
        assertEquals(ShadowTally(), absent.ml!!.shadow.tally.value)
    }

    @Test
    fun theModelIsClosedWhenNobodyPractises() = runBlocking {
        val source = FakeSource(GatedScoring(CountDownLatch(0)))
        val app = makeApp(source, idleMillis = 100)
        val run = makeRun(app)
        app.shadowSessionTake(run, wordTrial(), take(), TakeVerdict(rating = Rating.yes, outcome = Attempt.Outcome.Good, title = "Da!"))
        awaitRecords(1)
        // After the take, a quiet 100 ms closes the model.
        val closedAfterTake = source.closed.get()
        withTimeout(5_000) { while (source.closed.get() == closedAfterTake) delay(10) }
        assertEquals(1, source.opened.get())
        // The next take opens it again.
        app.shadowSessionTake(run, wordTrial(), take(), TakeVerdict(rating = Rating.yes, outcome = Attempt.Outcome.Good, title = "Da!"))
        awaitRecords(2)
        assertEquals(2, source.opened.get())
        val tally = app.ml!!.shadow.tally.value
        assertEquals(ShadowTally(compared = 2, agreed = 2), tally)
        assertNull(records.firstOrNull { it.status != ShadowStatus.Scored })
    }

    companion object {
        val content: FoniaContent by lazy {
            FoniaContent.load(ContentSource.directory(File(System.getProperty("fonia.content") ?: "../content")))
        }
    }
}
