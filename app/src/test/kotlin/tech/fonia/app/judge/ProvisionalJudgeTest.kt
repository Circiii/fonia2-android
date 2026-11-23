package tech.fonia.app.judge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.app.WavFixtures
import tech.fonia.live.Clip
import tech.fonia.live.LiveTarget
import tech.fonia.model.ErrorKind
import tech.fonia.model.Rung
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict

class ProvisionalJudgeTest {
    private val judge = ProvisionalJudge()

    private fun verdict(sound: SoundId, rung: Rung, fixture: String): Verdict =
        runBlocking { judge.verdict(sound, rung, WavFixtures.clip(fixture)) }

    @Test fun sHissHeldIsCorrectForS() {
        val s = TakeAnalysis.summarise(WavFixtures.clip("s_hold"), LiveTarget.of(SoundId.S))
        println("s_hold on S: $s")
        assertTrue("sNess ${s.meanSNess}", s.meanSNess >= 0.6f)
        assertTrue("hold ${s.bestHoldSeconds}", s.bestHoldSeconds >= 2.25f)
        assertEquals(Verdict.Correct, verdict(SoundId.S, Rung.Isolated, "s_hold"))
    }

    @Test fun shHissHeldIsCorrectForSh() {
        val s = TakeAnalysis.summarise(WavFixtures.clip("sh_hold"), LiveTarget.of(SoundId.Sh))
        println("sh_hold on Ș: $s")
        assertEquals(Verdict.Correct, verdict(SoundId.Sh, Rung.Isolated, "sh_hold"))
    }

    @Test fun sHissOnShIsAPlaceSwapHeardAsS() {
        assertEquals(Verdict.NamedError(ErrorKind.PlaceSwap, SoundId.S), verdict(SoundId.Sh, Rung.Isolated, "s_hold"))
        assertEquals(Verdict.NamedError(ErrorKind.PlaceSwap, SoundId.Sh), verdict(SoundId.S, Rung.Isolated, "sh_hold"))
    }

    @Test fun affricatesUseAffricatePlace() {
        assertEquals(Verdict.NamedError(ErrorKind.AffricatePlace, SoundId.Ts), verdict(SoundId.Ce, Rung.Isolated, "s_hold"))
    }

    @Test fun spokenWords() {
        val s = TakeAnalysis.summarise(WavFixtures.clip("sarah_s"), LiveTarget.of(SoundId.S))
        val sh = TakeAnalysis.summarise(WavFixtures.clip("sarah_sh"), LiveTarget.of(SoundId.Sh))
        println("sarah_s: $s\nsarah_sh: $sh")
        // An adult voice's S sits right on the CHILD prior's boundary: the phone abstains and asks the adult.
        assertEquals(Verdict.Unsure, verdict(SoundId.S, Rung.WordsInitial, "sarah_s"))
        assertEquals(Verdict.Correct, verdict(SoundId.Sh, Rung.WordsInitial, "sarah_sh"))
        assertEquals(Verdict.NamedError(ErrorKind.PlaceSwap, SoundId.Sh), verdict(SoundId.S, Rung.WordsInitial, "sarah_sh"))
    }

    @Test fun silenceAndEmptyAreRetakes() {
        assertEquals(Verdict.Retake, runBlocking { judge.verdict(SoundId.S, Rung.Isolated, Clip.Empty) })
        val quiet = Clip(FloatArray(48_000) { ((it * 7919) % 13 - 6) * 1e-5f }, 48_000.0)
        assertEquals(Verdict.Retake, runBlocking { judge.verdict(SoundId.S, Rung.Isolated, quiet) })
    }

    @Test fun shortRightSideHoldIsAlmost() {
        val s = TakeSummary(1.5, 1.2, 1.0, 0.9f, 0.0f, 1.0f, 0.0, 0.6f)
        assertEquals(Verdict.Almost, judge.decide(SoundId.S, Rung.Isolated, s))
        assertEquals(Verdict.Correct, judge.decide(SoundId.S, Rung.WordsInitial, s))
    }

    @Test fun inBetweenIsUnsureAndLAlwaysAsksTheAdult() {
        val mid = TakeSummary(3.0, 2.8, 2.5, 0.5f, 0.0f, 0.0f, 0.0, 0.6f)
        assertEquals(Verdict.Unsure, judge.decide(SoundId.S, Rung.Isolated, mid))
        assertEquals(Verdict.Unsure, judge.decide(SoundId.L, Rung.Isolated, mid))
    }
}
