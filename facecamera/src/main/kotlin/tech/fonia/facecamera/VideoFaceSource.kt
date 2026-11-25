package tech.fonia.facecamera

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import tech.fonia.face.FaceMeshFrame
import java.io.File
import java.nio.ByteBuffer

/**
 * Debug only: a recorded video in place of the camera, through the same face model, frame by frame. Used to watch
 * the speech coach on a real face without holding a phone (`foniaCoachVideo`). One thread calls [frameAt]; the
 * bitmap and the mesh frame it returns are reused by the next call.
 */
class VideoFaceSource(context: Context, file: File, private val maxSide: Int = 480) : AutoCloseable {
    private val retriever = MediaMetadataRetriever().apply { setDataSource(file.absolutePath) }
    private val pipeline = FaceLandmarkerPipeline(ModelBundle.load(context))
    private val frame = RgbaFrame()
    private val mesh = FaceMeshFrame()
    private val filler = MeshFrameFiller()
    private var pixels = ByteArray(0)

    val durationSeconds: Double =
        (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000.0

    /** The picture of the last [frameAt], for the screen. */
    @Volatile var picture: Bitmap? = null
        private set

    /**
     * Decodes the frame nearest [seconds] of the video and runs the face model on it; [tNanos] is the time the mesh
     * frame carries (the coach's clock). Null when the video has no frame there.
     */
    fun frameAt(seconds: Double, tNanos: Long): FaceMeshFrame? {
        val us = (seconds * 1e6).toLong()
        val decoded = retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, maxSide, maxSide) ?: return null
        val bitmap = if (decoded.config == Bitmap.Config.ARGB_8888) decoded else decoded.copy(Bitmap.Config.ARGB_8888, false)
        val w = bitmap.width
        val h = bitmap.height
        if (pixels.size != w * h * 4) pixels = ByteArray(w * h * 4)
        // ARGB_8888 is stored as R, G, B, A bytes.
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(pixels))
        frame.set(pixels, w, h, w * 4, 0, false)
        val found = pipeline.process(frame, tNanos / 1000, stream = true)
        filler.fill(pipeline, frame, tNanos, found, mesh)
        picture = bitmap
        return mesh
    }

    override fun close() {
        runCatching { retriever.release() }
        runCatching { pipeline.close() }
    }
}
