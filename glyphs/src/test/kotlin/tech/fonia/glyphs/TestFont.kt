package tech.fonia.glyphs

import java.io.File

internal object TestFont {
    val dir: File = File(System.getProperty("fonia.glyphs") ?: "../spec/glyphs")
    val heroFile: File get() = File(dir, "allo-hero.json")
    val outlines: AlloOutlines by lazy { heroFile.inputStream().buffered().use { AlloOutlines.load(it) } }

    fun gid(name: String): Int = outlines.glyphId(name).also { require(it >= 0) { "no glyph $name" } }
}

/** Records path commands as text, for assertions. */
internal class RecordingSink : PathSink {
    val ops = ArrayList<String>()
    override fun moveTo(x: Float, y: Float) { ops += "M $x $y" }
    override fun lineTo(x: Float, y: Float) { ops += "L $x $y" }
    override fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float) { ops += "Q $x1 $y1 $x2 $y2" }
    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { ops += "C" }
    override fun close() { ops += "Z" }
}

/** Accumulates numbers so the JIT cannot drop the work; allocates nothing. */
internal class CountingSink : PathSink {
    var count = 0
    var acc = 0f
    override fun moveTo(x: Float, y: Float) { count++; acc += x + y }
    override fun lineTo(x: Float, y: Float) { count++; acc += x - y }
    override fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float) { count++; acc += x1 + y2 }
    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { count++ }
    override fun close() { count++ }
}
