package tech.fonia.domain

import java.io.File
import java.util.Collections
import kotlinx.serialization.json.Json

// Port of FoniaDomainTests/Support.swift.

object Fixture {
    val content: FoniaContent by lazy { FoniaContent.load(ContentSource.directory(Paths.content)) }

    /** Built with the recorded clips, as iOS's `SessionBuilder(content:)` takes the bundled `VoiceIndex`. */
    val builder: SessionBuilder by lazy { SessionBuilder(content) { voiceIndex.clipPath(it, Language.En) != null } }

    val voiceIndex: VoiceIndex by lazy {
        VoiceIndex(Json.decodeFromString<Map<String, String>>(Paths.manifest.readText(Charsets.UTF_8)))
    }
}

/**
 * Paths in the content submodule, which Gradle passes as the `fonia.content` system property. iOS reached the same
 * files through the source tree: the JSON in `FoniaDomain/Resources`, `Resources/VoiceClips/manifest.json` and
 * `Pictures.xcassets`.
 */
object Paths {
    val content: File = File(System.getProperty("fonia.content") ?: error("fonia.content is not set: run the tests through Gradle"))
    val json = File(content, "json")

    /** `Pictures.xcassets` on iOS: one PNG per picture, named after it. */
    val pictures = File(content, "images/pictures")

    /** The asset-catalog properties of every image (each imageset's `Contents.json` on iOS). */
    val imageCatalog = File(content, "images/catalog.json")
    val manifest = File(content, "voice/manifest.json")

    val jsonFiles = listOf("sounds", "english", "diction", "exercises", "coach_plans", "listening")

    fun jsonText(name: String): String = File(json, "$name.json").readText(Charsets.UTF_8)

    /** iOS `tools/missing-clips.txt`. fonia-content does not carry it yet, so the test resources hold a verbatim copy. */
    fun missingClipsText(): String =
        Paths::class.java.getResourceAsStream("/missing-clips.txt")!!.use { it.readBytes().decodeToString() }

    /**
     * Android `tools/voice/human-needed.txt`: texts ElevenLabs could not say right, so they have no clip and wait for a
     * person to record them. Android never speaks a text with the phone's voice, so every other child-facing text needs
     * its clip. Lines: `group | language voice | text | file`.
     */
    fun humanNeededText(): String =
        Paths::class.java.getResourceAsStream("/human-needed.txt")!!.use { it.readBytes().decodeToString() }
}

/** Spelling rules for Romanian, kept apart from the JSON so tests can check the JSON against them. */
object Romanian {
    private val vowels = "aăâeiîou".characters().map(::nfc).toSet()

    /** One sound per token: "ceapă" gives CE e a p ă, and "rochie" keeps "ch" whole. */
    fun tokens(word: String): List<String> {
        // Swift's Array(word.lowercased()): Characters, which compare by canonical equivalence (NFC here).
        val letters = swiftLowercased(word).characters().map(::nfc)
        val tokens = mutableListOf<String>()
        var index = 0
        while (index < letters.size) {
            val letter = letters[index]
            val next = letters.getOrNull(index + 1)
            if ((letter == "c" || letter == "g") && next == "h") {
                tokens += letter + "h"
                index += 2
            } else if (letter == "c" && (next == "e" || next == "i")) {
                tokens += "CE"
                index += 1
            } else if (letter == "g" && (next == "e" || next == "i")) {
                tokens += "GE"
                index += 1
            } else {
                tokens += letter
                index += 1
            }
        }
        return tokens
    }

    fun targetSounds(word: String): List<SoundID> {
        val found = mutableListOf<SoundID>()
        for (token in tokens(word)) {
            val sound = when (token) {
                "s", "x" -> SoundID.S
                "ș" -> SoundID.Sh
                "z" -> SoundID.Z
                "j" -> SoundID.J
                "ț" -> SoundID.Ts
                "CE" -> SoundID.Ce
                "GE" -> SoundID.Ge
                "r" -> SoundID.R
                "l" -> SoundID.L
                else -> null
            }
            if (sound != null && sound !in found) found += sound
        }
        return found
    }

    /** Runs of two or more consonants, with "ch" and "gh" counted as one consonant. */
    fun clusters(word: String): List<String> {
        val clusters = mutableListOf<String>()
        var run = mutableListOf<String>()
        for (token in tokens(word) + "a") {
            if (token.characterCount == 1 && token in vowels) {
                if (run.size >= 2) {
                    clusters += run.joinToString("").replace("CE", "c").replace("GE", "g")
                }
                run = mutableListOf()
            } else {
                run += token
            }
        }
        return clusters
    }

    fun differByOneSound(first: String, second: String): Boolean {
        val a = tokens(first)
        val b = tokens(second)
        return a.size == b.size && a.zip(b).count { (x, y) -> x != y } == 1
    }
}

/** Whole phonemes in a British IPA transcription: long vowels, diphthongs and affricates stay in one piece. */
object IPA {
    private val multi = listOf("aɪ", "aʊ", "eɪ", "ɔɪ", "əʊ", "eə", "ɪə", "ʊə", "tʃ", "dʒ")

    fun phonemes(text: String): List<String> {
        val phonemes = mutableListOf<String>()
        val characters = text.characters()
        var rest = 0
        while (rest < characters.size) {
            val first = characters[rest]
            val match = multi.firstOrNull { characters.hasPrefix(rest, it) }
            if (match != null) {
                phonemes += match
                rest += match.characterCount
            } else if (canonicallyEqual(first, "ː") && phonemes.isNotEmpty()) {
                val last = phonemes.removeAt(phonemes.lastIndex)
                phonemes += last + "ː"
                rest += 1
            } else {
                phonemes += first
                rest += 1
            }
        }
        return phonemes
    }

    fun differByOnePhoneme(first: String, second: String): Boolean {
        val a = phonemes(first)
        val b = phonemes(second)
        return a.size == b.size && a.zip(b).count { (x, y) -> x != y } == 1
    }

    private fun List<String>.hasPrefix(start: Int, prefix: String): Boolean {
        val wanted = prefix.characters()
        if (start + wanted.size > size) return false
        return wanted.indices.all { canonicallyEqual(this[start + it], wanted[it]) }
    }
}

val Session.attempts: List<Trial> get() = trials.filter { it.countsAsAttempt }

/** Swift's `trials(in:)`. */
fun Session.trialsIn(kind: SessionBlock.Kind): List<Trial> = trials.filter { it.block.kind == kind }

// Swift's hasPrefix, hasSuffix and contains on Strings: whole Characters, compared by canonical equivalence.

private fun String.nfcCharacters(): List<String> = characters().map(::nfc)

fun String.swiftHasPrefix(prefix: String): Boolean {
    val text = nfcCharacters()
    val wanted = prefix.nfcCharacters()
    return text.size >= wanted.size && text.subList(0, wanted.size) == wanted
}

fun String.swiftHasSuffix(suffix: String): Boolean {
    val text = nfcCharacters()
    val wanted = suffix.nfcCharacters()
    return text.size >= wanted.size && text.subList(text.size - wanted.size, text.size) == wanted
}

fun String.swiftContains(other: String): Boolean = Collections.indexOfSubList(nfcCharacters(), other.nfcCharacters()) >= 0
