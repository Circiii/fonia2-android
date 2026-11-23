package tech.fonia.features.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.fonia.audio.AudioDecoder
import tech.fonia.audio.CapturedClip
import tech.fonia.audio.ClipLocation
import java.io.File

/**
 * Reads a voice clip or a kept recording as a mono take, so a model voice can be compared with the learner's and a
 * kept take can be played again.
 *
 * iOS reads the file with `AVAudioFile` as float32 at the file's own rate and keeps channel 0, with no resampling.
 * Here `AudioDecoder.decodeMono` from :audio reads it at its own rate too, and trims an M4A by its edit list as Core
 * Audio does, so a kept take comes back at its length. It averages the channels instead of keeping the first; every
 * voice clip and every take is mono, so the samples are the same.
 */
object ClipDecoder {
    /** Throws for a file with no audio or a decoder failure (`CocoaError(.fileReadCorruptFile)` on iOS). */
    suspend fun decode(file: File): CapturedClip = decode(ClipLocation.File(file))

    suspend fun decode(location: ClipLocation): CapturedClip = withContext(Dispatchers.Default) {
        val audio = AudioDecoder.decodeMono(location)
        CapturedClip(audio.samples, audio.sampleRate)
    }
}
