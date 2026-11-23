package tech.fonia.collector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId
import java.io.File

fun specFile(): PromptFile {
    val dir = System.getProperty("fonia.collector.spec") ?: "../spec/collector"
    return PromptCatalog.parse(File(dir, "prompts.json").readText())
}

class PromptsTest {
    private val file = specFile()

    @Test
    fun specIsValid() {
        val problems = PromptCatalog.validate(file)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun packsCoverTheNineSounds() {
        assertEquals(listOf("A", "B", "C"), file.packs.map { it.id })
        assertEquals(listOf("s", "ș", "z", "j"), file.pack("A")!!.sounds)
        assertEquals(listOf("r", "l"), file.pack("B")!!.sounds)
        assertEquals(listOf("ț", "ce", "ge"), file.pack("C")!!.sounds)
        assertEquals(SoundId.entries.map { it.raw }.toSet(), file.sounds.map { it.sound }.toSet())
    }

    @Test
    fun correctItemsPerSoundFollowTheTable() {
        for (spec in file.sounds) {
            val correct = PromptCatalog.correctPrompts(spec)
            assertEquals(3, correct.count { it.level == Level.Isolated })
            assertEquals(6, correct.count { it.level == Level.Syllable })
            assertEquals(9, correct.count { it.level == Level.Word })
            assertEquals(2, correct.count { it.level == Level.Sentence })
            for (p in Position.entries) assertEquals(3, correct.count { it.position == p })
        }
    }

    @Test
    fun promptIdsFollowTheExample() {
        val s = PromptCatalog.adultPrompts(file.sound("s")!!, therapist = true)
        assertTrue(s.any { it.promptId == "s_w_ini_sare" && it.label == "correct" })
        val interdental = s.single { it.key == "s_w_ini_sare__Interdental" }
        assertEquals(ErrorKind.Interdental, interdental.kind)
        assertEquals("Interdental", interdental.intendedLabel)
        val swap = s.single { it.key == "s_w_ini_sare__PlaceSwap" }
        assertEquals("șare", swap.imitation)
        assertEquals("PlaceSwap:ș", swap.intendedLabel)
        assertTrue(s.any { it.promptId == "s_iso_1" } && s.any { it.promptId == "s_syl_sa" } && s.any { it.promptId == "s_sen_2" })
        val sh = PromptCatalog.correctPrompts(file.sound("ș")!!)
        assertTrue(sh.any { it.promptId == "sh_w_med_masina" })
    }

    @Test
    fun interdentalAndLateralAreForTherapistsOnly() {
        val adult = PromptCatalog.plan(file, "A", Role.Adult)
        val therapist = PromptCatalog.plan(file, "A", Role.Therapist)
        assertFalse(adult.any { it.kind == ErrorKind.Interdental || it.kind == ErrorKind.Lateral })
        assertTrue(therapist.any { it.kind == ErrorKind.Interdental } && therapist.any { it.kind == ErrorKind.Lateral })
        assertEquals(170, adult.size)
        assertEquals(210, therapist.size)
        assertEquals(85, PromptCatalog.plan(file, "B", Role.Adult).size)
        assertEquals(140, PromptCatalog.plan(file, "C", Role.Adult).size)
        assertEquals(155, PromptCatalog.plan(file, "C", Role.Therapist).size)
    }

    @Test
    fun errorsComeAfterCorrectItemsOfTheSameSound() {
        val plan = PromptCatalog.plan(file, "A", Role.Adult)
        for (sound in plan.map { it.sound }.distinct()) {
            val items = plan.filter { it.sound == sound }
            val firstError = items.indexOfFirst { it.isError }
            assertEquals(20, firstError)
            assertTrue(items.drop(firstError).all { it.isError })
        }
    }

    @Test
    fun twoVariantsOfOneKindGetDistinctLabels() {
        val l = PromptCatalog.adultPrompts(file.sound("l")!!, therapist = false)
        val labels = l.filter { it.kind == ErrorKind.LtoGlide }.map { it.label }.toSet()
        assertEquals(setOf("LtoGlide-i", "LtoGlide-u"), labels)
    }

    @Test
    fun childModeHasSixPictureWordsPerSound() {
        val plan = PromptCatalog.plan(file, "A", Role.Child)
        assertEquals(24, plan.size)
        assertTrue(plan.all { it.level == Level.Word && it.pic != null && !it.isError })
        assertEquals(12, PromptCatalog.plan(file, "B", Role.Child).size)
        assertEquals(18, PromptCatalog.plan(file, "C", Role.Child).size)
    }

    @Test
    fun everyErrorKindNameExists() {
        for (spec in file.sounds) for (e in spec.errors) assertTrue(e.kind, ErrorKind.entries.any { it.name == e.kind })
    }

    @Test
    fun estimatedMinutes() {
        assertEquals(13, PromptCatalog.estimatedMinutes(170))
        assertEquals(4, PromptCatalog.estimatedMinutes(24, child = true))
    }

    @Test
    fun validationCatchesAMisplacedWord() {
        val s = file.sound("s")!!
        val broken = file.copy(sounds = file.sounds.map { if (it === s) s.copy(finalWords = listOf("sare", "os", "nas")) else it })
        assertTrue(PromptCatalog.validate(broken).any { "'sare' has no S final" in it })
    }
}

class SoundSpotterTest {
    @Test
    fun singleLetterSounds() {
        assertEquals(setOf(Position.Initial), SoundSpotter.positions("sare", SoundId.S))
        assertEquals(setOf(Position.Medial), SoundSpotter.positions("masă", SoundId.S))
        assertEquals(setOf(Position.Final), SoundSpotter.positions("nas", SoundId.S))
        assertEquals(setOf(Position.Initial, Position.Medial), SoundSpotter.positions("țurțur", SoundId.Ts))
        assertTrue(SoundSpotter.positions("șase", SoundId.S).contains(Position.Medial))
        assertTrue(SoundSpotter.positions("sare", SoundId.Sh).isEmpty())
    }

    @Test
    fun affricatesFollowTheSpelling() {
        assertEquals(setOf(Position.Initial), SoundSpotter.positions("ceas", SoundId.Ce))
        assertEquals(setOf(Position.Medial), SoundSpotter.positions("dulce", SoundId.Ce))
        assertEquals(setOf(Position.Final), SoundSpotter.positions("arici", SoundId.Ce))
        assertEquals(setOf(Position.Final), SoundSpotter.positions("covrigi", SoundId.Ge))
        assertTrue(SoundSpotter.positions("chec", SoundId.Ce).isEmpty())
        assertTrue(SoundSpotter.positions("ghiozdan", SoundId.Ge).isEmpty())
        assertTrue(SoundSpotter.positions("legume", SoundId.Ge).isEmpty())
        // ariciul (1) + cinci (2) + nuci (1)
        assertEquals(4, SoundSpotter.count("Ariciul are cinci nuci.", SoundId.Ce))
    }
}
