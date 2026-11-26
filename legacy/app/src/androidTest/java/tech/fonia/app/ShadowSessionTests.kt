package tech.fonia.app

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tech.fonia.features.ml.MlHub
import tech.fonia.features.ml.ModelCatalog
import tech.fonia.features.ml.ModelManager
import tech.fonia.features.pilot.PilotLog
import tech.fonia.features.pilot.PilotSwitch
import tech.fonia.features.session.ShadowBadgeSettings
import tech.fonia.speech.LocalFileFetcher
import java.io.File

/**
 * A real session with the shadow scorer present (docs/PLAN.md §6, 03 §1.2 P13): ZIPA-small int8 installed through the
 * model manager, the takes from the recorded clips, everything else the app's own engines. The session runs to its end
 * as it does without shadow mode, the developer badge shows ZIPA's verdicts, and no audio is kept anywhere.
 *
 * A run on the recorded clips works on a copy of the data and keeps no pilot log, as on iOS: what the log keeps of a
 * real session (each take's two verdicts, the verdict the child got, the dose) is covered by `ShadowModeTests` and
 * `PilotLogTests`. Here the log has to stay away, even with its switch on.
 *
 * The model comes from `/data/local/tmp` (`./gradlew :app:zipaModelToDevice`, or `adb push`); without it the test is
 * skipped.
 */
@RunWith(AndroidJUnit4::class)
class ShadowSessionTests : Journeys() {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun installZipaAndTheBadge() {
        val pushed = File(InstrumentationRegistry.getArguments().getString("foniaZipaModel") ?: DEFAULT_MODEL)
        assumeTrue("put ZIPA on the device first: ./gradlew :app:zipaModelToDevice", pushed.canRead())
        // The same pins, served from the pushed file instead of Hugging Face: the manager checks the size and SHA-256.
        val local = ModelCatalog.zipa(baseUrl = pushed.parentFile!!.toURI())
        val manager = ModelManager(MlHub.modelsFolder(context), listOf(local)) { LocalFileFetcher }
        if (!manager.isReady(local)) runBlocking { manager.download(local) }
        assertTrue(MlHub.manager(context).isReady(ModelCatalog.ZIPA))
        // The pilot log is off until switched on: on, so that its absence after the run means something.
        context.getSharedPreferences("fonia", Context.MODE_PRIVATE).edit()
            .putBoolean(ShadowBadgeSettings.KEY, true).putBoolean(PilotSwitch.KEY, true).commit()
        PilotLog.defaultFolder(context).deleteRecursively()
    }

    /** Answers whatever the session asks of a person, so the run reaches its end on its own. */
    private fun answerIfAsked() {
        for (label in listOf("Bine", "Mai departe", "Am făcut", "Ne-am înțeles")) {
            if (exists(button(label))) {
                tap(button(label))
                return
            }
        }
    }

    private val badgeVerdict = SemanticsMatcher("the badge shows a ZIPA verdict") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("ZIPA ") } == true
    }

    @Test
    fun aSessionWithTheShadowScorerRunsToItsEndAndShowsZipas() {
        launch {
            putExtra("foniaStart", "s:syllables")
            putExtra("foniaSpeakOnly", true)
            putExtra("foniaShort", true)
        }
        // A debug launch with `foniaStart` makes its own child (S, 6-7 ani) and skips the first run.
        assertTrue(waitFor(button("Mă opresc"), 15))

        // The session goes as it always does: shadow mode adds nothing to wait for.
        // The recap: "Gata" next to "Încă una" when more is waiting, otherwise the session's own end button.
        val end = button("Gata") or button("Gata pe azi")
        val deadline = System.currentTimeMillis() + 60_000
        while (!exists(end) && System.currentTimeMillis() < deadline) {
            if (!waitFor(end, 3)) answerIfAsked()
        }
        assertTrue(exists(end))
        assertTrue(exists(staticText("Bravo!")) || exists(staticText("Ai exersat!")))

        // ZIPA's verdicts arrive on their own thread, after the child's; the developer badge shows them.
        assertTrue("the badge never showed a ZIPA verdict", waitFor(badgeVerdict, 90))
        screenshot("session-end")

        // A test run keeps no pilot log: the shadow scores of the recorded clips never reach it.
        assertFalse(File(PilotLog.defaultFolder(context), PILOT_DATABASE).exists())

        // No audio anywhere new: the models folder holds the model, the child's takes aren't kept, on the copy either.
        val modelFiles = MlHub.modelsFolder(context).walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertEquals(listOf(ModelCatalog.ZIPA.mainFile), modelFiles)
        for (home in listOf(context.noBackupFilesDir, File(context.noBackupFilesDir, "TestRun"))) {
            val recordings = File(home, "Fonia/Recordings").walkTopDown().filter { it.isFile }.toList()
            assertTrue(recordings.toString(), recordings.isEmpty())
        }

        // One ONNX Runtime, the pinned one, and its telemetry provider gone from the app.
        assertEquals("1.31.1", OrtEnvironment.getEnvironment().version)
        val providers = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS).providers.orEmpty()
        assertFalse(providers.joinToString { it.name }, providers.any { it.name.startsWith("ai.onnxruntime") })

        tap(end)
        assertTrue(waitFor(button("Acasă"), 10))

        // The settings, behind the adult check: the model's size, its delete, and the developer switch of the badge.
        // (The dose counter in „Cum merge” reads the pilot log, which a test run doesn't keep.)
        tap(button("Progres"))
        assertTrue(waitFor(button("Setări")))
        tap(button("Setări"))
        passAdultCheck()
        // The pilot's switches are folded away under "Studiu și funcții noi".
        assertTrue(waitFor(buttonStartingWith("Studiu și funcții noi")))
        rule.onAllNodes(buttonStartingWith("Studiu și funcții noi")).onFirst().performScrollTo()
        tap(buttonStartingWith("Studiu și funcții noi"))
        assertTrue(waitFor(staticText("Ascultare pe sunete, în probă"), 10))
        assertTrue(exists(button("Șterge pachetul (${ModelCatalog.ZIPA.megabytes} MB)")))
        assertTrue(exists(button("Acordul cu ZIPA în lecție")))
        rule.onAllNodes(button("Acordul cu ZIPA în lecție")).onFirst().performScrollTo()
        // The sheet slides up for a moment; the picture is for a person, so it waits for it.
        Thread.sleep(1500)
        screenshot("settings")
    }

    /** For a person to look at the new screens: `adb exec-out run-as tech.fonia.app cat files/screens/<name>.png`. */
    private fun screenshot(name: String) {
        rule.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val folder = File(context.filesDir, "screens").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val DEFAULT_MODEL = "/data/local/tmp/fonia/zipa-small-crctc-500k/model.int8.onnx"
        const val PILOT_DATABASE = "pilot.sqlite"
    }
}
