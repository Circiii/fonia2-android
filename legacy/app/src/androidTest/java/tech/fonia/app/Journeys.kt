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
 * because a Compose test waits for the screen to be idle. `foniaGate` fixes the number the adult check asks
 * ([GATE_NUMBER]), so a journey can pass it the way an adult does. `foniaNoAdultCards` keeps the card for the adult
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

    /** The adult check is on screen: the hold ring, not yet the number. */
    protected fun waitForAdultCheck(seconds: Long = 5): Boolean = waitFor(button(HOLD_LABEL), seconds)

    /**
     * Passes the adult check as TalkBack does: its double tap stands for the three-second hold (a test can't time a
     * hold against the frame clock), then the number is typed on the keypad.
     */
    protected fun passAdultCheck() {
        assertTrue("adult check", waitForAdultCheck())
        rule.onAllNodes(button(HOLD_LABEL)).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(waitFor(staticText("patruzeci și șapte")))
        for (digit in GATE_NUMBER.toString()) tap(button(digit.toString()))
        rule.waitUntil(5_000) { !exists(staticText("patruzeci și șapte")) }
    }

    protected fun goBack() {
        tap(button("Înapoi"))
    }

    /** The voice welcome, left for later, then the page for the adult next to the child. */
    protected fun passWelcome() {
        assertTrue(waitFor(button("Pornește microfonul"), 15))
        tap(button("Mai târziu"))
        assertTrue(waitFor(staticText("Pentru adultul de lângă copil")))
        tap(button("Mai departe"))
    }

    /** The first run of a child who practises S, up to the moment the profile is made. */
    protected fun makeChild() {
        passWelcome()

        assertTrue(waitFor(button("Un copil, R, S, Ș și celelalte, pas cu pas")))
        tap(button("Un copil, R, S, Ș și celelalte, pas cu pas"))

        assertTrue(waitFor(staticText("Cum îl cheamă pe copil?")))
        tap(button("Sunetul S"))
        tap(button("Gata"))
    }

    /** A child's first run ends on the quick check's invitation; these journeys leave it for later. */
    protected fun reachChildHome() {
        makeChild()
        assertTrue(waitFor(staticText("Hai să vedem ce sunete exersăm"), 10))
        tap(button("Mai târziu"))
        rule.waitUntil(5_000) { !exists(staticText("Hai să vedem ce sunete exersăm")) }
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
        const val GATE_NUMBER = 47
        const val HOLD_LABEL = "Ține apăsat trei secunde pentru zona adultului"
    }
}
