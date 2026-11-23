package tech.fonia.glyphs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InterpolatorTest {
    private val font = TestFont.outlines

    @Test
    fun `metadata and axes`() {
        assertEquals(2048, font.upm)
        assertEquals("cdc994466d3a2dc77d5f72bd4f60c1bbf4d65dad5901ac064c7cc04e182c4fe4", font.fontSha256)
        assertEquals(listOf("wght", "ROND"), font.axes.map { it.tag })
        assertEquals(100f, font.axes[0].min); assertEquals(400f, font.axes[0].default); assertEquals(900f, font.axes[0].max)
        assertEquals(0f, font.axes[1].min); assertEquals(50f, font.axes[1].default); assertEquals(100f, font.axes[1].max)
        assertTrue(font.glyphCount > 120)
    }

    @Test
    fun `axis normalisation applies avar`() {
        val m = font.axisMap
        assertEquals(0f, m.normalize("wght", 400f))
        assertEquals(1f, m.normalize("wght", 900f))
        assertEquals(-1f, m.normalize("wght", 100f))
        assertEquals(0.18f, m.normalize("wght", 500f), 1e-3f)
        assertEquals(0.36f, m.normalize("wght", 600f), 1e-3f)
        assertEquals(0.76f, m.normalize("wght", 800f), 1e-3f)
        assertEquals(0.27f, m.normalize("wght", 550f), 1e-3f) // between avar points
        assertEquals(1f, m.normalize("wght", 2000f)) // clamped
        assertEquals(-0.8f, m.normalize("ROND", 10f), 1e-6f)
        assertEquals(1f, m.normalize("ROND", 100f))
    }

    @Test
    fun `tent scalars handle intermediate masters`() {
        val vi = VariationInterpolator(font)
        val out = FloatArray(font.regionCount)
        // wght 600 is the intermediate master: its region (0, 0.36, 1) x (ROND peak 0) is fully on
        vi.scalars(font.axisMap.normalize(0, 600f), 0f, out)
        val r = font.regions
        var found = false
        for (i in 0 until font.regionCount) {
            val wPeak = r[i * 6 + 1]; val rPeak = r[i * 6 + 4]
            if (wPeak in 0.35f..0.37f && rPeak == 0f) { assertEquals(1f, out[i], 1e-3f); found = true }
            if (wPeak == 1f) assertEquals(0f, out[i], 1e-3f, "max-weight regions are off at the intermediate master")
        }
        assertTrue(found)
        // every scalar stays in [0, 1]
        for (w in 100..900 step 37) for (rd in 0..100 step 7) {
            vi.scalars(font.axisMap.normalize(0, w.toFloat()), font.axisMap.normalize(1, rd.toFloat()), out)
            assertTrue(out.all { it in 0f..1f })
        }
    }

    @Test
    fun `setAxes only recomputes past the thresholds`() {
        val vi = VariationInterpolator(font)
        assertTrue(vi.setAxes(500f, 30f))
        assertFalse(vi.setAxes(500.2f, 30.05f))
        assertTrue(vi.setAxes(500.3f, 30f))
        assertTrue(vi.setAxes(500.3f, 30.2f))
    }

    @Test
    fun `heavier is wider and the comma of S-comma is its own component`() {
        val vi = VariationInterpolator(font)
        val s = TestFont.gid("S")
        vi.setAxes(100f, 50f); val thin = vi.advance(s)
        vi.setAxes(900f, 50f); val bold = vi.advance(s)
        assertTrue(bold > thin)

        val sc = font.glyphFor('Ș'.code)
        assertEquals("Scommaaccent", font.glyphName(sc))
        val out = GlyphOutline(font)
        vi.interpolate(sc, out)
        assertEquals(2, out.componentCount)
        val comma = out.componentIndexOf(TestFont.gid("commaaccent"))
        assertEquals(1, comma)
        val before = out.xy.copyOf()
        val dx0 = out.componentDx(comma)
        out.translateComponent(comma, 0f, -300f)
        assertEquals(dx0, out.componentDx(comma))
        for (p in 0 until out.pointCount) {
            val inComma = p >= out.componentPointStart(comma)
            assertEquals(before[2 * p + 1] - if (inComma) 300f else 0f, out.xy[2 * p + 1], "point $p")
        }
    }

    @Test
    fun `cmap features and kerning`() {
        assertEquals("a.1", font.glyphName(font.glyphFor('a'.code, GlyphFeatures.SS01)))
        assertEquals("a", font.glyphName(font.glyphFor('a'.code)))
        assertEquals("l.ss02", font.glyphName(font.glyphFor('l'.code, GlyphFeatures.SS02)))
        assertEquals("two.tf", font.glyphName(font.glyphFor('2'.code, GlyphFeatures.TNUM)))
        assertEquals("Scommaaccent", font.glyphName(font.glyphFor('Ş'.code))) // legacy cedilla
        assertEquals("tcommaaccent", font.glyphName(font.glyphFor('ţ'.code)))
        assertEquals(-1, font.glyphFor('Ω'.code))
        assertEquals(-99f, font.kerning(TestFont.gid("A"), TestFont.gid("C")))
        assertEquals(0f, font.kerning(TestFont.gid("o"), TestFont.gid("n")))
    }

    @Test
    fun `TrueType emission rules`() {
        val o = GlyphOutline(8, 2, 1)
        // contour 0: four off-curve points (a "circle" with implied on-curve midpoints)
        val pts = floatArrayOf(0f, 10f, 10f, 0f, 0f, -10f, -10f, 0f)
        System.arraycopy(pts, 0, o.xy, 0, 8)
        // contour 1: triangle of on-curve points
        val tri = floatArrayOf(0f, 0f, 4f, 0f, 0f, 4f)
        System.arraycopy(tri, 0, o.xy, 8, 6)
        for (i in 0 until 4) o.on[i] = false
        for (i in 4 until 7) o.on[i] = true
        o.contourEnds[0] = 3; o.contourEnds[1] = 6
        o.pointCount = 7; o.contourCount = 2
        val sink = RecordingSink()
        o.emit(sink)
        assertEquals(
            listOf(
                "M -5.0 5.0", "Q 0.0 10.0 5.0 5.0", "Q 10.0 0.0 5.0 -5.0", "Q 0.0 -10.0 -5.0 -5.0", "Q -10.0 0.0 -5.0 5.0", "Z",
                "M 0.0 0.0", "L 4.0 0.0", "L 0.0 4.0", "Z",
            ),
            sink.ops,
        )
        // flipped and scaled
        val s2 = RecordingSink()
        o.emitContours(s2, 1, 2, 2f, -2f, 1f, 1f)
        assertEquals(listOf("M 1.0 1.0", "L 9.0 1.0", "L 1.0 -7.0", "Z"), s2.ops)
    }

    @Test
    fun `real glyphs emit closed contours`() {
        val vi = VariationInterpolator(font)
        val out = GlyphOutline(font)
        val sink = RecordingSink()
        vi.setAxes(720f, 33f)
        vi.interpolate(font.glyphFor('Ș'.code), out)
        out.emit(sink)
        assertEquals(out.contourCount, sink.ops.count { it == "Z" })
        assertEquals(out.contourCount, sink.ops.count { it.startsWith("M") })
        val comma = RecordingSink()
        out.emitComponent(1, comma)
        assertEquals(1, comma.ops.count { it == "Z" })
    }
}
