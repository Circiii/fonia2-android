package tech.fonia.app

import android.content.Context
import tech.fonia.app.mirror.FaceFixture

/** Release build: no launch flags, no fixtures, no lab; the microphone is the only source. */
object VariantHooks {
    const val debug = false

    @Suppress("UNUSED_PARAMETER")
    fun takeSources(context: Context, flags: LaunchFlags): TakeSources? = null

    @Suppress("UNUSED_PARAMETER")
    fun faceFixture(context: Context, flags: LaunchFlags): FaceFixture? = null
}
