package tech.fonia.phones

import org.junit.jupiter.api.Test
import tech.fonia.judge.JudgeRequest
import tech.fonia.judge.Level
import tech.fonia.judge.Position
import tech.fonia.live.Clip
import tech.fonia.model.SoundId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The adapter from ZIPA + GOP to the judge's PhoneEvidence, on posteriors built by hand (`clean`, `spoken`). */
class ZipaPhoneScorerTest {
    private val scorer = ZipaPhoneScorer(AcousticModel { error("posteriors are given directly") })

    private fun evidence(said: String, text: String, sound: SoundId, level: Level = Level.Word, position: Position = Position.Any) =
        JudgeRequest(sound, level, text, position).let { r ->
            val target = TargetSound.fromRawValue(sound.raw)!!
            scorer.evidence(clean(said), ZipaPhoneScorer.pronunciation(r, target)!!, r, target)
        }

    @Test
    fun aGoodLHasEveryRatioPositive() {
        val e = evidence("l a k", "lac", SoundId.L)
        assertTrue(e.reliable, e.notes.toString())
        assertEquals(setOf("j", "w", "r", "∅", "j+w"), e.lpr.keys)
        assertTrue(e.lpr.values.all { it > 2f }, e.lpr.toString())
        assertTrue(e.gop!! > -0.5f)
        assertNotNull(e.targetSpan)
        assertTrue("heard:target" in e.notes)
    }

    @Test
    fun rSaidAsGlideAndAsLAreNamedByTheirOwnRatio() {
        val glide = evidence("j a k", "rac", SoundId.R)
        assertTrue(glide.lpr.getValue("j") < -2f, glide.lpr.toString())
        assertTrue(glide.lpr.getValue("l") > glide.lpr.getValue("j"))
        val asL = evidence("l a k", "rac", SoundId.R)
        assertTrue(asL.lpr.getValue("l") < -2f, asL.lpr.toString())
        assertTrue("heard:l" in asL.notes)
        val vowel = evidence("ə a k", "rac", SoundId.R)
        assertTrue(vowel.lpr.getValue("ə") < -2f, vowel.lpr.toString())
    }

    @Test
    fun theTapIsAcceptedForR() {
        val e = evidence("ɾ a k", "rac", SoundId.R)
        assertTrue(e.lpr.values.all { it > 0f }, e.lpr.toString())
    }

    @Test
    fun lSaidAsIOrUFeedsTheGroupedRatio() {
        val u = evidence("w a k", "lac", SoundId.L)
        assertTrue(u.lpr.getValue("j+w") < -2f, u.lpr.toString())
        assertTrue(u.lpr.getValue("j+w") <= u.lpr.getValue("w") + 1e-3f)
    }

    @Test
    fun affricatesAreTwoTokensAndTheirSubstitutesAreKeyedAsWritten() {
        val ts = evidence("s a r ə", "țară", SoundId.Ts, position = Position.Initial)
        assertTrue(ts.lpr.getValue("s") < -2f, ts.lpr.toString())
        val ge = evidence("ʒ e r", "ger", SoundId.Ge, position = Position.Initial)
        assertTrue(ge.lpr.getValue("ʒ") < -2f, ge.lpr.toString())
    }

    @Test
    fun zAtTheEndOfAWordMayBeVoiceless() {
        val e = evidence("o r e s", "orez", SoundId.Z, position = Position.Final)
        assertNull(e.lpr["s"], "word-final devoicing is not judged: ${e.lpr}")
        assertTrue(e.gop!! > -1f, e.lpr.toString())
        val mid = evidence("b u s ə", "buză", SoundId.Z, position = Position.Medial)
        assertTrue(mid.lpr.getValue("s") < -2f, mid.lpr.toString())
    }

    @Test
    fun anIsolatedSoundIsTheTargetAloneHeldOrRepeated() {
        val held = evidence("s s s", "ssss", SoundId.S, level = Level.Isolated)
        assertTrue(held.reliable, held.notes.toString())
        assertTrue(held.lpr.getValue("ʃ") > 2f, held.lpr.toString())
        val wrong = evidence("ʃ ʃ", "ssss", SoundId.S, level = Level.Isolated)
        assertTrue(wrong.lpr.getValue("ʃ") < -2f, wrong.lpr.toString())
    }

    @Test
    fun theOccurrenceAskedForSpeaks() {
        // „sas”: the first S right, the last said as Ș.
        val initial = evidence("s a ʃ", "sas", SoundId.S, position = Position.Initial)
        val final = evidence("s a ʃ", "sas", SoundId.S, position = Position.Final)
        val any = evidence("s a ʃ", "sas", SoundId.S, position = Position.Any)
        assertTrue(initial.lpr.getValue("ʃ") > 0f)
        assertTrue(final.lpr.getValue("ʃ") < -2f)
        assertEquals(final.lpr, any.lpr, "Any: the occurrence least likely correct")
    }

    @Test
    fun tooFewFramesOrAnotherWordAreUnreliable() {
        val short = spoken(mapOf("<blk>" to 0.9) to 5)
        val r = JudgeRequest(SoundId.L, Level.Word, "lac")
        val e = scorer.evidence(short, ZipaPhoneScorer.pronunciation(r, TargetSound.L)!!, r, TargetSound.L)
        assertFalse(e.reliable)
        assertTrue("tooShort" in e.notes)
        val other = evidence("m i n u n e", "lac", SoundId.L)
        assertFalse(other.reliable, other.toString())
        assertTrue("poorFit" in other.notes)
    }

    @Test
    fun noTargetInTheTextIsNoEvidence() {
        val r = JudgeRequest(SoundId.R, Level.Word, "lac")
        assertNull(ZipaPhoneScorer.pronunciation(r, TargetSound.R))
    }

    @Test
    fun theClipIs16kHzAndPadded() {
        val clip = Clip(FloatArray(48_000) { 0.1f }, 48_000.0)
        val x = ZipaPhoneScorer.to16k(clip)
        assertEquals(16_000, x.size)
        val padded = ZipaPhoneScorer.pad(x)
        assertEquals(16_000 + 2 * 4_000, padded.size)
        assertTrue(kotlin.math.abs(padded[100]) < 1e-3f)
    }

    @Test
    fun everyKeyIsAZipaSequence() {
        for (sound in TargetSound.entries) {
            val keys = ConfusionSets.keys(sound)
            assertEquals(keys.size, keys.toSet().size, sound.name)
            assertTrue(ConfusionSets.OMISSION in keys)
            for (c in ConfusionSets.of(sound)) assertTrue(c.variants.none { it in ConfusionSets.accepted(sound) }, "${sound.name} ${c.key}")
        }
    }
}
