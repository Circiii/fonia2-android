package tech.fonia.app.mirror

import androidx.compose.runtime.State
import androidx.compose.ui.graphics.ImageBitmap
import tech.fonia.facecamera.MouthTracker

/**
 * Where MIRROR's face comes from when it is not the camera: the debug build's still-image fixture
 * (`--es face fixture`, [tech.fonia.app.VariantHooks.faceFixture]); the release build has none. The images go through
 * the real Face Landmarker pipeline into [mouth], so everything after the camera is the real thing.
 */
interface FaceFixture {
    val mouth: MouthTracker

    /** The picture being analysed (upright, unmirrored), for the screen to draw where the preview would be. */
    val picture: State<ImageBitmap?>

    /** Short label for the screen's corner („Imagini de probă”). */
    val label: String

    /** Feeds the script, in a loop, until cancelled. */
    suspend fun run()
}
