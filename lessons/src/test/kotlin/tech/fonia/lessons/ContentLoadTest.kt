package tech.fonia.lessons

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tech.fonia.model.ErrorKind
import tech.fonia.model.Rung
import tech.fonia.model.SoundId

class ContentLoadTest {
    private val content = Fixtures.content

    @Test
    fun `every file of spec content v2 loads`() {
        assertEquals(2, content.index.schema)
        assertEquals(SoundId.entries.toSet(), content.sounds.keys)
        assertTrue(content.twisters.isNotEmpty())
        assertTrue(content.lines.isNotEmpty())
        assertEquals(6, content.parent.cards.size)
        assertEquals(10, content.parent.tips.size)
        val files = content.index.files + content.index.sounds.map { it.file } + "sounds.json" + "content.lock.json"
        files.forEach { assertTrue(File(Fixtures.contentDir, it).isFile, "missing $it") }
    }

    @Test
    fun `loads from strings, the way Android reads assets`() {
        val dir = Fixtures.contentDir
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "json" }
            .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readText() }
        val fromStrings = LessonContent.fromStrings(files)
        assertEquals(content.contentVersion, fromStrings.contentVersion)
        assertEquals(content.items(SoundId.S, Rung.WordsInitial), fromStrings.items(SoundId.S, Rung.WordsInitial))
    }

    @Test
    fun `a missing or broken file is a ContentException`() {
        assertFailsWith<ContentException> { LessonContent.fromStrings(emptyMap()) }
        val files = Fixtures.contentDir.walkTopDown().filter { it.isFile && it.extension == "json" }
            .associate { it.relativeTo(Fixtures.contentDir).invariantSeparatorsPath to it.readText() }.toMutableMap()
        files["sounds/r.json"] = "{ not json"
        assertFailsWith<ContentException> { LessonContent.fromStrings(files) }
    }

    @Test
    fun `every sound has material for every rung`() {
        for (sound in SoundId.entries) {
            val data = content.sound(sound)
            for (rung in Rung.entries) {
                when (rung) {
                    Rung.Warmup -> assertTrue(data.warmups.size >= 2, "${sound.raw} warm-ups")
                    Rung.Twister -> assertTrue(content.twisters(sound).size >= 2, "${sound.raw} twisters")
                    else -> assertTrue(content.items(sound, rung).isNotEmpty(), "${sound.raw} ${rung.raw}")
                }
                assertTrue(content.chapterKey(sound, rung).startsWith("open.${data.slug}."))
            }
            val warmIds = content.index.warmups.map { it.id }.toSet()
            data.warmups.forEach { assertTrue(it in warmIds, "${sound.raw}: warm-up $it is not in the library") }
        }
    }

    @Test
    fun `word counts meet the plan of chapter 06 §4`() {
        for (sound in SoundId.entries) {
            val data = content.sound(sound)
            for ((rungRaw, planned) in data.plan) {
                val n = content.items(sound, Rung.of(rungRaw)!!).size
                assertTrue(n >= planned, "${sound.raw} $rungRaw: $n < $planned")
            }
        }
    }

    @Test
    fun `text rules hold in every loaded string`() {
        val banned = Regex("(?i)\\b(greșit\\w*|diagnostic\\w*|terapi\\w*|tratează|risc\\w*|severitat\\w*|tulburar\\w*)\\b")
        val all = content.sounds.values.flatMap { d -> d.items.flatMap { listOfNotNull(it.text, it.prompt) + it.lines } +
            d.hero.chapters.values.map { it.text } } + content.lines.map { it.text } + content.twisters.map { it.text } +
            content.parent.tips + content.parent.privacy.paragraphs
        for (text in all) {
            assertTrue(text.none { it in "ŞşŢţ" }, "cedilla in '$text'")
            assertTrue(!banned.containsMatchIn(text), "banned word in '$text'")
        }
    }

    @Test
    fun `listen foils name real error kinds and error refs follow spec feedback`() {
        val kinds = ErrorKind.entries.map { it.raw }.toSet()
        for (data in content.sounds.values) {
            data.items.filter { it.type == "listen" }.forEach { assertTrue(it.mimics in kinds, "${it.id}: ${it.mimics}") }
            data.errors.forEach {
                assertTrue(it.kind in kinds)
                assertTrue(content.line(it.instructionKey) != null, "no Foni line for ${it.instructionKey}")
            }
        }
    }

    @Test
    fun `every line key the builder can emit exists`() {
        val fixed = listOf("greet.1", "greet.2", "greet.3", "greet.4", "greet.5", "move.1", "move.2", "move.3", "move.4",
            "step.listen", "step.listen.foil", "step.warmup", "step.hold.sustain", "step.hold.burst", "step.syllables",
            "step.syllables.split", "step.words", "step.words.final", "step.pairs", "step.sentence", "step.twister.slow",
            "step.twister.step", "step.retell", "step.mission", "step.surprise", "step.final", "model.repeat", "model.slow",
            "end.1", "end.2", "end.5", "end.6", "adult.5", "adult.6")
        fixed.forEach { assertTrue(content.line(it) != null, "foni_lines.json lacks $it") }
        content.index.warmups.forEach { assertTrue(content.line("warm.${it.id}") != null, "no warm.${it.id}") }
    }

    @Test
    fun `parent cards cover each rung once and templates name their variables`() {
        val covered = content.parent.cards.flatMap { it.rungs }
        assertEquals(Rung.entries.map { it.raw }.sorted(), covered.sorted())
        (content.parent.summaries + content.parent.reminders).forEach { t ->
            val used = Regex("\\{(\\w+)\\}").findAll(t.text).map { it.groupValues[1] }.toSet()
            assertEquals(t.vars.toSet(), used, t.id)
        }
        Rung.entries.forEach { assertTrue(it.raw in content.parent.rungNames) }
    }
}
