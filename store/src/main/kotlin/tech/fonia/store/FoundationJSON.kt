package tech.fonia.store

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

// What Foundation's `JSONEncoder` (swift-foundation, iOS 26) writes, reproduced byte for byte for the two encoders
// FoniaStore uses on iOS:
// - GRDB's column encoder: `.sortedKeys`, slashes escaped, dates as milliseconds since 1970;
// - the therapist export: `[.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]`, dates as ISO 8601.
// kotlinx.serialization writes `6200.0`, keeps insertion order and lowercase UUIDs, so it only reads JSON here.

/** A value ready to be written, with numbers already in their final text. */
internal sealed interface JsonNode

internal data class JText(val value: String) : JsonNode

internal data class JNumber(val literal: String) : JsonNode

internal data class JBool(val value: Boolean) : JsonNode

internal data object JNull : JsonNode

internal data class JArray(val items: List<JsonNode>) : JsonNode

internal data class JObject(val members: Map<String, JsonNode>) : JsonNode

/** The two `JSONEncoder.DateEncodingStrategy` values in use. */
internal enum class DateEncodingStrategy {
    /** GRDB's column encoder. */
    MillisecondsSince1970,

    /** The export: `2026-11-04T10:00:00Z`, UTC, whole seconds. */
    ISO8601,
}

internal fun jsonNumber(value: Int): JsonNode = JNumber(value.toString())

internal fun jsonNumber(value: Long): JsonNode = JNumber(value.toString())

/** `JSONEncoder` writes a `Double` as its Swift `description` without a trailing ".0", so 6200.0 becomes `6200`. */
internal fun jsonNumber(value: Double): JsonNode {
    // Both encoders keep the default `nonConformingFloatEncodingStrategy`, which throws.
    require(value.isFinite()) { "Unable to encode Double.$value directly in JSON." }
    return JNumber(swiftDescription(value).removeSuffix(".0"))
}

internal fun jsonUUID(uuid: UUID): JsonNode = JText(uuid.uuidString)

internal fun jsonDate(instant: Instant, strategy: DateEncodingStrategy): JsonNode = when (strategy) {
    DateEncodingStrategy.MillisecondsSince1970 -> jsonNumber(millisecondsSince1970(instant))
    DateEncodingStrategy.ISO8601 -> JText(iso8601(instant))
}

/** `1000 * timeIntervalSince1970`, exact for whole milliseconds. */
internal fun millisecondsSince1970(instant: Instant): Double = instant.epochSecond * 1000.0 + instant.nano / 1_000_000.0

/** `Date(timeIntervalSince1970: milliseconds / 1000)`. */
internal fun instantFromMillisecondsSince1970(milliseconds: Double): Instant {
    val whole = floor(milliseconds)
    return Instant.ofEpochMilli(whole.toLong()).plusNanos(((milliseconds - whole) * 1_000_000.0).roundToLong())
}

/** `Date(timeIntervalSince1970: seconds)`. */
internal fun instantFromSecondsSince1970(seconds: Double): Instant {
    val whole = floor(seconds)
    return Instant.ofEpochSecond(whole.toLong()).plusNanos(((seconds - whole) * 1_000_000_000.0).roundToLong())
}

/** `date.addingTimeInterval(seconds)`. */
internal fun Instant.addingTimeInterval(seconds: Double): Instant {
    val whole = floor(seconds)
    return plusSeconds(whole.toLong()).plusNanos(((seconds - whole) * 1_000_000_000.0).roundToLong())
}

private val iso8601Format: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

/** `.iso8601`: UTC, no fraction of a second (the seconds are truncated, as Foundation does). */
internal fun iso8601(instant: Instant): String = iso8601Format.format(instant)

// MARK: Swift's Double.description

private const val TWO_TO_THE_53 = 9_007_199_254_740_992.0

/**
 * Swift's `Double.description`: the shortest digits that read back as the same value, in decimal notation from
 * 1e-4 up to 2^53 (`0.0001`, `4800.0`) and in exponent notation outside it (`1e-05`, `1e+16`).
 */
internal fun swiftDescription(value: Double): String {
    if (value.isNaN()) return "nan"
    if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
    if (value == 0.0) return if (1.0 / value < 0) "-0.0" else "0.0"
    val magnitude = abs(value)
    val (digits, exponent) = shortestDigits(magnitude)
    val text = if (exponent < -3 || magnitude > TWO_TO_THE_53) {
        exponentialNotation(digits, exponent)
    } else {
        decimalNotation(digits, exponent)
    }
    return if (value < 0) "-$text" else text
}

/**
 * The shortest digit string that parses back to [value], and among those the closest, as `0.<digits> × 10^exponent`.
 * Worked out with exact decimals, so it does not depend on the platform's `Double.toString`, which is not
 * always the shortest on older Android runtimes.
 */
private fun shortestDigits(value: Double): Pair<String, Int> {
    val exact = BigDecimal(value)
    for (precision in 1..17) {
        val nearest = exact.round(MathContext(precision, RoundingMode.HALF_EVEN))
        // At a power of two the doubles below are twice as dense, so the decimal just above can round-trip
        // when the nearest one, just below, does not.
        val candidates = listOf(nearest, nearest.add(nearest.ulp()))
            .filter { java.lang.Double.parseDouble(it.toString()) == value }
        val best = candidates.minByOrNull { it.subtract(exact).abs() } ?: continue
        val stripped = best.stripTrailingZeros()
        val digits = stripped.unscaledValue().toString()
        return digits to digits.length - stripped.scale()
    }
    error("$value has no round-trip decimal of 17 digits")
}

private fun decimalNotation(digits: String, exponent: Int): String = when {
    exponent <= 0 -> "0." + "0".repeat(-exponent) + digits
    exponent >= digits.length -> digits + "0".repeat(exponent - digits.length) + ".0"
    else -> digits.substring(0, exponent) + "." + digits.substring(exponent)
}

private fun exponentialNotation(digits: String, exponent: Int): String {
    val power = exponent - 1
    val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
    val sign = if (power < 0) "-" else "+"
    return mantissa + "e" + sign + abs(power).toString().padStart(2, '0')
}

// MARK: Key order

/**
 * `.sortedKeys` orders keys by their UTF-8 bytes (`a.key.utf8.lexicographicallyPrecedes(b.key.utf8)` in
 * swift-foundation's `JSONWriter`), so `Z` comes before `a`. The older case-insensitive, numeric NSString order is
 * only kept for apps linked against SDKs before iOS 18; Fonia targets iOS 26. UTF-8 byte order is code point order,
 * which differs from Kotlin's UTF-16 `compareTo` above U+FFFF.
 */
internal object FoundationKeyOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            if (x != y) return x.compareTo(y)
            i += Character.charCount(x)
            j += Character.charCount(y)
        }
        return (a.length - i).compareTo(b.length - j)
    }
}

// MARK: Writer

/** The text `JSONEncoder` produces for [node] with the given output formatting (keys are always sorted). */
internal fun encodeJSON(node: JsonNode, prettyPrinted: Boolean, withoutEscapingSlashes: Boolean): String {
    val writer = FoundationJSONWriter(prettyPrinted, withoutEscapingSlashes)
    writer.write(node)
    return writer.output.toString()
}

/** What GRDB stores in a JSON column. */
internal fun databaseJSON(node: JsonNode): String = encodeJSON(node, prettyPrinted = false, withoutEscapingSlashes = false)

private const val HEX_DIGITS = "0123456789abcdef"

/** swift-foundation's `JSONWriter`: two-space indent, `"key" : value`, empty containers as `[\n\n<indent>]`. */
private class FoundationJSONWriter(private val pretty: Boolean, private val withoutEscapingSlashes: Boolean) {
    val output = StringBuilder()
    private var indent = 0

    fun write(node: JsonNode) {
        when (node) {
            is JText -> writeString(node.value)
            is JNumber -> output.append(node.literal)
            is JBool -> output.append(if (node.value) "true" else "false")
            JNull -> output.append("null")
            is JArray -> writeContainer('[', ']', node.items) { write(it) }
            is JObject -> {
                val members = node.members.entries.sortedWith { a, b -> FoundationKeyOrder.compare(a.key, b.key) }
                writeContainer('{', '}', members) { (key, value) ->
                    writeString(key)
                    output.append(if (pretty) " : " else ":")
                    write(value)
                }
            }
        }
    }

    private fun <T> writeContainer(open: Char, close: Char, elements: List<T>, writeElement: (T) -> Unit) {
        output.append(open)
        if (pretty) {
            output.append('\n')
            indent++
        }
        elements.forEachIndexed { index, element ->
            if (index > 0) output.append(if (pretty) ",\n" else ",")
            if (pretty) writeIndent()
            writeElement(element)
        }
        if (pretty) {
            output.append('\n')
            indent--
            writeIndent()
        }
        output.append(close)
    }

    private fun writeIndent() {
        repeat(indent) { output.append("  ") }
    }

    private fun writeString(value: String) {
        output.append('"')
        for (character in value) {
            when (character) {
                '"' -> output.append("\\\"")
                '\\' -> output.append("\\\\")
                '/' -> output.append(if (withoutEscapingSlashes) "/" else "\\/")
                '\b' -> output.append("\\b")
                '\u000C' -> output.append("\\f")
                '\n' -> output.append("\\n")
                '\r' -> output.append("\\r")
                '\t' -> output.append("\\t")
                in '\u0000'..'\u001F' -> output.append("\\u00")
                    .append(HEX_DIGITS[character.code shr 4])
                    .append(HEX_DIGITS[character.code and 0xF])
                else -> output.append(character)
            }
        }
        output.append('"')
    }
}
