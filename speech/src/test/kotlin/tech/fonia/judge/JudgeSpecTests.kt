package tech.fonia.judge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tech.fonia.model.SoundId
import java.io.File

/** The data files load, agree with each other, and keep the product rules (docs/REGULI.md "Texte", plan 03 §7). */
class JudgeSpecTests {
    private val spec = loadSpec()

    @Test
    fun everySoundHasAFusionSpecWithBothHeads() {
        for (sound in SoundId.entries) {
            val f = spec.fusionFor(sound)
            assertEquals(sound, f.soundId)
            assertTrue("fast" in f.heads && "full" in f.heads, sound.raw)
            assertTrue(f.source.contains("not trained"), "${sound.raw}: v0 must say it is hand-set")
            assertEquals(Features.VERSION, f.featureVersion)
        }
    }

    @Test
    fun theFullHeadIsTheFormulaOfItsRecordedConstants() {
        for (sound in SoundId.entries) {
            val f = spec.fusionFor(sound)
            val params = f.fullFrom ?: error("${sound.raw}: v1 records how its full head was made (fullFrom)")
            val expected = FullHead.build(f, params)
            val actual = f.heads.getValue("full")
            assertEquals(1f, actual.temperature, sound.raw)
            for (k in f.classes.indices) {
                assertEquals(expected.bias[k], actual.bias[k], 1e-3f, "${sound.raw} bias ${f.classes[k]}")
                for (i in f.inputs.indices) {
                    assertEquals(expected.weights[k][i], actual.weights[k][i], 1e-3f, "${sound.raw} ${f.classes[k]} × ${f.inputs[i].name}")
                }
            }
            for ((cls, key) in f.phoneMap) {
                assertTrue(cls in f.classes, "${sound.raw}: phoneMap class $cls")
                assertTrue(f.inputs.any { it.name == Features.LPR_PREFIX + key }, "${sound.raw}: no input lpr.$key")
            }
            // The fast head never reads the phone model.
            val fast = f.heads.getValue("fast")
            for ((i, input) in f.inputs.withIndex()) if (input.name.startsWith(Features.LPR_PREFIX) || input.name.startsWith("zipa.")) {
                assertTrue(fast.weights.all { it[i] == 0f }, "${sound.raw}: fast head weight on ${input.name}")
            }
        }
    }

    @Test
    fun loadsFromStringsAsAndroidAssetsWould() {
        val dir = specDir()
        val fusion = SoundId.entries.map { File(dir, "fusion/${JudgeSpec.fusionFileName(it)}").readText() }
        val fromText = JudgeSpec.fromStrings(
            File(dir, "thresholds/population.json").readText(), fusion,
            File(dir, "unlock.json").readText(), File(dir, "feedback.json").readText(),
        )
        assertEquals(spec.versions(SoundId.S), fromText.versions(SoundId.S))
    }

    @Test
    fun unlockedEntriesNeedEvidence() {
        assertEquals(emptyList<String>(), spec.unlock.problems())
        val bad = spec.unlock.copy(entries = spec.unlock.entries.map { if (it.sound == "s" && it.kind == "placeSwap") it.copy(state = "unlocked") else it })
        assertTrue(bad.problems().any { it.contains("without evidence") })
    }

    @Test
    fun everyKindOfEverySoundHasAnUnlockEntryAndFeedback() {
        val feedback = Feedback(spec.feedback)
        for (sound in SoundId.entries) {
            for (kind in spec.fusionFor(sound).kinds) {
                assertNotNull(spec.unlock.entry(sound, kind), "unlock ${sound.raw}/${kind.raw}")
                assertNotNull(feedback.forKind(sound, kind), "feedback ${sound.raw}/${kind.raw}")
            }
        }
        for (reason in RetakeReason.entries) assertNotNull(feedback.forRetake(reason), reason.raw)
        for (key in listOf("correct", "almost", "unsure")) assertNotNull(feedback.forVerdict(key), key)
    }

    @Test
    fun waveOneIsInTrialAndNothingIsUnlockedWithoutEvidence() {
        val trial = spec.unlock.entries.filter { it.state == "trial" }.map { "${it.sound}/${it.kind}" }.toSet()
        for (k in listOf("s/placeSwap", "ș/placeSwap", "z/devoicing", "j/devoicing", "s/stopping", "s/omission", "r/rToL", "s/weakOrShort")) {
            assertTrue(k in trial, k)
        }
        assertTrue(spec.unlock.entries.none { it.state == "unlocked" })
    }

    private val forbidden = listOf("greșit", "gresit", "diagnostic", "terapie", "tratează", "trateaza", "risc", "severitate", "tulburare")

    @Test
    fun childLinesAreShortKindAndUseCommaBelow() {
        val lines = spec.feedback.kinds.map { it.child } + spec.feedback.retake.values.map { it.child } +
            spec.feedback.verdicts.values.map { it.child }
        val adult = spec.feedback.kinds.map { it.adult } + spec.feedback.retake.values.mapNotNull { it.adult }
        for (line in lines) {
            val words = line.split(Regex("\\s+")).count { w -> w.any { it.isLetter() } }
            assertTrue(words <= 8, "more than 8 words: $line")
        }
        for (line in lines + adult) {
            val lower = line.lowercase()
            assertTrue(forbidden.none { lower.contains(it) }, "forbidden word in: $line")
            assertTrue(!line.contains('ş') && !line.contains('ţ') && !line.contains('Ş') && !line.contains('Ţ'), "cedilla in: $line")
        }
    }

    @Test
    fun animationsComeFromChapterFive() {
        val known = setOf(
            "comma-drop", "swap-glide", "trill-ripple", "voicing-wave", "air-to-centre", "tongue-behind-teeth", "closure-snap",
            "sag-and-refill", "slot-drop",
        )
        for (k in spec.feedback.kinds) assertTrue(k.animation in known, "${k.sound}/${k.kind}: ${k.animation}")
    }
}
