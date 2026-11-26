package tech.fonia.words

import kotlin.math.max
import kotlin.math.min

/**
 * "Was another word said?" for a one-word take, from what the Romanian recogniser heard. The sound judge praised a
 * different word when its alignment happened to fit („val” said for „pară”, emulator, 2026-11-25); the recogniser
 * knows words, so it can refuse that.
 *
 * It never judges the practised sound: the letters of the target sound are free (a child who says „lac” for „rac” said
 * the right word with the wrong R, and that is the sound judge's business). Only when the rest of the word differs in
 * at least two letters, and the recogniser is sure of what it heard, is the take another word. Pure.
 */
object OtherWord {
    /** Under this certainty the recogniser's word is not trusted and nothing is refused. */
    const val MIN_CONFIDENCE = 0.6

    /** Letters outside the target sound that must differ before the take counts as another word. */
    const val MIN_EDITS = 2

    /** The letters of [asked] that spell the practised sound ([sound]: s, ș, z, j, ț, ce, ge, r, l). */
    internal fun targetAt(asked: String, sound: String): BooleanArray {
        val free = BooleanArray(asked.length)
        for (i in asked.indices) {
            val c = asked[i]
            val next = asked.getOrNull(i + 1)
            free[i] = when (sound) {
                "ce" -> c == 'c' && (next == 'e' || next == 'i')
                "ge" -> c == 'g' && (next == 'e' || next == 'i')
                else -> sound.length == 1 && c == sound[0]
            }
        }
        return free
    }

    /** Lowercase letters only; vowels without their marks (the recogniser mixes ă/a and â/î), consonants as they are. */
    internal fun fold(text: String): String = buildString {
        for (c in text.lowercase()) when (c) {
            'ă', 'â' -> append('a')
            'î' -> append('i')
            'ş' -> append('ș')
            'ţ' -> append('ț')
            else -> if (c.isLetter()) append(c)
        }
    }

    /**
     * Letter edits between [asked] and [heard] with the target sound's letters free: replacing or dropping a free
     * letter costs nothing, and one extra letter next to it (a sound said in two, „țs”) costs nothing either. With
     * [within], letters heard before or after the word are free too: „steastea” holds „stea”, „zdrață” holds „rață”.
     */
    internal fun edits(asked: String, free: BooleanArray, heard: String, within: Boolean = false): Int {
        val n = asked.length
        val m = heard.length
        var prev = IntArray(m + 1) { if (within) 0 else it }
        for (i in 1..n) {
            val cur = IntArray(m + 1)
            cur[0] = prev[0] + if (free[i - 1]) 0 else 1
            for (j in 1..m) {
                val sub = prev[j - 1] + if (asked[i - 1] == heard[j - 1] || free[i - 1]) 0 else 1
                val del = prev[j] + if (free[i - 1]) 0 else 1
                val ins = cur[j - 1] + if (free[i - 1]) 0 else 1
                cur[j] = min(sub, min(del, ins))
            }
            prev = cur
        }
        return if (within) prev.min() else prev[m]
    }

    /**
     * True when [heard] is, with certainty, not the word [asked] (the practised [sound] aside). False whenever the
     * recogniser heard nothing, is unsure, or the difference is small.
     */
    fun isOther(asked: String, sound: String, heard: Heard): Boolean {
        val a = fold(asked)
        if (a.length < 2 || heard.words.isEmpty() || heard.confidence < MIN_CONFIDENCE) return false
        val free = targetAt(a, sound)
        val whole = fold(heard.text)
        if (whole.isEmpty()) return false
        // The closest of: everything heard as one word, or any single heard word (a breath or an "ă" beside it).
        val pieces = listOf(whole) + heard.words.map { fold(it.text) }
        // The word said whole inside a piece (said twice, or with a sound before it) is the word.
        if (pieces.any { edits(a, free, it, within = true) == 0 }) return false
        val (best, closest) = pieces.map { edits(a, free, it) to it }.minBy { it.first }
        // A scrap of two letters or less, half the word at most („ri” for „sare”), is the recogniser losing the word,
        // not another word. A whole short word („casă” for „păsărică”) is still another word.
        if (closest.length <= 2 && 2 * closest.length <= a.length) return false
        val fixed = free.count { !it }
        return best >= max(MIN_EDITS, (fixed + 1) / 2)
    }
}
