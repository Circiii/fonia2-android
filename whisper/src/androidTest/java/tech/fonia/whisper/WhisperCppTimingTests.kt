package tech.fonia.whisper

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not iOS tests: where a request's time goes on this device (one encoder pass against one decoder step),
 * the memory a loaded model holds, and that each step's alignment row is a distribution over the audio.
 */
@RunWith(AndroidJUnit4::class)
class WhisperCppTimingTests {
    @Test
    fun encoderAndDecoderStepCosts(): Unit = runBlocking {
        val folder = SpeechPaths.modelFolder
        assumeTrue(folder != null)
        val before = residentKilobytes()
        val runtime = WhisperCppLoader().load(folder!!) as WhisperCppRuntime
        val loaded = residentKilobytes()
        try {
            val window = fixtureSamples("en-ship").copyOf(runtime.windowSamples)
            val encodes = (0 until 3).map {
                val started = System.nanoTime()
                runtime.encodeFeatures(window)
                (System.nanoTime() - started) / 1e6
            }
            val encoded = runtime.encodeFeatures(window)!!
            val decoder = runtime.makeDecoder(encoded)!!
            val prefix = listOf(50258, 50259, 50359, 50363)
            val steps = mutableListOf<Double>()
            var row: FloatArray? = null
            for (round in 0 until 3) {
                for ((index, token) in prefix.withIndex()) {
                    val started = System.nanoTime()
                    val output = decoder.predictLogits(token, index)
                    steps += (System.nanoTime() - started) / 1e6
                    assertEquals(51865, output.logits.size)
                    row = output.alignmentWeights
                }
            }
            val peak = residentKilobytes()
            val alignment = row!!
            assertEquals(1500, alignment.size)
            val sum = alignment.sum()
            assertTrue("alignment row sums to $sum", sum in 0.999f..1.001f)
            assertTrue(alignment.all { it >= 0f })
            val message = "encoder pass ms ${encodes.map { "%.0f".format(it) }}, decoder step ms median ${"%.1f".format(steps.sorted()[steps.size / 2])} " +
                "(min ${"%.1f".format(steps.min())}, max ${"%.1f".format(steps.max())}), resident memory before load ${before / 1024} MB, " +
                "after load ${loaded / 1024} MB, after encoding and decoding ${peak / 1024} MB, library ${WhisperCppNative.library}"
            Log.i(TAG, message)
        } finally {
            runtime.unload()
        }
    }

    private fun residentKilobytes(): Long =
        File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }.split(Regex("\\s+"))[1].toLong()

    companion object {
        const val TAG = "FoniaWhisperTiming"
    }
}
