package tech.fonia.features.speaking

import android.content.Context
import tech.fonia.audio.CapturedClip

/** Release builds: no recorded take stands in for the microphone (see the debug twin). */
internal object SpeakingFixture {
    @Suppress("UNUSED_PARAMETER")
    fun take(context: Context): CapturedClip? = null
}
