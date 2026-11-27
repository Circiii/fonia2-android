package tech.fonia.ml

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import tech.fonia.judge.Head
import tech.fonia.judge.JudgeRequest
import tech.fonia.judge.JudgeSpec
import tech.fonia.judge.Level
import tech.fonia.judge.Position
import tech.fonia.judge.TakeJudge
import tech.fonia.judge.TalkerEstimator
import tech.fonia.judge.TalkerGroup
import tech.fonia.live.Clip
import tech.fonia.model.SoundId

/**
 * The whole judge with the phoneme model on the device (v2): the committed spec files, ZIPA from this test
 * APK's assets, a 48 kHz take → `measure` + `ZipaHost.score` + `decide`; the FULL head must decide. Logs the time of
 * each stage under `FoniaMl` (`adb logcat -d -s FoniaMl`). No UI.
 */
@RunWith(AndroidJUnit4::class)
class JudgeOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().context

    private fun log(message: String) {
        Log.i("FoniaMl", message)
        println("FoniaMl: $message")
    }

    private fun spec(): JudgeSpec {
        fun read(path: String) = context.assets.open("spec/$path").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val fusion = SoundId.entries.mapNotNull { runCatching { read("fusion/${JudgeSpec.fusionFileName(it)}") }.getOrNull() }
        return JudgeSpec.fromStrings(read("thresholds/population.json"), fusion, read("unlock.json"), read("feedback.json"))
    }

    /** An MP3 asset decoded to mono float PCM (MediaExtractor + MediaCodec), padded with 250 ms of faint noise. */
    private fun decode(asset: String): Clip? {
        val afd = runCatching { context.assets.openFd(asset) }.getOrNull() ?: return null
        val extractor = android.media.MediaExtractor()
        extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        val format = extractor.getTrackFormat(0)
        extractor.selectTrack(0)
        val rate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
        val codec = android.media.MediaCodec.createDecoderByType(format.getString(android.media.MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        val out = ArrayList<Float>()
        val info = android.media.MediaCodec.BufferInfo()
        var inputDone = false
        while (true) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o >= 0) {
                val buf = codec.getOutputBuffer(o)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val frames = buf.remaining() / channels
                for (k in 0 until frames) out += buf.get(k * channels) / 32768f
                codec.releaseOutputBuffer(o, false)
                if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        codec.stop(); codec.release(); extractor.release(); afd.close()
        var seed = 5L
        val pad = FloatArray(rate / 4) { seed = seed * 6364136223846793005L + 1L; (((seed ushr 33) % 2000) / 1000f - 1f) * 0.0003f }
        return Clip(pad + out.toFloatArray() + pad, rate.toDouble(), audioSource = "device-test")
    }

    @Test
    fun theFullHeadDecidesOnTheDevice() = runBlocking {
        val judge = TakeJudge(spec())
        val host = ZipaHost.create(context)
        val w0 = System.nanoTime()
        host.warmUp()
        val warmMs = (System.nanoTime() - w0) / 1e6
        val takes = listOf(
            Triple("sare", SoundId.S, "sare"), Triple("șarpe", SoundId.Sh, "șarpe"), Triple("rac", SoundId.R, "rac"), Triple("ger", SoundId.Ge, "ger"),
        ).mapNotNull { (file, sound, text) -> decode("judge-takes/$file.mp3")?.let { Triple(it, sound, text) } }
        org.junit.Assume.assumeTrue("no content takes packaged (content submodule missing)", takes.isNotEmpty())
        val measure = ArrayList<Double>()
        val score = ArrayList<Double>()
        val decide = ArrayList<Double>()
        val talker = ArrayList<Double>()
        val heads = ArrayList<Head>()
        repeat(5) { round ->
            for ((clip, sound, text) in takes) {
                val request = JudgeRequest(sound, Level.Word, text, Position.Initial, TalkerGroup.Woman)
                val t0 = System.nanoTime()
                val m = judge.measure(request, clip)
                val t1 = System.nanoTime()
                val phones = host.score(clip, request, timeoutMs = 10_000)
                val t2 = System.nanoTime()
                assertNotNull("ZIPA gave no evidence on $text", phones)
                val r = judge.decide(m, phones)
                val t3 = System.nanoTime()
                val est = TalkerEstimator.estimate(clip)
                val t4 = System.nanoTime()
                measure += (t1 - t0) / 1e6; score += (t2 - t1) / 1e6; decide += (t3 - t2) / 1e6; talker += (t4 - t3) / 1e6
                heads += r.head
                if (round == 0) log("$text: ${r.verdict} p=${"%.2f".format(r.confidence)} head=${r.head} fast=${r.fastCorrect} f0=${est.f0Hz} hint=${r.visual?.hint}")
            }
        }
        fun p50(v: List<Double>) = v.sorted()[v.size / 2]
        fun max(v: List<Double>) = v.maxOrNull() ?: 0.0
        val total = measure.indices.map { measure[it] + score[it] + decide[it] }
        log(
            "judge on ${Build.MODEL} (${Build.SUPPORTED_ABIS.first()}): warm-up ${"%.0f".format(warmMs)} ms; per take (${measure.size}) " +
                "measure p50 ${"%.0f".format(p50(measure))} ms, ZIPA score p50 ${"%.0f".format(p50(score))} ms (max ${"%.0f".format(max(score))}), " +
                "decide p50 ${"%.1f".format(p50(decide))} ms, talker estimate p50 ${"%.1f".format(p50(talker))} ms; " +
                "measure+score+decide p50 ${"%.0f".format(p50(total))} ms, max ${"%.0f".format(max(total))} ms; heads ${heads.groupingBy { it }.eachCount()}",
        )
        org.junit.Assert.assertTrue("the full head decided too few takes: $heads", heads.count { it == Head.Full } * 2 >= heads.size)
        host.close()
    }
}
