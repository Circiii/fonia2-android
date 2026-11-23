package tech.fonia.features.speaking

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tech.fonia.audio.CaptureInfo
import tech.fonia.audio.CapturedClip
import tech.fonia.audio.ClipLocation
import tech.fonia.audio.HopProcessor
import tech.fonia.audio.LiveLevels
import tech.fonia.audio.SpeechFallback
import tech.fonia.design.FoniaTheme
import tech.fonia.design.LocalReduceMotion
import tech.fonia.domain.ContentSource
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.Language
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.AudioBackend
import tech.fonia.features.app.AudioHub
import tech.fonia.features.app.AudioSessionPort
import tech.fonia.features.app.CaptureEnginePort
import tech.fonia.features.app.LocalStillMeters
import tech.fonia.features.app.PlaybackPort
import tech.fonia.features.app.SoundEffectsPort
import tech.fonia.features.app.SpeechFallbackPort
import tech.fonia.features.app.SpeechHub
import tech.fonia.features.app.SpeechModelPort
import tech.fonia.features.app.TakeSource
import tech.fonia.features.home.PathModel
import tech.fonia.features.speaking.bac.BacContent
import tech.fonia.features.speaking.bac.BacOralScreen
import tech.fonia.features.speaking.emphasis.EmphasisContent
import tech.fonia.features.speaking.emphasis.EmphasisScreen
import tech.fonia.speech.Transcriber
import tech.fonia.store.FoniaDatabase
import tech.fonia.store.FoniaRepository
import tech.fonia.store.Profile
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * The two speaking exercises that are not on iOS, on the emulator, driven as a person drives them. The takes are
 * synthetic speech whose word times are known, handed over by a scripted capture in place of the microphone, and the
 * recogniser is scripted with those times (`ScriptedTranscriber`, shared with the JVM tests), so the clip analysis,
 * the alignment, the emphasis rule and the report run for real without the 150 MB Whisper model. The preparation
 * clock is moved by hand. Screenshots go to the test app's files (`speaking-ml-screenshots/`).
 */
@RunWith(AndroidJUnit4::class)
class SpeakingMlTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private class HandClock : SpeakingClock {
        val now = AtomicLong(9_000_000_000L)

        override fun nanos(): Long = now.get()

        fun advance(seconds: Double) {
            now.addAndGet((seconds * 1e9).toLong())
        }
    }

    /** Each take gives the next clip of the queue, whatever the source: the microphone stand-in. */
    private class ScriptedAudio(private val clips: ArrayDeque<CapturedClip>) : AudioBackend {
        val played = mutableListOf<CapturedClip>()

        override val session = object : AudioSessionPort {
            override fun configure(playbackOnly: Boolean) = Unit
            override fun activate() = Unit
            override fun deactivate() = Unit
            override suspend fun requestMicrophone(prompt: suspend () -> Boolean): Boolean = true
        }

        override val playback = object : PlaybackPort {
            override suspend fun preload(urls: List<ClipLocation>) = Unit
            override suspend fun play(url: ClipLocation, levels: LiveLevels?): Boolean = false
            override suspend fun play(clip: CapturedClip, levels: LiveLevels?): Boolean {
                played.add(clip)
                return true
            }
            override suspend fun playRange(clip: CapturedClip, from: Double, to: Double): Boolean {
                played.add(clip)
                return true
            }
            override suspend fun setSpeed(value: Float) = Unit
            override suspend fun stop() = Unit
            override suspend fun release() = Unit
        }

        override val fallback = object : SpeechFallbackPort {
            override suspend fun speak(text: String, language: SpeechFallback.Language, levels: LiveLevels?) = Unit
            override fun stop() = Unit
        }

        override val effects: SoundEffectsPort? = null

        override fun captureEngine(source: TakeSource): CaptureEnginePort = object : CaptureEnginePort {
            private var running = false

            override suspend fun start(levels: LiveLevels, makeProcessor: (Double) -> HopProcessor) {
                running = true
            }

            override suspend fun stop(): CapturedClip {
                if (!running) return CapturedClip(FloatArray(0), 0.0)
                running = false
                return clips.removeFirst()
            }

            override suspend fun discard() {
                running = false
            }

            override val captureInfo: CaptureInfo? get() = null
        }
    }

    /** The speech package, installed, answering with the scripted recogniser. */
    private class ScriptedModel(private val transcriber: Transcriber) : SpeechModelPort {
        override val isReady: Boolean = true
        override val megabytes: Long = 151
        override suspend fun download(progress: (Double) -> Unit) = Unit
        override suspend fun delete() = Unit
        override fun makeTranscriber(): Transcriber = transcriber
        override suspend fun unload(transcriber: Transcriber) = Unit
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clock = HandClock()
    private val clips = ArrayDeque<CapturedClip>()
    private val recogniser = ScriptedTranscriber()
    private val audio = ScriptedAudio(clips)
    private lateinit var app: AppModel
    private lateinit var adult: Profile
    private val shots = File(context.filesDir, "speaking-ml-screenshots").apply { mkdirs() }

    @Before
    fun setUp() {
        val assets = context.assets
        val content = FoniaContent.load(ContentSource { path -> runCatching { assets.open(path).use { it.readBytes() } }.getOrNull() })
        val folder = File(context.cacheDir, "speaking-ml-test").apply { deleteRecursively() }
        app = AppModel(
            content = content,
            repository = FoniaRepository(FoniaDatabase.inMemory(File(folder, "Recordings"))),
            audio = AudioHub(voices = null, backend = audio, usesFixtures = false),
            speech = SpeechHub(model = ScriptedModel(recogniser), scope = scope),
            scope = scope,
        )
        adult = Profile(
            name = "Ioana",
            kind = Profile.Kind.Adult,
            ageBand = Profile.AgeBand.Adult,
            voiceGroup = Profile.VoiceGroup.Female,
            color = "cold",
            track = Profile.Track.Ro,
        )
        runBlocking { app.repository.saveProfile(adult) }
        scope.launch { app.start() }
        rule.waitUntil(10_000) { app.profiles.any { it.id == adult.id } }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun labelOf(node: androidx.compose.ui.semantics.SemanticsNode): String? {
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.takeIf { it.isNotEmpty() }?.let { return it.joinToString(", ") }
        node.config.getOrNull(SemanticsProperties.Text)?.takeIf { it.isNotEmpty() }?.let { texts -> return texts.joinToString(", ") { it.text } }
        return null
    }

    private fun button(startingWith: String): SemanticsMatcher =
        hasClickAction() and SemanticsMatcher("button \"$startingWith…\"") { labelOf(it)?.startsWith(startingWith) == true }

    private fun exists(matcher: SemanticsMatcher) = rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, seconds: Long = 10) = rule.waitUntil(seconds * 1000) { exists(matcher) }

    private fun tap(matcher: SemanticsMatcher) {
        waitFor(matcher)
        val node = rule.onAllNodes(matcher).onFirst()
        runCatching { node.performScrollTo() }
        node.performClick()
        rule.waitForIdle()
    }

    private fun scrollTo(matcher: SemanticsMatcher) {
        waitFor(matcher)
        rule.onAllNodes(matcher).onFirst().performScrollTo()
        rule.waitForIdle()
    }

    /** The whole screen, as the emulator shows it. */
    private fun screenshot(name: String) {
        rule.waitForIdle()
        Thread.sleep(500)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            FoniaTheme {
                // As `foniaReduceMotion` does for the app's UI tests: a live voice meter never lets Compose go idle.
                CompositionLocalProvider(LocalReduceMotion provides true, LocalStillMeters provides true) { content() }
            }
        }
    }

    /** A spoken answer: its words and times, queued as the next take and registered with the recogniser. */
    private fun queue(spoken: SyntheticSpeech.Spoken, readWrong: Set<String> = emptySet()) {
        clips.addLast(spoken.clip)
        recogniser.add(spoken, readWrong)
    }

    private fun answer(text: String) = SyntheticSpeech.say(text.split(' '), pauseAfter = { 0.08 }, seed = text.length)

    @Test
    fun theSpeakingPageOpensBothExercises() {
        show {
            val path = remember { PathModel(adult, app.content) }
            SpeakingHome(adult, app, path)
        }
        scrollTo(hasText("Citește cu voce tare"))
        scrollTo(button("Simulare BAC oral"))
        screenshot("00-speaking-page")
        tap(button("Simulare BAC oral"))
        waitFor(button("Trage un bilet"))
        assertTrue(exists(hasText("Ce urmăresc profesorii")))
        screenshot("00b-bac-from-the-page")
    }

    @Test
    fun aShortSimulationFromTheTicketToTheReport() {
        val ticket = BacContent.load(context.assets)!!.ticket("b03")!!
        val words = ticket.text.words
        // The reader marks one more key word while preparing ("grădiniță") and stresses all five.
        val extra = words.indexOfFirst { it.bare == "grădiniță" }
        queue(
            SyntheticSpeech.say(
                words.map { it.shown },
                stressed = ticket.text.keyIndices + extra,
                pauseAfter = SyntheticSpeech.readerPauses(words),
            ),
        )
        queue(answer("Anunțul spune că biblioteca caută voluntari pentru vară. Voluntarii citesc copiilor și ajută cititorii."))
        queue(answer("Emițătorul este biblioteca, iar receptorii sunt elevii de liceu. Ultima propoziție îi cheamă să vină."))
        queue(
            answer(
                "Cred că voluntariatul nu ar trebui să fie obligatoriu. În primul rând, ajutorul dat de bunăvoie e mai sincer. " +
                    "În al doilea rând, elevii au deja multe ore. În concluzie, școala poate să încurajeze voluntariatul fără să-l impună.",
            ),
        )
        show {
            BacOralScreen(adult, app, onDone = {}, clock = clock, draw = { content, _ -> content.ticket("b03")!! })
        }

        waitFor(button("Trage un bilet"))
        screenshot("bac-01-intro")
        tap(hasText("5 min") and hasClickAction())
        tap(button("Trage un bilet"))

        waitFor(hasText("Pregătire"))
        assertTrue(exists(hasText("5:00")))
        tap(hasTestTag("text-word-$extra"))
        assertTrue(exists(hasTestTag("text-word-$extra") and hasStateDescription("cuvânt-cheie")))
        clock.advance(61.0)
        rule.waitUntil(3_000) { exists(hasText("3:59")) }
        screenshot("bac-02-preparation")
        clock.advance(240.0)
        waitFor(hasText("Timpul de pregătire s-a terminat."))
        screenshot("bac-03-time-up")
        tap(button("Mai departe"))

        waitFor(button("Încep să citesc"))
        screenshot("bac-04-reading")
        tap(button("Încep să citesc"))
        waitFor(button("Am terminat"))
        screenshot("bac-05-reading-recording")
        tap(button("Am terminat"))

        for (task in listOf("Cerința 1", "Cerința 2", "Cerința 3: opinia ta")) {
            waitFor(hasText(task))
            if (task == "Cerința 1") screenshot("bac-06-task-1")
            tap(button("Încep să răspund"))
            waitFor(button("Am terminat"))
            if (task.startsWith("Cerința 3")) screenshot("bac-07-opinion-recording")
            tap(button("Am terminat"))
        }

        waitFor(hasText("Raportul de exersare"), seconds = 90)
        assertTrue(exists(hasTestTag("grade-note")))
        screenshot("bac-08-report")
        scrollTo(hasText("Cuvintele-cheie"))
        // All 108 words read as written, every punctuation mark paused at, the five key words stressed.
        assertTrue(exists(hasText("13 din 13 (100 %)")))
        assertTrue(exists(hasText("108 din 108 (100 %)")))
        assertTrue(exists(hasText("5 din 5 au ieșit în față (100 %)")))
        screenshot("bac-09-report-reading")
        scrollTo(hasText("Cerința 3: opinia"))
        scrollTo(hasText("Le-ai numărat altfel?"))
        assertTrue(exists(hasText("2 argumente, după cuvintele de legătură")))
        screenshot("bac-10-report-opinion")
        tap(hasClickAction() and SemanticsMatcher("plus") { labelOf(it) == "Mai multe argumente" })
        waitFor(hasText("3 argumente, numărate de tine"))
        scrollTo(hasText("Ce urmăresc profesorii"))
        scrollTo(button("Alt bilet"))
        screenshot("bac-11-report-end")

        val saved = runBlocking { app.repository.speeches(adult.id) }
        assertEquals(listOf("Simulare BAC oral, biletul 3"), saved.map { it.prompt })
        assertEquals(null, saved.single().transcript)
        assertEquals(0, clips.size)
    }

    @Test
    fun twoSentencesOfTheEmphasisExercise() {
        val content = EmphasisContent.load(context.assets)!!
        val first = content.items.first { it.id == "ro-01" }
        val second = content.items.first { it.id == "ro-04" }
        queue(SyntheticSpeech.say(first.text.words.map { it.shown }, stressed = first.text.keyIndices, pauseAfter = SyntheticSpeech.readerPauses(first.text.words), rate = 48000.0))
        queue(SyntheticSpeech.say(second.text.words.map { it.shown }, pauseAfter = SyntheticSpeech.readerPauses(second.text.words), rate = 48000.0))
        show {
            EmphasisScreen(Language.Ro, adult, app, onDone = {}, pick = { listOf(first, second) })
        }

        waitFor(button("Începem"))
        screenshot("emphasis-01-intro")
        tap(button("Începem"))
        waitFor(hasText("Cineva te întreabă:"))
        screenshot("emphasis-02-sentence")
        tap(button("Citește"))
        waitFor(button("Gata"))
        screenshot("emphasis-03-listening")
        tap(button("Gata"))
        waitFor(hasText("Da! Cuvântul marcat a ieșit în față."), seconds = 30)
        assertTrue(exists(hasText("„azi” a fost mai lung, mai tare și mai sus decât cuvintele din jur.")))
        screenshot("emphasis-04-stressed")

        tap(button("Mai departe"))
        tap(button("Citește"))
        tap(button("Gata"))
        waitFor(hasText("Încă nu."), seconds = 30)
        assertTrue(exists(hasText("„șapte” a fost cam la fel de lung și de tare ca vecinii lui.")))
        screenshot("emphasis-05-not-yet")

        tap(button("Vezi cum a mers"))
        waitFor(hasText("Cum a mers"))
        assertTrue(exists(hasText("Cuvinte marcate care au ieșit în față: 1 din 2.")))
        screenshot("emphasis-06-summary")
        val saved = runBlocking { app.repository.speeches(adult.id) }
        assertEquals(listOf("Cuvântul important"), saved.map { it.prompt })
    }
}
