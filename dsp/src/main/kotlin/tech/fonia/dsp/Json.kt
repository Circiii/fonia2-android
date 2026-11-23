package tech.fonia.dsp

// A small JSON reader and writer standing in for Foundation's JSONSerialization, JSONEncoder and
// JSONDecoder, so the module stays dependency-free as the Swift package is. Values are plain Kotlin:
// LinkedHashMap for objects, List for arrays, JsonNumber (the literal text, so a Float is parsed
// straight from its digits), String, Boolean and null.

/** Thrown for malformed JSON and for JSON that does not decode into the expected type. */
class JsonException(message: String) : Exception(message)

/** A JSON number, kept as written. */
internal class JsonNumber(val text: String) {
    override fun equals(other: Any?): Boolean = other is JsonNumber && other.text == text
    override fun hashCode(): Int = text.hashCode()
    override fun toString(): String = text
}

internal object Json {
    /**
     * Parses `text`. Like `JSONSerialization.jsonObject(with:)` without `.fragmentsAllowed`, the top
     * level must be an object or an array unless `allowFragments` is set.
     */
    fun parse(text: String, allowFragments: Boolean = false): Any? {
        val parser = Parser(text)
        parser.skipWhitespace()
        if (!allowFragments && parser.peek() != '{' && parser.peek() != '[') {
            throw JsonException("JSON text did not start with an array or object")
        }
        val value = parser.readValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw JsonException("Unexpected text after the JSON value at ${parser.position}")
        return value
    }

    /** `JSONEncoder` output with `[.prettyPrinted, .sortedKeys]`. */
    fun writePretty(value: Any?): String = StringBuilder().also { write(value, it, 0) }.toString()

    /** Compact output with keys in insertion order. */
    fun writeCompact(value: Any?): String = StringBuilder().also { writeCompact(value, it) }.toString()

    private fun write(value: Any?, out: StringBuilder, indent: Int) {
        when (value) {
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    out.append("{\n\n").append(" ".repeat(indent)).append('}')
                    return
                }
                out.append("{\n")
                val keys = value.keys.map { it as String }.sorted()
                keys.forEachIndexed { index, key ->
                    out.append(" ".repeat(indent + 2))
                    writeString(key, out)
                    out.append(" : ")
                    write(value[key], out, indent + 2)
                    if (index < keys.size - 1) out.append(',')
                    out.append('\n')
                }
                out.append(" ".repeat(indent)).append('}')
            }
            is List<*> -> {
                if (value.isEmpty()) {
                    out.append("[\n\n").append(" ".repeat(indent)).append(']')
                    return
                }
                out.append("[\n")
                value.forEachIndexed { index, element ->
                    out.append(" ".repeat(indent + 2))
                    write(element, out, indent + 2)
                    if (index < value.size - 1) out.append(',')
                    out.append('\n')
                }
                out.append(" ".repeat(indent)).append(']')
            }
            else -> writeScalar(value, out)
        }
    }

    private fun writeCompact(value: Any?, out: StringBuilder) {
        when (value) {
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, element) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(key as String, out)
                    out.append(':')
                    writeCompact(element, out)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { index, element ->
                    if (index > 0) out.append(',')
                    writeCompact(element, out)
                }
                out.append(']')
            }
            else -> writeScalar(value, out)
        }
    }

    private fun writeScalar(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is JsonNumber -> out.append(value.text)
            is String -> writeString(value, out)
            else -> throw JsonException("Cannot write ${value::class.java.simpleName} as JSON")
        }
    }

    private fun writeString(text: String, out: StringBuilder) {
        out.append('"')
        for (character in text) {
            when (character) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (character < ' ') out.append("\\u%04x".format(character.code)) else out.append(character)
            }
        }
        out.append('"')
    }

    // Number formatting as Swift's JSONEncoder writes it: the shortest text that reads back as the
    // same value, without a trailing ".0".

    fun number(value: Float): JsonNumber {
        if (!value.isFinite()) throw JsonException("Unable to encode $value directly in JSON")
        return JsonNumber(value.toString().removeSuffix(".0"))
    }

    fun number(value: Double): JsonNumber {
        if (!value.isFinite()) throw JsonException("Unable to encode $value directly in JSON")
        return JsonNumber(value.toString().removeSuffix(".0"))
    }

    fun number(value: Int): JsonNumber = JsonNumber(value.toString())

    private class Parser(private val text: String) {
        var position = 0
            private set

        fun atEnd(): Boolean = position >= text.length

        fun peek(): Char? = if (position < text.length) text[position] else null

        fun skipWhitespace() {
            while (position < text.length) {
                when (text[position]) {
                    ' ', '\t', '\n', '\r' -> position += 1
                    else -> return
                }
            }
        }

        fun readValue(): Any? {
            skipWhitespace()
            val character = peek() ?: throw JsonException("Unexpected end of JSON")
            return when {
                character == '{' -> readObject()
                character == '[' -> readArray()
                character == '"' -> readString()
                character == 't' -> readLiteral("true", true)
                character == 'f' -> readLiteral("false", false)
                character == 'n' -> readLiteral("null", null)
                character == '-' || character in '0'..'9' -> readNumber()
                else -> throw JsonException("Unexpected character '$character' at $position")
            }
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            if (!text.startsWith(word, position)) throw JsonException("Invalid literal at $position")
            position += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            position += 1
            skipWhitespace()
            if (peek() == '}') {
                position += 1
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw JsonException("Expected a key at $position")
                val key = readString()
                skipWhitespace()
                if (peek() != ':') throw JsonException("Expected ':' at $position")
                position += 1
                result[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position += 1
                    '}' -> {
                        position += 1
                        return result
                    }
                    else -> throw JsonException("Expected ',' or '}' at $position")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val result = ArrayList<Any?>()
            position += 1
            skipWhitespace()
            if (peek() == ']') {
                position += 1
                return result
            }
            while (true) {
                result.add(readValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> position += 1
                    ']' -> {
                        position += 1
                        return result
                    }
                    else -> throw JsonException("Expected ',' or ']' at $position")
                }
            }
        }

        private fun readString(): String {
            position += 1
            val out = StringBuilder()
            while (true) {
                if (position >= text.length) throw JsonException("Unterminated string")
                val character = text[position]
                when {
                    character == '"' -> {
                        position += 1
                        return out.toString()
                    }
                    character == '\\' -> {
                        if (position + 1 >= text.length) throw JsonException("Unterminated escape")
                        val escaped = text[position + 1]
                        position += 2
                        when (escaped) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (position + 4 > text.length) throw JsonException("Invalid unicode escape")
                                val hex = text.substring(position, position + 4)
                                val code = hex.toIntOrNull(16) ?: throw JsonException("Invalid unicode escape \\u$hex")
                                out.append(code.toChar())
                                position += 4
                            }
                            else -> throw JsonException("Invalid escape \\$escaped")
                        }
                    }
                    character < ' ' -> throw JsonException("Unescaped control character in string")
                    else -> {
                        out.append(character)
                        position += 1
                    }
                }
            }
        }

        private fun readNumber(): JsonNumber {
            val start = position
            if (peek() == '-') position += 1
            when {
                peek() == '0' -> position += 1
                peek()?.let { it in '1'..'9' } == true -> while (peek()?.let { it in '0'..'9' } == true) position += 1
                else -> throw JsonException("Invalid number at $start")
            }
            if (peek() == '.') {
                position += 1
                if (peek()?.let { it in '0'..'9' } != true) throw JsonException("Invalid number at $start")
                while (peek()?.let { it in '0'..'9' } == true) position += 1
            }
            if (peek() == 'e' || peek() == 'E') {
                position += 1
                if (peek() == '+' || peek() == '-') position += 1
                if (peek()?.let { it in '0'..'9' } != true) throw JsonException("Invalid number at $start")
                while (peek()?.let { it in '0'..'9' } == true) position += 1
            }
            return JsonNumber(text.substring(start, position))
        }
    }
}

// Typed reads with JSONDecoder's strictness: a missing key or a value of the wrong type throws.

internal fun Map<*, *>.member(key: String): Any? {
    if (!containsKey(key)) throw JsonException("keyNotFound: \"$key\"")
    return get(key)
}

internal fun Any?.asObject(key: String): Map<*, *> = this as? Map<*, *> ?: throw JsonException("typeMismatch: \"$key\" is not an object")

internal fun Any?.asArray(key: String): List<*> = this as? List<*> ?: throw JsonException("typeMismatch: \"$key\" is not an array")

internal fun Any?.asString(key: String): String = this as? String ?: throw JsonException("typeMismatch: \"$key\" is not a string")

internal fun Any?.asFloat(key: String): Float {
    val number = this as? JsonNumber ?: throw JsonException("typeMismatch: \"$key\" is not a number")
    val value = number.text.toFloat()
    if (!value.isFinite()) throw JsonException("dataCorrupted: ${number.text} does not fit in Float")
    return value
}

internal fun Any?.asDouble(key: String): Double {
    val number = this as? JsonNumber ?: throw JsonException("typeMismatch: \"$key\" is not a number")
    val value = number.text.toDouble()
    if (!value.isFinite()) throw JsonException("dataCorrupted: ${number.text} does not fit in Double")
    return value
}

internal fun Any?.asInt(key: String): Int {
    val number = this as? JsonNumber ?: throw JsonException("typeMismatch: \"$key\" is not a number")
    number.text.toIntOrNull()?.let { return it }
    val value = number.text.toDouble()
    if (value != kotlin.math.floor(value) || value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
        throw JsonException("dataCorrupted: ${number.text} does not fit in Int")
    }
    return value.toInt()
}
