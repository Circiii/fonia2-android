package tech.fonia.glyphs

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reads any JSON value into Map / List / Double / String / Boolean / null (tests only). */
internal fun readTree(file: File): Any? = file.reader(Charsets.UTF_8).buffered().use { readValue(JsonReader(it)) }

private fun readValue(r: JsonReader): Any? = when (r.peek()) {
    JsonReader.Token.BEGIN_OBJECT -> {
        val m = LinkedHashMap<String, Any?>()
        r.beginObject(); while (r.hasNext()) { val k = r.nextName(); m[k] = readValue(r) }; r.endObject(); m
    }
    JsonReader.Token.BEGIN_ARRAY -> {
        val l = ArrayList<Any?>()
        r.beginArray(); while (r.hasNext()) l += readValue(r); r.endArray(); l
    }
    JsonReader.Token.STRING -> r.nextString()
    JsonReader.Token.NUMBER -> r.nextDouble()
    JsonReader.Token.BOOLEAN -> r.nextBoolean()
    else -> { r.skipValue(); null }
}

@Suppress("UNCHECKED_CAST")
class GoldenOutlineTest {
    private val font = TestFont.outlines
    private val index = readTree(File(TestFont.dir, "golden/index.json")) as Map<String, Any?>
    private val tol = index["tolerance"] as Map<String, Double>
    private val coordTol = tol["coord"]!!.toFloat()
    private val advTol = tol["adv"]!!.toFloat()

    @Test
    fun `outlines match the fontTools instancer at 56 positions`() {
        val goldenGlyphs = index["glyphs"] as List<String>
        val positions = index["positions"] as List<List<Double>>
        assertEquals(20, goldenGlyphs.size)
        assertEquals(56, positions.size)
        val vi = VariationInterpolator(font)
        val out = GlyphOutline(font)
        var cases = 0
        var maxCoord = 0f
        var maxAdv = 0f
        var worst = ""
        for (pos in positions) {
            val w = pos[0].toInt(); val rd = pos[1].toInt()
            val doc = readTree(File(TestFont.dir, "golden/w%03d_r%03d.json".format(w, rd))) as Map<String, Any?>
            val glyphs = doc["glyphs"] as Map<String, Map<String, Any?>>
            vi.setAxes(w.toFloat(), rd.toFloat(), force = true)
            for ((name, g) in glyphs) {
                val id = TestFont.gid(name)
                vi.interpolate(id, out)
                val advErr = abs(out.advance - (g["adv"] as Double).toFloat())
                maxAdv = maxOf(maxAdv, advErr)
                assertTrue(advErr <= advTol, "$name @ $w/$rd advance ${out.advance} vs ${g["adv"]}")
                val comps = g["comps"] as List<List<Any?>>?
                if (comps != null) {
                    assertEquals(comps.size, out.componentCount, name)
                    comps.forEachIndexed { i, c ->
                        assertEquals(c[0], font.glyphName(out.componentGlyph(i)))
                        val e = maxOf(abs(out.componentDx(i) - (c[1] as Double).toFloat()), abs(out.componentDy(i) - (c[2] as Double).toFloat()))
                        if (e > maxCoord) { maxCoord = e; worst = "$name comp $i @ $w/$rd" }
                        assertTrue(e <= coordTol, "$name comp $i @ $w/$rd: error $e")
                    }
                } else {
                    val xy = (g["xy"] as List<Double>?) ?: emptyList()
                    assertEquals(xy.size, out.pointCount * 2, "$name point count")
                    for (k in xy.indices) {
                        val e = abs(out.xy[k] - xy[k].toFloat())
                        if (e > maxCoord) { maxCoord = e; worst = "$name[$k] @ $w/$rd" }
                        assertTrue(e <= coordTol, "$name coord $k @ $w/$rd: ${out.xy[k]} vs ${xy[k]}")
                    }
                }
                if (name in goldenGlyphs) cases++
            }
        }
        assertEquals(1120, cases)
        println("golden: $cases cases, max coord error $maxCoord ($worst), max advance error $maxAdv")
    }

    @Test
    fun `default instance reproduces the stored defaults exactly`() {
        val vi = VariationInterpolator(font)
        vi.setAxes(400f, 50f, force = true)
        assertTrue(vi.scalars.all { it == 0f }, "all region scalars are 0 at the default")
        val out = GlyphOutline(font)
        for (id in 0 until font.glyphCount) {
            vi.interpolate(id, out)
            assertEquals(font.defaultAdvance(id), out.advance, font.glyphName(id))
            val g = font.glyphs[id]
            if (g.comps == null) for (k in 0 until g.pointCount * 2) assertEquals(g.xy[k], out.xy[k])
        }
    }
}
