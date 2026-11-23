package tech.fonia.domain

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

// Port of FoniaDomainTests/SmallTypesTests.swift: 5 suites, 21 test functions.

@DisplayName("Plural")
class PluralTests {
    @ParameterizedTest
    @MethodSource("dayCounts")
    fun days(count: Int, expected: String) {
        assertEquals(expected, Plural.format(count, Plural.Noun.Zi))
    }

    @ParameterizedTest
    @MethodSource("nouns")
    fun nounTable(
        noun: Plural.Noun, one: String, two: String, nineteen: String, twenty: String, twentyOne: String,
        hundred: String, hundredOne: String, hundredTwenty: String,
    ) {
        assertEquals(one, Plural.format(1, noun))
        assertEquals(two, Plural.format(2, noun))
        assertEquals(nineteen, Plural.format(19, noun))
        assertEquals(twenty, Plural.format(20, noun))
        assertEquals(twentyOne, Plural.format(21, noun))
        assertEquals(hundred, Plural.format(100, noun))
        assertEquals(hundredOne, Plural.format(101, noun))
        assertEquals(hundredTwenty, Plural.format(120, noun))
    }

    @Test
    fun coversEveryNoun() {
        assertEquals(10, Plural.Noun.entries.size)
    }

    @Test
    fun wordIsTheBareNoun() {
        assertEquals("minut", Plural.word(1, Plural.Noun.Minut))
        assertEquals("minute", Plural.word(0, Plural.Noun.Minut))
        assertEquals("minute", Plural.word(20, Plural.Noun.Minut))
    }

    @Test
    fun formLeavesTheNumberToTheCaller() {
        assertEquals("de zile", Plural.form(20, Plural.Noun.Zi))
        assertEquals("zile", Plural.form(3, Plural.Noun.Zi))
        assertEquals("stea", Plural.form(1, Plural.Noun.Stea))
    }

    companion object {
        @JvmStatic
        fun dayCounts(): List<Arguments> = listOf(
            Arguments.of(0, "0 zile"), Arguments.of(1, "1 zi"), Arguments.of(2, "2 zile"), Arguments.of(19, "19 zile"),
            Arguments.of(20, "20 de zile"), Arguments.of(21, "21 de zile"), Arguments.of(100, "100 de zile"), Arguments.of(101, "101 zile"),
            Arguments.of(119, "119 zile"), Arguments.of(120, "120 de zile"), Arguments.of(1000, "1.000 de zile"), Arguments.of(1001, "1.001 zile"),
        )

        @JvmStatic
        fun nouns(): List<Arguments> = listOf(
            Arguments.of(Plural.Noun.Zi, "1 zi", "2 zile", "19 zile", "20 de zile", "21 de zile", "100 de zile", "101 zile", "120 de zile"),
            Arguments.of(Plural.Noun.Incercare, "1 încercare", "2 încercări", "19 încercări", "20 de încercări", "21 de încercări", "100 de încercări", "101 încercări", "120 de încercări"),
            Arguments.of(Plural.Noun.Minut, "1 minut", "2 minute", "19 minute", "20 de minute", "21 de minute", "100 de minute", "101 minute", "120 de minute"),
            Arguments.of(Plural.Noun.Cuvant, "1 cuvânt", "2 cuvinte", "19 cuvinte", "20 de cuvinte", "21 de cuvinte", "100 de cuvinte", "101 cuvinte", "120 de cuvinte"),
            Arguments.of(Plural.Noun.Sunet, "1 sunet", "2 sunete", "19 sunete", "20 de sunete", "21 de sunete", "100 de sunete", "101 sunete", "120 de sunete"),
            Arguments.of(Plural.Noun.Sedinta, "1 ședință", "2 ședințe", "19 ședințe", "20 de ședințe", "21 de ședințe", "100 de ședințe", "101 ședințe", "120 de ședințe"),
            Arguments.of(Plural.Noun.Punct, "1 punct", "2 puncte", "19 puncte", "20 de puncte", "21 de puncte", "100 de puncte", "101 puncte", "120 de puncte"),
            Arguments.of(Plural.Noun.Stea, "1 stea", "2 stele", "19 stele", "20 de stele", "21 de stele", "100 de stele", "101 stele", "120 de stele"),
            Arguments.of(Plural.Noun.Exercitiu, "1 exercițiu", "2 exerciții", "19 exerciții", "20 de exerciții", "21 de exerciții", "100 de exerciții", "101 exerciții", "120 de exerciții"),
        )
    }
}

@DisplayName("Voice index")
class VoiceIndexTests {
    private val index = VoiceIndex(
        mapOf(
            "ro|ro|sare" to "ro/ro/a.mp3",
            "en|en|ship" to "en/en/b.mp3",
            "en|en2|ship" to "en/en2/c.mp3",
            "en|en3|sheep" to "en/en3/d.mp3",
        ),
    )

    @ParameterizedTest
    @MethodSource("siteNormalisation")
    fun normalisesLikeTheSite(input: String, expected: String) {
        assertEquals(expected, VoiceIndex.normalised(input))
    }

    @Test
    fun findsTheExactVoice() {
        assertEquals("en/en2/c.mp3", index.clipPath("SHIP", Language.En, Voice.En2))
    }

    @Test
    fun defaultsToTheLanguageVoice() {
        assertEquals("ro/ro/a.mp3", index.clipPath("sare", Language.Ro))
        assertEquals("en/en/b.mp3", index.clipPath("ship", Language.En))
    }

    @Test
    fun fallsBackToTheDefaultVoiceFirst() {
        assertEquals("en/en/b.mp3", index.clipPath("ship", Language.En, Voice.En4))
    }

    @Test
    fun fallsBackToAnyVoiceOfTheLanguage() {
        assertEquals("en/en3/d.mp3", index.clipPath("sheep", Language.En, Voice.En2))
    }

    @Test
    fun doesNotCrossLanguages() {
        assertEquals(null, index.clipPath("ship", Language.Ro))
        assertEquals(null, index.clipPath("nope", Language.En))
    }

    @Test
    fun hasClipIgnoresFallback() {
        assertTrue(index.hasClip("ship", Language.En, Voice.En2))
        assertFalse(index.hasClip("ship", Language.En, Voice.En4))
    }

    /** iOS builds a bundle with `VoiceClips/manifest.json`; the default folder here is the content's `voice/`. */
    @Test
    fun loadsFromABundleFolder(@TempDir root: File) {
        val folder = File(root, VoiceIndex.defaultSubdirectory)
        folder.mkdirs()
        File(folder, "manifest.json").writeText("""{"ro|ro|sare":"ro/ro/a.mp3"}""", Charsets.UTF_8)

        val loaded = VoiceIndex.load(ContentSource.directory(root))
        assertEquals("ro/ro/a.mp3", loaded.clipPath("Sare", Language.Ro))
        val url = assertNotNull(loaded.clipUrl("sare", Language.Ro))
        assertTrue(url.endsWith("voice/ro/ro/a.mp3"), url)
    }

    @Test
    fun reportsAMissingManifest(@TempDir root: File) {
        assertFailsWith<VoiceIndex.LoadError.ManifestNotFound> {
            VoiceIndex.load(ContentSource.directory(root))
        }
    }

    @Test
    fun realManifestHasTheFourEnglishVoices() {
        for (voice in Voice.english) {
            assertTrue(Fixture.voiceIndex.hasClip("ship", Language.En, voice))
        }
    }

    companion object {
        @JvmStatic
        fun siteNormalisation(): List<Arguments> = listOf(
            Arguments.of("  Sare  ", "sare"),
            Arguments.of("Cos,   COȘ", "cos, coș"),
            Arguments.of("line\none", "line one"),
            Arguments.of("non breaking", "non breaking"),
            Arguments.of("Șase sași în șase saci.", "șase sași în șase saci."),
        )
    }
}

@DisplayName("SeededRandom")
class SeededRandomTests {
    @Test
    fun aKnownSeedStaysStable() {
        // Pins the generator: a change here would reorder every session's trials.
        val random = SeededRandom(seed = 1uL)
        assertEquals(0x910A_2DEC_8902_5CC1uL, random.next())
    }
}

@DisplayName("Viseme")
class VisemeTests {
    @Test
    fun portKeepsTheSourceValues() {
        assertEquals(
            MouthParams(open = 0.1, width = 0.55, round = 0.0, smile = 0.6, teeth = 0.25, tongue = TonguePos.Rest, vibrate = false, lipBite = false, puff = 0.0),
            Viseme.Rest.mouth,
        )
        assertEquals(
            MouthParams(open = 0.13, width = 0.86, round = 0.0, smile = 0.45, teeth = 1.0, tongue = TonguePos.Hidden, vibrate = false, lipBite = false, puff = 0.0),
            Viseme.S.mouth,
        )
        assertTrue(Viseme.R.mouth.vibrate)
        assertEquals(TonguePos.TipUp, Viseme.R.mouth.tongue)
        assertTrue(Viseme.F.mouth.lipBite)
        assertEquals(1.0, Viseme.Puff.mouth.puff)
    }

    @Test
    fun holdsEveryPoseOfTheSource() {
        assertEquals(40, Viseme.entries.size)
        for (raw in listOf("ă", "î", "ș", "ț", "ce", "ge", "th", "smileBig", "tongueInsideUp", "blow")) {
            assertNotNull(Viseme.fromRawValue(raw), raw)
        }
    }

    @ParameterizedTest
    @MethodSource("graphemeCases")
    fun graphemes(grapheme: String, expected: Viseme) {
        assertEquals(expected, Viseme.forGrapheme(grapheme))
    }

    companion object {
        @JvmStatic
        fun graphemeCases(): List<Arguments> = listOf(
            Arguments.of("A", Viseme.A), Arguments.of("â", Viseme.ICircumflex), Arguments.of("c", Viseme.K), Arguments.of("x", Viseme.K),
            Arguments.of("y", Viseme.I), Arguments.of("Ș", Viseme.Sh), Arguments.of("q", Viseme.Rest), Arguments.of("TH", Viseme.Th),
        )
    }
}

@DisplayName("Exercise")
class ExerciseTests {
    @Test
    fun coversEveryKindFromTheCourse() {
        val names = Exercise.Kind.entries.map { it.rawValue }.toSet()
        assertEquals(
            setOf(
                "intro", "how", "hear", "warmup", "sustain", "repeat", "name", "choose", "sort", "twister", "stress",
                "volume", "glide", "intonation", "expression", "pace", "pauses", "shadow", "retell", "describe", "mission",
            ),
            names,
        )
    }

    @Test
    fun productionAndListeningAreApart() {
        assertTrue(Exercise.Volume.isProduction)
        assertFalse(Exercise.Volume.isIdentification)
        assertTrue(Exercise.Stress(word = "ANimal", wrong = "aniMAL").isIdentification)
        assertFalse(Exercise.Stress(word = "ANimal", wrong = "aniMAL").isProduction)
        assertTrue(Exercise.Sustain(target = SustainTarget.Breath, seconds = 5.0, role = SustainRole.Practice).isProduction)
        assertFalse(Exercise.Sustain(target = SustainTarget.Sound(SoundID.R), seconds = 2.0, role = SustainRole.Facilitation).isProduction)
        assertFalse(Exercise.Mission(MissionPrompt(text = "x")).isProduction)
    }
}
