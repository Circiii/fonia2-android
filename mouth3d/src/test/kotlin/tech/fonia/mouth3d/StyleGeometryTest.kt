package tech.fonia.mouth3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.model.SoundId
import tech.fonia.mouth3d.style.Anatomy
import tech.fonia.mouth3d.style.Geo
import tech.fonia.mouth3d.style.LipContours
import tech.fonia.mouth3d.style.Mat4
import tech.fonia.mouth3d.style.MeshBuilder
import tech.fonia.mouth3d.style.P2
import tech.fonia.mouth3d.style.SagittalShape
import tech.fonia.mouth3d.style.CutOverlay
import tech.fonia.mouth3d.style.HeadModel
import tech.fonia.mouth3d.style.StyleScene
import tech.fonia.mouth3d.style.TongueBlend
import kotlin.math.PI
import kotlin.math.abs

class StyleGeometryTest {
    private val lib = TestData.library
    private fun pose(k: String) = lib.specs.getValue(k).pose
    private fun closure(k: String) = lib.specs.getValue(k).animation!!.keys.first().pose

    private fun area(poly: List<P2>, tris: IntArray): Float {
        var a = 0f
        for (t in tris.indices step 3) a += abs(Geo.area2(listOf(poly[tris[t]], poly[tris[t + 1]], poly[tris[t + 2]])))
        return a / 2
    }

    @Test
    fun `ear clipping covers convex and concave polygons in either winding`() {
        val square = listOf(P2(0f, 0f), P2(2f, 0f), P2(2f, 2f), P2(0f, 2f))
        assertEquals(6, Geo.triangulate(square).size)
        val ell = listOf(P2(0f, 0f), P2(3f, 0f), P2(3f, 1f), P2(1f, 1f), P2(1f, 3f), P2(0f, 3f))
        for (poly in listOf(ell, ell.reversed())) {
            val t = Geo.triangulate(poly)
            assertEquals(12, t.size)
            assertEquals(5f, area(poly, t), 1e-4f)
        }
        // the real outlines: the triangles add up to the outline's own area
        val sh = SagittalShape.of(pose("s"))
        for (poly in listOf(sh.tongue, sh.silhouette, sh.airSpace, Anatomy.upperCrown, Anatomy.palateBone)) {
            assertEquals(abs(Geo.area2(poly)) / 2, area(poly, Geo.triangulate(poly)), abs(Geo.area2(poly)) * 0.01f)
        }
    }

    @Test
    fun `tongue blend is exact at its anchors and its weights sum to one`() {
        for (a in TongueBlend.anchors) {
            val p = Pose.of(mapOf(PoseChannel.TongueTip to a.tip, PoseChannel.TongueForward to a.forward, PoseChannel.TongueRaise to a.raise, PoseChannel.TongueGroove to a.groove, PoseChannel.TongueCup to a.cup, PoseChannel.TongueSidesDown to a.sides))
            val pts = TongueBlend.shape(p, a.shape.jaw)
            for (k in 0 until 7) assertEquals(a.shape.pts[k].x, pts[k].x, 1e-3f)
        }
        val w = TongueBlend.weights(pose("r").with(PoseChannel.TongueTip, 0.7f))
        assertEquals(1f, w.sum(), 1e-4f)
    }

    @Test
    fun `each sound puts the tongue tip where the logopedic convention says`() {
        val s = SagittalShape.of(pose("s"))
        val sh = SagittalShape.of(pose("sh"))
        val r = SagittalShape.of(pose("r"))
        val l = SagittalShape.of(pose("l"))
        // S: tip down, below the upper incisal edge, behind the lower incisors, no contact with the roof
        assertTrue(s.tip.y > Anatomy.UPPER_EDGE.y)
        assertTrue(s.tip.x < s.lowerCrown.maxOf { it.x })
        assertNull(s.contact)
        // Ș: tip up and pulled back, away from the teeth
        assertTrue(sh.tip.y < s.tip.y - 15 && sh.tip.x < s.tip.x - 4)
        // R, L, Ț and CE closures: the tip (or blade) reaches the ridge
        assertTrue(r.tip.y < Anatomy.UPPER_EDGE.y - 15)
        assertNotNull(l.contact)
        assertNotNull(SagittalShape.of(closure("ts")).contact)
        assertNotNull(SagittalShape.of(closure("ce")).contact)
        // rounded lips stick out further than spread ones
        assertTrue(sh.upperLip[5].x > s.upperLip[5].x + 6)
    }

    @Test
    fun `rounded and spread lips remain distinct while keeping a full vermilion`() {
        val rest = LipContours(pose("rest"), 0f)
        val s = LipContours(pose("s"), 1f)
        val sh = LipContours(pose("sh"), 2f)
        assertTrue(s.halfWidth > rest.halfWidth + 5)
        assertTrue(sh.halfWidth < rest.halfWidth - 5)
        assertTrue(sh.protrusion > 5 && s.protrusion == 0f)
        for (k in PoseLibrary.FILES) {
            val c = LipContours(pose(k), 3f)
            for (i in 1 until 64) {
                if (i == 32) continue
                val t = (2 * PI * i / 64).toFloat()
                val inn = c.inner(t); val out = c.outer(t)
                val up = i < 32
                assertTrue("$k $i", if (up) out.y >= inn.y - 1e-3f else out.y <= inn.y + 1e-3f)
            }
        }
    }

    @Test
    fun `a closure holds the air behind the tongue and the release lets it go`() {
        val ts = lib.specs.getValue("ts")
        val sh = SagittalShape.of(closure("ts"))
        val held = CutOverlay.particles(sh, ts.air!!.stream, true, 1f, closure = true, sinceRelease = 10f)
        val tipX = sh.tip.x
        assertTrue(held.all { it.p.x < tipX })
        val free = CutOverlay.particles(SagittalShape.of(ts.pose), ts.air!!.stream, true, 1f, closure = false, sinceRelease = 0.1f)
        assertTrue(free.any { it.p.x > Anatomy.UPPER_EDGE.x && it.alpha > 0.1f })
    }

    @Test
    fun `every pose builds finite geometry in both views and half-way`() {
        for (k in PoseLibrary.FILES) {
            val spec = lib.specs.getValue(k)
            for (ia in listOf(0f, 0.5f, 1f)) {
                val f = SceneFrame(spec.pose, 76f * ia, 0f, ia, 1f, true, 1.2f)
                val mb = MeshBuilder()
                StyleScene.build(mb, f, spec, StylePreviewTest.GUIDE, Articulator.Air, StyleScene.camera(f, 800, 480))
                assertTrue(mb.vertexCount > 500 && mb.batches.isNotEmpty())
                for (i in 0 until mb.vertexCount * MeshBuilder.STRIDE) assertTrue("$k NaN", mb.data[i].isFinite())
                for (i in 0 until mb.indexCount) assertTrue(mb.indices[i] in 0 until mb.vertexCount)
            }
        }
    }

    @Test
    fun `head, teeth and tongue are closed meshes, so the cut can be capped`() {
        val frames = PoseLibrary.FILES.map { it to pose(it) } +
            listOf("ts", "ce", "ge").map { "${it}_closure" to closure(it) }
        for ((k, p) in frames) {
            val mb = MeshBuilder()
            val ranges = HeadModel.Ranges()
            HeadModel.build(mb, p, SagittalShape.of(p), StylePreviewTest.GUIDE, HeadModel.Glow(0f, 0f, 0f), 0f, ranges)
            for ((from, to) in listOf(ranges.head0 to ranges.head1, ranges.teeth0 to ranges.teeth1, ranges.tongue0 to ranges.tongue1)) {
                // weld by position, drop degenerate triangles (poles), then every edge must be shared by exactly two
                val key = HashMap<Long, Int>()
                fun id(v: Int): Int {
                    val o = v * MeshBuilder.STRIDE
                    val q = ((Math.round(mb.data[o] * 1000.0) * 73856093L) xor (Math.round(mb.data[o + 1] * 1000.0) * 19349663L) xor (Math.round(mb.data[o + 2] * 1000.0) * 83492791L))
                    return key.getOrPut(q) { key.size }
                }
                val edges = HashMap<Long, Int>()
                for (t in from until to step 3) {
                    val a = id(mb.indices[t]); val b = id(mb.indices[t + 1]); val c = id(mb.indices[t + 2])
                    if (a == b || b == c || a == c) continue
                    for ((u, v) in listOf(a to b, b to c, c to a)) {
                        val e = minOf(u, v).toLong() * 1_000_000L + maxOf(u, v)
                        edges[e] = (edges[e] ?: 0) + 1
                    }
                }
                val bad = edges.values.count { it != 2 }
                assertEquals("$k mesh $from..$to has $bad open or shared edges", 0, bad)
            }
        }
    }

    @Test
    fun `camera frames the front mouth at about 70 percent of the width`() {
        val spec = lib.forSound(SoundId.S)
        val f = SceneFrame(spec.pose, 0f, 0f, 0f, 1f, false)
        val cam = StyleScene.camera(f, 900, 500)
        val l = LipContours(spec.pose, 1f)
        val mvp = Mat4.mul(cam.proj, cam.mv)
        val o = FloatArray(4)
        Mat4.apply(mvp, l.halfWidth + l.cornerThick, 0f, 5f, o)
        val ndcX = o[0] / o[3]
        assertTrue("corner at $ndcX", ndcX in 0.68f..0.84f)
    }
}
