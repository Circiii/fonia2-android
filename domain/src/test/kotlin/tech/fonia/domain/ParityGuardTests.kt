package tech.fonia.domain

import java.text.Normalizer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Android-only guards, not ports of iOS tests: they pin the places where Kotlin needs extra code to behave like Swift
 * (unsigned arithmetic, Characters, canonical equivalence, `Decodable`), and the catalog values listed in the parity
 * inventory (§3.7), which no iOS domain test covers.
 */
@DisplayName("Parity guards")
class ParityGuardTests {
    @Test
    fun generatorMatchesReferenceSplitMix64AndFnv1a() {
        // Reference values from an independent implementation of SplitMix64 and FNV-1a 64.
        val zero = SeededRandom(0uL)
        assertEquals(listOf(0xE220_A839_7B1D_CDAFuL, 0x6E78_9E6A_A1B9_65F4uL, 0x06C4_5D18_8009_454FuL), List(3) { zero.next() })
        val one = SeededRandom(1uL)
        assertEquals(listOf(0x910A_2DEC_8902_5CC1uL, 0xBEEB_8DA1_658E_EC67uL, 0xF893_A2EE_FB32_555EuL), List(3) { one.next() })
        assertEquals(0xCBF2_9CE4_8422_2325uL, SeededRandom.hash(""))
        assertEquals(0xAF63_DC4C_8601_EC8CuL, SeededRandom.hash("a"))
        assertEquals(0x8594_4171_F739_67E8uL, SeededRandom.hash("foobar"))
    }

    @Test
    fun drawsAreUnsignedAndShortListsDrawNothing() {
        // 0x910A2DEC89025CC1 % 10 is 5 unsigned; a signed Long remainder would give -1.
        assertEquals(5, SeededRandom(1uL).below(10))
        assertEquals(listOf(1, 2, 0, 4, 3), SeededRandom(42uL).shuffled(listOf(0, 1, 2, 3, 4)))
        assertEquals(0.3898297483912715, SeededRandom(7uL).unit())
        val shuffled = SeededRandom(9uL)
        shuffled.shuffled(emptyList<Int>())
        shuffled.shuffled(listOf(1))
        assertEquals(SeededRandom(9uL).next(), shuffled.next())
    }

    @Test
    fun textFollowsSwiftCharactersAndCaseMapping() {
        // White_Space as Swift reads it: CR LF is one Character, U+0085, U+2003 and U+202F count (Java's isWhitespace
        // drops U+202F), and a space carrying a combining mark is one whitespace Character.
        assertEquals("a b c d e x", VoiceIndex.normalised("a\r\nb\u0085c d e ́x"))
        assertEquals("șață îâ ii̇", VoiceIndex.normalised("ȘAȚĂ ÎÂ  Iİ"))
        // Swift lowercases scalar by scalar, with no final-sigma rule.
        assertEquals("σασ", swiftLowercased("ΣΑΣ"))
        assertEquals(listOf("CE", "CI"), "CE/CI".splitCharacters("/"))
        assertEquals(listOf("a", "b"), "//a//b/".splitCharacters("/"))
        assertEquals(2, "șa".characterCount)
        assertEquals("Ăl", "Ăla".prefixCharacters(2))
    }

    @Test
    fun canonicallyEquivalentTextMatchesAsInSwift() {
        val decomposed = "ș" // s + combining comma below, canonically equal to ș
        assertEquals(SoundID.Sh, SoundID.fromRawValue(decomposed))
        assertEquals(Viseme.Sh, Viseme.forGrapheme("Ș"))
        val index = VoiceIndex(mapOf("ro|ro|șase" to "ro/ro/a.mp3"))
        assertEquals("ro/ro/a.mp3", index.clipPath("${decomposed}ase", Language.Ro))
        assertEquals(SoundID.Sh, contentJson.decodeFromString<SoundID>("\"$decomposed\""))
    }

    @Test
    fun decodingIsAsStrictAsSwiftDecodable() {
        val complete = """{"text":"sare","picture":null,"otherSounds":["r"],"clusters":[],"namable":false,"recorded":true,"extra":1}"""
        assertEquals(WordItem(text = "sare", otherSounds = listOf(SoundID.R)), contentJson.decodeFromString<WordItem>(complete))
        // Every non-optional key is required, even where the constructor has a default.
        assertFailsWith<SerializationException> {
            contentJson.decodeFromString<WordItem>("""{"text":"sare","otherSounds":[],"clusters":[],"namable":false}""")
        }
        assertFailsWith<SerializationException> { contentJson.decodeFromString<SoundID>("\"q\"") }
    }

    @Test
    fun loadReportsTheFirstMissingFile() {
        assertEquals(
            FoniaContent.LoadError.MissingResource("sounds"),
            assertFailsWith<FoniaContent.LoadError.MissingResource> { FoniaContent.load { null } },
        )
        val onlySounds = ContentSource { path -> if (path == "json/sounds.json") Paths.jsonText("sounds").encodeToByteArray() else null }
        assertEquals(
            FoniaContent.LoadError.MissingResource("english"),
            assertFailsWith<FoniaContent.LoadError.MissingResource> { FoniaContent.load(onlySounds) },
        )
    }

    /** Kotlin compares strings by code unit and Swift by canonical equivalence: the two agree because all content is NFC. */
    @Test
    fun contentIsNfcWithCommaBelowLetters() {
        val strings = mutableListOf<String>()
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> node.forEach { (key, value) -> strings += key; walk(value) }
                is JsonArray -> node.forEach(::walk)
                is JsonPrimitive -> if (node.isString) strings += node.content
            }
        }
        for (file in Paths.jsonFiles) walk(Json.parseToJsonElement(Paths.jsonText(file)))
        walk(Json.parseToJsonElement(Paths.manifest.readText(Charsets.UTF_8)))
        assertTrue(strings.size > 1000)
        for (text in strings) {
            assertTrue(Normalizer.isNormalized(text, Normalizer.Form.NFC), "not NFC: $text")
            // The iOS test reads the raw files, so a cedilla written as an escape would slip past it.
            assertTrue(text.none { it in "ŞşŢţ" }, "cedilla in: $text")
        }
    }

    @Test
    fun catalogMatchesTheInventory() {
        val catalog = Catalog(Fixture.content)
        val sounds = catalog.soundUnits(SoundID.defaultOrder)
        assertEquals(
            listOf("warmup", "sound-l", "sound-s", "sound-z", "sound-ș", "sound-j", "sound-ț", "sound-ce", "sound-ge", "sound-r", "voicing", "stories"),
            sounds.map { it.id },
        )
        val s = catalog.unit(Fixture.content.sound(SoundID.S))
        assertEquals("Sunetul S", s.title)
        assertEquals("Sâsâilă", s.subtitle)
        assertEquals("S", s.mark)
        assertEquals("1f40d", s.art)
        assertEquals(
            listOf("meet", "warmup", "isolated", "syllables", "back", "wordsInitial", "wordsMedial", "wordsFinal", "clusters", "pairs", "sentences", "retell", "everyday").map { "s-$it" },
            s.lessons.map { it.id },
        )
        assertEquals("S sau Ș?", s.lessons.first { it.id == "s-pairs" }.title)
        assertEquals("Spunem S, nimic altceva", s.lessons.first { it.id == "s-isolated" }.detail)
        assertEquals(SessionFocus.Warmups(listOf("zambet", "dinti", "limba-jos"), breath = false), s.lessons[1].focus)
        assertEquals(Rung.Pairs, s.lessons.first { it.id == "s-pairs" }.rung)
        val ce = catalog.unit(Fixture.content.sound(SoundID.Ce))
        assertEquals("Sunetele CE/CI", ce.title)
        assertEquals("CE", ce.mark)
        assertEquals("CE sau Ț?", ce.lessons.first { it.id == "ce-pairs" }.title)
        assertEquals("GE sau CE?", catalog.unit(Fixture.content.sound(SoundID.Ge)).lessons.first { it.id == "ge-pairs" }.title)

        assertEquals(
            listOf("stress", "schwa", "ship-sheep", "full-fool", "th", "bad-bed", "cut-cat", "pin-bin", "work-walk", "bed-bet", "w-v").map { "en-$it" },
            catalog.contrastUnits.map { it.id },
        )
        val schwa = catalog.contrastUnits.first { it.id == "en-schwa" }
        assertEquals(listOf("en-schwa-say", "en-schwa-sentences"), schwa.lessons.map { it.id })
        assertEquals("Silabele fără accent se spun scurt, ca un „ă” abia atins", schwa.subtitle)
        assertEquals("ə", schwa.mark)
        val th = catalog.contrastUnits.first { it.id == "en-th" }
        assertEquals(listOf("en-th-listen", "en-th-say", "en-th-sentences", "en-th-check"), th.lessons.map { it.id })
        assertEquals("θ", th.mark)
        assertEquals(listOf("en-melody-0", "en-melody-1", "en-melody-2", "en-melody-3"), catalog.melodyUnit.lessons.map { it.id })
        assertEquals(
            listOf("d-pace", "d-pauses", "d-shadow", "d-warmup", "d-finalWords", "d-twisters", "d-volumeLadder", "d-glide", "d-statementOrQuestion", "d-emotion", "d-candles", "d-longSound"),
            catalog.dictionUnits.flatMap { unit -> unit.lessons.map { it.id } },
        )
        assertEquals(12 + 12 + 5, catalog.allUnits(SoundID.defaultOrder).size)
        assertEquals(listOf("warmup", "voicing", "stories"), catalog.playUnits.map { it.id })
    }
}
