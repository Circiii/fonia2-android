package tech.fonia.app

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * because a Compose test waits for the screen to be idle.
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

    protected fun tap(matcher: SemanticsMatcher) {
        val node = rule.onAllNodes(matcher).onFirst()
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    protected fun goBack() {
        tap(button("Înapoi"))
    }

    /** The first run of a child who practises S, up to the moment the profile is made. */
    protected fun makeChild() {
        assertTrue(waitFor(button("Pornește microfonul"), 15))
        tap(button("Mai târziu"))

        assertTrue(waitFor(button("Un copil, R, S, Ș și celelalte, pas cu pas")))
        tap(button("Un copil, R, S, Ș și celelalte, pas cu pas"))

        assertTrue(waitFor(staticText("Cum îl cheamă pe copil?")))
        tap(button("Sunetul S"))
        tap(button("Gata"))
    }

    protected fun reachChildHome() {
        makeChild()
        assertTrue(waitFor(button("Acasă")))
    }

    protected fun reachAdultHome() {
        assertTrue(waitFor(button("Mai târziu"), 15))
        tap(button("Mai târziu"))

        assertTrue(waitFor(button("Eu, Pronunție în engleză, dicție, vorbit în public")))
        tap(button("Eu, Pronunție în engleză, dicție, vorbit în public"))

        assertTrue(waitFor(button("Gata")))
        tap(button("Gata"))
        assertTrue(waitFor(button("Acasă")))
    }
}
