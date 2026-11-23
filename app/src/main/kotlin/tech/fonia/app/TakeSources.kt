package tech.fonia.app

import tech.fonia.audio.CaptureSource
import tech.fonia.live.LiveTarget
import tech.fonia.model.SoundId

/**
 * Where a take's audio comes from when it is not the microphone (fixture mode, A3). The debug build provides one from
 * the launch flags ([VariantHooks.takeSources]); the release build has none, so it can only ever use the microphone.
 */
interface TakeSources {
    /** Short name for logs and the attempt row (`fixture:s_hold`). */
    val label: String

    /** Told before each take, so a fixture can pick the clip that matches the item ([type]: `hold`, `word`, `train`…). */
    fun beforeTake(sound: SoundId, type: String)

    /** Told before a public-speaking take (NO-UM, MINUTE), so a fixture can switch to its speech clip. */
    fun beforeSpeaking() {}

    /** The source for the take about to open (called by the audio director on its own thread). */
    fun source(target: LiveTarget): CaptureSource
}
