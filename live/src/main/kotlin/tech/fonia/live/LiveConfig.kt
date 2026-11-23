package tech.fonia.live

import tech.fonia.model.SoundId

/** CLOCK_MONOTONIC in nanoseconds. Injected so tests run on virtual time. */
fun interface NanoClock {
    fun nanos(): Long

    companion object {
        /** `System.nanoTime()`, which is CLOCK_MONOTONIC on Android, the clock Oboe timestamps use. */
        val System: NanoClock = NanoClock { java.lang.System.nanoTime() }
    }
}

/** Whose voice the analyser is tuned to (maps onto `tech.fonia.dsp.VoiceGroup`). */
enum class Talker { Child, Female, Male }

/** Which side of the S/Ș scale a sibilant target sits on. */
enum class SibilantSide { S, Sh }

/** What the current step listens for. Drives [LiveSnapshot.onTarget], [LiveSnapshot.fault] and the hold time. */
sealed interface LiveTarget {
    /** S family (S, Z, Ț) or Ș family (Ș, J, CE, GE); [voiced] for Z, J, GE. */
    data class Sibilant(val side: SibilantSide, val voiced: Boolean) : LiveTarget

    /** R. */
    data object Trill : LiveTarget

    /** Voice on (L, "palm on the throat"). */
    data object Voice : LiveTarget

    /** Blowing (the candle). */
    data object Blow : LiveTarget

    /** No sound target: anything loud enough counts (warm-ups, free talk). */
    data object AnySound : LiveTarget

    companion object {
        fun of(sound: SoundId): LiveTarget = when (sound) {
            SoundId.S, SoundId.Ts -> Sibilant(SibilantSide.S, voiced = false)
            SoundId.Z -> Sibilant(SibilantSide.S, voiced = true)
            SoundId.Sh, SoundId.Ce -> Sibilant(SibilantSide.Sh, voiced = false)
            SoundId.J, SoundId.Ge -> Sibilant(SibilantSide.Sh, voiced = true)
            SoundId.R -> Trill
            SoundId.L -> Voice
        }
    }
}

/**
 * Tunables of the continuous band. Data, not code (docs/REGULI.md): the app loads them from `spec/` and saves the version
 * with each attempt. Defaults follow docs/v2/02 §4 and decision N3.
 */
data class LiveConfig(
    /** Heavy analysis step (`live.analysisHopSeconds`, N3). 0.01 s = every 10 ms hop; the dsp default is 512/48000. */
    val analysisHopSeconds: Double = 0.01,
    val talker: Talker = Talker.Child,
    /** A break in the target shorter than this does not end the hold. */
    val holdGraceSeconds: Double = 0.15,
    /** true: [LiveSnapshot.holdSeconds] restarts after a longer break (continuous hold); false: it only pauses. */
    val holdResetsOnBreak: Boolean = true,
    /** Trill strength that counts as holding R (the old `SustainExercise` used 0.3). */
    val trillHold: Float = 0.3f,
    /** Fast level that counts as holding [LiveTarget.AnySound] / [LiveTarget.Voice] loudness. */
    val levelHold: Float = 0.3f,
    /** Blow strength that counts as blowing. */
    val blowHold: Float = 0.25f,
    /** |sample| at or above this, in a run of [clipRunSamples], is clipping. */
    val clipLevel: Float = 0.985f,
    val clipRunSamples: Int = 3,
    /** How long a clipping hop keeps [LiveSnapshot.captureQuality] at CLIPPING. */
    val clipHoldSeconds: Double = 0.5,
    /** Hops kept for the analysis-time and latency percentiles of [LiveStats]. */
    val statsWindow: Int = 512,
)
