package tech.fonia.domain

import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// Swift's String is a collection of Characters (extended grapheme clusters) and compares by canonical equivalence;
// Kotlin's String is UTF-16 code units compared one by one. The helpers here give the Swift behaviour wherever
// FoniaDomain depends on it. The two agree on NFC text, which is all the shipped content (see ParityGuardTests).

private val characterPattern = Regex("""\X""")

/** The string's Swift `Character`s: extended grapheme clusters. */
internal fun String.characters(): List<String> = characterPattern.findAll(this).map { it.value }.toList()

/** Swift `count` on a `String`: the number of Characters, not of UTF-16 units. */
internal val String.characterCount: Int get() = characters().size

/** Swift `prefix(_:)` on a `String`: its first `count` Characters. */
internal fun String.prefixCharacters(count: Int): String = characters().take(count).joinToString("")

internal fun nfc(text: String): String =
    if (Normalizer.isNormalized(text, Normalizer.Form.NFC)) text else Normalizer.normalize(text, Normalizer.Form.NFC)

/** Swift `==` on `String` and `Character`: canonical equivalence, so "s" + U+0326 equals "ș". */
internal fun canonicallyEqual(a: String, b: String): Boolean = a == b || nfc(a) == nfc(b)

/**
 * Swift `String.lowercased()`: each scalar's full lowercase mapping on its own, with no locale and no context. Lowercasing
 * one scalar at a time with `Locale.ROOT` gives exactly that (Ș→ș, Ț→ț, Ă→ă, Â→â, Î→î, İ→i̇); lowercasing the whole
 * string would also apply Java's final-sigma rule, which Swift does not.
 */
internal fun swiftLowercased(text: String): String {
    val result = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val scalar = text.codePointAt(index)
        val width = Character.charCount(scalar)
        if (scalar < 0x80) {
            result.append(Character.toLowerCase(scalar.toChar()))
        } else {
            result.append(text.substring(index, index + width).lowercase(Locale.ROOT))
        }
        index += width
    }
    return result.toString()
}

/**
 * The Unicode `White_Space` property, which Swift's `Character.isWhitespace` reads from a Character's first scalar.
 * Java's `Character.isWhitespace` is a different set (it leaves out U+00A0 and U+202F and adds U+001C–U+001F).
 */
internal fun isWhiteSpaceScalar(scalar: Int): Boolean = when (scalar) {
    in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
    else -> false
}

/** Swift `Character.isWhitespace`, for one Character. */
internal val String.isWhitespaceCharacter: Boolean get() = isNotEmpty() && isWhiteSpaceScalar(codePointAt(0))

/** Swift `Character.isLetter`: the first scalar has the Unicode `Alphabetic` property. */
internal val String.isLetterCharacter: Boolean get() = isNotEmpty() && Character.isAlphabetic(codePointAt(0))

/** Swift `split(whereSeparator:)` on a `String`: cuts between Characters and drops the empty pieces. */
internal fun String.splitCharacters(isSeparator: (String) -> Boolean): List<String> {
    val pieces = mutableListOf<String>()
    val current = StringBuilder()
    for (character in characters()) {
        if (isSeparator(character)) {
            if (current.isNotEmpty()) {
                pieces += current.toString()
                current.setLength(0)
            }
        } else {
            current.append(character)
        }
    }
    if (current.isNotEmpty()) pieces += current.toString()
    return pieces
}

/** Swift `split(separator:)` with one Character as the separator. */
internal fun String.splitCharacters(separator: String): List<String> = splitCharacters { canonicallyEqual(it, separator) }

/** Swift's `RawRepresentable` with a `String` raw value: the value used in JSON, ids and seeds. */
interface StringRawValue {
    val rawValue: String
}

/** Swift `init?(rawValue:)`. The comparison is Swift's string equality, so canonically equivalent text matches. */
internal fun <T : StringRawValue> List<T>.byRawValue(rawValue: String): T? =
    firstOrNull { it.rawValue == rawValue } ?: nfc(rawValue).let { wanted -> firstOrNull { nfc(it.rawValue) == wanted } }

/** Decodes a raw-valued enum like Swift's synthesized `Decodable`: by raw value, failing on any other string. */
internal open class StringRawValueSerializer<T : StringRawValue>(
    private val typeName: String,
    private val values: () -> List<T>,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("tech.fonia.domain.$typeName", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(value.rawValue)

    override fun deserialize(decoder: Decoder): T {
        val raw = decoder.decodeString()
        return values().byRawValue(raw) ?: throw SerializationException("Cannot initialize $typeName from invalid String value $raw")
    }
}
