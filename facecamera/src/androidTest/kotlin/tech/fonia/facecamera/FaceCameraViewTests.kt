package tech.fonia.facecamera

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tech.fonia.face.FaceFrame
import tech.fonia.face.FaceLevels
import tech.fonia.face.FaceSourceError
import tech.fonia.face.FakeFaceSource
import kotlin.time.Duration.Companion.seconds

/**
 * `CameraViewLifetimeTests` (FoniaFaceTests/FaceTests.swift), the three tests iOS runs on `FaceCameraHostView` in a
 * `UIWindow`, here on [FaceCameraView] in a composition: the source runs only while the view is composed, a view
 * that leaves before its start ran never starts it, and an unsupported source shows the placeholder and reports why.
 */
@RunWith(AndroidJUnit4::class)
class FaceCameraViewTests {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun holdingSource() = FakeFaceSource(script = listOf(FakeFaceSource.Step(FaceFrame(jawOpen = 0.5f), 60.seconds)))

    /** `settle()`: 50 ms, after the composition has caught up. */
    private fun settle() {
        compose.waitForIdle()
        Thread.sleep(50)
        compose.waitForIdle()
    }

    @Test(timeout = 60_000)
    fun followsTheWindow() {
        val levels = FaceLevels()
        val source = holdingSource()
        var shown by mutableStateOf(false)
        compose.setContent { if (shown) FaceCameraView(source, levels) }
        settle()
        assertFalse(levels.isPresent)

        shown = true
        settle()
        assertTrue(levels.isPresent)

        shown = false
        compose.waitForIdle()
        assertFalse(levels.isPresent)
        settle()
        assertFalse(levels.isPresent)

        shown = true
        settle()
        assertTrue(levels.isPresent)

        // dismantleUIView: the whole composition goes.
        compose.activityRule.scenario.onActivity { it.setContent {} }
        compose.waitForIdle()
        assertFalse(levels.isPresent)
    }

    @Test(timeout = 60_000)
    fun leavingBeforeTheTaskRuns() {
        val levels = FaceLevels()
        val source = holdingSource()
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {} // gives the window its lifecycle and saved-state owners
            // Composed on attach, disposed on detach, both before the main thread runs anything else, so the
            // effect that starts the source never gets to run.
            val view = ComposeView(activity).apply { setContent { FaceCameraView(source, levels) } }
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            root.addView(view)
            root.removeView(view)
        }
        settle()
        assertFalse(levels.isPresent)
    }

    @Test(timeout = 60_000)
    fun unsupportedSource() {
        var failure: Throwable? = null
        val source = CameraFaceSource(CameraFaceTracker(targetContext, CameraSelector.LENS_FACING_FRONT, cameraPresent = false))
        compose.setContent { FaceCameraView(source, FaceLevels(), onFailure = { failure = it }) }
        compose.onNodeWithTag(PLACEHOLDER_TAG).assertExists()
        compose.onNodeWithTag(CAMERA_PREVIEW_TAG).assertDoesNotExist()
        settle()
        assertSame(FaceSourceError.Unsupported, failure)
    }
}
