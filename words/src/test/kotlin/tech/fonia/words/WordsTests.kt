package tech.fonia.words

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WordsTests {
    private fun heard(text: String, confidence: Double = 0.95, blank: Double = 0.4): Heard {
        val words = text.split(' ').filter { it.isNotEmpty() }.mapIndexed { i, w -> HeardWord(w, i * 0.4, i * 0.4 + 0.3, confidence) }
        return Heard(words, blank, confidence, words.size * 0.4)
    }

    @Test
    fun featuresMatchTheReference() {
        val root = Json.parseToJsonElement(javaClass.getResource("/features.json")!!.readText()).jsonObject
        val n = root.getValue("samples").jsonPrimitive.int
        var seed = 12345L
        val x = FloatArray(n) { i ->
            seed = (seed * 1103515245L + 12345L) % 2147483648L
            (0.3 * sin(2 * Math.PI * (200 + 3000.0 * i / n) * i / 16000) + 0.01 * (seed / 2147483648.0 - 0.5)).toFloat()
        }
        val out = FloatArray(RoAsrFeatures.frameCount(n) * RoAsrFeatures.BANDS)
        val frames = RoAsrFeatures.compute(x, out)
        assertEquals(root.getValue("frames").jsonPrimitive.int, frames)
        for ((key, value) in root.getValue("points").jsonObject) {
            val (band, frame) = key.split(',').map { it.toInt() }
            assertTrue(abs(out[band * frames + frame] - value.jsonPrimitive.double) < 2e-3, "band $band frame $frame: ${out[band * frames + frame]} vs $value")
        }
    }

    @Test
    fun decodesPiecesIntoWordsWithTimes() {
        val vocabulary = RoAsrVocabulary(listOf("<unk>", "▁le", "ul", "▁a", "re"), blank = 5, frameSeconds = 0.08)
        // frames: blank, _le, _le, ul, blank, _a, re, blank
        val ids = intArrayOf(5, 1, 1, 2, 5, 3, 4, 5)
        val values = FloatArray(ids.size * 6) { -20f }
        ids.forEachIndexed { t, id -> values[t * 6 + id] = -0.05f }
        val heard = CtcWords.decode(CtcOutput(ids.size, 6, values), vocabulary)
        assertEquals("leul are", heard.text)
        assertEquals(0.08, heard.words[0].start, 1e-9)
        assertEquals(0.32, heard.words[0].end, 1e-9)
        assertEquals(3.0 / 8, heard.blankShare, 1e-9)
        assertTrue(heard.confidence > 0.9)
    }

    @Test
    fun aSentenceSaidWholeIsAllSaid() {
        val check = WordMatcher.check("Lili lăsa lalele lângă lacul liniștit.", heard("lili lăsa lalele lângă lacul liniștit"))
        assertTrue(check.allSaid())
        assertNull(WordsAdvice.next(check))
        assertEquals("lili+ lăsa+ lalele+ lângă+ lacul+ liniștit+", check.trace())
    }

    @Test
    fun theSoundUnderPracticeNeverCostsAWord() {
        // "șoarele" for "soarele", "lața" for "rața", "zoi" for "joi": that is for the sound judge, not the word check.
        assertTrue(WordMatcher.check("Soarele strălucește tare.", heard("șoarele strălucește tare")).allSaid())
        assertTrue(WordMatcher.check("Rața înoată pe lac.", heard("lața înoată pe lac")).allSaid())
        assertTrue(WordMatcher.check("Joi mergem la școală.", heard("zoi mergem la scoală")).allSaid())
    }

    @Test
    fun gluedAndSplitWordsStillMatch() {
        val check = WordMatcher.check("Eu am cântat rac în loc de lac.", heard("eu am cânta racîn loc de lac"))
        assertTrue(check.allSaid(), check.trace())
        val split = WordMatcher.check("Spune-mi povestea leului.", heard("spunem povestea leului"))
        assertTrue(split.allSaid(), split.trace())
    }

    @Test
    fun aSkippedWordIsNamedInOneStep() {
        val check = WordMatcher.check("Leul are un balon.", heard("leul are balon"))
        assertEquals(listOf("un"), check.missing.map { it.word })
        val advice = assertNotNull(WordsAdvice.next(check))
        assertTrue("«un»" in advice && advice.split(' ').size <= 8, advice)
    }

    @Test
    fun halfASentenceIsNotAllSaid() {
        val check = WordMatcher.check("Lili lăsa lalele lângă lacul liniștit.", heard("lili lăsa lalele"))
        assertTrue(!check.allSaid() && !check.mostSaid())
        assertEquals("Spune toată propoziția, rar.", WordsAdvice.next(check))
    }

    @Test
    fun aNoisyOrQuietTakeAbstains() {
        val noisy = WordMatcher.check("Leul are un balon.", heard("leul se se balon", confidence = 0.55))
        assertEquals(Abstain.Unsure, noisy.abstain)
        assertTrue(!noisy.allSaid())
        val quiet = WordMatcher.check("Leul are un balon.", heard("leul are un balon"), peakDb = -52.0)
        assertEquals(Abstain.Quiet, quiet.abstain)
        val silent = WordMatcher.check("Leul are un balon.", Heard(emptyList(), 1.0, 0.0, 3.0))
        assertEquals(Abstain.Nothing, silent.abstain)
        for (c in listOf(noisy, quiet, silent)) assertTrue(assertNotNull(WordsAdvice.next(c)).split(' ').size <= 8)
    }

    @Test
    fun adviceNeverBlames() {
        val banned = listOf("greșit", "gresit", "rău", "prost", "nu e bine")
        val cases = listOf(
            WordMatcher.check("Leul are un balon.", heard("leul are")),
            WordMatcher.check("Leul are un balon.", heard("leul are un bal")),
            WordMatcher.check("balon", heard("ba")),
        )
        for (c in cases) {
            val advice = WordsAdvice.next(c) ?: continue
            assertTrue(banned.none { it in advice.lowercase() } && advice.split(' ').size <= 8, advice)
        }
    }

    @Test
    fun freeSpeechCountsFillersRepeatsAndPace() {
        val speech = SpeechText.of(heard("deci eu cred că că practic mergem de fapt la mare adică mâine"))
        assertEquals(listOf("deci", "practic", "de fapt", "adică"), speech.fillers.map { it.text })
        assertEquals(listOf("că"), speech.repeats.map { it.text })
        assertTrue(speech.wordsPerMinute in 140.0..160.0, "${speech.wordsPerMinute}")
        assertTrue(speech.lines(maxWords = 5).all { it.text.split(' ').size <= 5 })
    }

    @Test
    fun readingKnowsWhereTheReaderGotToAndWhereHeStumbled() {
        val reading = ReadingCheck.of("Ana are mere roșii și pere galbene în coș.", heard("ana are me roșii și pere"))
        assertEquals(5, reading.reachedIndex)
        assertEquals(listOf("mere"), reading.stumbles.map { it.word })
    }

    @Test
    fun theSpecFileMatchesTheDefaults() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "spec/thresholds/words.json") }.first { it.isFile }
        assertEquals(WordsSpec(), WordsSpec.parse(file.readText()))
    }

    @Test
    fun theRealModelHearsTheReferenceClip() {
        val dir = File(System.getProperty("fonia.models") ?: "", "roasr-parakeet-ro-110m")
        val model = File(dir, "ctc.int8.onnx")
        val clip = File(dir, "reference.wav")
        assumeTrue(model.isFile && clip.isFile, "no Romanian word model in the cache (tools/asr/export_roasr.py)")
        val bytes = clip.readBytes()
        val data = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(data.remaining()) { data.get(it) / 32768f }
        val expected = Json.parseToJsonElement(File(dir, "reference.json").readText()).jsonObject
        RoAsrModel.load(model.absolutePath, threads = 4).use { asr ->
            val heard = asr.hear(samples)
            assertEquals(expected.getValue("text").jsonPrimitive.content, heard.text)
            val firstEnd = expected.getValue("words").jsonArray[0].jsonArray[2].jsonPrimitive.double
            assertEquals(firstEnd, heard.words[0].end, 0.081)
            assertTrue(WordMatcher.check("Șarpele se târăște prin iarbă.", heard).allSaid())
            // Silence gives no words.
            assertTrue(asr.hear(FloatArray(48_000)).words.isEmpty())
            // A long recording is cut and its times continue across the cut.
            val long = FloatArray(samples.size * 16) { samples[it % samples.size] }
            val many = asr.hear(long)
            assertTrue(many.words.size >= 70 && many.words.last().start > long.size / 16000.0 - 3, "${many.words.size} words, last at ${many.words.lastOrNull()?.start}")
        }
    }
}
