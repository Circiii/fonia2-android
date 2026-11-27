package tech.fonia.phones

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * "Is the target sound being said right now?", from the phoneme model's frame posteriors of the last second of audio.
 * The living letter moves and lights on this and not on just anything: acoustic measurements alone lit on a vacuum
 * cleaner and on other hisses (`tools/eval/out/live_aim.md`), the phoneme model does not
 * (`tools/eval/out/live_spot.md`).
 *
 * Pure. A single-token sound scores the highest posterior of its tokens over the recent frames; an affricate (Ț, CE,
 * GE: ZIPA has no affricate symbols) needs its stop within [STOP_GAP] frames before its hiss.
 */
object LiveSpotter {
    /** How far back "now" reaches: 20 frames of 20 ms. */
    const val RECENT_FRAMES = 20

    /** Frames of the trailing padding [ZipaPhoneScorer.pad] adds (0.25 s). */
    const val PAD_FRAMES = 13

    /** A stop this many frames or fewer before the hiss makes an affricate. */
    const val STOP_GAP = 5

    /**
     * The score at or above which [sound] counts as heard. Chosen on the evaluation clips and the noise clips
     * (`tools/eval/out/live_spot.md`): the lowest threshold at which no noise lights the sound and at most about one
     * clip in ten without the sound does.
     */
    fun tau(sound: TargetSound): Float = when (sound) {
        TargetSound.S, TargetSound.R, TargetSound.L -> 0.5f
        TargetSound.Sh, TargetSound.Z, TargetSound.Ts, TargetSound.Ce -> 0.35f
        TargetSound.J, TargetSound.Ge -> 0.2f
    }

    fun heard(post: Posteriors, sound: TargetSound): Boolean = score(post, sound) >= tau(sound)

    private fun ids(vararg tokens: String): IntArray = tokens.filter { ZipaInventory.contains(it) }.map { ZipaInventory.id(it) }.toIntArray()

    private val hiss: Map<TargetSound, IntArray> = mapOf(
        TargetSound.S to ids("s"),
        TargetSound.Sh to ids("ʃ", "ʂ", "ɕ"),
        TargetSound.Z to ids("z"),
        TargetSound.J to ids("ʒ", "ʐ", "ʑ"),
        TargetSound.Ts to ids("s"),
        TargetSound.Ce to ids("ʃ", "ʂ", "ɕ"),
        TargetSound.Ge to ids("ʒ", "ʐ", "ʑ"),
        TargetSound.R to ids("r", "ɾ"),
        TargetSound.L to ids("l"),
    )
    private val stop: Map<TargetSound, IntArray> = mapOf(
        TargetSound.Ts to ids("t"),
        TargetSound.Ce to ids("t", "c"),
        TargetSound.Ge to ids("d", "ɟ"),
    )

    private fun mass(post: Posteriors, frame: Int, tokens: IntArray): Float {
        var sum = 0f
        for (id in tokens) sum += exp(post[frame, id])
        return min(1f, sum)
    }

    /** 0..1: how sure the model is that [sound] was said within the last [lastFrames] frames of [post]. */
    fun score(post: Posteriors, sound: TargetSound, lastFrames: Int = RECENT_FRAMES + PAD_FRAMES): Float {
        val from = max(0, post.frames - lastFrames)
        val main = hiss.getValue(sound)
        val before = stop[sound]
        var best = 0f
        for (t in from until post.frames) {
            var p = mass(post, t, main)
            if (before != null && p > best) {
                var s = 0f
                for (u in max(0, t - STOP_GAP) until t) s = max(s, mass(post, u, before))
                p = min(p, s)
            }
            if (p > best) best = p
        }
        return best
    }
}
