package tech.fonia.features.reading

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/**
 * The draft lists and texts of `assets/drafts/reading.json`: what the speech therapist will review, checked for what
 * a machine can check. Each list has the syllables of its name, by the hand-made split and by an independent count;
 * every list word is in the permissive lexicon it was chosen from; the texts are 50 to 150 words; set B matches set A;
 * and the copy follows `tools/lint_copy.py`.
 */
class ReadingContentTests {
    private val content = readingContent
    private val lists = content.probes.filter { it.kind != ProbeKind.Text }
    private val texts = content.probes.filter { it.kind == ProbeKind.Text }

    @Test
    fun itIsADraftUntilALogopedApprovesIt() {
        assertEquals("ciornă", content.status)
        assertNull(content.approvedBy)
        assertTrue(content.isDraft)
        assertTrue(content.notice, "logoped" in content.notice)
        assertTrue(content.notice, "PEAFC" in content.notice)
    }

    @Test
    fun twoSetsOfTheFourProbes() {
        assertEquals(listOf("A", "B"), content.sets)
        for (set in content.sets) {
            assertEquals(ProbeKind.entries.toList(), content.probes.filter { it.set == set }.map { it.kind })
        }
        assertEquals(content.probes.size, content.probes.map { it.id }.toSet().size)
    }

    @Test
    fun everyListWordHasTheSyllablesOfItsList() {
        for (probe in lists) {
            val syllables = probe.kind.syllables!!
            for (word in probe.words) {
                assertEquals("${probe.id}: ${word.syllables}", syllables, word.syllableCount)
                assertEquals("${probe.id}: ${word.text} by rule", syllables, RomanianSyllables.count(word.text))
                assertEquals(word.text, word.syllables.joinToString(""))
                word.syllables.forEachIndexed { index, chunk ->
                    assertTrue("${probe.id}: \"$chunk\" in ${word.text}", RomanianSyllables.isOneSyllable(chunk, index == word.syllables.lastIndex))
                }
                assertTrue("${probe.id}: ${word.text}", word.text.all { it in "abcdefghijklmnopqrstuvwxyzăâîșț" })
            }
        }
    }

    @Test
    fun theListsHaveRoomForAFastReader() {
        // The norms' mean is 41, 38 and 26 words a minute (SD 20, 18, 13): a list runs out only past about one SD above.
        val sizes = mapOf(ProbeKind.Mono to 60, ProbeKind.Bi to 50, ProbeKind.Tri to 40)
        for (probe in lists) assertEquals(probe.id, sizes.getValue(probe.kind), probe.words.size)
    }

    @Test
    fun listWordsComeFromThePermissiveLexicon() {
        val file = File(repositoryRoot, "legacy/features/src/test/resources/reading/romanian_cv.dict")
        // The vendored copy is the upstream file, byte for byte (line ends normalised, whatever git does on Windows).
        val bytes = file.readText(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("5e4b905dfcfbfb0f59e2c83aa8ae3cd9a6195dc5daf1dacc5618c28300e14687", digest)
        val lexicon = bytes.toString(Charsets.UTF_8).lines().map { it.substringBefore('\t') }.toSet()
        for (probe in lists) {
            for (word in probe.words) assertTrue("${probe.id}: ${word.text} is not in MFA romanian_cv", word.text in lexicon)
        }
    }

    @Test
    fun noWordRepeatsAndTheSetsShareNoListWord() {
        for (probe in lists) assertEquals(probe.id, probe.words.size, probe.words.map { it.text }.toSet().size)
        for (kind in listOf(ProbeKind.Mono, ProbeKind.Bi, ProbeKind.Tri)) {
            val a = content.probe(kind, "A")!!.words.map { it.text }.toSet()
            val b = content.probe(kind, "B")!!.words.map { it.text }.toSet()
            assertTrue("$kind: ${a intersect b}", (a intersect b).isEmpty())
        }
    }

    @Test
    fun textsAreShortAndTheirSyllablesAddUp() {
        for (probe in texts) {
            assertTrue("${probe.id}: ${probe.words.size} words", probe.words.size in 50..150)
            for (word in probe.words) {
                val letters = ReadingContent.lettersOf(word.text)
                assertEquals("${probe.id}: ${word.text}", RomanianSyllables.count(letters), word.syllableCount)
                assertEquals(letters, word.syllables.joinToString(""))
                word.syllables.forEachIndexed { index, chunk ->
                    assertTrue("${probe.id}: \"$chunk\" in ${word.text}", RomanianSyllables.isOneSyllable(chunk, index == word.syllables.lastIndex))
                }
            }
        }
    }

    @Test
    fun setBMatchesSetA() {
        for (kind in ProbeKind.entries) {
            val a = content.probe(kind, "A")!!
            val b = content.probe(kind, "B")!!
            val letters = { probe: ReadingProbe, count: Int -> probe.words.take(count).sumOf { ReadingContent.lettersOf(it.text).length } }
            if (kind == ProbeKind.Text) {
                assertTrue("words ${a.words.size} / ${b.words.size}", within(a.words.size, b.words.size, 0.05))
                assertTrue("syllables ${a.totalSyllables} / ${b.totalSyllables}", within(a.totalSyllables, b.totalSyllables, 0.05))
                assertTrue("letters", within(letters(a, a.words.size), letters(b, b.words.size), 0.05))
            } else {
                assertEquals(a.words.size, b.words.size)
                assertTrue("$kind letters", within(letters(a, a.words.size), letters(b, b.words.size), 0.03))
                // Most children read only the first part of a list in a minute, so the start has to match too.
                assertTrue("$kind first 30", within(letters(a, 30), letters(b, 30), 0.05))
                val marked = { probe: ReadingProbe -> probe.words.count { word -> word.text.any { it in "ăâîșț" } } }
                assertTrue("$kind diacritics ${marked(a)} / ${marked(b)}", abs(marked(a) - marked(b)) <= 2)
            }
        }
    }

    @Test
    fun eachSettingKeepsLinesShort() {
        for (preset in ReadingPreset.entries) {
            assertTrue(preset.title, preset.maxWordsPerLine in 5..8)
            // Wider letters only ever come with wider word gaps (A §6.1 [55]).
            assertTrue(preset.title, preset.wordGap >= ReadingPreset.Standard.wordGap)
        }
        val byLetters = ReadingPreset.entries.sortedBy { it.letterSpacing }
        assertEquals(byLetters, ReadingPreset.entries.sortedBy { it.wordGap })
    }

    @Test
    fun theCopyFollowsTheLint() {
        // The checker itself finds what the script finds, and the product words.
        assertEquals(4, CopyRules.problems("O soluție puternică, fără risc. \u0162").size)
        val problems = mutableListOf<String>()
        fun walk(node: JsonElement, where: String) {
            when (node) {
                is JsonPrimitive -> node.contentOrNull?.takeIf { node.isString }?.let { text ->
                    CopyRules.problems(text).forEach { problems += "$where: $it" }
                    if (';' in text) problems += "$where: semicolon"
                }
                is JsonObject -> node.forEach { (key, value) -> walk(value, "$where/$key") }
                is JsonArray -> node.forEachIndexed { index, value -> walk(value, "$where/$index") }
            }
        }
        walk(Json.parseToJsonElement(readingJSON()), "")
        val raw = readingJSON()
        if (raw.any { it in CopyRules.CEDILLAS }) problems += "cedilla in the file"
        assertEquals(emptyList<String>(), problems)
    }

    private fun within(a: Int, b: Int, share: Double) = abs(a - b) <= share * maxOf(a, b)
}
