package tech.fonia.speaking

import tech.fonia.speech.TranscriptWord
import java.text.Normalizer
import java.util.Locale

/** A filler, repetition or false start found in the recognised words; [first]..[last] index the words. */
data class LexicalHit(
    val kind: SpanKind,
    val first: Int,
    val last: Int,
    val label: String,
    val confidence: Double,
    val reason: String,
)

/**
 * Transcript-based detection (runs when Whisper is available): Romanian hesitation tokens, discourse markers used as
 * fillers, repetitions and false starts. Conservative by design: a marker is only a filler when it stands apart
 * (pause or punctuation on both sides, or next to another filler); "deci" in "am muncit, deci am reușit" or "practic"
 * in "un exercițiu practic" is left alone. One-sided cases get a confidence under the cut line, so the screen can
 * offer them dotted without cutting them.
 */
object LexicalDetector {
    fun detect(words: List<TranscriptWord>, thresholds: SpeakingThresholds = SpeakingThresholds.v1): List<LexicalHit> {
        val lex = thresholds.lexicon
        if (words.isEmpty()) return emptyList()
        val norm = words.map { normalize(it.word) }
        val hesitation = Regex(lex.hesitationPattern)
        val markers = lex.markers
            .map { it to normalize(it.text).split(' ').filter { p -> p.isNotEmpty() } }
            .sortedByDescending { it.second.size }
        val hits = ArrayList<LexicalHit>()
        val flagged = BooleanArray(words.size)

        fun gap(a: Int, b: Int): Double = words[b].start - words[a].end
        fun isHesitation(i: Int) = i in words.indices && norm[i].isNotEmpty() && hesitation.matches(norm[i])
        fun markerAt(i: Int): Boolean = markers.any { (_, parts) -> i + parts.size <= words.size && (parts.indices).all { norm[i + it] == parts[it] } }

        // Hesitation tokens first: they also make their neighbours "set apart".
        for (i in words.indices) if (isHesitation(i)) {
            flagged[i] = true
            hits.add(LexicalHit(SpanKind.HESITATION, i, i, norm[i], lex.hesitationConfidence, "token"))
        }

        var i = 0
        while (i < words.size) {
            if (flagged[i] || norm[i].isEmpty()) { i++; continue }
            var matched = false
            for ((marker, parts) in markers) {
                val len = parts.size
                if (i + len > words.size || !(0 until len).all { norm[i + it] == parts[it] }) continue
                val last = i + len - 1
                val key = parts.joinToString(" ")
                val prev = if (i > 0) norm[i - 1] else null
                val next = if (last + 1 < words.size) norm[last + 1] else null
                val apartBefore = i == 0 || endsClause(words[i - 1].word) || gap(i - 1, i) >= lex.setApartGapSeconds || flagged[i - 1]
                val apartAfter = last + 1 >= words.size || endsClause(words[last].word) ||
                    gap(last, last + 1) >= lex.setApartGapSeconds || isHesitation(last + 1) || markerAt(last + 1)
                val clauseStart = i == 0 || endsSentence(words[i - 1].word) || gap(i - 1, i) >= 0.25 || flagged[i - 1]
                if (next != null && next in (lex.keepAfter[key] ?: emptyList()) && !endsClause(words[last].word)) break
                if (prev != null && prev in (lex.keepBefore[key] ?: emptyList()) && !apartBefore) break
                val confidence = when (marker.rule) {
                    "clauseStart" -> if (apartBefore) marker.confidence else null
                    "aloneAtStart" -> if (clauseStart && apartAfter) marker.confidence else null
                    else -> when {
                        apartBefore && apartAfter -> marker.confidence
                        apartBefore || apartAfter -> marker.confidence - 0.25
                        else -> null
                    }
                } ?: break
                if (confidence < 0.35) break
                val why = "${marker.rule}:" + (if (apartBefore) "before" else "") + (if (apartAfter) "+after" else "")
                hits.add(LexicalHit(SpanKind.MARKER, i, last, key, confidence, why))
                for (k in i..last) flagged[k] = true
                i = last + 1
                matched = true
                break
            }
            if (!matched) i++
        }

        // Repetitions and false starts, over the words that are not fillers.
        val content = words.indices.filter { !flagged[it] && norm[it].isNotEmpty() }
        val inRepeat = BooleanArray(words.size)
        var c = 0
        while (c < content.size) {
            var found = false
            for (n in 3 downTo 1) {
                if (c + 2 * n > content.size) continue
                val first = (0 until n).map { norm[content[c + it]] }
                val second = (0 until n).map { norm[content[c + n + it]] }
                if (first != second) continue
                if (n == 1 && first[0] in lex.keepDoubles) continue
                if (first.all { it.all(Char::isDigit) }) continue
                val a = content[c]
                val b = content[c + n - 1]
                hits.add(LexicalHit(SpanKind.REPETITION, a, b, first.joinToString(" "), lex.repetitionConfidence, "repeat$n"))
                for (k in a..b) inRepeat[k] = true
                c += n
                found = true
                break
            }
            if (!found) c++
        }
        for (k in 0 until content.size - 1) {
            val a = content[k]
            if (inRepeat[a]) continue
            val raw = words[a].word.trim()
            val broken = raw.endsWith("-") || raw.endsWith("—") || raw.endsWith("…")
            val w = norm[a]
            val v = norm[content[k + 1]]
            if (w.length < lex.falseStartMinPrefix || w.length >= v.length || !v.startsWith(w)) continue
            if (!broken && (w in lex.falseStartNeverWords || w.length < 2)) continue
            val confidence = if (broken) lex.falseStartConfidence + 0.1 else lex.falseStartConfidence
            hits.add(LexicalHit(SpanKind.FALSE_START, a, a, "$w-", confidence, if (broken) "brokenPrefix" else "prefix"))
        }
        return hits.sortedBy { words[it.first].start }
    }

    private val clauseEnds = setOf('.', ',', '!', '?', ';', ':', '…')
    private val sentenceEnds = setOf('.', '!', '?', '…', ';', ':')

    internal fun endsClause(raw: String): Boolean = raw.trimEnd().lastOrNull()?.let { it in clauseEnds } ?: false

    internal fun endsSentence(raw: String): Boolean = raw.trimEnd().lastOrNull()?.let { it in sentenceEnds } ?: false

    /** Lower case (Romanian), cedilla letters to comma letters, NFC, edge punctuation stripped (inner - and ' kept). */
    fun normalize(raw: String): String {
        var s = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC).lowercase(Locale.forLanguageTag("ro"))
        s = s.replace('ş', 'ș').replace('ţ', 'ț').replace('’', '\'').replace('ǎ', 'ă')
        var a = 0
        var b = s.length
        while (a < b && !s[a].isLetterOrDigit()) a++
        while (b > a && !s[b - 1].isLetterOrDigit()) b--
        return s.substring(a, b)
    }
}
