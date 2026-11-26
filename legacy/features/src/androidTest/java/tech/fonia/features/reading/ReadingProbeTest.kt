package tech.fonia.features.reading

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
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
import tech.fonia.design.FoniaTheme
import tech.fonia.design.LocalReduceMotion
import tech.fonia.domain.ContentSource
import tech.fonia.domain.FoniaContent
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.AudioHub
import tech.fonia.features.app.Destination
import tech.fonia.features.app.NavStack
import tech.fonia.features.app.NavigationHost
import tech.fonia.features.app.NoAudioBackend
import tech.fonia.features.app.SpeechHub
import tech.fonia.features.grownups.ParentsScreen
import tech.fonia.features.home.DestinationScreen
import tech.fonia.features.home.PathModel
import tech.fonia.store.FoniaDatabase
import tech.fonia.store.FoniaRepository
import tech.fonia.store.Profile
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/**
 * A whole probe on the emulator, as an adult does it: pick the list, give the instructions, start the clock, tap the
 * words read differently, let the minute run out (on a clock the test moves, so it takes seconds), tap the last word,
 * read the result with the child's curve, then make the report and look at its pages. The store and the PDF are
 * checked at the end. Screenshots of each step go to the test app's files (`reading-screenshots/`).
 */
@RunWith(AndroidJUnit4::class)
class ReadingProbeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** The probe's clock, moved by hand. */
    private class HandClock : ProbeClock {
        val now = AtomicLong(5_000_000_000L)

        override fun nanos(): Long = now.get()

        fun advance(seconds: Double) {
            now.addAndGet((seconds * 1e9).toLong())
        }
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clock = HandClock()
    private val today = LocalDate.of(2026, 11, 6)
    private lateinit var app: AppModel
    private lateinit var store: ReadingStore
    private lateinit var child: Profile
    // Each test runs on a new instance, so the folder is never emptied here: the files are overwritten by name.
    private val shots = File(context.filesDir, "reading-screenshots").apply { mkdirs() }

    @Before
    fun setUp() {
        val assets = context.assets
        val content = FoniaContent.load(ContentSource { path -> runCatching { assets.open(path).use { it.readBytes() } }.getOrNull() })
        val folder = File(context.cacheDir, "reading-test").apply { deleteRecursively() }
        store = ReadingStore(File(folder, "Reading"))
        app = AppModel(
            content = content,
            repository = FoniaRepository(FoniaDatabase.inMemory(File(folder, "Recordings"))),
            audio = AudioHub(voices = null, backend = NoAudioBackend(context), usesFixtures = false),
            speech = SpeechHub(model = null, scope = scope),
            scope = scope,
            reading = store,
        )
        child = Profile(
            name = "Ana",
            kind = Profile.Kind.Child,
            ageBand = Profile.AgeBand.SixToSeven,
            voiceGroup = Profile.VoiceGroup.Child,
            color = "cold",
            targetSounds = listOf("s"),
            track = Profile.Track.Ro,
        )
        runBlocking {
            app.repository.saveProfile(child)
            // Three earlier probes of the same list, so the curve has a history.
            for ((day, correct) in listOf("2026-06-10" to 24, "2026-08-03" to 29, "2026-09-28" to 33)) {
                store.add(child.id, earlier(day, correct))
            }
        }
        scope.launch { app.start() }
        rule.waitUntil(10_000) { app.profiles.any { it.id == child.id } }
    }

    /** The reading page alone, on the hand-moved clock. */
    private fun showReadingHome() {
        rule.setContent {
            FoniaTheme {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    ReadingHome(child.id, app, clock = clock, today = { today })
                }
            }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun earlier(day: String, correct: Int) = ProbeRecord(
        takenAt = LocalDate.parse(day).atTime(17, 0).toInstant(ZoneOffset.UTC),
        localDay = day,
        probeID = "A-mono",
        kind = ProbeKind.Mono,
        set = "A",
        contentVersion = "ciorna-2026-11-06",
        preset = ReadingPreset.Standard,
        startedBy = StartSource.Tap,
        seconds = 60.0,
        totalWords = 60,
        wordsRead = correct + 3,
        errorIndices = listOf(1, 4, 7),
        syllablesRead = correct + 3,
        syllablesCorrect = correct,
    )

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

    private fun word(index: Int) = hasTestTag("word-$index")

    /** The whole screen, system bars included, as the emulator shows it. */
    private fun screenshot(name: String) {
        rule.waitForIdle()
        Thread.sleep(400)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun theGrownUpsAreaOpensTheReadingPage() {
        // The way an adult gets here in the app: "Setări" behind the adults' door, then "Citire".
        rule.setContent {
            FoniaTheme {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    val path = remember { PathModel(child, app.content) }
                    NavigationHost(remember { NavStack() }) { destination ->
                        if (destination == Destination.Root) {
                            ParentsScreen(child, app, path, onShowGrownups = {})
                        } else {
                            DestinationScreen(destination, app, path, onShowGrownups = {})
                        }
                    }
                }
            }
        }
        // The settings ask for an adult while a child is on the phone; the number is the one the check shows.
        rule.runOnIdle { app.gate.answer(app.gate.question.answer) }
        waitFor(button("Citire"))
        screenshot("00-grownups-row")
        tap(button("Citire"))
        waitFor(hasText("Ce citește"))
        assertTrue(exists(button("Cuvinte de o silabă")))
        assertTrue(exists(button("Un text scurt")))
    }

    @Test
    fun aWholeProbeFromTheListToTheReport() {
        showReadingHome()
        // The adults' page, with the curve of the three earlier probes.
        waitFor(button("Cuvinte de o silabă"))
        assertTrue(exists(hasText("Cum citește de la o probă la alta")))
        screenshot("01-reading-home")

        // The three settings of C9, with a preview; the probe below runs in Standard.
        tap(hasText("Lat") and hasClickAction())
        runCatching { rule.onAllNodes(hasContentDescription("Așa arată: Lat")).onFirst().performScrollTo() }
        screenshot("01b-reading-settings")
        tap(hasText("Standard") and hasClickAction())
        runCatching { rule.onAllNodes(button("Cuvinte de o silabă")).onFirst().performScrollTo() }

        // Set B is offered: the last probe used set A.
        tap(button("Cuvinte de o silabă"))
        waitFor(hasText("Spune-i copilului"))
        assertTrue(exists(hasText("Setul B")))
        screenshot("02-probe-instructions")

        tap(button("Arată cuvintele"))
        waitFor(button("Pornește"))
        screenshot("03-probe-ready")

        tap(button("Pornește"))
        waitFor(hasText("Atinge cuvintele citite altfel sau sărite."))
        for (index in listOf(2, 5, 9)) tap(word(index))
        tap(word(5)) // read well after all
        clock.advance(31.0)
        rule.waitUntil(3_000) { exists(hasText("0:31")) }
        screenshot("04-probe-running")

        clock.advance(30.0)
        waitFor(hasText("A trecut un minut."))
        tap(word(39))
        screenshot("05-probe-last-word")

        tap(button("Vezi rezultatul"))
        waitFor(hasText("Rezultatul"))
        // 40 read, 2 tapped: 38 correct in the minute.
        assertTrue(exists(hasText("38")))
        assertTrue(exists(hasText("38 de cuvinte corecte pe minut: în banda raportată pentru final de clasa I.")))
        screenshot("06-results")

        val kept = runBlocking { store.load(child.id) }.probes.single { it.set == "B" }
        assertEquals("B-mono", kept.probeID)
        assertEquals(40, kept.wordsRead)
        assertEquals(listOf(2, 9), kept.errorIndices)
        assertEquals(60.0, kept.seconds, 0.0)
        assertEquals(StartSource.Tap, kept.startedBy)
        assertEquals(38, kept.score.wcpmShown)

        tap(button("Gata"))
        waitFor(button("Raportul pentru învățător"))
        tap(button("Raportul pentru învățător"))
        waitFor(button("Fă PDF-ul"))
        screenshot("07-report-form")
        tap(button("Fă PDF-ul"))
        waitFor(button("Trimite PDF-ul"), seconds = 30)
        assertTrue(exists(hasTestTag("report-page-0")))
        screenshot("08-report-preview")

        val pdf = File(ReadingReportPdf.folder(context), "fonia-citire-$today.pdf")
        assertTrue(runBlocking { store.load(child.id) }.report.pseudonym == "Elevul A")
        assertTrue(pdf.isFile)
        val pages = ReadingReportPdf.render(pdf, 1240)
        // The cover with parts 1 and 2, then parts 3 to 6, then the page that maps it all onto Anexa 1.
        assertTrue("${pages.size} pages", pages.size >= 3)
        pages.forEachIndexed { index, page ->
            File(shots, "09-report-page-${index + 1}.png").outputStream().use { page.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        // The text, in the airy setting: a title, then the story, short lines.
        tap(button("Gata"))
        waitFor(button("Un text scurt"))
        tap(hasText("Aerisit") and hasClickAction())
        tap(button("Un text scurt"))
        waitFor(button("Arată textul"))
        tap(button("Arată textul"))
        waitFor(word(0))
        // The last probe used set B, so set A comes next.
        assertTrue(exists(hasText("La bunica")))
        screenshot("10-text-probe-airy")
        tap(button("Închide"))
        tap(button("Oprește"))
        waitFor(button("Un text scurt"))
    }

    @Test
    fun aDeletedProfileTakesItsProbesAlong() {
        showReadingHome()
        waitFor(button("Cuvinte de o silabă"))
        assertTrue(runBlocking { store.load(child.id) }.probes.isNotEmpty())
        runBlocking { app.repository.deleteProfile(child.id) }
        rule.waitUntil(10_000) { runBlocking { store.load(child.id) }.probes.isEmpty() }
    }
}
