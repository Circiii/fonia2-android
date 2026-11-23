package tech.fonia.facecamera

import tech.fonia.face.BlendshapeValues
import java.io.Closeable
import java.io.File
import java.io.Writer

/**
 * The recording tool for recalibrating the thresholds (PARITY.md): one CSV row per analysed frame, with the pose the
 * person is holding, the model's 52 raw scores, the seven coefficients after [BlendshapeCalibration], the head and gaze
 * angles and the timing. Numbers only, no image. The same faces recorded with the same labels on an iPhone (ARKit's
 * values) give the pairs the calibration curves are fitted from.
 *
 * Written from the camera thread, read nowhere in the app: the debug screen (FaceRecorderActivity) saves the file in
 * the app's own storage, to be pulled with adb.
 */
class FaceRecording(private val writer: Writer) : Closeable {
    constructor(file: File) : this(file.bufferedWriter())

    private var rows = 0

    init {
        writer.write(HEADER.joinToString(","))
        writer.write("\n")
    }

    /** One frame, labelled with the pose being held (a FaceCheck raw value, or "neutral"). */
    @Synchronized
    fun write(label: String, analysis: FaceAnalysis) {
        require(label.none { it == ',' || it == '\n' || it == '"' }) { "label must not need quoting" }
        val row = StringBuilder(1024)
        row.append(analysis.timestampNanos / 1_000_000).append(',')
        row.append(label).append(',')
        row.append(if (analysis.isPresent) 1 else 0).append(',')
        row.append(if (analysis.detected) 1 else 0)
        for (score in analysis.scores) row.append(',').append(score)
        for (value in analysis.mapped) row.append(',').append(value)
        row.append(',').append(analysis.headYaw).append(',').append(analysis.headPitch)
        row.append(',').append(analysis.gazeYaw).append(',').append(analysis.gazePitch)
        row.append(',').append(analysis.eyesUsed)
        row.append(',').append(analysis.tongueOutScore)
        row.append(',').append(analysis.processingNanos / 1e6)
        row.append('\n')
        writer.write(row.toString())
        rows++
    }

    @get:Synchronized
    val rowCount: Int get() = rows

    @Synchronized
    override fun close() = writer.close()

    companion object {
        val HEADER: List<String> = listOf("time_ms", "label", "present", "detected") +
            Blendshapes.NAMES.map { "score_$it" } +
            BlendshapeValues.NAMES.map { "mapped_$it" } +
            listOf("headYaw", "headPitch", "gazeYaw", "gazePitch", "eyesUsed", "tongueOutModel", "processing_ms")
    }
}
