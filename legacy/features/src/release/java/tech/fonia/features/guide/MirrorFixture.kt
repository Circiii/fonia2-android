package tech.fonia.features.guide

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.ImageBitmap
import tech.fonia.facecamera.MouthTracker

/** Release builds have no still-portrait mirror: [make] is always null (see the debug twin). */
internal class MirrorFixture private constructor() {
    val mouth: MouthTracker get() = error("no fixture in release builds")
    val picture: State<ImageBitmap?> get() = error("no fixture in release builds")

    suspend fun run() = Unit

    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun make(context: Context, face: String?): MirrorFixture? = null
    }
}
