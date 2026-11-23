package tech.fonia.glyphs

import java.io.Reader

/**
 * Minimal pull parser for the exported JSON. Numbers are parsed without allocating
 * (the export holds ~400k of them), strings allocate. Not a general-purpose JSON library:
 * it accepts standard JSON and fails loudly on anything else.
 */
internal class JsonReader(private val reader: Reader) {
    enum class Token { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME, STRING, NUMBER, BOOLEAN, NULL, END }

    private val buf = CharArray(1 shl 16)
    private var pos = 0
    private var limit = 0
    private val sb = StringBuilder()

    // Container stack: true = object. expectName is true right after '{' or ',' inside an object.
    private val stack = BooleanArray(64)
    private var depth = 0
    private var expectName = false
    private var needComma = false

    private fun fill(): Boolean {
        limit = reader.read(buf, 0, buf.size)
        pos = 0
        if (limit <= 0) { limit = 0; return false }
        return true
    }

    private fun peekChar(): Int {
        while (true) {
            if (pos >= limit && !fill()) return -1
            val c = buf[pos]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++ else return c.code
        }
    }

    private fun readChar(): Char {
        if (pos >= limit && !fill()) error("unexpected end of JSON")
        return buf[pos++]
    }

    private fun error(msg: String): Nothing = throw IllegalArgumentException("JSON: $msg")

    fun peek(): Token {
        var c = peekChar()
        if (c == -1) return Token.END
        if (c == '}'.code) return Token.END_OBJECT
        if (c == ']'.code) return Token.END_ARRAY
        if (needComma) {
            if (c != ','.code) error("expected ',' got '${c.toChar()}'")
            pos++
            needComma = false
            if (depth > 0 && stack[depth - 1]) expectName = true
            c = peekChar()
        }
        if (expectName) return Token.NAME
        return when (c) {
            '{'.code -> Token.BEGIN_OBJECT
            '['.code -> Token.BEGIN_ARRAY
            '"'.code -> Token.STRING
            't'.code, 'f'.code -> Token.BOOLEAN
            'n'.code -> Token.NULL
            else -> Token.NUMBER
        }
    }

    private fun expect(t: Token) {
        val p = peek()
        if (p != t) error("expected $t, got $p")
    }

    private fun afterValue() { needComma = true }

    fun beginObject() { expect(Token.BEGIN_OBJECT); pos++; stack[depth++] = true; expectName = true; needComma = false }
    fun beginArray() { expect(Token.BEGIN_ARRAY); pos++; stack[depth++] = false; expectName = false; needComma = false }

    fun endObject() {
        if (peekChar() != '}'.code) error("expected '}'")
        pos++; depth--; expectName = false; afterValue()
    }

    fun endArray() {
        if (peekChar() != ']'.code) error("expected ']'")
        pos++; depth--; expectName = false; afterValue()
    }

    fun hasNext(): Boolean {
        val t = peek()
        return t != Token.END_OBJECT && t != Token.END_ARRAY && t != Token.END
    }

    fun nextName(): String {
        expect(Token.NAME)
        val s = readString()
        if (peekChar() != ':'.code) error("expected ':'")
        pos++
        expectName = false
        needComma = false
        return s
    }

    fun nextString(): String {
        expect(Token.STRING)
        val s = readString()
        afterValue()
        return s
    }

    fun nextBoolean(): Boolean {
        expect(Token.BOOLEAN)
        val v = readChar() == 't'
        repeat(if (v) 3 else 4) { readChar() }
        afterValue()
        return v
    }

    private fun readString(): String {
        readChar() // opening quote
        sb.setLength(0)
        while (true) {
            val c = readChar()
            when (c) {
                '"' -> return sb.toString()
                '\\' -> when (val e = readChar()) {
                    '"', '\\', '/' -> sb.append(e)
                    'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                    'u' -> {
                        var v = 0
                        repeat(4) { v = (v shl 4) or Character.digit(readChar(), 16).also { d -> if (d < 0) error("bad \\u") } }
                        sb.append(v.toChar())
                    }
                    else -> error("bad escape \\$e")
                }
                else -> sb.append(c)
            }
        }
    }

    fun nextFloat(): Float = nextDouble().toFloat()
    fun nextInt(): Int = nextDouble().toInt()

    fun nextDouble(): Double {
        expect(Token.NUMBER)
        var neg = false
        var c = readChar()
        if (c == '-') { neg = true; c = readChar() }
        var mant = 0L
        var digits = 0
        var exp10 = 0
        var long = false
        if (c !in '0'..'9') error("bad number")
        var fraction = false
        var ch = c
        while (true) {
            if (digits < 18) {
                mant = mant * 10 + (ch - '0')
                if (mant != 0L) digits++
                if (fraction) exp10--
            } else {
                long = true
                if (!fraction) exp10++
            }
            var next = peekRaw()
            if (next == '.' && !fraction) { readChar(); fraction = true; next = peekRaw() }
            if (next !in '0'..'9') break
            ch = readChar()
        }
        var next = peekRaw()
        if (next == 'e' || next == 'E') {
            readChar()
            var eneg = false
            var e = 0
            next = peekRaw()
            if (next == '+' || next == '-') { eneg = readChar() == '-'; next = peekRaw() }
            while (next in '0'..'9') { e = e * 10 + (readChar() - '0'); next = peekRaw() }
            exp10 += if (eneg) -e else e
        }
        afterValue()
        // Exact fast path: mantissa < 2^53 and |exp| <= 22 gives a correctly rounded result.
        val v = if (!long && mant < (1L shl 53) && exp10 >= -22 && exp10 <= 22) {
            if (exp10 >= 0) mant * POW10[exp10] else mant / POW10[-exp10]
        } else {
            mant * Math.pow(10.0, exp10.toDouble())
        }
        return if (neg) -v else v
    }

    private fun peekRaw(): Char {
        if (pos >= limit && !fill()) return '\u0000'
        return buf[pos]
    }

    fun skipValue() {
        when (peek()) {
            Token.BEGIN_OBJECT -> { beginObject(); while (hasNext()) { nextName(); skipValue() }; endObject() }
            Token.BEGIN_ARRAY -> { beginArray(); while (hasNext()) skipValue(); endArray() }
            Token.STRING -> nextString()
            Token.NUMBER -> nextDouble()
            Token.BOOLEAN -> nextBoolean()
            Token.NULL -> { repeat(4) { readChar() }; afterValue() }
            else -> error("cannot skip ${peek()}")
        }
    }

    /** Reads a numeric array, appending to [out]. */
    fun readFloats(out: FloatList) {
        beginArray()
        while (hasNext()) out.add(nextFloat())
        endArray()
    }

    private companion object {
        val POW10 = DoubleArray(23).also { var p = 1.0; for (i in it.indices) { it[i] = p; p *= 10.0 } }
    }
}

/** Growable float array used only while loading. */
internal class FloatList(initial: Int = 64) {
    var data = FloatArray(initial)
    var size = 0
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
    fun clear() { size = 0 }
    fun toArray(): FloatArray = data.copyOf(size)
}
