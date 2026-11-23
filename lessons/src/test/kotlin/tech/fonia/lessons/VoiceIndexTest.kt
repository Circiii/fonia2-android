package tech.fonia.lessons

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class VoiceIndexTest {
    private val indexFile = File(Fixtures.voiceDir, "index.json")

    @Test
    fun `human-needed and unknown keys have no clip`() {
        val index = VoiceIndex("Foni", mapOf("w.sanie" to VoiceClip("clips/w.sanie.m4a", "sanie", "word", "Foni", true)))
        assertEquals("clips/w.sanie.m4a", index.file("w.sanie"))
        assertNull(index.file(VoiceIndex.HUMAN_NEEDED))
        assertNull(index.file("w.nothing"))
        assertNull(index.file(null))
    }

    @Test
    fun `every checked clip exists and says the content's text`() {
        assumeTrue(indexFile.isFile, "no voice index yet")
        val index = VoiceIndex.fromDirectory(Fixtures.voiceDir)
        val content = Fixtures.content
        val texts = mutableMapOf<String, String>()
        content.lines.forEach { texts[it.key] = it.text }
        content.twisters.forEach { texts[it.voice] = it.text }
        content.sounds.values.forEach { d ->
            d.hero.chapters.values.forEach { texts[it.voice] = it.text }
            d.items.forEach { i ->
                when (i.type) {
                    "pair" -> { texts[i.voiceA!!] = i.a!!; texts[i.voiceB!!] = i.b!! }
                    "hold" -> texts[i.promptVoice!!] = i.prompt!!
                    "story" -> i.lineVoices.zip(i.lines).forEach { (k, l) -> texts[k] = l }
                    else -> i.voice?.let { texts[it] = i.text }
                }
            }
        }
        for ((key, clip) in index.clips) {
            assertTrue(clip.checked, "$key did not pass voice_check.py")
            assertTrue(File(Fixtures.voiceDir, clip.file).isFile, "$key: ${clip.file} missing")
            texts[key]?.let { assertEquals(it, clip.text, key) }
        }
        val referenced = texts.keys.filter { it != VoiceIndex.HUMAN_NEEDED }
        val covered = referenced.count { index.has(it) }
        assertTrue(covered > 0)
    }
}
