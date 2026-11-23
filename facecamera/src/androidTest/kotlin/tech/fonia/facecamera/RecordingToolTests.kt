package tech.fonia.facecamera

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.fonia.face.BlendshapeValues
import java.io.File

/**
 * The recording tool end to end on the device: analysed frames (here the five stills, labelled with the pose each
 * shows) into a CSV in the app's own storage, read back. The debug screen does the same with the camera.
 */
@RunWith(AndroidJUnit4::class)
class RecordingToolTests {
    @Test
    fun recordsScoresAndMappedValues() {
        val pipeline = TestPipeline.pipeline
        val labels = mapOf("smile" to "smile", "speaking" to "relaxed", "wide_open" to "wideOpen", "pout" to "lipBite", "small_smile" to "smile")
        val file = File(targetContext.getExternalFilesDir(null), "face-recordings/test.csv").apply { parentFile!!.mkdirs() }
        val analysis = FaceAnalysis()
        val expected = mutableListOf<FloatArray>()
        FaceRecording(file).use { recording ->
            for ((k, name) in FACE_NAMES.withIndex()) {
                assertTrue(pipeline.process(AndroidFixtures.frame(name), 0, stream = false))
                val observation = pipeline.observation(BlendshapeCalibration.IDENTITY, FloatArray(7))
                analysis.fill(pipeline, observation, timestamp = k * 33_000_000L, nanos = pipeline.timings[4])
                recording.write(labels.getValue(name), analysis)
                expected.add(pipeline.scores.copyOf())
            }
        }
        val lines = file.readLines()
        report("recording: ${file.absolutePath}, ${lines.size - 1} rows of ${lines[0].split(",").size} columns")
        val header = lines[0].split(",")
        assertEquals(FaceRecording.HEADER, header)
        assertEquals(FACE_NAMES.size, lines.size - 1)
        for ((k, line) in lines.drop(1).withIndex()) {
            val row = line.split(",")
            assertEquals(labels.getValue(FACE_NAMES[k]), row[1])
            assertEquals("1", row[2])
            val scores = FloatArray(52) { row[4 + it].toFloat() }
            assertEquals(0f, maxAbsDiff(expected[k], scores), 0f)
            // Identity mapping: each mapped coefficient is its score; tongueOut is 0.
            for ((i, name) in BlendshapeValues.NAMES.withIndex()) {
                val mapped = row[header.indexOf("mapped_$name")].toFloat()
                val source = Blendshapes.NAMES.indexOf(name)
                assertEquals(name, if (source < 0) 0f else scores[source], mapped, 0f)
            }
        }
    }
}
