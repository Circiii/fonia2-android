package tech.fonia.ml

import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.fonia.judge.JudgeRequest
import tech.fonia.judge.Level
import tech.fonia.judge.Position
import tech.fonia.live.Clip
import tech.fonia.model.SoundId
import tech.fonia.phones.ZipaModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * ZIPA from the test APK's assets on the device: mapped without a copy, the same transcription of the authors' sample
 * as on the PC (when the build found `sample.wav` in the cache), and the full-head time per take. No UI. Results go to
 * logcat under `FoniaMl` (`adb logcat -d -s FoniaMl`).
 */
@RunWith(AndroidJUnit4::class)
class ZipaDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().context

    private fun log(message: String) {
        Log.i("FoniaMl", message)
        println("FoniaMl: $message")
    }

    private val entry = ModelRegistry.load(context.assets).entry(ModelRegistry.ZIPA)

    /** -Pfonia.ml.modelOnDevice=true builds the test APK without the model; it is then read from the pushed file. */
    private val inApk = runCatching { context.assets.openFd(entry.asset).close() }.isSuccess

    private fun openBuffer(): ByteBuffer =
        if (inApk) {
            val opened = AssetModelFile.open(context, entry)
            assertTrue("the model must be stored uncompressed and mapped, got ${opened.source}", opened.mapped)
            opened.buffer
        } else {
            val path = InstrumentationRegistry.getArguments().getString("foniaZipaModel")!!
            log("model not in this test APK (-Pfonia.ml.modelOnDevice): mapping $path")
            java.io.RandomAccessFile(path, "r").use { it.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, it.length()) }
        }

    @Test
    fun mapsTheModelFromTheApkAndMatchesThePcTranscription() {
        val buffer = openBuffer()
        assertEquals(entry.bytes, buffer.capacity().toLong())
        val sample = runCatching { context.assets.open("zipa-reference/sample.wav").use { it.readBytes() } }.getOrNull()
        val t0 = System.nanoTime()
        ZipaModel.load(buffer, intraOpThreads = 2).use { model ->
            log("cold session ${"%.0f".format((System.nanoTime() - t0) / 1e6)} ms on ${Build.MODEL} (${Build.SUPPORTED_ABIS.first()})")
            if (sample == null) {
                log("zipa-reference/sample.wav not packaged: parity check skipped")
                return
            }
            val pcm = wav16(sample)
            val ours = model.posteriors(pcm).greedy().joinToString(" ")
            val head = "▁ m ɪ s t ə ˞ k w ɪ l t ə ˞ ɪ z ð ə ə p ɑ s t ə l ʌ v ð ə m ɪ d ə l k l æ s ə z ə n d w i ɑ ɹ g l " +
                "æ d t u w ɛ l k ə m h ɪ z g"
            log("sample.wav: $ours")
            assertTrue("Android ORT transcription differs from the published one: $ours", ours == "$head ɔ ɑ s p ə l" || ours == "$head ɑ s p ə l")
        }
    }

    @Test
    fun fullHeadTimePerTake() = runBlocking {
        val pssBefore = Debug.getPss()
        val host = if (inApk) ZipaHost.create(context) else ZipaHost(entry.modelVersion) { threads ->
            val model = ZipaModel.load(openBuffer(), threads)
            model to model
        }
        val t0 = System.nanoTime()
        host.warmUp()
        val warmMs = (System.nanoTime() - t0) / 1e6
        val pssWarm = Debug.getPss()
        assertEquals(HostState.Warm, host.state.value)
        // A 1.2 s take at 48 kHz (the capture rate): a voiced vowel–hiss–vowel shape, enough for the whole chain.
        val rate = 48_000.0
        val samples = FloatArray((1.2 * rate).toInt()) { i ->
            val t = i / rate
            val voiced = if (t < 0.45 || t > 0.65) 0.2 * sin(2 * PI * 210 * t) + 0.1 * sin(2 * PI * 630 * t) else 0.0
            val hiss = if (t in 0.45..0.65) ((i * 1103515245L + 12345L) % 1000 / 1000.0 - 0.5) * 0.2 else 0.0
            (voiced + hiss).toFloat()
        }
        val request = JudgeRequest(SoundId.S, Level.Word, "casă", Position.Medial)
        val totals = ArrayList<Double>()
        repeat(20) {
            val s0 = System.nanoTime()
            val evidence = host.score(Clip(samples, rate), request, timeoutMs = 10_000)
            totals += (System.nanoTime() - s0) / 1e6
            assertNotNull(evidence)
        }
        totals.sort()
        val t = host.lastTimings
        log(
            "warm-up ${"%.0f".format(warmMs)} ms (load ${"%.0f".format(t.loadMs)} ms), threads ${ZipaHostConfig().intraOpThreads}, " +
                "per take p50 ${"%.0f".format(totals[10])} ms, p95 ${"%.0f".format(totals[18])} ms, max ${"%.0f".format(totals.last())} ms " +
                "(last: resample ${"%.1f".format(t.resampleMs)}, model ${"%.1f".format(t.modelMs)}, scoring ${"%.2f".format(t.scoringMs)} ms); " +
                "PSS ${pssBefore / 1024} MB -> ${pssWarm / 1024} MB warm",
        )
        host.trim()
        host.warmUp()
        assertEquals(HostState.Warm, host.state.value)
        host.close()
    }

    private fun wav16(bytes: ByteArray): FloatArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var channels = 1
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = b.getInt(offset + 4)
            if (id == "fmt ") channels = b.getShort(offset + 10).toInt()
            if (id == "data") return FloatArray(size / 2 / channels) { b.getShort(offset + 8 + it * 2 * channels) / 32768f }
            offset += 8 + size + (size and 1)
        }
        error("no data chunk")
    }
}
