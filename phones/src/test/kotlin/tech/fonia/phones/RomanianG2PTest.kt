package tech.fonia.phones

import org.junit.jupiter.api.Test
import tech.fonia.domain.ContentSource
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.SoundID
import tech.fonia.domain.WordItem
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RomanianG2PTest {
    private fun ipa(word: String) = RomanianG2P.pronounce(word).ipa

    @Test
    fun consonantsOfThePracticeSounds() {
        assertEquals("l a k", ipa("lac"))
        assertEquals("r a k", ipa("rac"))
        assertEquals("ts a p", ipa("țap"))
        assertEquals("ʃ o k", ipa("șoc"))
        assertEquals("ʒ o k", ipa("joc"))
        assertEquals("z ə p a d ə", ipa("zăpadă"))
        assertEquals("k s i l o f o n", ipa("xilofon"))
    }

    @Test
    fun ceCiGeGiAreAffricatesAndTheirMarkVowelIsSilent() {
        assertEquals("tʃ a p ə", ipa("ceapă"))
        assertEquals("tʃ a s", ipa("ceas"))
        assertEquals("tʃ a j", ipa("ceai"))
        assertEquals("tʃ o k o l a t ə", ipa("ciocolată"))
        assertEquals("tʃ u p e r k ə", ipa("ciupercă"))
        assertEquals("tʃ i r e ʃ e", ipa("cireșe"))
        assertEquals("dʒ e m", ipa("gem"))
        assertEquals("dʒ a n t ə", ipa("geantă"))
        assertEquals("dʒ i r a f ə", ipa("girafă"))
        assertEquals("m ə r dʒ e l e", ipa("mărgele"))
        assertEquals("ɨ n dʒ e r", ipa("înger"))
        // che/chi, ghe/ghi are velar.
        assertEquals("o k e l a r i", ipa("ochelari"))
        assertEquals("r o k i e", ipa("rochie"))
        assertEquals("g e m", ipa("ghem"))
    }

    @Test
    fun finalIAfterAConsonantIsWrittenIAsZipaWritesIt() {
        assertEquals("a r i tʃ i", ipa("arici"))
        assertEquals("n u tʃ i", ipa("nuci"))
        assertEquals("k o v r i dʒ i", ipa("covrigi"))
        assertEquals("f u l dʒ i", ipa("fulgi"))
        assertEquals("b ə j e ts i", ipa("băieți"))
    }

    @Test
    fun vowelsDiphthongsAndGlides() {
        assertEquals("ʃ o a r e tʃ e", ipa("șoarece"))
        assertEquals("ts e s t o a s ə", ipa("țestoasă"))
        assertEquals("l a l e a", ipa("lalea"))
        assertEquals("l e w", ipa("leu"))
        assertEquals("ʒ e l e w", ipa("jeleu"))
        assertEquals("d i n o z a w r", ipa("dinozaur"))
        assertEquals("a w t o b u z", ipa("autobuz"))
        assertEquals("j e p u r e", ipa("iepure"))
        assertEquals("k ɨ j n i", ipa("câini"))
        assertEquals("p ə l ə r i e", ipa("pălărie"))
        assertEquals("k o p i j", ipa("copii"))
        assertEquals("k a j", ipa("cai"))
    }

    @Test
    fun pronounsStartWithJ() {
        assertEquals("j e l", ipa("el"))
        assertEquals("j e l e", ipa("ele"))
        assertEquals("e s", ipa("es"))
        assertEquals("e l e f a n t", ipa("elefant"))
    }

    @Test
    fun cedillasCapitalsAndWordBoundaries() {
        assertEquals(ipa("șoarece"), ipa("Şoarece"))
        assertEquals(ipa("țap"), ipa("ţap"))
        val drill = RomanianG2P.pronounce("sa-sa-sa")
        assertEquals(listOf("▁", "s", "a", "▁", "s", "a", "▁", "s", "a"), drill.labels)
        assertEquals(listOf(0, 2, 4), drill.occurrences(TargetSound.S))
    }

    @Test
    fun targetsCarryTheirSoundAndLetters() {
        val ceapa = RomanianG2P.pronounce("ceapă")
        assertEquals(TargetSound.Ce, ceapa.phones[0].sound)
        assertEquals(0..1, ceapa.phones[0].letters)
        assertEquals(listOf("t", "ʃ"), ceapa.phones[0].tokens)
        val rinocer = RomanianG2P.pronounce("rinocer")
        assertEquals(listOf(0, 6), rinocer.occurrences(TargetSound.R))
        assertEquals(listOf(TargetSound.R, TargetSound.Ce), rinocer.sounds)
        assertEquals(6..6, rinocer.phones[6].letters)
    }

    @Test
    fun everyTokenIsAZipaToken() {
        for (word in contentWords()) {
            for (label in RomanianG2P.pronounce(word).labels) assertTrue(ZipaInventory.contains(label), "$word: $label")
        }
    }

    @Test
    fun soundIdsMatchTheDomain() {
        assertEquals(SoundID.entries.map { it.rawValue }.toSet(), TargetSound.entries.map { it.rawValue }.toSet())
    }

    // MARK: - The word lists of sounds.json

    /** Where the practice sound sits, as `position_ok` in `content/tools/check_ro_content.py` reads it. */
    private fun positionOk(pronunciation: Pronunciation, sound: TargetSound, where: String): Boolean {
        val phones = pronunciation.phones
        val at = pronunciation.occurrences(sound)
        if (at.isEmpty()) return false
        val last = phones.lastIndex
        return when (where) {
            "initial" -> at.first() == 0
            // „arici”, „covrigi”: the asyllabic final i after the target.
            "final" -> at.last() == last || (at.last() == last - 1 && phones[last].tokens == listOf("i"))
            else -> at.any { it in 1 until last }
        }
    }

    @Test
    fun everyPracticeWordHasItsSoundWhereItsListSays() {
        val failures = ArrayList<String>()
        for (sound in content.sounds) {
            val target = TargetSound.fromRawValue(sound.id.rawValue)!!
            val lists = mapOf("initial" to sound.words.initial, "medial" to sound.words.medial, "final" to sound.words.final)
            for ((where, words) in lists) {
                for (word in words) {
                    val pronunciation = RomanianG2P.pronounce(word.text)
                    if (!positionOk(pronunciation, target, where)) failures += "${sound.id.rawValue} $where: $pronunciation"
                }
            }
            for (word in sound.words.clusters) {
                if (RomanianG2P.pronounce(word.text).occurrences(target).isEmpty()) failures += "${sound.id.rawValue} cluster: ${word.text}"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun theOtherSoundsOfEachWordAreTheOnesTheContentLists() {
        val failures = ArrayList<String>()
        for (sound in content.sounds) {
            for (word in sound.allWords) {
                val found = RomanianG2P.pronounce(word.text).sounds.map { it.rawValue }.toSet() - sound.id.rawValue
                val listed = word.otherSounds.map { it.rawValue }.toSet()
                if (found != listed) failures += "${sound.id.rawValue}: ${word.text} G2P $found, content $listed"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun everySyllableDrillHoldsItsSound() {
        for (sound in content.sounds) {
            val target = TargetSound.fromRawValue(sound.id.rawValue)!!
            val drills = sound.syllables.direct + sound.syllables.inverse + sound.syllables.between + sound.syllables.repeated
            for (drill in drills) {
                assertTrue(RomanianG2P.pronounce(drill).occurrences(target).isNotEmpty(), "${sound.id.rawValue}: $drill")
            }
        }
    }

    /** The single phone where [a] and [b] differ, or null when they differ elsewhere or in length. */
    private fun minimalDifference(a: Pronunciation, b: Pronunciation): Int? {
        if (a.phones.size != b.phones.size) return null
        val differing = a.phones.indices.filter { a.phones[it].tokens != b.phones[it].tokens }
        return differing.singleOrNull()
    }

    @Test
    fun contrastPairsDifferByTheirTwoSoundsOnly() {
        for (sound in content.sounds) {
            for (contrast in sound.contrasts) {
                for (pair in contrast.pairs.filter { !it.isSuggested }) {
                    val a = RomanianG2P.pronounce(pair.a.text)
                    val b = RomanianG2P.pronounce(pair.b.text)
                    val at = minimalDifference(a, b)
                    assertTrue(at != null, "$a and $b are not a minimal pair")
                    assertEquals(sound.id.rawValue, a.phones[at].sound?.rawValue, "$a")
                    assertEquals(contrast.with.rawValue, b.phones[at].sound?.rawValue, "$b")
                }
            }
        }
    }

    @Test
    fun theSuggestedPairCeapaTeapaIsNotMinimal() {
        // A pair proposed for logoped review: the e of „țeapă” is a vowel, the e of „ceapă” only marks CE
        // (/tʃapə/ against /tseapə/), which RoLEX also says (fonia-android-plan 04 §2.4).
        assertEquals(null, minimalDifference(RomanianG2P.pronounce("ceapă"), RomanianG2P.pronounce("țeapă")))
        assertEquals(1, minimalDifference(RomanianG2P.pronounce("ace"), RomanianG2P.pronounce("ațe")))
    }

    companion object {
        private val loaded: Result<FoniaContent> by lazy {
            runCatching {
                val root = System.getProperty("fonia.content") ?: error("fonia.content is not set: run the tests through Gradle")
                FoniaContent.load(ContentSource.directory(File(root)))
            }
        }

        /** The content submodule; a worktree without it (`git submodule update --init`, or `-Pfonia.content=<dir>`) skips. */
        val content: FoniaContent
            get() {
                org.junit.jupiter.api.Assumptions.assumeTrue(loaded.isSuccess) { "content not available: ${loaded.exceptionOrNull()?.message}" }
                return loaded.getOrThrow()
            }

        fun contentWords(): List<String> = content.sounds.flatMap { sound ->
            sound.allWords.map(WordItem::text) + sound.syllables.direct + sound.syllables.inverse + sound.syllables.between +
                sound.contrasts.flatMap { c -> c.pairs.flatMap { listOf(it.a.text, it.b.text) } }
        }.distinct()
    }
}
