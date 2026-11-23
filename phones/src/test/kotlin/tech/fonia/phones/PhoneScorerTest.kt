package tech.fonia.phones

import org.junit.jupiter.api.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Builds posteriors that "say" a phone sequence: each entry holds a frame-level distribution (token → probability,
 * the rest spread evenly over the other tokens), repeated for its number of frames.
 */
internal fun spoken(vararg frames: Pair<Map<String, Double>, Int>): Posteriors {
    val rows = frames.sumOf { it.second }
    val values = FloatArray(rows * ZipaInventory.SIZE)
    var t = 0
    for ((distribution, count) in frames) {
        val rest = (1.0 - distribution.values.sum()).coerceAtLeast(1e-6) / (ZipaInventory.SIZE - distribution.size)
        repeat(count) {
            for (v in 0 until ZipaInventory.SIZE) {
                val p = distribution[ZipaInventory.tokens[v]] ?: rest
                values[t * ZipaInventory.SIZE + v] = ln(p).toFloat()
            }
            t++
        }
    }
    return Posteriors(rows, ZipaInventory.SIZE, values)
}

/** A clean utterance: silence, `▁`, then each token for [perToken] frames with a blank after it, then silence. */
internal fun clean(tokens: String, perToken: Int = 3, peak: Double = 0.9): Posteriors {
    val parts = ArrayList<Pair<Map<String, Double>, Int>>()
    parts += mapOf("<blk>" to 0.95) to 4
    parts += mapOf("▁" to 0.95) to 2
    for (token in tokens.split(" ").filter { it.isNotEmpty() }) {
        parts += mapOf(token to peak, "<blk>" to (1 - peak) / 2) to perToken
        parts += mapOf("<blk>" to 0.95) to 1
    }
    parts += mapOf("<blk>" to 0.95) to 8
    return spoken(*parts.toTypedArray())
}

class PhoneScorerTest {
    private val scorer = PhoneScorer(AcousticModel { error("posteriors are given directly") })

    @Test
    fun theTargetAsWrittenIsCorrect() {
        val score = scorer.judge(clean("l a k"), RomanianG2P.pronounce("lac"), TargetSound.L)
        assertEquals(Verdict.Correct, score.verdict)
        assertNull(score.abstention)
        val target = score.targets.single()
        assertTrue(target.lpr > 3.0, "lpr ${target.lpr}")
        assertTrue(target.pCorrect > 0.9)
        assertEquals(target.pCorrect, score.confidence)
        assertEquals(0..0, target.letters)
    }

    @Test
    fun rForLIsTryAgainHeardAsR() {
        val score = scorer.judge(clean("r a k"), RomanianG2P.pronounce("lac"), TargetSound.L)
        assertEquals(Verdict.TryAgain, score.verdict)
        assertEquals("r", score.heardAs?.id)
        assertEquals(TargetSound.R, score.heardAs?.sound)
        assertTrue(score.targets.single().lpr < -3.0)
        assertTrue(score.confidence > 0.9)
    }

    @Test
    fun aMissingTargetIsHeardAsOmission() {
        val score = scorer.judge(clean("a k"), RomanianG2P.pronounce("lac"), TargetSound.L)
        assertEquals(Verdict.TryAgain, score.verdict)
        val heardAs = score.heardAs!!
        assertTrue(heardAs.isOmission)
        assertEquals("lipsește", heardAs.soundsLike)
    }

    @Test
    fun theTappedRIsCorrectUnlessTheGameAsksForATrill() {
        val tapped = clean("ɾ a k")
        val word = RomanianG2P.pronounce("rac")
        val normal = scorer.judge(tapped, word, TargetSound.R)
        assertEquals(Verdict.Correct, normal.verdict)
        assertEquals(listOf("ɾ"), normal.targets.single().gop.correct.bestVariant)
        val trillGame = PhoneScorer(AcousticModel { error("unused") }, ScoringPolicy(tapAccepted = false))
        val strict = trillGame.judge(tapped, word, TargetSound.R)
        assertEquals(Verdict.TryAgain, strict.verdict)
        assertEquals("ɾ", strict.heardAs?.id)
    }

    @Test
    fun affricatesAreTwoTokensAndLosingTheStopIsHeardAsS() {
        val word = RomanianG2P.pronounce("țap")
        assertEquals(Verdict.Correct, scorer.judge(clean("t s a p"), word, TargetSound.Ts).verdict)
        val noStop = scorer.judge(clean("s a p"), word, TargetSound.Ts)
        assertEquals(Verdict.TryAgain, noStop.verdict)
        assertEquals(TargetSound.S, noStop.heardAs?.sound)
        val ce = scorer.judge(clean("t ʃ a p"), word, TargetSound.Ts)
        assertEquals(TargetSound.Ce, ce.heardAs?.sound)
    }

    @Test
    fun anEvenSplitBetweenTargetAndAlternativeAbstains() {
        val parts = arrayOf(
            mapOf("<blk>" to 0.95) to 4, mapOf("▁" to 0.95) to 2,
            mapOf("l" to 0.45, "r" to 0.45) to 3, mapOf("<blk>" to 0.95) to 1,
            mapOf("a" to 0.9) to 3, mapOf("<blk>" to 0.95) to 1,
            mapOf("k" to 0.9) to 3, mapOf("<blk>" to 0.95) to 8,
        )
        val score = scorer.judge(spoken(*parts), RomanianG2P.pronounce("lac"), TargetSound.L)
        assertEquals(Verdict.NotHeard, score.verdict)
        assertEquals(Abstention.Uncertain, score.abstention)
        assertEquals(0.0, score.confidence)
    }

    @Test
    fun anotherWordIsNotHeardRatherThanJudged() {
        val score = scorer.judge(clean("m u n t e l u"), RomanianG2P.pronounce("lac"), TargetSound.L)
        assertEquals(Verdict.NotHeard, score.verdict)
        assertEquals(Abstention.PoorFit, score.abstention)
        assertTrue(score.fitPerLabel < -3.0, "fit ${score.fitPerLabel}")
    }

    @Test
    fun theWordAsSaidFitsTheFreePath() {
        val score = scorer.judge(clean("l a k"), RomanianG2P.pronounce("lac"), TargetSound.L)
        assertTrue(score.fitPerLabel > -0.5, "fit ${score.fitPerLabel}")
    }

    @Test
    fun tooLittleAudioAbstains() {
        val short = spoken(mapOf("▁" to 0.9) to 2, mapOf("l" to 0.9) to 3)
        assertEquals(Abstention.TooShort, scorer.judge(short, RomanianG2P.pronounce("lac"), TargetSound.L).abstention)
    }

    @Test
    fun aWordWithoutTheSoundIsRefused() {
        val score = scorer.judge(clean("m a k"), RomanianG2P.pronounce("mac"), TargetSound.R)
        assertEquals(Abstention.NoTarget, score.abstention)
        assertTrue(score.targets.isEmpty())
    }

    @Test
    fun everyOccurrenceIsScoredAndTheWorstDecides() {
        // „rinocer” with its last R said as L.
        val score = scorer.judge(clean("r i n o t ʃ e l"), RomanianG2P.pronounce("rinocer"), TargetSound.R)
        assertEquals(2, score.targets.size)
        assertEquals(Verdict.Correct, score.targets[0].verdict)
        assertEquals(Verdict.TryAgain, score.targets[1].verdict)
        assertEquals(Verdict.TryAgain, score.verdict)
        assertEquals("l", score.heardAs?.id)
        assertEquals(6..6, score.targets[1].letters)
    }

    @Test
    fun theGopPosteriorIsNormalisedOverTheCandidates() {
        val gop = RestrictedGop().score(clean("ʃ a p"), RomanianG2P.pronounce("sap"), 0, TargetSound.S)
        val all = listOf(gop.correct.logLikelihood) + gop.alternatives.map { it.logLikelihood }
        val max = all.max()
        val logTotal = max + ln(all.sumOf { exp(it - max) })
        assertEquals(exp(gop.correct.logLikelihood - logTotal), gop.pCorrect, 1e-9)
        assertTrue(gop.pCorrect < 0.01)
        assertEquals("ʃ", gop.best.alternative?.id)
        assertTrue(gop.gop <= 0.0)
        assertEquals(gop.correct.logLikelihood - gop.best.logLikelihood, gop.lpr, 1e-12)
    }

    @Test
    fun aRemovedFirstPhoneKeepsTheWordStart() {
        val word = RomanianG2P.pronounce("lac")
        assertEquals(listOf("▁", "l", "a", "k"), word.labels)
        assertEquals(listOf("▁", "a", "k"), word.replacing(0, emptyList()).labels)
        assertEquals(listOf("▁", "r", "a", "k"), word.replacing(0, listOf("r"), TargetSound.R).labels)
    }

    @Test
    fun theDecisionsAllocateNothingPerFrame() {
        // A word ten times longer in time must not allocate more: the forward rows are reused.
        val gop = RestrictedGop()
        val word = RomanianG2P.pronounce("lac")
        val short = clean("l a k", perToken = 3)
        val long = clean("l a k", perToken = 30)
        fun bytes(posteriors: Posteriors) = (0 until 5).minOf { countingAllocatedBytes { gop.score(posteriors, word, 0, TargetSound.L) } }
        repeat(20) { gop.score(long, word, 0, TargetSound.L) }
        assertEquals(bytes(short), bytes(long))
    }
}
