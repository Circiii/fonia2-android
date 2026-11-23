package tech.fonia.app.judge

import tech.fonia.live.Clip
import tech.fonia.live.LiveBoard
import tech.fonia.live.LiveConfig
import tech.fonia.live.LivePipeline
import tech.fonia.live.LiveTarget
import kotlin.math.max
import kotlin.math.roundToInt

/** Aggregates of the continuous band over one take (what the provisional judge decides from). */
data class TakeSummary(
    val seconds: Double,
    /** Time the analyser said "speaking" (with its hang time). */
    val speakingSeconds: Double,
    /** Time a fricative (hiss) was present. */
    val fricativeSeconds: Double,
    /** sNess (0 = Ș … 1 = S) averaged over fricative hops, weighted by the hiss strength; NaN without friction. */
    val meanSNess: Float,
    /** Share of the fricative time that was voiced (Z, J). */
    val voicedShare: Float,
    /** Longest continuous hold of the target ([LiveTarget]). */
    val bestHoldSeconds: Float,
    /** Time a trill was detected (R). */
    val trillSeconds: Double,
    /** Loudest fast level (0–1 above the room floor). */
    val peakLevel: Float,
)

/**
 * Re-runs the live pipeline (`:engine:live`, the same code that drives the letter) over a finished take, hop by hop,
 * and sums what it saw. Deterministic and pure: the same clip always gives the same summary, on the phone and on the PC.
 */
object TakeAnalysis {
    fun summarise(clip: Clip, target: LiveTarget, config: LiveConfig = LiveConfig()): TakeSummary {
        if (clip.isEmpty || clip.sampleRate <= 0) return TakeSummary(0.0, 0.0, 0.0, Float.NaN, 0f, 0f, 0.0, 0f)
        val pipeline = LivePipeline(LiveBoard(), config)
        pipeline.start(clip.sampleRate, target)
        val hop = max(1, (clip.sampleRate * config.analysisHopSeconds).roundToInt())
        val dt = hop / clip.sampleRate
        var speaking = 0.0
        var fricative = 0.0
        var voiced = 0.0
        var trill = 0.0
        var sWeighted = 0.0
        var weight = 0.0
        var best = 0f
        var peak = 0f
        var offset = 0
        val samples = clip.samples
        while (offset + hop <= samples.size) {
            pipeline.process(samples, offset, hop, 0L)
            val s = pipeline.lastWritten
            if (s.speaking) speaking += dt
            if (s.fricative) {
                fricative += dt
                if (s.fricativeVoiced) voiced += dt
                val w = max(0.05f, s.sibilance).toDouble()
                sWeighted += s.sNess * w
                weight += w
            }
            if (s.trillDetected) trill += dt
            if (s.bestHoldSeconds > best) best = s.bestHoldSeconds
            if (s.level > peak) peak = s.level
            offset += hop
        }
        pipeline.stop()
        return TakeSummary(
            seconds = clip.duration,
            speakingSeconds = speaking,
            fricativeSeconds = fricative,
            meanSNess = if (weight > 0) (sWeighted / weight).toFloat() else Float.NaN,
            voicedShare = if (fricative > 0) (voiced / fricative).toFloat() else 0f,
            bestHoldSeconds = best,
            trillSeconds = trill,
            peakLevel = peak,
        )
    }
}
