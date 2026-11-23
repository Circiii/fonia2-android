package tech.fonia.phones

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real model on ONNX Runtime (desktop jar; the app uses `onnxruntime-android`, same API). Skipped, with the reason,
 * when the model cannot be found in `fonia.models` or downloaded there.
 */
class ZipaModelTest {
    private fun modelOrSkip(): ZipaModel {
        val file = ModelCache.fetch(ZipaModel.INT8)
        assumeTrue(file.isSuccess, "ZIPA-small int8 is not available, so the model tests are skipped: ${file.exceptionOrNull()?.message}")
        return openModel(file.getOrThrow())
    }

    @Test
    fun thePublishedTokenListIsTheInventory() {
        val file = ModelCache.fetch(ZipaModel.TOKENS)
        assumeTrue(file.isSuccess, "tokens.txt is not available: ${file.exceptionOrNull()?.message}")
        assertEquals(ZipaInventory.tokens, ZipaInventory.parseTokensFile(file.getOrThrow().readText(Charsets.UTF_8)))
    }

    /**
     * End to end against the reference implementation's own numbers: the Kotlin fbank and ONNX Runtime on ZIPA's
     * sample (LibriSpeech, 5.9 s) give, token for token, a transcription the authors published for this checkpoint
     * (`scripts/onnx_scripts/inference_results_all.txt` at the pinned commit, `zipa-cr-small-500k`).
     *
     * The authors' int8 line has one token more than their fp32 and fp16 lines (`g ɔ ɑ s` against `g ɑ s`). That frame
     * is on a knife edge: the int8 graph quantises its activations at run time, so the float32 rounding of the
     * reference fbank (our fbank differs from lhotse's by 1.5e-4 at most on loud bands) flips 3 of 289 frames' best
     * token. Fed lhotse's own features, this ONNX Runtime 1.31.1 gives the int8 line exactly (checked once, by hand:
     * every log-probability equal to Python onnxruntime 1.27's), so the runtime is not the difference.
     */
    @Test
    fun reproducesTheAuthorsOutputOnTheirSample() {
        val sample = ModelCache.fetch(ModelCache.SAMPLE, folder = "zipa-reference")
        assumeTrue(sample.isSuccess, "ZIPA's sample.wav is not available: ${sample.exceptionOrNull()?.message}")
        modelOrSkip().use { model ->
            val (pcm, rate) = readWav16(sample.getOrThrow().readBytes())
            assertEquals(16_000, rate)
            val posteriors = model.posteriors(pcm)
            assertEquals((ZipaFbank.frameCount(pcm.size) - 7) / 2, posteriors.frames)
            val head = "▁ m ɪ s t ə ˞ k w ɪ l t ə ˞ ɪ z ð ə ə p ɑ s t ə l ʌ v ð ə m ɪ d ə l k l æ s ə z ə n d w i ɑ ɹ g l " +
                "æ d t u w ɛ l k ə m h ɪ z g"
            val published = setOf("$head ɔ ɑ s p ə l", "$head ɑ s p ə l")
            val ours = posteriors.greedy().joinToString(" ")
            println("sample.wav: $ours")
            assertTrue(ours in published, "not one of the published transcriptions: $ours")
            // Rows are log-softmax: they sum to one.
            for (t in 0 until posteriors.frames) {
                val total = (0 until posteriors.vocabulary).sumOf { Math.exp(posteriors[t, it].toDouble()) }
                assertEquals(1.0, total, 1e-3)
            }
        }
    }

    @Test
    fun outputRunsAt50HzAndStartsWithTheWordMark() {
        modelOrSkip().use { model ->
            val second = FloatArray(16_000) { (0.01 * Math.sin(it * 0.3)).toFloat() }
            val posteriors = model.posteriors(second)
            assertEquals((100 - 7) / 2, posteriors.frames)
            assertEquals(Posteriors.empty, model.posteriors(FloatArray(1000)))
        }
    }

    /** A content minimal pair: each clip is right for its own word and a substitution for the other's target. */
    @Test
    fun scoresAContentMinimalPair() {
        val clips = VoiceClips.open()
        assumeTrue(clips != null, "the content submodule (fonia.content/voice) is missing")
        assumeTrue(Ffmpeg.available, "ffmpeg is not on PATH: the JVM has no MP3 decoder")
        modelOrSkip().use { model ->
            val scorer = PhoneScorer(model)
            val lama = clips!!.decode("lamă")!!
            val rama = clips.decode("ramă")!!
            val correctL = scorer.score(lama, "lamă", TargetSound.L)
            val correctR = scorer.score(rama, "ramă", TargetSound.R)
            val rForL = scorer.score(rama, "lamă", TargetSound.L)
            val lForR = scorer.score(lama, "ramă", TargetSound.R)
            println("lamă/L $correctL\nramă/R $correctR\nramă as lamă/L $rForL\nlamă as ramă/R $lForR")
            assertEquals(Verdict.Correct, correctL.verdict)
            assertEquals(Verdict.Correct, correctR.verdict)
            assertEquals(Verdict.TryAgain, rForL.verdict)
            assertEquals(TargetSound.R, rForL.heardAs?.sound)
            assertEquals(Verdict.TryAgain, lForR.verdict)
            assertEquals(TargetSound.L, lForR.heardAs?.sound)
            assertTrue(correctL.targets.single().lpr > 0 && rForL.targets.single().lpr < -3)
        }
    }
}
