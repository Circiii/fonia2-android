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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tech.fonia.domain.Plural
import tech.fonia.features.ml.MlHub
import tech.fonia.features.ml.ModelCatalog
import tech.fonia.features.ml.ModelManager
import tech.fonia.features.pilot.PilotEnvironment
import tech.fonia.features.pilot.PilotLog
import tech.fonia.features.pilot.PilotSnapshot
import tech.fonia.features.session.ShadowBadgeSettings
import tech.fonia.speech.LocalFileFetcher
import java.io.File

/**
 * A real session with the shadow scorer present (docs/PLAN.md §6, 03 §1.2 P13): ZIPA-small int8 installed through the
 * model manager, the takes from the recorded clips, everything else the app's own engines. The session runs to its end
 * as it does without shadow mode, the developer badge shows ZIPA's verdicts, and the pilot log holds every take's two
 * verdicts with the same verdict the session recorded, and no audio anywhere.
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
        context.getSharedPreferences("fonia", Context.MODE_PRIVATE).edit().putBoolean(ShadowBadgeSettings.KEY, true).commit()
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

    private fun readLog(): PilotSnapshot {
        val log = PilotLog.open(PilotLog.defaultFolder(context), PilotEnvironment("test", "test", 0))
        try {
            return runBlocking { log.snapshot() }
        } finally {
            log.close()
        }
    }

    @Test
    fun aSessionWithTheShadowScorerKeepsItsVerdictsAndLogsZipas() {
        launch {
            putExtra("foniaStart", "s:syllables")
            putExtra("foniaSpeakOnly", true)
            putExtra("foniaShort", true)
        }
        makeChild()
        assertTrue(waitFor(button("Mă opresc"), 10))

        // The session goes as it always does: shadow mode adds nothing to wait for.
        val end = button("Gata pe azi")
        val deadline = System.currentTimeMillis() + 60_000
        while (!exists(end) && System.currentTimeMillis() < deadline) {
            if (!waitFor(end, 3)) answerIfAsked()
        }
        assertTrue(exists(end))
        assertTrue(exists(staticText("Bravo, ai reușit!")) || exists(staticText("Ne vedem mâine")))

        // ZIPA's verdicts arrive on their own thread, after the child's; the developer badge shows them.
        assertTrue("the badge never showed a ZIPA verdict", waitFor(badgeVerdict, 90))
        screenshot("session-end")

        // The pilot log: every spoken item TakeJudge judged is scored, next to the verdict the session recorded for it.
        var snapshot = readLog()
        val until = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < until) {
            snapshot = readLog()
            val judged = snapshot.attempts.values.flatten().count { it["mode"] == "production" && it["exercise"] in judgedKinds && it["sound"] == "s" }
            val logged = snapshot.shadow.values.flatten().size
            if (judged > 0 && logged >= judged) break
            runBlocking { delay(500) }
        }
        val profile = snapshot.profiles.single()
        val attempts = snapshot.attempts.getValue(profile.profileId)
        val shadow = snapshot.shadow.getValue(profile.profileId)
        assertTrue("no production attempt logged: $attempts", attempts.any { it["mode"] == "production" && it["sound"] == "s" })
        assertTrue("no shadow score logged", shadow.isNotEmpty())
        for (row in shadow) {
            assertEquals(row.toString(), "scored", row["status"])
            assertNotNull(row.toString(), row["verdict"])
            assertEquals(ModelCatalog.ZIPA.label, row["model"])
            assertEquals("fixture", row["input"])
            // The verdict the child got is the one the session saved for that take: shadow mode changed nothing.
            val attempt = attempts.firstOrNull { it["take"] == row["take"] }
            assertNotNull("no attempt for take ${row["take"]}: $attempts", attempt)
            assertEquals(row.toString(), attempt!!["machine"], row["shown"])
        }

        // No audio anywhere new: the log is a database, the models folder holds the model, the child's takes aren't kept.
        val pilotFiles = PilotLog.defaultFolder(context).listFiles().orEmpty().map { it.name }
        assertTrue(pilotFiles.toString(), pilotFiles.all { it.startsWith(PILOT_DATABASE) })
        val modelFiles = MlHub.modelsFolder(context).walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertEquals(listOf(ModelCatalog.ZIPA.mainFile), modelFiles)
        val recordings = File(context.noBackupFilesDir, "Fonia/Recordings").walkTopDown().filter { it.isFile }.toList()
        assertTrue(recordings.toString(), recordings.isEmpty())

        // One ONNX Runtime, the pinned one, and its telemetry provider gone from the app.
        assertEquals("1.31.1", OrtEnvironment.getEnvironment().version)
        val providers = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS).providers.orEmpty()
        assertFalse(providers.joinToString { it.name }, providers.any { it.name.startsWith("ai.onnxruntime") })

        tap(end)
        assertTrue(waitFor(button("Acasă"), 10))

        // The dose counter, behind the adults' door: today's takes of S, and the first of its five intensive days.
        tap(button("Progres"))
        assertTrue(waitFor(button("Setări")))
        tap(button("Setări"))
        assertTrue(waitFor(button("Sunt adult")))
        tap(button("Sunt adult"))
        assertTrue(waitFor(buttonStartingWith("Cum merge")))
        tap(buttonStartingWith("Cum merge"))
        assertTrue(waitFor(staticText("Încercări pe zi și pe săptămână")))
        val counted = attempts.count { it["dose"] == true }
        assertTrue(exists(staticText("Azi: ${Plural.format(counted, Plural.Noun.Incercare)}")))
        assertTrue(exists(staticText("Primele 5 zile cu S: 1 din 5 cu exerciții.")))
        screenshot("dose")

        // The settings: the model's size, its delete, and the developer switch of the badge.
        goBack()
        assertTrue(waitFor(button("Alte setări")))
        tap(button("Alte setări"))
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

        /** The spoken items TakeJudge judges and shadow mode scores (sustained sounds have their own judge). */
        val judgedKinds = setOf("repeat", "name", "twister")
    }
}
