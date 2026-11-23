package tech.fonia.glyphs

import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LayoutAndOpsTest {
    private val font = TestFont.outlines

    @Test
    fun `layout reserves the widest advance and centres the live glyph`() {
        val layout = LetterLayout(font)
        val line = LetterLine()
        layout.layout("Șarpe", line) // decomposed Ș
        assertEquals(5, line.count)
        assertEquals("Scommaaccent", font.glyphName(line.glyph[0]))
        assertEquals(0, line.missing)
        val vi = VariationInterpolator(font)
        for (i in 0 until line.count) {
            val g = line.glyph[i]
            for (w in intArrayOf(100, 400, 900)) for (r in intArrayOf(0, 50, 100)) {
                vi.setAxes(w.toFloat(), r.toFloat())
                assertTrue(vi.advance(g) <= layout.reservedAdvance(g) + 0.01f, "${font.glyphName(g)} wider than reserved at $w/$r")
            }
            if (i > 0) assertTrue(line.slotX[i] >= line.slotX[i - 1] + line.slotWidth[i - 1] - 200f)
            val adv = font.defaultAdvance(g)
            assertEquals(line.slotCenter(i), line.glyphX(i, adv, 1f) + adv / 2f, 1e-3f)
        }
        assertEquals(line.slotX[4] + line.slotWidth[4], line.width, 1e-3f)

        val noKern = LetterLine()
        layout.layout("AC", noKern, kerning = false)
        val kern = LetterLine()
        layout.layout("AC", kern)
        assertEquals(noKern.width - 99f * 1.3f, kern.width, 1e-2f)

        layout.layout("aΩb", line)
        assertEquals(2, line.count); assertEquals(1, line.missing)
    }

    private fun outline(ch: Char, w: Float = 400f, r: Float = 50f): QuadOutline {
        val vi = VariationInterpolator(font)
        vi.setAxes(w, r)
        val g = GlyphOutline(font)
        vi.interpolate(font.glyphFor(ch.code), g)
        return QuadOutline().also { QuadOutline.from(g, it) }
    }

    private fun bounds(q: QuadOutline): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (j in 0 until q.segmentCount) {
            val x = q.seg[4 * j]; val y = q.seg[4 * j + 1]
            b[0] = minOf(b[0], x); b[1] = minOf(b[1], y); b[2] = maxOf(b[2], x); b[3] = maxOf(b[3], y)
        }
        return b
    }

    @Test
    fun `quad form keeps the outline`() {
        val vi = VariationInterpolator(font)
        val g = GlyphOutline(font)
        vi.interpolate(font.glyphFor('S'.code), g)
        val q = QuadOutline()
        QuadOutline.from(g, q)
        assertEquals(g.contourCount, q.contourCount)
        // both forms produce the same path apart from explicit vs implied points
        val a = RecordingSink(); g.emit(a)
        val b = RecordingSink(); q.emit(b)
        assertEquals(a.ops.first(), b.ops.first())
        assertEquals(a.ops.count { it.startsWith("Q") }, b.ops.count { it.startsWith("Q") })
    }

    @Test
    fun `subdivide bounds segment length and keeps the shape`() {
        val q = outline('O')
        val sub = QuadOutline()
        val maxLen = 0.08f * font.upm
        OutlineOps.subdivide(q, sub, maxLen)
        assertTrue(sub.segmentCount > q.segmentCount)
        assertEquals(q.contourCount, sub.contourCount)
        for (c in 0 until sub.contourCount) {
            val s = sub.contourStart(c); val e = sub.contourEnd[c]
            for (j in s until e) {
                val n = if (j + 1 == e) s else j + 1
                assertTrue(hypot(sub.seg[4 * n] - sub.seg[4 * j], sub.seg[4 * n + 1] - sub.seg[4 * j + 1]) <= maxLen * 1.05f)
            }
        }
        val b0 = bounds(q); val b1 = bounds(sub)
        for (i in 0..3) assertEquals(b0[i], b1[i], 6f)
    }

    @Test
    fun `offset inflates and shear slants`() {
        val q = outline('O')
        val fat = QuadOutline()
        OutlineOps.offsetAlongNormals(q, fat, 40f)
        val b0 = bounds(q); val b1 = bounds(fat)
        assertEquals(b0[2] - b0[0] + 80f, b1[2] - b1[0], 4f)
        assertEquals(b0[3] - b0[1] + 80f, b1[3] - b1[1], 4f)
        val sh = QuadOutline()
        OutlineOps.shear(q, sh, 0.5f, 0f)
        for (j in 0 until q.segmentCount) assertEquals(q.seg[4 * j] + 0.5f * q.seg[4 * j + 1], sh.seg[4 * j], 1e-3f)
        val mid = QuadOutline()
        OutlineOps.lerp(q, sh, 0.5f, mid)
        assertEquals((q.seg[0] + sh.seg[0]) / 2, mid.seg[0], 1e-3f)
    }

    @Test
    fun `noise is deterministic, bounded and seed dependent`() {
        val sub = QuadOutline()
        OutlineOps.subdivide(outline('S'), sub, 0.08f * font.upm)
        val a = QuadOutline(); val b = QuadOutline(); val c = QuadOutline()
        val amp = 0.035f * font.upm
        OutlineOps.noiseDisplace(sub, a, 1, amp, 0.35f * font.upm)
        OutlineOps.noiseDisplace(sub, b, 1, amp, 0.35f * font.upm)
        OutlineOps.noiseDisplace(sub, c, 2, amp, 0.35f * font.upm)
        var diff = 0f
        for (i in 0 until sub.segmentCount * 4) {
            assertEquals(a.seg[i], b.seg[i])
            assertTrue(abs(a.seg[i] - sub.seg[i]) <= amp + 1e-3f)
            diff = maxOf(diff, abs(a.seg[i] - c.seg[i]))
        }
        assertTrue(diff > amp * 0.2f)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `noise matches the reference vectors (iOS parity)`() {
        val doc = readTree(File(TestFont.dir, "golden/noise.json")) as Map<String, Any?>
        val tol = (doc["tolerance"] as Double).toFloat()
        for (v in doc["lattice"] as List<List<Double>>) {
            assertEquals(v[2].toFloat(), OutlineNoise.lattice(v[0].toInt(), v[1].toInt()), "lattice ${v[0]} ${v[1]}")
        }
        for (v in doc["value"] as List<List<Double>>) {
            assertEquals(v[3].toFloat(), OutlineNoise.value(v[0].toInt(), v[1].toFloat(), v[2].toInt()), tol)
        }
    }

    @Test
    fun `even samples lie on the outline with outward normals`() {
        val q = outline('O')
        val xy = FloatArray(160); val nn = FloatArray(160)
        assertEquals(80, OutlineOps.sampleEvenly(q, 80, xy, nn))
        val b = bounds(q)
        val cx = (b[0] + b[2]) / 2; val cy = (b[1] + b[3]) / 2
        // samples on the outer contour (far from the centre) must have normals pointing away from it
        var outer = 0
        for (i in 0 until 80) {
            assertEquals(1f, hypot(nn[2 * i], nn[2 * i + 1]), 1e-3f)
            val rx = xy[2 * i] - cx; val ry = xy[2 * i + 1] - cy
            val r = hypot(rx, ry)
            val dot = (rx * nn[2 * i] + ry * nn[2 * i + 1]) / r
            if (r > (b[2] - b[0]) * 0.45f) { outer++; assertTrue(dot > 0.5f, "outer normal $i points in") }
            else assertTrue(dot < -0.5f, "counter normal $i points away from the ink")
        }
        assertTrue(outer in 20..70)
        assertNotEquals(xy[0], xy[2])
    }
}
