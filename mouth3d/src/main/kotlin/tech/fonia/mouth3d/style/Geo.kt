package tech.fonia.mouth3d.style

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A point of the 2D side drawing (x forward, y down, drawing units) or of any planar outline. */
data class P2(val x: Float, val y: Float) {
    operator fun plus(o: P2) = P2(x + o.x, y + o.y)
    operator fun minus(o: P2) = P2(x - o.x, y - o.y)
    operator fun times(k: Float) = P2(x * k, y * k)
    fun toward(o: P2, t: Float) = P2(x + (o.x - x) * t, y + (o.y - y) * t)
    fun moved(dx: Float, dy: Float) = P2(x + dx, y + dy)
    fun dist(o: P2) = hypot(x - o.x, y - o.y)
}

/** Small pure geometry used by both views and by the JVM preview. */
object Geo {
    /** Uniform Catmull-Rom through [pts], [steps] samples per span (closed: back to the start, without repeating it). */
    fun catmull(pts: List<P2>, closed: Boolean, steps: Int): List<P2> {
        val n = pts.size
        if (n < 3) return pts
        fun at(i: Int) = if (closed) pts[((i % n) + n) % n] else pts[i.coerceIn(0, n - 1)]
        val out = ArrayList<P2>(n * steps + 1)
        val spans = if (closed) n else n - 1
        for (i in 0 until spans) {
            val p0 = at(i - 1); val p1 = at(i); val p2 = at(i + 1); val p3 = at(i + 2)
            for (s in 0 until steps) {
                val t = s.toFloat() / steps
                val t2 = t * t; val t3 = t2 * t
                fun b(a: Float, b: Float, c: Float, d: Float) = 0.5f * (2 * b + (c - a) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (3 * b - a - 3 * c + d) * t3)
                out += P2(b(p0.x, p1.x, p2.x, p3.x), b(p0.y, p1.y, p2.y, p3.y))
            }
        }
        if (!closed) out += pts.last()
        return out
    }

    /** Twice the signed area (positive = counter-clockwise in a y-up frame). */
    fun area2(poly: List<P2>): Float {
        var a = 0f
        for (i in poly.indices) { val p = poly[i]; val q = poly[(i + 1) % poly.size]; a += p.x * q.y - q.x * p.y }
        return a
    }

    /**
     * Ear clipping of a simple polygon (either winding). Returns index triples into [poly]; near-duplicate points are
     * skipped; if the polygon is not simple it finishes with a fan so something is always drawn.
     */
    fun triangulate(poly: List<P2>): IntArray {
        val idx = ArrayList<Int>(poly.size)
        for (i in poly.indices) if (idx.isEmpty() || poly[idx.last()].dist(poly[i]) > 1e-4f) idx += i
        if (idx.size > 2 && poly[idx.first()].dist(poly[idx.last()]) <= 1e-4f) idx.removeAt(idx.size - 1)
        val out = ArrayList<Int>((idx.size - 2).coerceAtLeast(0) * 3)
        if (idx.size < 3) return IntArray(0)
        val ccw = area2(idx.map { poly[it] }) > 0
        fun cross(a: P2, b: P2, c: P2) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        fun inside(p: P2, a: P2, b: P2, c: P2): Boolean {
            val d1 = cross(a, b, p); val d2 = cross(b, c, p); val d3 = cross(c, a, p)
            return if (ccw) d1 >= 0 && d2 >= 0 && d3 >= 0 else d1 <= 0 && d2 <= 0 && d3 <= 0
        }
        var guard = 0
        var i = 0
        while (idx.size > 3 && guard < 4 * poly.size * poly.size + 16) {
            guard++
            val n = idx.size
            val ia = idx[(i + n - 1) % n]; val ib = idx[i % n]; val ic = idx[(i + 1) % n]
            val a = poly[ia]; val b = poly[ib]; val c = poly[ic]
            val cr = cross(a, b, c)
            val convex = if (ccw) cr > 1e-7f else cr < -1e-7f
            var ear = convex
            if (ear) for (k in idx) {
                if (k == ia || k == ib || k == ic) continue
                if (inside(poly[k], a, b, c)) { ear = false; break }
            }
            if (ear) {
                out += ia; out += ib; out += ic
                idx.removeAt(i % n)
                if (i >= idx.size) i = 0
            } else {
                i = (i + 1) % n
                if (abs(cr) < 1e-9f && guard > 2 * n) { idx.removeAt(i % idx.size); if (i >= idx.size) i = 0 }
            }
        }
        if (idx.size > 3) for (k in 1 until idx.size - 1) { out += idx[0]; out += idx[k]; out += idx[k + 1] }
        else if (idx.size == 3) { out += idx[0]; out += idx[1]; out += idx[2] }
        return out.toIntArray()
    }

    /** Unit normals (left of travel, in a y-up frame) per point of a polyline, averaged at the joints. */
    fun normals(line: List<P2>, closed: Boolean): List<P2> = line.indices.map { i ->
        val n = line.size
        val a = if (closed) line[(i - 1 + n) % n] else line[(i - 1).coerceAtLeast(0)]
        val b = if (closed) line[(i + 1) % n] else line[(i + 1).coerceAtMost(n - 1)]
        val dx = b.x - a.x; val dy = b.y - a.y
        val l = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f)
        P2(-dy / l, dx / l)
    }

    /** Arc length of a polyline. */
    fun length(line: List<P2>): Float { var s = 0f; for (i in 1 until line.size) s += line[i].dist(line[i - 1]); return s }

    /** The point at arc length [s] along [line] and the unit tangent there. */
    fun at(line: List<P2>, s: Float): Pair<P2, P2> {
        var left = s.coerceAtLeast(0f)
        for (i in 1 until line.size) {
            val a = line[i - 1]; val b = line[i]
            val d = a.dist(b)
            if (left <= d || i == line.size - 1) {
                val t = if (d < 1e-6f) 0f else (left / d).coerceIn(0f, 1f)
                return a.toward(b, t) to P2((b.x - a.x) / d.coerceAtLeast(1e-6f), (b.y - a.y) / d.coerceAtLeast(1e-6f))
            }
            left -= d
        }
        return line.last() to P2(1f, 0f)
    }

    /** y of a polyline at x (first crossing), or null. */
    fun yAt(line: List<P2>, x: Float): Float? {
        for (i in 0 until line.size - 1) {
            val a = line[i]; val b = line[i + 1]
            if ((a.x - x) * (b.x - x) <= 0 && a.x != b.x) return a.y + (b.y - a.y) * (x - a.x) / (b.x - a.x)
        }
        return null
    }

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

/** Column-major 4×4 matrices (the GL layout), enough for a camera. */
object Mat4 {
    fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0 until 4) for (rr in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + rr] * b[c * 4 + k]
            r[c * 4 + rr] = s
        }
        return r
    }

    fun translate(x: Float, y: Float, z: Float) = identity().also { it[12] = x; it[13] = y; it[14] = z }

    fun rotX(deg: Float): FloatArray {
        val a = Math.toRadians(deg.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
        return identity().also { it[5] = c; it[6] = s; it[9] = -s; it[10] = c }
    }

    fun rotY(deg: Float): FloatArray {
        val a = Math.toRadians(deg.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
        return identity().also { it[0] = c; it[2] = -s; it[8] = s; it[10] = c }
    }

    fun perspective(fovYDeg: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(Math.toRadians(fovYDeg / 2.0)).toFloat()
        return FloatArray(16).also {
            it[0] = f / aspect; it[5] = f
            it[10] = (far + near) / (near - far); it[11] = -1f
            it[14] = 2f * far * near / (near - far)
        }
    }

    /** (x, y, z, w) of m · (x, y, z, 1). */
    fun apply(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray) {
        for (r in 0 until 4) out[r] = m[r] * x + m[4 + r] * y + m[8 + r] * z + m[12 + r]
    }
}
