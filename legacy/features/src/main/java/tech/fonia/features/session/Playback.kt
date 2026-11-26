package tech.fonia.features.session

import tech.fonia.audio.CapturedClip
import java.util.UUID

/**
 * The end of a lesson's mixed block (iOS `Session/Playback.swift`). Its takes said nothing in the moment, only the soft
 * "heard you"; now the learner hears up to three of their own good ones again, from memory. Nothing of it is written
 * anywhere. A child is never told a count, and with fewer than three good takes hears only praise for going all the way.
 */
class Playback(
    /** Shown, and said in the recorded voice to a child. */
    val line: String,
    /** Played in order. */
    val takes: List<CapturedClip>,
) {
    /** `Identifiable`: a new playback is a new screen. */
    val id: UUID = UUID.randomUUID()

    /** What the end of the block does: play takes back, or only float the usual count over the next trial. */
    data class End(val playback: Playback?, val note: String?)

    /** The words: [shown] opens the playback, [note] floats over the next trial instead. */
    data class Lines(val shown: String?, val note: String?)

    companion object {
        /** How many good takes play back. */
        const val PLAYED = 3
        const val CHILD_LISTEN = "Gata! Ascultă trei care au sunat bine."
        const val CHILD_DONE = "Gata! Bravo că ai mers până la capăt."

        /**
         * What the end of the block does: play takes back, or (for an adult with fewer than three good) only float the
         * usual count. [good] and [total] count the block as the other blocks do.
         */
        fun end(good: Int, total: Int, takes: List<CapturedClip>, isChild: Boolean): End {
            val chosen = spread(takes.size).map { takes[it] }
            val line = lines(good, total, goodTakes = takes.size, isChild = isChild)
            val shown = line.shown ?: return End(playback = null, note = line.note)
            return End(playback = Playback(shown, chosen), note = null)
        }

        /** The words: `shown` opens the playback, `note` floats over the next trial instead. */
        fun lines(good: Int, total: Int, goodTakes: Int, isChild: Boolean): Lines {
            val plays = goodTakes >= PLAYED
            if (isChild) return Lines(shown = if (plays) CHILD_LISTEN else CHILD_DONE, note = null)
            if (plays) return Lines(shown = "$good din $total au sunat bine. Ascultă trei dintre ele.", note = null)
            return Lines(shown = null, note = if (total >= 3) SessionRun.blockNote(good, total, isChild = false) else null)
        }

        /**
         * Which takes play: the first, the middle and the last good one, so the three come from across the block. None
         * when there are fewer than three.
         */
        fun spread(count: Int): List<Int> {
            if (count < PLAYED) return emptyList()
            return listOf(0, (count - 1) / 2, count - 1)
        }
    }
}
