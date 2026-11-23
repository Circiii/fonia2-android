package tech.fonia.judge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict

/** P4 arithmetic and P5 (plan 03 §7) on hand-made probabilities. */
class DecisionPolicyTests {
    private val spec = loadSpec()
    private val s = spec.fusionFor(SoundId.S)

    private fun probs(vararg pairs: Pair<String, Float>): Probs {
        val map = pairs.toMap()
        val values = FloatArray(s.classes.size) { map[s.classes[it]] ?: 0f }
        val sum = values.sum()
        return Probs(s, FloatArray(values.size) { values[it] / sum })
    }

    private fun unlockWith(state: String, level: String? = null, kind: String = "placeSwap"): UnlockView {
        val entries = spec.unlock.entries.map {
            if (it.sound == "s" && it.kind == kind) it.copy(state = state, evidenceLevel = level, evidence = null) else it
        }
        return UnlockView(spec.unlock.copy(entries = entries))
    }

    private fun decide(p: Probs, head: Head = Head.Full, unlock: UnlockView = UnlockView.everythingUnlocked(),
                       runtime: RuntimeContext = RuntimeContext(), blocked: Set<ErrorKind> = emptySet()) =
        DecisionPolicy.decide(p, head, SoundId.S, unlock, runtime, blocked, fullBand)

    private val fullBand = CaptureReport(
        durationSeconds = 1.0, sampleRate = 48000.0, speechSeconds = 0.6, speechRmsDb = -20f, dynamicRangeDb = 40f,
        clippedFraction = 0f, bandwidthHz = 20000f, dropped = 0, bandSnrDb = 30f, f0MedianHz = 200f, bandwidthTier = "full",
    )

    @Test
    fun softmaxSumsToOneAndMasksMissingInputs() {
        val head = FusionHead(s)
        val p = head.probabilities(emptyMap(), "fast")
        assertEquals(1f, p.sum(), 1e-4f)
        // All inputs missing: the logits are the biases alone.
        val x = head.inputVector(mapOf("toward" to Float.NaN, "fricDurMs" to 1e9f))
        assertEquals(0f, x[s.inputs.indexOfFirst { it.name == "toward" }])
        assertEquals(3f, x[s.inputs.indexOfFirst { it.name == "fricDurMs" }])
    }

    @Test
    fun correctAboveTauOkFullButNotFast() {
        val p = probs("correct" to 0.85f, "placeSwap" to 0.15f)
        assertEquals(Verdict.Correct, decide(p, Head.Full).verdict)
        // The fast head needs 0.90; between thresholds it is Almost.
        assertEquals(Verdict.Almost, decide(p, Head.Fast).verdict)
    }

    @Test
    fun aClearUnlockedErrorIsNamedWithWhatWasHeard() {
        val d = decide(probs("correct" to 0.02f, "placeSwap" to 0.95f, "stopping" to 0.03f))
        assertEquals(Verdict.NamedError(ErrorKind.PlaceSwap, SoundId.Sh), d.verdict)
    }

    @Test
    fun aTrialKindStaysWithTheAdultInProba() {
        val d = decide(probs("correct" to 0.02f, "placeSwap" to 0.95f, "stopping" to 0.03f), unlock = unlockWith("trial"))
        assertEquals(Verdict.Unsure, d.verdict)
        assertEquals(ErrorKind.PlaceSwap, d.withheld)
        assertTrue(d.adultNote!!.inTrial)
        assertEquals(SoundId.Sh, d.adultNote!!.heardAs)
    }

    @Test
    fun aLockedKindLeavesOnlyTheTrace() {
        val d = decide(probs("correct" to 0.02f, "placeSwap" to 0.95f, "stopping" to 0.03f), unlock = unlockWith("locked"))
        assertEquals(Verdict.Unsure, d.verdict)
        assertNull(d.adultNote)
        assertEquals(ErrorKind.PlaceSwap, d.topError)
    }

    @Test
    fun imitationEvidenceNeedsAnAdultACalibratedChildAndAStricterThreshold() {
        val unlock = unlockWith("unlocked", "imitations")
        val p = probs("correct" to 0.02f, "placeSwap" to 0.95f, "stopping" to 0.03f)
        assertTrue(decide(p, unlock = unlock).verdict !is Verdict.NamedError)
        val ready = RuntimeContext(adultPresent = true, calibrationTier = 1)
        assertEquals(Verdict.NamedError(ErrorKind.PlaceSwap, SoundId.Sh), decide(p, unlock = unlock, runtime = ready).verdict)
        // 0.88 passes tauErr 0.85 but not tauErr + 0.07.
        val weaker = probs("correct" to 0.02f, "placeSwap" to 0.88f, "stopping" to 0.10f)
        assertTrue(decide(weaker, unlock = unlock, runtime = ready).verdict !is Verdict.NamedError)
    }

    @Test
    fun aFineKindWaitsUntilTheCoarseOnesAreRuledOut() {
        // Place (level 4) cannot be named while presence (level 1) still holds 0.2.
        val d = decide(probs("correct" to 0.0f, "placeSwap" to 0.8f, "omission" to 0.2f))
        assertTrue(d.verdict !is Verdict.NamedError)
        assertEquals(ErrorKind.PlaceSwap, d.topError)
    }

    @Test
    fun twoCloseKindsAreNotNamed() {
        val d = decide(probs("correct" to 0.0f, "stopping" to 0.5f, "weakOrShort" to 0.5f))
        assertTrue(d.verdict !is Verdict.NamedError)
    }

    @Test
    fun aKindWhoseMeasurementIsInvalidIsNotNamed() {
        val d = decide(probs("correct" to 0.02f, "placeSwap" to 0.95f, "stopping" to 0.03f), blocked = setOf(ErrorKind.PlaceSwap))
        assertTrue(d.verdict !is Verdict.NamedError)
    }

    @Test
    fun fastHeadNeedsMoreForAnError() {
        // 0.87: enough for the full head (0.85), not for the fast one (0.90).
        val p = probs("correct" to 0.03f, "placeSwap" to 0.87f, "stopping" to 0.10f)
        assertTrue(decide(p, Head.Full).verdict is Verdict.NamedError)
        assertTrue(decide(p, Head.Fast).verdict !is Verdict.NamedError)
    }

    @Test
    fun adultDisagreementsRaiseTheBar() {
        val p = probs("correct" to 0.85f, "placeSwap" to 0.15f)
        assertEquals(Verdict.Almost, decide(p, runtime = RuntimeContext(childOkOffset = 0.1f)).verdict)
        assertNotNull(decide(p).verdict)
    }
}
