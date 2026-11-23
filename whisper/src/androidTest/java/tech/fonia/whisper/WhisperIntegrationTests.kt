package tech.fonia.whisper

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tech.fonia.speech.InferenceCost
import tech.fonia.speech.PairOutcome
import tech.fonia.speech.SpeechJudge
import tech.fonia.speech.SpeechLanguage
import tech.fonia.speech.SpeechModelStore
import tech.fonia.speech.SpeechReport
import tech.fonia.speech.SpeechText
import tech.fonia.speech.WhisperEngine
import tech.fonia.speech.WordStatus
import tech.fonia.speech.judgeSentence
import tech.fonia.speech.speechReport
import java.io.File
import kotlin.math.abs

/**
 * FoniaSpeechTests/WhisperIntegrationTests.swift, suite "Whisper on real audio": the real model on the
 * device's CPU through whisper.cpp, with the same fixtures, inputs and expectations as iOS. Skipped when
 * the instrumentation argument `foniaWhisperModel` names no model (iOS: FONIA_WHISPER_MODEL unset).
 * JUnit runs a class's tests one at a time, as Swift's `.serialized` does.
 *
 * The harness also writes results.jsonl, as iOS does for tools/eval_speech, and compares it with the
 * iOS rows of tools/eval_speech/report.md (assets/eval_speech/ios_report.json): see [ParityReport].
 */
@RunWith(AndroidJUnit4::class)
class WhisperIntegrationTests {
    private data class PairCase(
        val fixture: String,
        val target: String,
        val rival: String,
        val contrast: String?,
        /** What `site/scripts/asr-check.ts` expects, kept to compare against rather than to gate on. */
        val webExpectsTarget: Boolean,
    )

    private data class SentenceCase(val fixture: String, val target: String, val webExpectsHeard: Boolean)

    @Before
    fun needsModel() {
        assumeTrue("set foniaWhisperModel to a model folder (whisper/build.gradle.kts does)", SpeechPaths.modelFolder != null)
    }

    private fun modelFolder(): File = SpeechPaths.modelFolder!!

    private fun engine(): WhisperEngine = Shared.engine(modelFolder())

    @Test(timeout = 30L * 60L * 1000L)
    fun harness() = runBlocking {
        // "evaluates every fixture and writes the raw results"
        val folder = modelFolder()
        val store = SpeechModelStore(
            manifest = WhisperCppModel.WHISPER_BASE_FROM_HUGGING_FACE,
            root = folder.parentFile!!,
            loader = WhisperCppLoader(),
        )
        assertTrue(store.isReady)

        var started = System.nanoTime()
        store.prewarm()
        val prewarmSeconds = seconds(since = started)

        val engine = store.makeEngine()
        started = System.nanoTime()
        engine.load()
        val loadSeconds = seconds(since = started)

        val warmup = engine.scorePair(fixtureSamples("en-ship"), target = "ship", rival = "sheep", language = SpeechLanguage.EN)
        val warm = engine.scorePair(fixtureSamples("en-ship"), target = "ship", rival = "sheep", language = SpeechLanguage.EN)

        val rows = mutableListOf<JsonObject>()
        rows.add(
            row(
                "kind" to "meta",
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "system" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.first()}",
                "cores" to Runtime.getRuntime().availableProcessors(),
                "threads" to WhisperCppThreads.preferred(),
                "library" to WhisperCppNative.library,
                "ggml" to WhisperCppNative.systemInfo().trim(),
                "prewarmSeconds" to prewarmSeconds,
                "loadSeconds" to loadSeconds,
                "firstClipMs" to warmup.cost.milliseconds,
                "secondClipMs" to warm.cost.milliseconds,
            ),
        )

        for (item in PAIR_CASES) {
            val audio = samples(item.fixture)
            val (score, cost) = medianTiming {
                val score = engine.scorePair(audio, target = item.target, rival = item.rival, language = SpeechLanguage.EN)
                score to score.cost
            }
            val outcome = SpeechJudge.judgePair(score, contrast = item.contrast)
            rows.add(
                row(
                    "kind" to "pair",
                    "fixture" to item.fixture,
                    "target" to item.target,
                    "rival" to item.rival,
                    "contrast" to item.contrast,
                    "heard" to score.heard,
                    "targetLogp" to (if (score.same) null else score.targetLogp),
                    "rivalLogp" to (if (score.same) null else score.rivalLogp),
                    "heardLogp" to score.heardLogp,
                    "noSpeechProb" to score.noSpeechProb,
                    "margin" to score.margin,
                    "pTarget" to score.pTarget,
                    "outcome" to name(outcome),
                    "webExpectsTarget" to item.webExpectsTarget,
                    "ms" to cost.milliseconds,
                    "encoderPasses" to cost.encoderPasses,
                    "decoderSteps" to cost.decoderSteps,
                ),
            )
        }

        for (item in SENTENCE_CASES) {
            val audio = samples(item.fixture)
            val (score, cost) = medianTiming {
                val score = engine.scoreSentence(audio, target = item.target, language = SpeechLanguage.EN)
                score to score.cost
            }
            val outcome = SpeechJudge.judgeSentence(score)
            rows.add(
                row(
                    "kind" to "sentence",
                    "fixture" to item.fixture,
                    "target" to item.target,
                    "heard" to outcome.heard,
                    "result" to outcome.result.rawValue,
                    "score" to outcome.score,
                    "wordsHeard" to outcome.wordsHeard,
                    "webExpectsHeard" to item.webExpectsHeard,
                    "words" to JsonArray(
                        outcome.words.map {
                            row("word" to it.word, "status" to it.status.rawValue, "index" to it.index, "heard" to it.heard, "prob" to it.prob, "start" to it.start, "end" to it.end)
                        },
                    ),
                    "ms" to cost.milliseconds,
                    "encoderPasses" to cost.encoderPasses,
                    "decoderSteps" to cost.decoderSteps,
                ),
            )
        }

        for ((fixture, language) in listOf("en-speech" to SpeechLanguage.EN, "ro-speech" to SpeechLanguage.RO)) {
            val lexicon = if (language == SpeechLanguage.EN) TestLexicon.EN else TestLexicon.RO
            val audio = samples(fixture)
            val (heard, cost) = medianTiming {
                val heard = engine.transcribe(audio, language = language, verbatim = true)
                heard to heard.cost
            }
            val report = SpeechJudge.speechReport(heard, language = language, lexicon = lexicon)
            rows.add(
                row(
                    "kind" to "speech",
                    "fixture" to fixture,
                    "language" to language.rawValue,
                    "text" to heard.text,
                    "words" to JsonArray(heard.words.map { row("word" to it.word, "start" to it.start, "end" to it.end, "prob" to it.prob) }),
                    "wordsPerMinute" to report.wordsPerMinute,
                    "pace" to report.pace.rawValue,
                    "speakingSeconds" to report.speakingSeconds,
                    "fillerCounts" to JsonObject(report.fillerCounts.toSortedMap().mapValues { JsonPrimitive(it.value) }),
                    "hesitationsPer100Words" to report.hesitationsPer100Words,
                    "repeats" to report.repeats.size,
                    "unclear" to JsonArray(report.unclear.map { JsonPrimitive(it.word) }),
                    "pauses" to report.pauses.size,
                    "score" to report.score,
                    "focus" to name(report.focus),
                    "ms" to cost.milliseconds,
                    "encoderPasses" to cost.encoderPasses,
                    "decoderSteps" to cost.decoderSteps,
                ),
            )
        }
        // Not on iOS: the suite's shared engine holds a second model, and the emulator has 2 GB.
        engine.unload()

        val results = rows.joinToString("\n") { it.toString() } + "\n"
        val ios = InstrumentationRegistry.getInstrumentation().context.assets.open("eval_speech/ios_report.json").use { it.readBytes().toString(Charsets.UTF_8) }
        val parity = ParityReport.compare(rows, ios)
        export("results.jsonl", results)
        export("parity.md", parity)
        results.lines().filter { it.isNotEmpty() }.forEach { Log.i(EVAL_TAG, it) }
        parity.lines().forEach { Log.i(PARITY_TAG, it) }
    }

    // The two specs the web e2e suite pins for pairs.
    @Test
    fun pairRight() = runBlocking {
        // "en-pair-right: ship said right passes against sheep"
        val score = engine().scorePair(fixtureSamples("en-ship"), target = "ship", rival = "sheep", language = SpeechLanguage.EN)
        assertTrue(SpeechJudge.judgePair(score).wordHeard)
        assertTrue(score.pTarget > 0.8)
    }

    @Test
    fun pairWrong() = runBlocking {
        // "en-pair-wrong: sheep when ship was asked sounds like the rival or stays unclear"
        val score = engine().scorePair(fixtureSamples("en-sheep"), target = "ship", rival = "sheep", language = SpeechLanguage.EN)
        val outcome = SpeechJudge.judgePair(score)
        assertTrue("expected rival or unclear, got $outcome", outcome is PairOutcome.Rival || outcome == PairOutcome.Unclear)
    }

    @Test
    fun onePass() = runBlocking {
        // "a pair costs one encoder pass, and shared token prefixes cost one decoder step"
        val score = engine().scorePair(fixtureSamples("en-sheep"), target = "sheep", rival = "ship", language = SpeechLanguage.EN)
        assertEquals(1, score.cost.encoderPasses)
        // Six spellings of about two tokens each would be 36 steps if each ran alone from the start.
        assertTrue("${score.cost.decoderSteps} steps", score.cost.decoderSteps < 20)
    }

    @Test
    fun reuseIsClean() = runBlocking {
        // "a text scores the same alone and in a batch, so reused decoder state is clean"
        val engine = engine()
        val clip = fixtureSamples("en-sheep")
        val texts = listOf("sheep", "Sheep", "Sheep.", "ship", "Ship", "Ship.")
        val batch = engine.scoreTexts(clip, language = SpeechLanguage.EN, texts = texts)
        assertEquals(texts, batch.map { it.text })
        for ((text, together) in texts.zip(batch)) {
            val alone = engine.scoreTexts(clip, language = SpeechLanguage.EN, texts = listOf(text))
            val score = alone.first()
            assertTrue("$text: ${score.logp} alone, ${together.logp} in the batch", abs(score.logp - together.logp) < 0.02)
            assertEquals(together.tokens.map { it.id }, score.tokens.map { it.id })
        }
    }

    @Test
    fun forcedScores() = runBlocking {
        // "forced tokens get real, negative log-probabilities, which prefix forcing cannot give"
        val scores = engine().scoreTexts(fixtureSamples("en-think"), language = SpeechLanguage.EN, texts = listOf("sink"))
        val score = scores.first()
        assertTrue(score.tokens.size >= 2)
        assertTrue(score.tokens.all { it.logp < 0 })
        assertEquals(50257, score.tokens.last().id)
        assertTrue(score.logp < -0.5)
    }

    @Test
    fun silence() = runBlocking {
        // "says nothing was heard on near silence"
        val score = engine().scorePair(quietNoise(), target = "ship", rival = "sheep", language = SpeechLanguage.EN)
        assertEquals(PairOutcome.NotHeard, SpeechJudge.judgePair(score))
    }

    @Test
    fun emptyClip() = runBlocking {
        // "handles an empty clip"
        val heard = engine().transcribe(FloatArray(0), language = SpeechLanguage.EN)
        assertTrue(heard.words.isEmpty())
    }

    @Test
    fun wrongSentence() = runBlocking {
        // "sentence: a substitution is flagged with what was heard, and every word keeps a place in the take"
        val score = engine().scoreSentence(fixtureSamples("en-wrong-think"), target = "I think three things.", language = SpeechLanguage.EN)
        val outcome = SpeechJudge.judgeSentence(score)
        assertFalse(outcome.wordsHeard)
        // Dictionary(uniqueKeysWithValues:) traps on a repeated key.
        val keys = outcome.words.map { it.word.lowercase() }
        assertEquals(keys.size, keys.toSet().size)
        val status = outcome.words.associate { it.word.lowercase() to it.status }
        assertEquals(WordStatus.GOOD, status["i"])
        assertEquals(WordStatus.MISSED, status["think"])
        assertEquals(WordStatus.MISSED, status["three"])
        for (word in outcome.words) {
            assertTrue("$word", word.end > word.start)
        }
    }

    @Test
    fun accentSentence() = runBlocking {
        // "sentence: a Romanian accent saying the right words passes"
        val score = engine().scoreSentence(fixtureSamples("ro-accent-think"), target = "I think three things.", language = SpeechLanguage.EN)
        val outcome = SpeechJudge.judgeSentence(score)
        assertTrue(outcome.wordsHeard)
        assertTrue("${outcome.words}", outcome.words.all { it.status == WordStatus.GOOD })
    }

    @Test
    fun e2eSentence() = runBlocking {
        // "sentence: every word of the e2e sentence is marked"
        val score = engine().scoreSentence(fixtureSamples("en-sentence-sheep"), target = "The sheep is on the ship.", language = SpeechLanguage.EN)
        val outcome = SpeechJudge.judgeSentence(score)
        assertEquals(6, outcome.words.size)
        assertEquals(WordStatus.GOOD, outcome.words.firstOrNull { it.word == "sheep" }?.status)
    }

    @Test
    fun englishSpeech() = runBlocking {
        // "free speech: finds the fillers in English, in the verbatim prompt's register"
        val heard = engine().transcribe(fixtureSamples("en-speech"), language = SpeechLanguage.EN, verbatim = true)
        val report = SpeechJudge.speechReport(heard, language = SpeechLanguage.EN, lexicon = TestLexicon.EN)
        assertTrue("${report.fillerCounts} in '${heard.text}'", report.fillerCounts["like"] != null)
        assertTrue("${report.fillerCounts} in '${heard.text}'", report.fillerCounts["basically"] != null)
        assertTrue("${report.wordsPerMinute}", report.wordsPerMinute > 100)
    }

    @Test
    fun romanianSpeech() = runBlocking {
        // "free speech: finds the fillers in Romanian and keeps word timings in order"
        val heard = engine().transcribe(fixtureSamples("ro-speech"), language = SpeechLanguage.RO, verbatim = true)
        val report = SpeechJudge.speechReport(heard, language = SpeechLanguage.RO, lexicon = TestLexicon.RO)
        assertTrue("${report.fillerCounts} in '${heard.text}'", report.fillerCounts["deci"] != null)
        assertTrue("${report.fillerCounts} in '${heard.text}'", report.fillerCounts["practic"] != null)
        val starts = heard.words.map { it.start }
        assertTrue("$starts", starts.zipWithNext().all { (a, b) -> a <= b + 0.05 })
    }

    @Test
    fun romanianWordBoundaries() = runBlocking {
        // "keeps Romanian words apart when one opens with a diacritic"
        val scores = engine().scoreTexts(fixtureSamples("ro-speech"), language = SpeechLanguage.RO, texts = listOf("șapte țări înțeleg"))
        assertEquals(listOf("șapte", "țări", "înțeleg"), scores.firstOrNull()?.words?.map { it.word })
    }

    @Test
    fun unloads() = runBlocking {
        // "unloads and loads again on the next request"
        val engine = engine()
        engine.load()
        assertTrue(engine.isLoaded)
        engine.unload()
        assertFalse(engine.isLoaded)
        val heard = engine.transcribe(fixtureSamples("en-work"), language = SpeechLanguage.EN)
        assertEquals(listOf("work"), SpeechText.words(heard.text))
    }

    /** iOS keeps one engine for the suite in an actor; the harness builds its own through the store. */
    private object Shared {
        private var engine: WhisperEngine? = null

        @Synchronized
        fun engine(folder: File): WhisperEngine = engine ?: WhisperEngine(folder, WhisperCppLoader()).also { engine = it }
    }

    companion object {
        const val EVAL_TAG = "FoniaWhisperEval"
        const val PARITY_TAG = "FoniaWhisperParity"

        private val PAIR_CASES = listOf(
            PairCase("en-ship", "ship", "sheep", "ship-sheep", true),
            PairCase("en-ship", "sheep", "ship", "ship-sheep", false),
            PairCase("en-sheep", "sheep", "ship", "ship-sheep", true),
            PairCase("en-sheep", "ship", "sheep", "ship-sheep", false),
            PairCase("en-think", "think", "sink", "think-sink", true),
            PairCase("en-think", "sink", "think", "think-sink", false),
            PairCase("en-sink", "sink", "think", "think-sink", true),
            PairCase("en-sink", "think", "sink", "think-sink", false),
            PairCase("en-wine", "wine", "vine", "wine-vine", true),
            PairCase("en-wine", "vine", "wine", "wine-vine", false),
            PairCase("en-vine", "vine", "wine", "wine-vine", true),
            PairCase("en-vine", "wine", "vine", "wine-vine", false),
            PairCase("en-walk", "walk", "work", "walk-work", true),
            PairCase("en-walk", "work", "walk", "walk-work", false),
            PairCase("en-work", "work", "walk", "walk-work", true),
            PairCase("en-work", "walk", "work", "walk-work", false),
            PairCase("ro-accent-sheep", "The sheep is on the ship.", "The ship is on the ship.", null, true),
            PairCase("ro-accent-think", "I think three things.", "I sink tree sings.", null, true),
            PairCase("en-wrong-think", "I think three things.", "I sink tree sings.", null, false),
            PairCase("ro-accent-walk", "I walk to work.", "I work to walk.", null, true),
            PairCase("en-wine", "ship", "sheep", null, false),
            PairCase("silence", "ship", "sheep", null, false),
            PairCase("en-animal", "ANimal", "aniMAL", null, true),
        )

        private val SENTENCE_CASES = listOf(
            SentenceCase("en-sentence-sheep", "The sheep is on the ship.", true),
            SentenceCase("en-sentence-think", "I think three things.", true),
            SentenceCase("en-wrong-think", "I think three things.", false),
            SentenceCase("ro-accent-sheep", "The sheep is on the ship.", true),
            SentenceCase("ro-accent-think", "I think three things.", true),
            SentenceCase("ro-accent-walk", "I walk to work.", true),
            SentenceCase("en-sentence-sheep", "The ship is on the sheep.", false),
        )

        private fun samples(fixture: String): FloatArray = if (fixture == "silence") quietNoise() else fixtureSamples(fixture)

        /** Runs a request three times and keeps the median time: other work on the machine skews any one run. */
        private suspend fun <T> medianTiming(request: suspend () -> Pair<T, InferenceCost>): Pair<T, InferenceCost> {
            val results = (0 until 3).map { request() }.sortedBy { it.second.milliseconds }
            return results[1]
        }

        private fun seconds(since: Long): Double = (System.nanoTime() - since).toDouble() / 1e9

        fun name(outcome: PairOutcome): String = when (outcome) {
            PairOutcome.NotHeard -> "notHeard"
            PairOutcome.SameText -> "sameText"
            is PairOutcome.OtherWord -> "otherWord(${outcome.heard})"
            is PairOutcome.Target -> "target"
            is PairOutcome.Rival -> "rival"
            PairOutcome.Unclear -> "unclear"
        }

        fun name(focus: SpeechReport.Focus): String = when (focus) {
            SpeechReport.Focus.NotHeard -> "notHeard"
            is SpeechReport.Focus.ManyHesitations -> "manyHesitations(${focus.count})"
            SpeechReport.Focus.Fast -> "fast"
            SpeechReport.Focus.Slow -> "slow"
            SpeechReport.Focus.Clean -> "clean"
        }

        /** A JSON object with sorted keys, as JSONEncoder's `.sortedKeys` writes it. */
        fun row(vararg entries: Pair<String, Any?>): JsonObject = JsonObject(entries.associate { (key, value) -> key to json(value) }.toSortedMap())

        private fun json(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }

        /** Hands a file back to the computer through the test storage service; logcat keeps a copy either way. */
        private fun export(name: String, text: String) {
            try {
                TestStorage().openOutputFile(name).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            } catch (e: Exception) {
                Log.w(EVAL_TAG, "test storage unavailable for $name: $e")
            }
        }
    }
}
