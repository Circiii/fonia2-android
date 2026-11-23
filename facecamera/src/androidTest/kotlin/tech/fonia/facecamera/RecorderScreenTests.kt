package tech.fonia.facecamera

import android.Manifest
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The debug recording screen opens, starts a recording file and stops it. */
@RunWith(AndroidJUnit4::class)
class RecorderScreenTests {
    @get:Rule(order = 0)
    val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<FaceRecorderActivity>()

    @Test(timeout = 60_000)
    fun recordsToAFile() {
        val directory = File(targetContext.getExternalFilesDir(null), "face-recordings")
        val before = directory.listFiles()?.toSet() ?: emptySet()
        compose.onNodeWithText("Poziția ținută:").assertExists()
        compose.onNodeWithText("smile").performClick()
        compose.onNodeWithText("Înregistrează").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Oprește", substring = true).performClick()
        compose.waitForIdle()
        val created = (directory.listFiles()?.toSet() ?: emptySet()) - before
        assertEquals(1, created.size)
        val lines = created.single().readLines()
        assertEquals(FaceRecording.HEADER.joinToString(","), lines.first())
        assertTrue(lines.drop(1).all { it.split(",")[1] == "smile" })
        report("recorder screen: ${created.single().name}, ${lines.size - 1} frames")
    }
}
