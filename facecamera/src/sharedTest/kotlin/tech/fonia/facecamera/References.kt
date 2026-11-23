package tech.fonia.facecamera

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.math.abs
import kotlin.math.max

// Fixtures in src/androidTest/assets/faces, read by the JVM tests from the source tree and by the instrumented tests
// from the test APK's assets. Produced by facecamera/tools/face_reference (MODELS.md).

/** Reads fixture files: the JVM tests from disk, the instrumented tests from assets. */
interface Fixtures {
    fun text(name: String): String

    /** A PNG as packed ARGB pixels (Bitmap/BufferedImage order), with its size. */
    fun argb(name: String): Image

    class Image(val width: Int, val height: Int, val argb: IntArray)
}

/** The five still frames, each with MediaPipe's IMAGE-mode output. */
val FACE_NAMES = listOf("smile", "speaking", "wide_open", "pout", "small_smile")

/** MediaPipe's output on one frame (`<name>.json`). */
class FaceReference(json: String) {
    private val root = Json.parseToJsonElement(json).jsonObject
    val width = root["width"]!!.jsonPrimitive.int
    val height = root["height"]!!.jsonPrimitive.int
    val face = root["face"]!!.jsonPrimitive.boolean

    /** 478 × (x, y, z), normalized. */
    val landmarks: FloatArray = root["landmarks"]!!.jsonArray.flatMap { p -> p.jsonArray.map { it.jsonPrimitive.float } }.toFloatArray()

    /** 52 scores in model order. */
    val blendshapes: FloatArray = root["blendshapes"]!!.jsonObject.let { b -> FloatArray(52) { b[Blendshapes.NAMES[it]]!!.jsonPrimitive.float } }

    /** Row-major 4x4, canonical face → camera, centimetres. */
    val matrix: DoubleArray = root["matrix"]!!.jsonArray.flatMap { r -> r.jsonArray.map { it.jsonPrimitive.float.toDouble() } }.toDoubleArray()

    /** The face rectangle the Python prototype's detector step gave: centre x, centre y, width, height, rotation. */
    val prototypeRect: FloatArray = root["prototype_detection_rect"]!!.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
}

/** MediaPipe's VIDEO-mode output on a sequence of exact crops and gray frames (`sequence.json`). */
class SequenceReference(json: String) {
    private val root = Json.parseToJsonElement(json).jsonObject
    private val canvas = root["canvas"]!!.jsonObject
    val canvasWidth = canvas["width"]!!.jsonPrimitive.int
    val canvasHeight = canvas["height"]!!.jsonPrimitive.int
    val placeX = canvas["x"]!!.jsonPrimitive.int
    val placeY = canvas["y"]!!.jsonPrimitive.int
    val gray = canvas["gray"]!!.jsonPrimitive.int
    val frameWidth = root["frame"]!!.jsonObject["width"]!!.jsonPrimitive.int
    val frameHeight = root["frame"]!!.jsonObject["height"]!!.jsonPrimitive.int
    val timestampsMs: List<Long> = root["timestamps_ms"]!!.jsonArray.map { it.jsonPrimitive.long }

    /** Crop offset per frame, or null for an all-gray frame. */
    val offsets: List<Pair<Int, Int>?> = root["offsets"]!!.jsonArray.map {
        if (it is JsonNull) null else it.jsonArray.let { o -> o[0].jsonPrimitive.int to o[1].jsonPrimitive.int }
    }

    class Frame(val landmarks: Map<Int, FloatArray>, val blendshapes: FloatArray, val matrix: DoubleArray)

    val frames: List<Frame?> = root["frames"]!!.jsonArray.map { element ->
        if (element is JsonNull) return@map null
        val f = element as JsonObject
        Frame(
            landmarks = f["landmarks"]!!.jsonObject.entries.associate { (k, v) -> k.toInt() to v.jsonArray.map { it.jsonPrimitive.float }.toFloatArray() },
            blendshapes = f["blendshapes"]!!.jsonObject.let { b -> FloatArray(52) { b[Blendshapes.NAMES[it]]!!.jsonPrimitive.float } },
            matrix = (f["matrix"] as JsonArray).flatMap { r -> r.jsonArray.map { it.jsonPrimitive.float.toDouble() } }.toDoubleArray(),
        )
    }

    /** Frame [k] as RGBA bytes: a crop of [source] placed on the gray canvas, or plain gray. */
    fun frame(k: Int, source: Fixtures.Image): ByteArray {
        val out = ByteArray(frameWidth * frameHeight * 4)
        val offset = offsets[k]
        for (y in 0 until frameHeight) {
            for (x in 0 until frameWidth) {
                var argb = (0xff shl 24) or (gray shl 16) or (gray shl 8) or gray
                if (offset != null) {
                    val sx = x + offset.first - placeX
                    val sy = y + offset.second - placeY
                    if (sx in 0 until source.width && sy in 0 until source.height) argb = source.argb[sy * source.width + sx]
                }
                putRgba(out, (y * frameWidth + x) * 4, argb)
            }
        }
        return out
    }
}

fun putRgba(out: ByteArray, at: Int, argb: Int) {
    out[at] = (argb shr 16).toByte()
    out[at + 1] = (argb shr 8).toByte()
    out[at + 2] = argb.toByte()
    out[at + 3] = (argb ushr 24).toByte()
}

/** Packed ARGB pixels as an RGBA buffer with no row padding. */
fun Fixtures.Image.rgba(): ByteArray {
    val out = ByteArray(width * height * 4)
    for (i in argb.indices) putRgba(out, i * 4, argb[i])
    return out
}

/**
 * The image as a camera would hand it over: stored rotated by [rotationDegrees] counter-clockwise (so that turning it
 * [rotationDegrees] clockwise makes it upright again), optionally mirrored first.
 */
fun Fixtures.Image.asBuffer(rotationDegrees: Int, mirrored: Boolean = false): Triple<ByteArray, Int, Int> {
    val w = width
    val h = height
    fun upright(x: Int, y: Int): Int = argb[y * w + (if (mirrored) w - 1 - x else x)]
    val bw = if (rotationDegrees % 180 == 0) w else h
    val bh = if (rotationDegrees % 180 == 0) h else w
    val out = ByteArray(bw * bh * 4)
    for (by in 0 until bh) {
        for (bx in 0 until bw) {
            // Upright pixel shown at buffer (bx, by) once the buffer is turned clockwise.
            val (ux, uy) = when (rotationDegrees) {
                0 -> bx to by
                90 -> (bh - 1 - by) to bx
                180 -> (bw - 1 - bx) to (bh - 1 - by)
                else -> by to (bw - 1 - bx)
            }
            putRgba(out, (by * bw + bx) * 4, upright(ux, uy))
        }
    }
    return Triple(out, bw, bh)
}

fun maxAbsDiff(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) { "${a.size} vs ${b.size}" }
    var m = 0f
    for (i in a.indices) m = max(m, abs(a[i] - b[i]))
    return m
}

/** The largest difference between the 3x3 rotation blocks of two row-major 4x4 matrices. */
fun maxRotationDiff(a: DoubleArray, b: DoubleArray): Double {
    var m = 0.0
    for (r in 0 until 3) for (c in 0 until 3) m = max(m, abs(a[r * 4 + c] - b[r * 4 + c]))
    return m
}

/** The pose in [geometry] as a row-major 4x4, as MediaPipe reports it. */
internal fun poseMatrix(geometry: FaceGeometry): DoubleArray {
    val r = geometry.rotationScale
    val t = geometry.translation
    return doubleArrayOf(
        r[0], r[1], r[2], t[0],
        r[3], r[4], r[5], t[1],
        r[6], r[7], r[8], t[2],
        0.0, 0.0, 0.0, 1.0,
    )
}
