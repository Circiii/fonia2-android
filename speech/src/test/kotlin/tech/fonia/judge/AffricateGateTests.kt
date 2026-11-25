package tech.fonia.judge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict

/** The affricate onset in the trace and the two opt-in gates (`judge2.affricate`, `judge2.finalL`), off by default. */
class AffricateGateTests {
    private val spec = loadSpec()
    private val detector = TakeJudge(spec, unlock = UnlockView.everythingUnlocked())

    private fun with(affricate: Map<String, AffricateGate> = emptyMap(), finalL: FinalLThresholds = FinalLThresholds()): TakeJudge {
        val j2 = spec.population.judge2.copy(affricate = affricate, finalL = finalL)
        return TakeJudge(JudgeSpec(spec.population.copy(judge2 = j2), spec.fusion, spec.unlock, spec.feedback), unlock = UnlockView.everythingUnlocked())
    }

    private fun word(sound: SoundId, text: String, position: Position) = JudgeRequest(sound, Level.Word, text, position, TalkerGroup.Woman)

    /** A stop release: 4 ms of loud broadband noise. */
    private fun burst() = shaped(0.004, listOf(500f to 0f, 12000f to 0f), amp = 0.25f, seed = 21)

    /** Ș-like hiss whose amplitude climbs linearly over the first [rampMs]. */
    private fun swell(seconds: Double, rampMs: Double): FloatArray {
        val h = shHiss(seconds)
        val k = n(rampMs / 1000)
        return FloatArray(h.size) { h[it] * if (it < k) it.toFloat() / k else 1f }
    }

    /** „cer” said with a release, and „șer” (the hiss swelling in over 50 ms) asked as CE. */
    private val release = clip(cat(quiet(0.3), burst(), shHiss(0.09), vowel(0.35), quiet(0.3)))
    private val fricative = clip(cat(quiet(0.3), swell(0.15, 50.0), vowel(0.35), quiet(0.3)))
    private val cer = word(SoundId.Ce, "cer", Position.Initial)

    /** ZIPA-small on a good [tʃ]: it hears [ʃ] and [s] over the target. */
    private val heardFricative = PhoneEvidence(
        mapOf("ts" to 6f, "ʃ" to -3f, "t" to 8f, "s" to -2f, "∅" to 8f), fitPerLabel = 0.2f, frames = 40, gop = -3.4f,
    )
    private val heardTarget = PhoneEvidence(
        mapOf("ts" to 8f, "ʃ" to 8f, "t" to 8f, "s" to 8f, "∅" to 8f), fitPerLabel = 0.2f, frames = 40, gop = -0.01f,
    )

    @Test
    fun theShippedSpecHasBothGatesOff() {
        val j2 = spec.population.judge2
        assertTrue(j2.affricate.isEmpty(), "${j2.affricate}")
        assertEquals(0f, j2.finalL.minGlideNats)
        // A gate read with no thresholds is off.
        assertTrue(AffricateGate().trustAt > 1f && AffricateGate().vetoAt < 0f)
        // A spec without the sections reads as off.
        val parsed = JudgeSpec.json.decodeFromString(Judge2Thresholds.serializer(), """{"version": "x"}""")
        assertTrue(parsed.affricate.isEmpty())
        assertEquals(FinalLThresholds(), parsed.finalL)
        val on = JudgeSpec.json.decodeFromString(Judge2Thresholds.serializer(),
            """{"affricate": {"ce": {"trustAt": 0.7, "trust": ["ʃ", "s"], "vetoAt": 0.2}}, "finalL": {"minGlideNats": 8}}""")
        assertEquals(AffricateGate(0.7f, listOf("ʃ", "s"), 0.2f), on.affricate["ce"])
        assertEquals(FinalLThresholds(8f, mask = false), on.finalL)
    }

    @Test
    fun theTraceCarriesTheOnsetAndItSplitsReleaseFromSwell() {
        val stop = detector.measure(cer, release)
        val swell = detector.measure(cer, fricative)
        val a = detector.decide(stop).evidence.features
        val b = detector.decide(swell).evidence.features
        assertTrue(a.getValue(Features.STOP_ONSET) >= 0.7f, "release $a")
        assertTrue(b.getValue(Features.STOP_ONSET) <= 0.3f, "swell $b")
        assertTrue(a.getValue(Features.ONSET_ATTACK) > b.getValue(Features.ONSET_ATTACK), "attack ${a[Features.ONSET_ATTACK]} ${b[Features.ONSET_ATTACK]}")
    }

    @Test
    fun withTheGatesOffNothingChanges() {
        // Listed sounds but sentinel thresholds: the same as no section at all.
        val sentinel = with(mapOf("ce" to AffricateGate(trust = listOf("ʃ", "s"))), FinalLThresholds(0f, mask = true))
        for (take in listOf(release, fricative)) for (phones in listOf(null, heardFricative, heardTarget)) {
            val m = detector.measure(cer, take)
            val off = detector.decide(m, phones)
            val same = sentinel.decide(m, phones)
            assertEquals(off.verdict, same.verdict)
            assertEquals(off.probabilities, same.probabilities)
            assertFalse("stopTrusted" in same.evidence.features || "stopVeto" in same.evidence.notes, same.evidence.notes.toString())
        }
    }

    @Test
    fun trustSetsAsideTheFricativesZipaHeardOnlyAfterARelease() {
        val trust = with(mapOf("ce" to AffricateGate(trustAt = 0.6f, trust = listOf("ʃ", "s"))))
        val m = detector.measure(cer, release)
        val off = detector.decide(m, heardFricative)
        val on = trust.decide(m, heardFricative)
        assertEquals(1f, on.evidence.features["stopTrusted"])
        assertFalse(Features.LPR_PREFIX + "ʃ" in on.evidence.features || Features.LPR_PREFIX + "s" in on.evidence.features)
        // GOP over what is left (ts at 6, t and ∅ at 8): −ln(1 + e^−6 + 2·e^−8).
        assertEquals(-0.0031f, on.evidence.features.getValue(Features.ZIPA_GOP), 0.0005f)
        assertTrue(on.probabilities.getValue("correct") > off.probabilities.getValue("correct"), "${off.probabilities} → ${on.probabilities}")
        assertEquals(Verdict.Correct, on.verdict, "$off → $on")
        // A swell keeps what ZIPA heard.
        val s = detector.measure(cer, fricative)
        assertEquals(detector.decide(s, heardFricative).probabilities, trust.decide(s, heardFricative).probabilities)
    }

    @Test
    fun vetoKeepsASwellFromPraiseAndLetsAReleaseThrough() {
        val veto = with(mapOf("ce" to AffricateGate(vetoAt = 0.3f)))
        for ((take, vetoed) in listOf(release to false, fricative to true)) {
            val m = detector.measure(cer, take)
            val off = detector.decide(m, heardTarget)
            val on = veto.decide(m, heardTarget)
            if (off.verdict != Verdict.Correct || !vetoed) {
                assertEquals(off.verdict, on.verdict)
                assertFalse("stopVeto" in on.evidence.notes)
            } else {
                assertEquals(Verdict.Almost, on.verdict)
                assertTrue("stopVeto" in on.evidence.notes)
            }
        }
        // The swell asked as CE with ZIPA agreeing is praised without the veto: the case the veto is for.
        assertEquals(Verdict.Correct, detector.decide(detector.measure(cer, fricative), heardTarget).verdict)
    }

    /** „val”: ZIPA moves a good dark L towards [w]; [glide] is lpr(j+w). */
    private fun lEvidence(glide: Float, wordFinal: Boolean?) = PhoneEvidence(
        mapOf("j" to 6f, "w" to glide + 0.3f, "r" to 8f, "∅" to 8f, "j+w" to glide), fitPerLabel = 0.2f, frames = 40,
        gop = -kotlin.math.ln(1f + kotlin.math.exp(-6f) + kotlin.math.exp(-(glide + 0.3f)) + 2 * kotlin.math.exp(-8f)), wordFinal = wordFinal,
    )

    @Test
    fun aWeakGlideAtAFinalLIsNotNamedWhenTheGateIsOn() {
        val take = clip(cat(quiet(0.3), vowel(0.4), quiet(0.3)))
        val m = detector.measure(word(SoundId.L, "val", Position.Final), take)
        assertEquals(1f, detector.decide(m).evidence.features[Features.WORD_FINAL])
        val weak = lEvidence(-5f, wordFinal = true)
        val off = detector.decide(m, weak)
        assertEquals(ErrorKind.LtoGlide, (off.verdict as? Verdict.NamedError)?.kind, "$off")
        val gate = with(finalL = FinalLThresholds(minGlideNats = 8f))
        val on = gate.decide(m, weak)
        assertEquals(Verdict.Almost, on.verdict)
        assertNull(on.topError)
        assertTrue("finalGlideWeak" in on.evidence.notes)
        // Strong glide evidence still names it; so does a medial L, and the scorer's word-final flag wins over the text.
        assertEquals(off.verdict, gate.decide(m, lEvidence(-9f, wordFinal = true)).verdict)
        assertEquals(off.verdict, gate.decide(m, lEvidence(-5f, wordFinal = false)).verdict)
        val ala = detector.measure(word(SoundId.L, "ala", Position.Medial), clip(cat(quiet(0.3), vowel(0.4), quiet(0.3))))
        assertEquals(0f, detector.decide(ala).evidence.features[Features.WORD_FINAL])
        assertEquals(detector.decide(ala, lEvidence(-5f, null)).verdict, gate.decide(ala, lEvidence(-5f, null)).verdict)
        // Mask mode: the glide candidates are masked before the head instead.
        val masked = with(finalL = FinalLThresholds(minGlideNats = 8f, mask = true)).decide(m, weak)
        assertEquals(1f, masked.evidence.features["finalGlideMasked"])
        assertTrue(masked.probabilities.getValue("correct") > off.probabilities.getValue("correct"), "${off.probabilities} → ${masked.probabilities}")
    }
}
