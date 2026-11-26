package tech.fonia.app

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule

/**
 * What the journeys share: launching the real app with launch arguments as intent extras (iOS `launchArguments`), and
 * finding things the way XCUITest does. A button's label is its content description, else its texts joined with ", "
 * (SwiftUI's combined label); a static text is a text that isn't part of a button.
 *
 * Every journey starts from nothing (`foniaResetData`), takes come from the recorded clips (`foniaFixtures`, always
 * on in the iOS simulator), and `foniaReduceMotion` stills what never stops moving (Foni, the sky, the voice meters),
 * because a Compose test waits for the screen to be idle. `foniaGate` fixes the multiplication the adult check asks
 * (7 × 8, answer [GATE_NUMBER]), so a journey can pass it the way an adult does. `foniaNoAdultCards` keeps the card for the adult
 * out of the way of the lessons; the cards have their own tests.
 */
abstract class Journeys {
    @get:Rule
    val rule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    protected fun launch(extras: Intent.() -> Unit = {}) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("foniaResetData", true)
            .putExtra("foniaFixtures", true)
            .putExtra("foniaReduceMotion", true)
            .putExtra("foniaGate", GATE_NUMBER)
            .putExtra("foniaNoAdultCards", true)
            .apply(extras)
        scenario = ActivityScenario.launch(intent)
    }

    @After
    fun closeApp() {
        scenario?.close()
    }

    private fun labelOf(node: SemanticsNode): String? {
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.takeIf { it.isNotEmpty() }?.let { return it.joinToString(", ") }
        node.config.getOrNull(SemanticsProperties.Text)?.takeIf { it.isNotEmpty() }?.let { texts -> return texts.joinToString(", ") { it.text } }
        return null
    }

    /** `app.buttons[label]`. */
    protected fun button(label: String): SemanticsMatcher =
        hasClickAction() and SemanticsMatcher("button \"$label\"") { labelOf(it) == label }

    /** `app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", text)).firstMatch`. */
    protected fun buttonStartingWith(startingWith: String): SemanticsMatcher =
        hasClickAction() and SemanticsMatcher("button starting with \"$startingWith\"") { labelOf(it)?.startsWith(startingWith) == true }

    /** `app.staticTexts[text]`: a text that isn't part of a button, as XCUITest keeps a button's texts in the button. */
    protected fun staticText(text: String): SemanticsMatcher =
        !hasClickAction() and SemanticsMatcher("text \"$text\"") { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }

    /** `app.otherElements[label]`: anything with this accessibility label. */
    protected fun element(label: String): SemanticsMatcher =
        SemanticsMatcher("element \"$label\"") { labelOf(it) == label }

    protected fun exists(matcher: SemanticsMatcher): Boolean = rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** `waitForExistence(timeout:)`. */
    protected fun waitFor(matcher: SemanticsMatcher, seconds: Long = 5): Boolean = try {
        rule.waitUntil(seconds * 1000) { exists(matcher) }
        true
    } catch (_: ComposeTimeoutException) {
        false
    }

    /**
     * Taps a button as XCUITest does, through its accessibility action: the lower doors on Home sit under the tab bar
     * until scrolled, and a touch there lands on the bar.
     */
    protected fun tap(matcher: SemanticsMatcher) {
        val node = rule.onAllNodes(matcher).onFirst()
        runCatching { node.performScrollTo() }
        node.performSemanticsAction(SemanticsActions.OnClick)
    }

    /** The adult check is on screen: the multiplication (iOS `adultGatePrompt`). */
    protected fun waitForAdultCheck(seconds: Long = 5): Boolean = waitFor(element(GATE_PROMPT), seconds)

    /** Passes the adult check as an adult does: taps the right answer of the fixed multiplication. */
    protected fun passAdultCheck() {
        assertTrue("adult check", waitForAdultCheck())
        tap(button(GATE_NUMBER.toString()))
        rule.waitUntil(5_000) { !exists(element(GATE_PROMPT)) }
    }

    protected fun goBack() {
        tap(button("Înapoi"))
    }

    /** The voice welcome, left for later. */
    protected fun passWelcome() {
        assertTrue(waitFor(button("Pornește microfonul"), 15))
        tap(button("Mai târziu"))
    }

    /** The page for the adult next to a child: it comes right after "Un copil". */
    protected fun passAdultNote() {
        assertTrue(waitFor(staticText("Pentru adultul de lângă copil")))
        tap(button("Mai departe"))
    }

    /** The first run of a child who practises S, up to the moment the profile is made. */
    protected fun makeChild() {
        passWelcome()

        assertTrue(waitFor(button("Un copil, R, S, Ș și celelalte, pas cu pas")))
        tap(button("Un copil, R, S, Ș și celelalte, pas cu pas"))
        passAdultNote()

        assertTrue(waitFor(staticText("Cum îl cheamă pe copil?")))
        // "Gata" waits for an age.
        tap(button("6-7 ani"))
        tap(button("Sunetul S"))
        tap(button("Gata"))
    }

    /**
     * A child's first run goes on to the voice calibration: quiet, then a held "aaa", "sss" and "șșș" from the recorded
     * clips, about 20 seconds with no way out while the microphone is open. It ends on its result, left with "Gata".
     */
    protected fun passCalibration() {
        assertTrue("calibration result", waitFor(button("Încă o dată"), 45))
        tap(button("Gata"))
    }

    /** The quick check's invitation, which ends a child's first run, left for later. */
    protected fun leaveCheckOffer() {
        assertTrue(waitFor(staticText("Hai să vedem ce sunete exersăm"), 10))
        tap(button("Mai târziu"))
        rule.waitUntil(5_000) { !exists(staticText("Hai să vedem ce sunete exersăm")) }
    }

    /** A child's first run ends on the calibration, then the quick check's invitation; these journeys leave it for later. */
    protected fun reachChildHome() {
        makeChild()
        passCalibration()
        leaveCheckOffer()
        assertTrue(waitFor(button("Acasă")))
    }

    protected fun reachAdultHome() {
        passWelcome()

        assertTrue(waitFor(button("Eu, Pronunție în engleză, dicție, vorbit în public")))
        tap(button("Eu, Pronunție în engleză, dicție, vorbit în public"))

        assertTrue(waitFor(button("Gata")))
        tap(button("Gata"))
        assertTrue(waitFor(button("Acasă")))
    }

    companion object {
        /** `foniaGate 56`: the check always asks 7 × 8, with 49, 56, 63 and 64 to tap. */
        const val GATE_NUMBER = 56
        const val GATE_PROMPT = "Cât face 7 × 8?"
    }
}
