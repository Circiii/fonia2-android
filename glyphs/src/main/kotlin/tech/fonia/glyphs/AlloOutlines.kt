package tech.fonia.glyphs

import java.io.InputStream
import java.io.InputStreamReader
import java.util.Base64

/** OpenType feature switches the export resolves (single substitutions only). */
object GlyphFeatures {
    const val NONE = 0
    /** ss01: single-storey a (a.1, abreve.1, acircumflex.1). */
    const val SS01 = 1
    /** ss02: I with serifs, l with tail, one with foot, slashed zero. */
    const val SS02 = 2
    /** tnum: tabular digits (*.tf). */
    const val TNUM = 4
    /** zero: slashed zero. */
    const val ZERO = 8
    internal val TAGS = arrayOf("ss01", "ss02", "tnum", "zero")
}

/** One variation axis of the export (opsz is pinned, so only wght and ROND remain). */
class Axis internal constructor(
    val tag: String,
    val min: Float,
    val default: Float,
    val max: Float,
    internal val avarFrom: FloatArray,
    internal val avarTo: FloatArray,
)

internal class Glyph(
    val name: String,
    /** Point count of a simple glyph (0 for composites and empty glyphs). */
    val pointCount: Int,
    val ends: IntArray,
    val on: BooleanArray,
    val xy: FloatArray,
    val advance: Float,
    /** Component glyph ids (null for simple glyphs). */
    val comps: IntArray?,
    /** Default component offsets, 2 per component. */
    val compXY: FloatArray?,
    /** Region index of each tuple. */
    val tupleRegion: IntArray,
    /** Dense deltas, [tupleRegion.size] rows of [stride] floats; the last float of a row is the advance delta. */
    val deltas: FloatArray,
    val stride: Int,
)

/**
 * The exported Allo outlines (`spec/glyphs/allo-hero.json`): defaults, dense deltas per
 * wght x ROND region, composites, cmap, alternates and kerning. Immutable and thread-safe;
 * load once at start-up (off the main thread) and share.
 */
class AlloOutlines internal constructor(
    val upm: Int,
    val ascent: Int,
    val descent: Int,
    val capHeight: Int,
    val xHeight: Int,
    /** SHA-256 of the source font, as recorded by the export. */
    val fontSha256: String,
    val axes: List<Axis>,
    /** [regionCount] x [axes] x (lo, peak, hi), normalised. */
    internal val regions: FloatArray,
    val regionCount: Int,
    internal val glyphs: Array<Glyph>,
    private val names: Map<String, Int>,
    private val cmapCp: IntArray,
    private val cmapGid: IntArray,
    private val altFrom: Array<IntArray>,
    private val altTo: Array<IntArray>,
    private val kernKeys: LongArray,
    private val kernValues: FloatArray,
    /** Axis position (user units) the kerning was measured at. */
    val kernAt: FloatArray,
) {
    val glyphCount: Int get() = glyphs.size

    /** The axis normaliser (default normalisation + avar). */
    val axisMap = AxisMap(axes)

    /** Upper bounds over every glyph, for sizing [GlyphOutline] buffers. */
    val maxPoints: Int
    val maxContours: Int
    val maxComponents: Int

    init {
        var p = 0; var c = 0; var k = 0
        for (g in glyphs) {
            if (g.comps != null) {
                var pp = 0; var cc = 0
                for (id in g.comps) { pp += glyphs[id].pointCount; cc += glyphs[id].ends.size }
                p = maxOf(p, pp); c = maxOf(c, cc); k = maxOf(k, g.comps.size)
            } else {
                p = maxOf(p, g.pointCount); c = maxOf(c, g.ends.size)
            }
        }
        maxPoints = p; maxContours = c; maxComponents = maxOf(k, 1)
    }

    /** Glyph id for a glyph name, or -1. */
    fun glyphId(name: String): Int = names[name] ?: -1

    fun glyphName(id: Int): String = glyphs[id].name

    fun isComposite(id: Int): Boolean = glyphs[id].comps != null

    /** Advance at the default instance (wght 400, ROND 50, opsz 32), font units. */
    fun defaultAdvance(id: Int): Float = glyphs[id].advance

    /** Component glyph ids of a composite (empty for simple glyphs). Allocates; not for the frame loop. */
    fun components(id: Int): IntArray = glyphs[id].comps?.copyOf() ?: IntArray(0)

    /**
     * Glyph for a code point with [features] (bit set of [GlyphFeatures]), or -1 when the export
     * does not contain it. Legacy cedilla Ş ş Ţ ţ map to the comma glyphs.
     */
    fun glyphFor(codePoint: Int, features: Int = GlyphFeatures.NONE): Int {
        val i = cmapCp.binarySearch(codePoint)
        if (i < 0) return -1
        var gid = cmapGid[i]
        if (features != 0) {
            for (f in altFrom.indices) {
                if (features and (1 shl f) == 0) continue
                val j = altFrom[f].binarySearch(gid)
                if (j >= 0) gid = altTo[f][j]
            }
        }
        return gid
    }

    /** Pair kerning in font units at [kernAt] (wght 900, ROND 100), 0 when none. */
    fun kerning(left: Int, right: Int): Float {
        if (left < 0 || right < 0) return 0f
        val i = kernKeys.binarySearch((left.toLong() shl 32) or right.toLong())
        return if (i >= 0) kernValues[i] else 0f
    }

    companion object {
        /** Parses `allo-hero.json`. Closes nothing; the caller owns [stream]. */
        fun load(stream: InputStream): AlloOutlines = Loader(JsonReader(InputStreamReader(stream, Charsets.UTF_8))).load()
    }
}

private class RawGlyph(val name: String) {
    var adv = 0f
    var ends = IntArray(0)
    var on: ByteArray? = null
    var xy = FloatArray(0)
    var compNames: List<String>? = null
    var compXY: FloatArray? = null
    var regions = IntArray(0)
    var deltas = FloatArray(0)
}

private class Loader(private val r: JsonReader) {
    fun load(): AlloOutlines {
        var format = -1
        var upm = 0; var ascent = 0; var descent = 0; var capHeight = 0; var xHeight = 0
        var sha = ""
        val axes = ArrayList<Axis>()
        val regions = FloatList()
        var regionCount = 0
        val cmap = ArrayList<Pair<Int, String>>()
        val alt = HashMap<String, List<Pair<String, String>>>()
        val kern = ArrayList<Triple<String, String, Float>>()
        val kernAt = FloatArray(2)
        val raw = ArrayList<RawGlyph>()

        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "format" -> format = r.nextInt()
                "sha256" -> sha = r.nextString()
                "upm" -> upm = r.nextInt()
                "ascent" -> ascent = r.nextInt()
                "descent" -> descent = r.nextInt()
                "capHeight" -> capHeight = r.nextInt()
                "xHeight" -> xHeight = r.nextInt()
                "axes" -> { r.beginArray(); while (r.hasNext()) axes += readAxis(); r.endArray() }
                "regions" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        r.beginArray()
                        while (r.hasNext()) r.readFloats(regions)
                        r.endArray()
                        regionCount++
                    }
                    r.endArray()
                }
                "cmap" -> {
                    r.beginArray()
                    while (r.hasNext()) { r.beginArray(); val cp = r.nextInt(); val n = r.nextString(); r.endArray(); cmap += cp to n }
                    r.endArray()
                }
                "alt" -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        val tag = r.nextName()
                        val list = ArrayList<Pair<String, String>>()
                        r.beginObject()
                        while (r.hasNext()) { val k = r.nextName(); list += k to r.nextString() }
                        r.endObject()
                        alt[tag] = list
                    }
                    r.endObject()
                }
                "kernAt" -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) { "wght" -> kernAt[0] = r.nextFloat(); "ROND" -> kernAt[1] = r.nextFloat(); else -> r.skipValue() }
                    }
                    r.endObject()
                }
                "kern" -> {
                    r.beginArray()
                    while (r.hasNext()) { r.beginArray(); val a = r.nextString(); val b = r.nextString(); val v = r.nextFloat(); r.endArray(); kern += Triple(a, b, v) }
                    r.endArray()
                }
                "glyphs" -> {
                    r.beginObject()
                    val scratch = FloatList(1 shl 14)
                    while (r.hasNext()) raw += readGlyph(r.nextName(), scratch)
                    r.endObject()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        require(format == 1) { "allo-hero.json format $format, expected 1" }
        require(axes.size == 2 && axes[0].tag == "wght" && axes[1].tag == "ROND") { "axes must be wght, ROND" }
        require(regions.size == regionCount * axes.size * 3) { "bad regions" }

        val names = HashMap<String, Int>(raw.size * 2)
        raw.forEachIndexed { i, g -> names[g.name] = i }
        fun id(n: String) = names[n] ?: throw IllegalArgumentException("unknown glyph $n")

        val glyphs = Array(raw.size) { i ->
            val g = raw[i]
            val comps = g.compNames?.map(::id)?.toIntArray()
            val n = g.xy.size / 2
            val stride = if (comps != null) comps.size * 2 + 1 else n * 2 + 1
            require(g.deltas.size == g.regions.size * stride) { "${g.name}: delta rows must have $stride numbers" }
            for (rr in g.regions) require(rr in 0 until regionCount) { "${g.name}: bad region $rr" }
            val on = BooleanArray(n) { k -> ((g.on?.getOrNull(k shr 3)?.toInt() ?: 0) shr (k and 7)) and 1 == 1 }
            require(g.ends.isEmpty() || g.ends.last() == n - 1) { "${g.name}: contour ends do not match points" }
            Glyph(g.name, n, g.ends, on, g.xy, g.adv, comps, g.compXY, g.regions, g.deltas, stride)
        }
        for (g in glyphs) g.comps?.forEach { require(glyphs[it].comps == null) { "${g.name}: nested composites are not supported" } }

        val cm = cmap.sortedBy { it.first }
        val altFrom = Array(GlyphFeatures.TAGS.size) { IntArray(0) }
        val altTo = Array(GlyphFeatures.TAGS.size) { IntArray(0) }
        GlyphFeatures.TAGS.forEachIndexed { f, tag ->
            val pairs = (alt[tag] ?: emptyList()).map { id(it.first) to id(it.second) }.sortedBy { it.first }
            altFrom[f] = pairs.map { it.first }.toIntArray()
            altTo[f] = pairs.map { it.second }.toIntArray()
        }
        val kp = kern.map { ((id(it.first).toLong() shl 32) or id(it.second).toLong()) to it.third }.sortedBy { it.first }

        return AlloOutlines(
            upm, ascent, descent, capHeight, xHeight, sha, axes, regions.toArray(), regionCount, glyphs, names,
            cm.map { it.first }.toIntArray(), cm.map { id(it.second) }.toIntArray(),
            altFrom, altTo, kp.map { it.first }.toLongArray(), kp.map { it.second }.toFloatArray(), kernAt,
        )
    }

    private fun readAxis(): Axis {
        var tag = ""; var min = 0f; var def = 0f; var max = 0f
        val from = FloatList(8); val to = FloatList(8)
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "tag" -> tag = r.nextString()
                "min" -> min = r.nextFloat()
                "def" -> def = r.nextFloat()
                "max" -> max = r.nextFloat()
                "avar" -> {
                    r.beginArray()
                    while (r.hasNext()) { r.beginArray(); from.add(r.nextFloat()); to.add(r.nextFloat()); r.endArray() }
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return Axis(tag, min, def, max, from.toArray(), to.toArray())
    }

    private fun readGlyph(name: String, scratch: FloatList): RawGlyph {
        val g = RawGlyph(name)
        val regionList = ArrayList<Int>()
        scratch.clear()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "adv" -> g.adv = r.nextFloat()
                "ends" -> { val l = FloatList(8); r.readFloats(l); g.ends = IntArray(l.size) { l.data[it].toInt() } }
                "on" -> g.on = Base64.getDecoder().decode(r.nextString())
                "xy" -> { val l = FloatList(256); r.readFloats(l); g.xy = l.toArray() }
                "comps" -> {
                    val ns = ArrayList<String>(); val xy = FloatList(8)
                    r.beginArray()
                    while (r.hasNext()) { r.beginArray(); ns += r.nextString(); xy.add(r.nextFloat()); xy.add(r.nextFloat()); r.endArray() }
                    r.endArray()
                    g.compNames = ns; g.compXY = xy.toArray()
                }
                "tuples" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) { "r" -> regionList += r.nextInt(); "d" -> r.readFloats(scratch); else -> r.skipValue() }
                        }
                        r.endObject()
                    }
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        g.regions = regionList.toIntArray()
        g.deltas = scratch.toArray()
        return g
    }
}
