package tech.fonia.dsp

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sign
import kotlin.math.truncate

// Swift semantics the port depends on. No Swift file holds these; they replace what the Swift
// standard library and Foundation give the iOS code for free.

/**
 * Swift's `Float.pi`, `0x1.921fb4p1` (bits 0x40490FDA): π rounded toward zero, one ulp below the
 * nearest Float (`PI.toFloat()`), which Swift avoids because it lies above π. Every window and
 * test tone uses it.
 */
internal const val PI_F: Float = 3.1415925f

/** Swift `x.rounded()`: to nearest, ties away from zero. Kotlin's `round` ties to even. */
internal fun Double.rounded(): Double {
    val whole = truncate(this)
    return if (abs(this - whole) >= 0.5) whole + sign(this) else whole
}

/** Swift `x.rounded()` on Float: to nearest, ties away from zero. */
internal fun Float.rounded(): Float {
    val whole = truncate(this)
    return if (abs(this - whole) >= 0.5f) whole + sign(this) else whole
}

// Swift's `min` and `max` are `y < x ? y : x` and `y >= x ? y : x`. They differ from `Math.min` and
// `Math.max` on NaN and signed zeros, so the port uses these everywhere the Swift code says min or max.
// Declared in the package, they win over kotlin.math, which no file here star-imports.

internal fun min(x: Float, y: Float): Float = if (y < x) y else x
internal fun max(x: Float, y: Float): Float = if (y >= x) y else x
internal fun min(x: Double, y: Double): Double = if (y < x) y else x
internal fun max(x: Double, y: Double): Double = if (y >= x) y else x
internal fun min(x: Int, y: Int): Int = if (y < x) y else x
internal fun max(x: Int, y: Int): Int = if (y >= x) y else x
internal fun min(x: Float, y: Float, z: Float): Float = min(min(x, y), z)
internal fun max(x: Double, y: Double, z: Double): Double = max(max(x, y), z)

private const val LN2 = 0.6931471805599453

/**
 * `log2` exact on powers of two, as Darwin's is: `ceil(log2(1024.0))` must be 10, and `ln(x) / ln(2)`
 * can land a hair above it. Elsewhere within an ulp or two of the correctly rounded value.
 */
internal fun log2(x: Double): Double {
    if (x > 0 && x.isFinite() && x >= java.lang.Double.MIN_NORMAL) {
        val exponent = Math.getExponent(x)
        val mantissa = Math.scalb(x, -exponent)
        return if (mantissa == 1.0) exponent.toDouble() else exponent + ln(mantissa) / LN2
    }
    return ln(x) / LN2
}

/** Float `log2`, computed in Double and rounded once. */
internal fun log2(x: Float): Float = log2(x.toDouble()).toFloat()

// MARK: Text

private val graphemeCluster = Regex("\\X")

/**
 * Swift `Array(string)`: one element per extended grapheme cluster (a `Character`), not per UTF-16
 * unit, so "s" plus a combining comma is one letter.
 */
internal fun characters(text: String): List<String> = graphemeCluster.findAll(text).map { it.value }.toList()

/** Swift `string.count`. */
internal fun characterCount(text: String): Int = graphemeCluster.findAll(text).count()

/** Swift compares `Character`s by canonical equivalence; comparing NFC forms does the same. */
internal fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

/** Swift `Character.isLetter`: the first scalar has the Alphabetic property. */
internal fun isLetter(character: String): Boolean = character.isNotEmpty() && Character.isAlphabetic(character.codePointAt(0))

/**
 * Swift `String <`: lexicographic over the Unicode scalars of the NFC forms. Kotlin's `compareTo`
 * compares UTF-16 units, which orders characters above the BMP differently.
 */
internal val swiftStringOrder: Comparator<String> = Comparator { a, b ->
    val left = nfc(a)
    val right = nfc(b)
    var i = 0
    var j = 0
    while (i < left.length && j < right.length) {
        val x = left.codePointAt(i)
        val y = right.codePointAt(j)
        if (x != y) return@Comparator x.compareTo(y)
        i += Character.charCount(x)
        j += Character.charCount(y)
    }
    (left.length - i).compareTo(right.length - j)
}

/** `CharacterSet.whitespacesAndNewlines`: Unicode Zs, tab, U+000A to U+000D, U+0085, U+2028, U+2029. */
private fun isWhitespaceOrNewline(codePoint: Int): Boolean =
    codePoint == 0x09 || codePoint in 0x0A..0x0D || codePoint == 0x85 || codePoint == 0x2028 || codePoint == 0x2029 ||
        Character.getType(codePoint) == Character.SPACE_SEPARATOR.toInt()

/** Swift `trimmingCharacters(in: .whitespacesAndNewlines)`, scalar by scalar from both ends. */
internal fun trimmingWhitespacesAndNewlines(text: String): String {
    var start = 0
    var end = text.length
    while (start < end) {
        val codePoint = text.codePointAt(start)
        if (!isWhitespaceOrNewline(codePoint)) break
        start += Character.charCount(codePoint)
    }
    while (end > start) {
        val codePoint = text.codePointBefore(end)
        if (!isWhitespaceOrNewline(codePoint)) break
        end -= Character.charCount(codePoint)
    }
    return text.substring(start, end)
}

/** A growable FloatArray, standing in for appending to a Swift `[Float]`. */
internal class FloatList(capacity: Int = 16) {
    private var values = FloatArray(maxOf(1, capacity))
    var size = 0
        private set

    fun add(value: Float) {
        if (size == values.size) values = values.copyOf(values.size * 2)
        values[size++] = value
    }

    operator fun get(index: Int): Float = values[index]

    fun toFloatArray(): FloatArray = values.copyOf(size)
}

/** Receives one value at a time without boxing it, so the live path can stream without allocating. */
internal fun interface FloatSink {
    fun accept(value: Float)
}
