package tech.fonia.features.adaptive

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tech.fonia.design.FoniaTheme
import tech.fonia.design.LocalReduceMotion
import tech.fonia.domain.ContentSource
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.Rung
import tech.fonia.domain.SoundID
import tech.fonia.domain.adaptive.AdaptiveProfile
import tech.fonia.domain.adaptive.ChallengeState
import tech.fonia.domain.adaptive.ItemPosition
import tech.fonia.domain.adaptive.KeyMastery
import tech.fonia.domain.adaptive.ModeSetting
import tech.fonia.domain.adaptive.PracticeMode
import tech.fonia.domain.adaptive.SoundAdaptive
import tech.fonia.domain.adaptive.Step
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

/**
 * The adaptive pilot's page as an adult reaches it: "Setări" behind the adults' door, "Mod adaptiv (pilot)", the
 * switch, the mode of a sound, and the estimates in words. Screenshots go to the test app's files
 * (`adaptive-screenshots/`).
 */
@RunWith(AndroidJUnit4::class)
class AdaptivePilotScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: AppModel
    private lateinit var store: AdaptiveStore
    private lateinit var child: Profile
    private val shots = File(context.filesDir, "adaptive-screenshots").apply { mkdirs() }

    @Before
    fun setUp() {
        val assets = context.assets
        val content = FoniaContent.load(ContentSource { path -> runCatching { assets.open(path).use { it.readBytes() } }.getOrNull() })
        val folder = File(context.cacheDir, "adaptive-test").apply { deleteRecursively() }
        store = AdaptiveStore(File(folder, "Adaptive"))
        app = AppModel(
            content = content,
            repository = FoniaRepository(FoniaDatabase.inMemory(File(folder, "Recordings"))),
            audio = AudioHub(voices = null, backend = NoAudioBackend(context), usesFixtures = false),
            speech = SpeechHub(model = null, scope = scope),
            scope = scope,
            adaptive = store,
        )
        child = Profile(
            name = "Ana",
            kind = Profile.Kind.Child,
            ageBand = Profile.AgeBand.SixToSeven,
            voiceGroup = Profile.VoiceGroup.Child,
            color = "cold",
            targetSounds = listOf("s", "r"),
            track = Profile.Track.Ro,
        )
        runBlocking {
            app.repository.saveProfile(child)
            // Left from earlier sessions, with the switch now off: one place still practised, one sure on two days.
            store.update(child.id) {
                AdaptiveProfile(
                    enabled = false,
                    sounds = mapOf(
                        SoundID.S to SoundAdaptive(
                            mode = PracticeMode.Acquisition,
                            challenge = ChallengeState(Rung.WordsMedial, Step(Rung.WordsInitial)),
                            mastery = listOf(
                                KeyMastery(ItemPosition.Initial, Rung.WordsInitial, 0.96, 40, listOf("2026-11-03", "2026-11-04")),
                                KeyMastery(ItemPosition.Medial, Rung.WordsMedial, 0.42, 18),
                            ),
                        ),
                    ),
                )
            }
        }
        scope.launch { app.start() }
        rule.waitUntil(10_000) { app.profiles.any { it.id == child.id } }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun labelOf(node: androidx.compose.ui.semantics.SemanticsNode): String? =
        node.config.getOrNull(SemanticsProperties.Text)?.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.text }

    private fun button(startingWith: String): SemanticsMatcher =
        hasClickAction() and SemanticsMatcher("button \"$startingWith…\"") { labelOf(it)?.startsWith(startingWith) == true }

    private fun exists(matcher: SemanticsMatcher) = rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, seconds: Long = 10) = rule.waitUntil(seconds * 1000) { exists(matcher) }

    private fun tap(matcher: SemanticsMatcher) {
        rule.waitUntil(10_000) { rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        val node = rule.onAllNodes(matcher).onFirst()
        runCatching { node.performScrollTo() }
        node.performClick()
        rule.waitForIdle()
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        Thread.sleep(400)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun anAdultSwitchesThePilotChoosesAModeAndReadsTheEstimates() {
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
        waitFor(button("Mod adaptiv (pilot)"))
        screenshot("00-grownups-row")
        tap(button("Mod adaptiv (pilot)"))
        waitFor(hasText("Cum exersează fiecare sunet"))
        // The estimates in words: what is still practised, and what came out right on two days.
        assertTrue(exists(hasText("Sunete de exersat")))
        assertTrue(exists(hasText("Cuvinte cu S la mijloc")))
        assertTrue(exists(hasText("Încă îl exersăm")))
        assertTrue(exists(hasText("Ies sigur")))
        assertTrue(exists(hasText("Cuvinte care încep cu S")))
        assertTrue(exists(hasText("Acum: achiziție, la „Cuvinte care încep cu S”")))
        screenshot("01-adaptive-off")

        tap(hasText("Mod adaptiv (pilot)") and isToggleable())
        rule.waitUntil(5_000) { runBlocking { store.load(child.id).enabled } }
        // S is the first sound: its "Achiziție".
        tap(hasText("Achiziție") and isToggleable())
        rule.waitUntil(5_000) { runBlocking { store.load(child.id).sound(SoundID.S).setting == ModeSetting.Acquisition } }
        screenshot("02-adaptive-on")

        // Nothing on the page reads as a diagnosis or as a percentage.
        val texts = rule.onAllNodes(SemanticsMatcher("any text") { labelOf(it) != null }, useUnmergedTree = true).fetchSemanticsNodes().mapNotNull(::labelOf)
        assertFalse(texts.joinToString("\n"), texts.any { Regex("risc|diagnostic|severitate|tulburare|%", RegexOption.IGNORE_CASE).containsMatchIn(it) })
    }
}
