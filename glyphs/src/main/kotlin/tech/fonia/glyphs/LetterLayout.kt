package tech.fonia.glyphs

import java.text.Normalizer

/**
 * Positions of the letters of a word, fixed for the life of the word so nothing jumps while a
 * letter inflates: every letter gets a slot as wide as its advance at the reserved instance
 * (wght 900, ROND 100), plus half the maximum rim on each side, times the maximum scale; the live
 * glyph is centred in its slot each frame ([LetterLine.glyphX]). Kerning (measured at the same
 * reserved instance) is added between slots when asked.
 *
 * Layout happens when the text changes, not per frame (it normalises the string, which allocates).
 */
class LetterLayout(
    val font: AlloOutlines,
    val reservedWght: Float = 900f,
    val reservedRond: Float = 100f,
) {
    private val reserved = FloatArray(font.glyphCount)

    init {
        val vi = VariationInterpolator(font)
        vi.setAxes(reservedWght, reservedRond, force = true)
        for (g in 0 until font.glyphCount) reserved[g] = vi.advance(g)
    }

    /** Advance of a glyph at the reserved instance, font units. */
    fun reservedAdvance(glyphId: Int): Float = reserved[glyphId]

    /**
     * Lays out [text] (NFC-normalised; S + U+0326 becomes Ș) into [out], font units, x from 0.
     * [rimEm]: the largest rim stroke width in em (§4: 0.24). [maxScale]: the largest scale the
     * letters reach (§4: 1.30, or 1.70 for POP). Code points the export lacks are skipped and
     * counted in [LetterLine.missing].
     */
    fun layout(
        text: CharSequence,
        out: LetterLine,
        rimEm: Float = 0.24f,
        maxScale: Float = 1.3f,
        features: Int = GlyphFeatures.NONE,
        kerning: Boolean = true,
    ) {
        val s = Normalizer.normalize(text, Normalizer.Form.NFC)
        out.reset(s.length)
        val rim = rimEm * font.upm
        var x = 0f
        var prev = -1
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val start = i
            i += Character.charCount(cp)
            val g = font.glyphFor(cp, features)
            if (g < 0) { out.missing++; continue }
            if (kerning && prev >= 0) x += font.kerning(prev, g) * maxScale
            val w = (reserved[g] + rim) * maxScale
            out.add(g, start, x, w)
            x += w
            prev = g
        }
        out.width = x
        out.scale = maxScale
    }
}

/** Result of [LetterLayout.layout]; reusable. */
class LetterLine(capacity: Int = 16) {
    var count = 0
        private set
    var glyph = IntArray(capacity)
        private set
    /** Index of the letter in the normalised text. */
    var charIndex = IntArray(capacity)
        private set
    /** Left edge of each slot, font units. */
    var slotX = FloatArray(capacity)
        private set
    var slotWidth = FloatArray(capacity)
        private set
    /** Total width, font units. */
    var width = 0f
        internal set
    var missing = 0
        internal set
    internal var scale = 1f

    internal fun reset(n: Int) {
        count = 0; width = 0f; missing = 0
        if (glyph.size < n) {
            glyph = IntArray(n); charIndex = IntArray(n); slotX = FloatArray(n); slotWidth = FloatArray(n)
        }
    }

    internal fun add(g: Int, ci: Int, x: Float, w: Float) {
        glyph[count] = g; charIndex[count] = ci; slotX[count] = x; slotWidth[count] = w
        count++
    }

    /**
     * x (font units, unscaled glyph space) where letter [i]'s origin goes so a glyph of advance
     * [advance] drawn at [scale] is centred in its slot: `slotX + (slotWidth - advance * scale) / 2`.
     */
    fun glyphX(i: Int, advance: Float, scale: Float): Float = slotX[i] + (slotWidth[i] - advance * scale) * 0.5f

    /** Centre of slot [i], font units. Scale the letter around this point. */
    fun slotCenter(i: Int): Float = slotX[i] + slotWidth[i] * 0.5f
}
