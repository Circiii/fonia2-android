package tech.fonia.speech

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Not iOS tests: the WhisperIntegrationTests need the real model (speech/PARITY.md). These run the
 * same properties through the ported session and engine over [FakeWhisper], so the decoding loop,
 * the shared prefixes, the forced scores, the windows and the queue are checked before the Android
 * runtime exists.
 */
@DisplayName("Whisper port over a scripted runtime")
class WhisperSeamTests {
    private val folder = SpeechPaths.temporaryFolder()

    @AfterEach
    fun cleanUp() {
        folder.deleteRecursively()
    }

    /** The level of a clip says which text it holds; near silence says nothing. */
    private val sayings = mapOf(0.2f to " ship", 0.3f to " sheep", 0.4f to " think", 0.5f to " The ship is here")

    private val whisper = FakeWhisper { audio ->
        var level = 0f
        for (x in audio) level = maxOf(level, abs(x))
        sayings.entries.firstOrNull { abs(it.key - level) < 1e-6f }?.value
    }

    private fun clip(seconds: Double, level: Float) = FloatArray((seconds * 16_000).toInt()) { level }

    private fun engine(suppress: List<Int> = emptyList()) = WhisperEngine(modelFolder = whisper.modelFolder(folder, suppress), loader = whisper)

    @Test
    @DisplayName("transcribes greedily, with word times that move forward")
    fun transcribes() {
        val heard = runSuspend { engine().transcribe(clip(2.0, 0.5f), SpeechLanguage.EN) }
        assertEquals("The ship is here", heard.text)
        assertEquals(listOf("The", "ship", "is", "here"), heard.words.map { it.word })
        assertEquals(heard.words.map { it.start }.sorted(), heard.words.map { it.start })
        assertTrue(heard.words.all { it.end > it.start })
        assertTrue(heard.noSpeechProb < 0.01)
        assertEquals(1, heard.cost.encoderPasses)
    }

    @Test
    @DisplayName("ship said right passes against sheep")
    fun pairRight() {
        val score = runSuspend { engine().scorePair(clip(1.0, 0.2f), target = "ship", rival = "sheep", language = SpeechLanguage.EN) }
        val outcome = SpeechJudge.judgePair(score)
        assertTrue(outcome.wordHeard)
        assertTrue(score.pTarget > 0.8)
    }

    @Test
    @DisplayName("sheep when ship was asked sounds like the rival")
    fun pairWrong() {
        val score = runSuspend { engine().scorePair(clip(1.0, 0.3f), target = "ship", rival = "sheep", language = SpeechLanguage.EN) }
        assertTrue(SpeechJudge.judgePair(score) is PairOutcome.Rival)
    }

    @Test
    @DisplayName("a pair costs one encoder pass, and shared token prefixes cost one decoder step")
    fun onePass() {
        val score = runSuspend { engine().scorePair(clip(1.0, 0.2f), target = "ship", rival = "sheep", language = SpeechLanguage.EN) }
        assertEquals(1, score.cost.encoderPasses)
        assertEquals(1, whisper.encoderPasses)
        // 4 prefix steps + 1 greedy step + 6 spellings, of which "Sheep." and "Ship." reuse "Sheep" and "Ship".
        assertEquals(4 + 1 + 6, score.cost.decoderSteps)
    }

    @Test
    @DisplayName("a text scores the same alone and in a batch, so reused decoder state is clean")
    fun reuseIsClean() {
        val texts = listOf("sheep", "Sheep", "Sheep.", "ship", "Ship", "Ship.")
        val engine = engine()
        val together = runSuspend { engine.scoreTexts(clip(1.0, 0.2f), SpeechLanguage.EN, texts) }
        for ((k, text) in texts.withIndex()) {
            val alone = runSuspend { engine.scoreTexts(clip(1.0, 0.2f), SpeechLanguage.EN, listOf(text)) }.single()
            assertEquals(text, together[k].text)
            assertTrue(abs(alone.logp - together[k].logp) < 0.02)
            assertEquals(alone.tokens.map { it.id }, together[k].tokens.map { it.id })
        }
    }

    @Test
    @DisplayName("forced tokens get real, negative log-probabilities, which prefix forcing cannot give")
    fun forcedScores() {
        val score = runSuspend { engine().scoreTexts(clip(1.0, 0.4f), SpeechLanguage.EN, listOf("sink")) }.single()
        assertTrue(score.tokens.size >= 2)
        assertTrue(score.tokens.all { it.logp < 0 })
        assertEquals(whisper.tokenizer.specialTokens.endToken, score.tokens.last().id)
        assertTrue(score.logp < -0.5)
    }

    @Test
    @DisplayName("says nothing was heard on near silence")
    fun silence() {
        val score = runSuspend { engine().scorePair(quietNoise(), target = "ship", rival = "sheep", language = SpeechLanguage.EN) }
        assertEquals(PairOutcome.NotHeard, SpeechJudge.judgePair(score))
        assertTrue(score.noSpeechProb > 0.9)
    }

    @Test
    @DisplayName("handles an empty clip")
    fun emptyClip() {
        val heard = runSuspend { engine().transcribe(FloatArray(0), SpeechLanguage.EN) }
        assertTrue(heard.words.isEmpty())
    }

    @Test
    @DisplayName("sentence: forced words keep a place in the take")
    fun sentence() {
        val score = runSuspend { engine().scoreSentence(clip(2.0, 0.5f), target = "The ship is here.", language = SpeechLanguage.EN) }
        val outcome = SpeechJudge.judgeSentence(score)
        assertEquals(SentenceOutcome.Result.ALL_HEARD, outcome.result)
        assertEquals(listOf("The", "ship", "is", "here"), outcome.words.map { it.word })
        assertTrue(outcome.words.all { it.end > it.start })
    }

    @Test
    @DisplayName("cuts long audio into windows and moves their words to clip time")
    fun windows() {
        val rate = 16_000
        val samples = FloatArray(40 * rate) { if (it < 25 * rate) 0.2f else 0.3f }
        for (i in (25 * rate) until (25 * rate + rate / 2)) samples[i] = 0f
        val heard = runSuspend { engine().transcribe(samples, SpeechLanguage.EN) }
        val cut = TokenTiming.windows(samples)[0].upperBound
        assertEquals("ship sheep", heard.text)
        assertEquals(2, heard.cost.encoderPasses)
        assertEquals(listOf("ship", "sheep"), heard.words.map { it.word })
        assertTrue(heard.words[0].end <= cut / 16_000.0 + 1e-9)
        assertTrue(heard.words[1].start >= cut / 16_000.0 - 1e-9)
    }

    @Test
    @DisplayName("scoring alone reads only the first 30 s")
    fun scoresFirstWindowOnly() {
        val scores = runSuspend { engine().scoreTexts(clip(40.0, 0.2f), SpeechLanguage.EN, listOf("ship")) }
        assertEquals(1, whisper.encoderPasses)
        assertEquals(1, scores.size)
    }

    @Test
    @DisplayName("a verbatim transcript starts from the disfluent prompt")
    fun verbatim() {
        val heard = runSuspend { engine().transcribe(clip(1.0, 0.2f), SpeechLanguage.RO, verbatim = true) }
        val specials = whisper.tokenizer.specialTokens
        val fed = whisper.decoderHistories.single()
        assertEquals(specials.startOfPreviousToken, fed.first())
        val prompt = whisper.tokenizer.encode(WhisperSession.verbatimPrompt(SpeechLanguage.RO))
        assertEquals(prompt, fed.subList(1, 1 + prompt.size))
        assertEquals(listOf(specials.startOfTranscriptToken, whisper.tokenizer.id("<|ro|>"), specials.transcribeToken, specials.noTimestampsToken), fed.subList(1 + prompt.size, 5 + prompt.size))
        assertEquals("ship", heard.text)
    }

    @Test
    @DisplayName("never writes a suppressed token")
    fun suppressed() {
        val ship = whisper.tokenizer.id(" ship")
        val heard = runSuspend { engine(suppress = listOf(ship)).transcribe(clip(1.0, 0.2f), SpeechLanguage.EN) }
        assertFalse(heard.text.contains("ship"))
    }

    @Test
    @DisplayName("unloads and loads again on the next request")
    fun unloads() {
        val engine = engine()
        runSuspend { engine.load() }
        assertTrue(engine.isLoaded)
        runSuspend { engine.unload() }
        assertFalse(engine.isLoaded)
        assertEquals(1, whisper.unloads)
        val heard = runSuspend { engine.transcribe(clip(1.0, 0.2f), SpeechLanguage.EN) }
        assertTrue(engine.isLoaded)
        assertEquals(2, whisper.loads)
        assertEquals(listOf("ship"), SpeechText.words(heard.text))
    }

    @Test
    @DisplayName("the store prewarms and builds an engine once the model is in place")
    fun storeMakesEngine() {
        val modelFolder = whisper.modelFolder(folder)
        val manifest = SpeechModelManifest(
            name = modelFolder.name,
            files = listOf("generation_config.json", "tokenizer.json").map {
                val file = File(modelFolder, it)
                SpeechModelFile(url = file.toURI(), path = it, byteCount = file.length(), sha256 = SpeechModelStore.sha256(file))
            },
        )
        val store = SpeechModelStore(manifest = manifest, root = folder, loader = whisper)
        assertTrue(store.isReady)
        runSuspend { store.prewarm() }
        assertEquals(1, whisper.prewarms)
        val heard = runSuspend { store.makeEngine().transcribe(clip(1.0, 0.3f), SpeechLanguage.EN) }
        assertEquals("sheep", heard.text)
    }

    @Test
    @DisplayName("requests run one at a time, in the order they arrive")
    fun queue() {
        val queue = RequestQueue()
        val log = Collections.synchronizedList(mutableListOf<String>())
        val gate = CountDownLatch(1)
        val first = thread {
            runSuspend {
                queue.serial {
                    log.add("a in")
                    gate.await()
                    log.add("a out")
                }
            }
        }
        while (!log.contains("a in")) Thread.sleep(1)
        val others = listOf("b", "c").map { name ->
            val waiter = thread { runSuspend { queue.serial { log.add("$name in"); log.add("$name out") } } }
            Thread.sleep(50)
            waiter
        }
        assertEquals(listOf("a in"), log.toList())
        gate.countDown()
        first.join()
        others.forEach { it.join() }
        assertEquals(listOf("a in", "a out", "b in", "b out", "c in", "c out"), log.toList())
    }
}
