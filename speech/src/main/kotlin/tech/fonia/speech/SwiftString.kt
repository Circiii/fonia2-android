package tech.fonia.speech

import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern

/**
 * Swift's String rules, which the iOS code relies on without spelling them out. A Swift `Character`
 * is an extended grapheme cluster and two of them are equal when canonically equivalent; case
 * mapping goes scalar by scalar; `<` compares the NFC scalars; Foundation's character sets have
 * fixed members. Kotlin strings are UTF-16 code units, so each rule lives here once.
 */
internal object SwiftString {
    private val GRAPHEME: Pattern = Pattern.compile("\\X")
    private const val CAPITAL_SIGMA = 0x03A3

    fun nfc(s: String): String =
        if (Normalizer.isNormalized(s, Normalizer.Form.NFC)) s else Normalizer.normalize(s, Normalizer.Form.NFC)

    /** `Array(string)`: the Characters. */
    fun characters(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val matcher = GRAPHEME.matcher(s)
        val out = ArrayList<String>(s.length)
        while (matcher.find()) out.add(matcher.group())
        return out
    }

    /** `String == String`: canonical equivalence. */
    fun equal(a: String, b: String): Boolean = a == b || nfc(a) == nfc(b)

    /** `[String] == [String]`. */
    fun equal(a: List<String>, b: List<String>): Boolean = a.size == b.size && a.indices.all { equal(a[it], b[it]) }

    /** `String < String`: the scalars of the NFC forms, in order. */
    fun compare(a: String, b: String): Int {
        val x = nfc(a)
        val y = nfc(b)
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            val cx = x.codePointAt(i)
            val cy = y.codePointAt(j)
            if (cx != cy) return cx.compareTo(cy)
            i += Character.charCount(cx)
            j += Character.charCount(cy)
        }
        return (x.length - i).compareTo(y.length - j)
    }

    /** `Unicode.Scalar.Properties.isWhitespace`, which is also every member of `.whitespacesAndNewlines`. */
    fun isWhiteSpaceScalar(cp: Int): Boolean = cp in 0x09..0x0D || cp == 0x85 || Character.isSpaceChar(cp)

    /** Foundation's `CharacterSet.whitespaces`: general category Zs plus tab. */
    fun isFoundationWhitespace(cp: Int): Boolean = cp == 0x09 || Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

    /** `Character.isWhitespace` looks at the first scalar only. */
    fun isWhitespace(character: String): Boolean = character.isNotEmpty() && isWhiteSpaceScalar(character.codePointAt(0))

    /** `trimmingCharacters(in:)`, which works scalar by scalar. */
    fun trimming(s: String, member: (Int) -> Boolean): String {
        var start = 0
        var end = s.length
        while (start < end) {
            val cp = s.codePointAt(start)
            if (!member(cp)) break
            start += Character.charCount(cp)
        }
        while (end > start) {
            val cp = s.codePointBefore(end)
            if (!member(cp)) break
            end -= Character.charCount(cp)
        }
        return s.substring(start, end)
    }

    fun trimmingWhitespacesAndNewlines(s: String): String = trimming(s, ::isWhiteSpaceScalar)

    fun trimmingWhitespaces(s: String): String = trimming(s, ::isFoundationWhitespace)

    /**
     * `lowercased()`: each scalar's full lowercase mapping. Java's root-locale mapping is the same
     * apart from the final-sigma rule, which looks at context, so a capital sigma goes scalar by scalar.
     */
    fun lowercased(s: String): String {
        if (s.indexOf(CAPITAL_SIGMA.toChar()) < 0) return s.lowercase(Locale.ROOT)
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val n = Character.charCount(s.codePointAt(i))
            out.append(s.substring(i, i + n).lowercase(Locale.ROOT))
            i += n
        }
        return out.toString()
    }

    /** `uppercased()`: uppercase mappings have no context rules outside Turkic and Lithuanian locales. */
    fun uppercased(s: String): String = s.uppercase(Locale.ROOT)

    /**
     * `replacingOccurrences(of:with:)` for one-Character targets: whole Characters only, matched by
     * canonical equivalence, as Foundation's default (non-literal) search does.
     */
    fun replacingCharacters(s: String, replacements: Map<String, String>): String {
        val normalized = nfc(s)
        if (replacements.keys.none { normalized.contains(nfc(it)) }) return s
        val out = StringBuilder(s.length)
        for (character in characters(s)) {
            val hit = replacements.entries.firstOrNull { equal(character, it.key) }
            out.append(hit?.value ?: character)
        }
        return out.toString()
    }

    /** `hasPrefix`: Character by Character. */
    fun hasPrefix(characters: List<String>, prefix: List<String>): Boolean =
        prefix.size <= characters.size && prefix.indices.all { equal(characters[it], prefix[it]) }

    /** `Set<String>.contains` and dictionary lookups hash the canonical form. */
    fun <V> lookup(map: Map<String, V>, key: String): V? =
        map[key] ?: map.entries.firstOrNull { equal(it.key, key) }?.value
}

/**
 * Swift's numeric helpers. `min(x, y)` returns `y < x ? y : x` and `max(x, y)` returns
 * `y >= x ? y : x`; `Sequence.min()` and `max()` keep the first extreme by `<`. Kotlin's
 * versions propagate NaN and order the zeros, so they are not used for parity code.
 */
internal object SwiftMath {
    fun min(x: Double, y: Double): Double = if (y < x) y else x

    fun max(x: Double, y: Double): Double = if (y >= x) y else x

    fun min(x: Float, y: Float): Float = if (y < x) y else x

    fun max(x: Float, y: Float): Float = if (y >= x) y else x

    fun minOrNull(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        var result = values[0]
        for (k in 1 until values.size) if (values[k] < result) result = values[k]
        return result
    }

    fun maxOrNull(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        var result = values[0]
        for (k in 1 until values.size) if (result < values[k]) result = values[k]
        return result
    }
}
