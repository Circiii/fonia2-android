package tech.fonia.glyphs

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerformanceTest {
    private val font = TestFont.outlines
    private val word = "ȘARPELE ȚOPĂIE ÎN GRĂDINĂ: șase țânțari, Ăla e bine? ★✓→".codePoints()
        .map { font.glyphFor(it) }.filter { it >= 0 }.toArray()

    private fun frame(vi: VariationInterpolator, out: GlyphOutline, quad: QuadOutline?, sink: CountingSink, f: Int) {
        // axes move every frame (as while the voice inflates the letter)
        vi.setAxes(180f + (f % 720), (f * 7 % 100).toFloat())
        for (g in word) {
            vi.interpolate(g, out)
            if (quad != null) { QuadOutline.from(out, quad); quad.emit(sink) } else out.emit(sink, 0.1f, -0.1f, 5f, 200f)
        }
    }

    @Test
    fun `interpolate and emit allocate nothing`() {
        val tmx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assertTrue(tmx.isThreadAllocatedMemorySupported)
        tmx.isThreadAllocatedMemoryEnabled = true
        val vi = VariationInterpolator(font)
        val out = GlyphOutline(font)
        val quad = QuadOutline(512, 32)
        val sub = QuadOutline(4096, 32)
        val noisy = QuadOutline(4096, 32)
        val sink = CountingSink()
        val sampleXY = FloatArray(160); val sampleN = FloatArray(160)
        fun ops(f: Int) {
            vi.interpolate(word[f % word.size], out)
            QuadOutline.from(out, quad)
            OutlineOps.subdivide(quad, sub, 0.08f * font.upm)
            OutlineOps.noiseDisplace(sub, noisy, f and 3, 0.035f * font.upm, 0.35f * font.upm)
            OutlineOps.offsetAlongNormals(quad, sub, 20f)
            OutlineOps.shear(sub, sub, 0.158f)
            OutlineOps.sampleEvenly(quad, 80, sampleXY, sampleN)
            noisy.emit(sink)
        }
        // warm up: JIT, buffer growth
        for (f in 0 until 3000) { frame(vi, out, null, sink, f); ops(f) }
        val tid = Thread.currentThread().id
        val calib0 = tmx.getThreadAllocatedBytes(tid)
        val calib1 = tmx.getThreadAllocatedBytes(tid)
        val overhead = calib1 - calib0
        val before = tmx.getThreadAllocatedBytes(tid)
        var calls = 0
        var f = 0
        while (calls < 10_000) { frame(vi, out, null, sink, f++); calls += word.size }
        for (i in 0 until 1000) ops(i)
        val after = tmx.getThreadAllocatedBytes(tid)
        val allocated = after - before - overhead
        assertTrue(sink.count > 0)
        assertEquals(0L, allocated, "bytes allocated in $calls interpolate+emit calls and 1000 op chains")
    }

    @Test
    fun `interpolation time per glyph`() {
        val vi = VariationInterpolator(font)
        val out = GlyphOutline(font)
        val sink = CountingSink()
        for (f in 0 until 5000) frame(vi, out, null, sink, f)
        val frames = 4000
        val t0 = System.nanoTime()
        for (f in 0 until frames) frame(vi, out, null, sink, f)
        val ns = (System.nanoTime() - t0).toDouble() / (frames * word.size)
        val q = QuadOutline(512, 32)
        for (f in 0 until 2000) frame(vi, out, q, sink, f)
        val t1 = System.nanoTime()
        for (f in 0 until frames) frame(vi, out, q, sink, f)
        val nsQuad = (System.nanoTime() - t1).toDouble() / (frames * word.size)
        println("interpolate + emit: %.2f us/glyph (%d glyphs/frame -> %.3f ms/frame); via QuadOutline: %.2f us/glyph"
            .format(ns / 1000, word.size, ns * word.size / 1e6, nsQuad / 1000))
        // PC JVM bound, generous so CI noise does not fail it; the phone target (50 us) is measured in :benchmark
        assertTrue(ns < 50_000, "interpolate+emit took $ns ns per glyph")
    }

    @Test
    fun `load time`() {
        val bytes = TestFont.heroFile.readBytes()
        repeat(3) { AlloOutlines.load(bytes.inputStream()) }
        val t0 = System.nanoTime()
        val n = 5
        repeat(n) { AlloOutlines.load(bytes.inputStream()) }
        val ms = (System.nanoTime() - t0) / 1e6 / n
        println("load allo-hero.json (${bytes.size} bytes): %.1f ms".format(ms))
        assertTrue(ms < 2000)
    }
}
