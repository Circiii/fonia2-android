package tech.fonia.facecamera

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * MediaPipe's Face Landmarker bundle (`assets/models/face_landmarker.task`, Apache-2.0; source and checksum in
 * MODELS.md): a zip with the three TFLite models this module runs on LiteRT, plus the canonical face used for the
 * head pose. The file is pinned by SHA-256 and read once per process.
 */
internal class ModelBundle(
    val faceDetector: ByteBuffer,
    val faceLandmarks: ByteBuffer,
    val blendshapes: ByteBuffer,
    val geometry: GeometryMetadata,
) {
    class InvalidBundle(message: String) : Exception(message)

    companion object {
        const val ASSET = "models/face_landmarker.task"
        const val SHA256 = "64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff"
        const val FACE_DETECTOR = "face_detector.tflite"
        const val FACE_LANDMARKS = "face_landmarks_detector.tflite"
        const val BLENDSHAPES = "face_blendshapes.tflite"
        const val GEOMETRY = "geometry_pipeline_metadata_landmarks.binarypb"

        @Volatile private var loaded: ModelBundle? = null

        /** The bundle from the app's assets, verified and unzipped on first use. */
        fun load(context: Context): ModelBundle {
            loaded?.let { return it }
            synchronized(this) {
                loaded?.let { return it }
                val bytes = context.assets.open(ASSET).use { it.readBytes() }
                return parse(bytes).also { loaded = it }
            }
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

        /** Checks the pinned checksum, then takes the four files out of the zip. */
        fun parse(bytes: ByteArray): ModelBundle {
            val digest = sha256(bytes)
            if (digest != SHA256) throw InvalidBundle("$ASSET has SHA-256 $digest, expected $SHA256")
            val files = unzip(bytes)
            fun file(name: String) = files[name] ?: throw InvalidBundle("$ASSET has no $name")
            return ModelBundle(
                faceDetector = direct(file(FACE_DETECTOR)),
                faceLandmarks = direct(file(FACE_LANDMARKS)),
                blendshapes = direct(file(BLENDSHAPES)),
                geometry = GeometryMetadata.parse(file(GEOMETRY)),
            )
        }

        /**
         * The zip's files, found through its central directory. MediaPipe aligns the entries (two bytes before the
         * first header, padding between files), so a streaming reader such as ZipInputStream stops after the first.
         */
        internal fun unzip(bytes: ByteArray): Map<String, ByteArray> {
            fun u16(at: Int) = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)
            fun u32(at: Int) = u16(at) or (u16(at + 2) shl 16)
            val end = (bytes.size - 22 downTo 0).firstOrNull { u32(it) == 0x06054b50 } ?: throw InvalidBundle("$ASSET is not a zip")
            val count = u16(end + 10)
            val directorySize = u32(end + 12)
            val directoryStart = end - directorySize
            // Bytes before the archive shift every recorded offset.
            val shift = directoryStart - u32(end + 16)
            val files = HashMap<String, ByteArray>()
            var at = directoryStart
            repeat(count) {
                if (u32(at) != 0x02014b50) throw InvalidBundle("$ASSET: bad central directory")
                val method = u16(at + 10)
                val compressedSize = u32(at + 20)
                val size = u32(at + 24)
                val nameLength = u16(at + 28)
                val extraLength = u16(at + 30)
                val commentLength = u16(at + 32)
                val header = u32(at + 42) + shift
                val name = String(bytes, at + 46, nameLength, Charsets.UTF_8)
                if (u32(header) != 0x04034b50) throw InvalidBundle("$ASSET: bad header for $name")
                val data = header + 30 + u16(header + 26) + u16(header + 28)
                files[name] = when (method) {
                    0 -> bytes.copyOfRange(data, data + size)
                    8 -> java.util.zip.Inflater(true).run {
                        setInput(bytes, data, compressedSize)
                        ByteArray(size).also { out -> inflate(out); end() }
                    }
                    else -> throw InvalidBundle("$ASSET: $name is compressed with method $method")
                }
                at += 46 + nameLength + extraLength + commentLength
            }
            return files
        }

        /** LiteRT reads a model from a direct buffer, which must stay alive as long as the interpreter. */
        private fun direct(bytes: ByteArray): ByteBuffer =
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
    }
}

/**
 * `geometry_pipeline_metadata_landmarks.binarypb` (MediaPipe `GeometryPipelineMetadata`): the canonical face in
 * centimetres (+x to the face's left, +y up, +z out of the face, as ARKit's face anchor) and the weights of the 33
 * landmarks the Procrustes fit uses, so that the pose follows the skull rather than the expression.
 */
internal class GeometryMetadata(
    /** x, y, z of the 468 canonical vertices, vertex after vertex. */
    val canonical: FloatArray,
    /** Procrustes weight per landmark; 0 for landmarks outside the basis. */
    val weights: FloatArray,
    val inputSource: Int,
) {
    val vertexCount: Int get() = canonical.size / 3

    companion object {
        /** Field numbers from geometry_pipeline_metadata.proto and mesh_3d.proto. */
        private const val CANONICAL_MESH = 1
        private const val PROCRUSTES_BASIS = 2
        private const val INPUT_SOURCE = 3
        private const val VERTEX_BUFFER = 3
        private const val LANDMARK_ID = 1
        private const val WEIGHT = 2
        private const val VERTEX_SIZE = 5 // VERTEX_PT: x, y, z, u, v

        fun parse(bytes: ByteArray): GeometryMetadata {
            var inputSource = 0
            val vertexBuffer = ArrayList<Float>()
            val basis = ArrayList<Pair<Int, Float>>()
            ProtoReader(bytes).forEachField { field, reader ->
                when (field) {
                    INPUT_SOURCE -> inputSource = reader.varint().toInt()
                    CANONICAL_MESH -> reader.message().forEachField { meshField, mesh ->
                        if (meshField == VERTEX_BUFFER) mesh.floats(vertexBuffer) else mesh.skip()
                    }
                    PROCRUSTES_BASIS -> {
                        var id = 0
                        var weight = 0f
                        reader.message().forEachField { refField, ref ->
                            when (refField) {
                                LANDMARK_ID -> id = ref.varint().toInt()
                                WEIGHT -> weight = ref.fixed32Float()
                                else -> ref.skip()
                            }
                        }
                        basis.add(id to weight)
                    }
                    else -> reader.skip()
                }
            }
            require(vertexBuffer.size % VERTEX_SIZE == 0) { "vertex buffer of ${vertexBuffer.size} floats" }
            val count = vertexBuffer.size / VERTEX_SIZE
            val canonical = FloatArray(count * 3) { vertexBuffer[(it / 3) * VERTEX_SIZE + it % 3] }
            val weights = FloatArray(count)
            for ((id, weight) in basis) weights[id] = weight
            return GeometryMetadata(canonical, weights, inputSource)
        }
    }
}

/** Just enough of the protobuf wire format for the geometry metadata. */
internal class ProtoReader(private val bytes: ByteArray, private var position: Int = 0, private val end: Int = bytes.size) {
    private var wireType = 0

    fun forEachField(action: (field: Int, reader: ProtoReader) -> Unit) {
        while (position < end) {
            val key = varint()
            wireType = (key and 7).toInt()
            action((key ushr 3).toInt(), this)
        }
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = bytes[position++].toInt() and 0xff
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b < 0x80) return result
            shift += 7
        }
    }

    fun fixed32Float(): Float {
        val bits = (bytes[position].toInt() and 0xff) or
            ((bytes[position + 1].toInt() and 0xff) shl 8) or
            ((bytes[position + 2].toInt() and 0xff) shl 16) or
            ((bytes[position + 3].toInt() and 0xff) shl 24)
        position += 4
        return Float.fromBits(bits)
    }

    fun message(): ProtoReader {
        val length = varint().toInt()
        val reader = ProtoReader(bytes, position, position + length)
        position += length
        return reader
    }

    /** A repeated float, packed (wire type 2) or not (wire type 5). */
    fun floats(into: MutableList<Float>) {
        if (wireType == 5) {
            into.add(fixed32Float())
            return
        }
        val length = varint().toInt()
        val stop = position + length
        while (position < stop) into.add(fixed32Float())
    }

    fun skip() {
        when (wireType) {
            0 -> varint()
            1 -> position += 8
            2 -> position += varint().toInt()
            5 -> position += 4
            else -> error("wire type $wireType")
        }
    }
}
