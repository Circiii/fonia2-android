package tech.fonia.domain

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

// Port of FoniaDomainTests/ContentTests.swift: 7 suites, 45 test functions.

@DisplayName("JSON resources")
class ContentTests {
    private val content = Fixture.content

    @Test
    fun everyFileLoads() {
        assertEquals(9, content.sounds.size)
        assertEquals(11, content.english.contrasts.size)
        assertEquals(DictionAreaID.entries.size, content.diction.areas.size)
        assertEquals(19, content.warmups.size)
        assertEquals(15, content.coachPlans.size)
        assertEquals(5, content.listening.check.size)
    }

    @Test
    fun soundsComeInTheDefaultOrder() {
        assertEquals(SoundID.defaultOrder, content.sounds.map { it.id })
        assertEquals(SoundID.L, SoundID.defaultOrder.first())
        assertEquals(SoundID.R, SoundID.defaultOrder.last())
    }

    @ParameterizedTest
    @MethodSource("families")
    fun family(id: SoundID, family: SoundFamily) {
        assertEquals(family, content.sound(id).family)
    }

    /** The adult note explains the sound; it never says at what age children usually say it. */
    @ParameterizedTest
    @MethodSource("defaultOrder")
    fun parentNoteCarriesNoAgeNorm(id: SoundID) {
        val note = content.sound(id).parentNote
        assertTrue(note.isNotEmpty())
        val ageWords = Regex("""\bani\b|vârst""", RegexOption.IGNORE_CASE)
        assertEquals(null, ageWords.find(note), "$id: $note")
        assertFalse(note.contains("  "))
    }

    @ParameterizedTest
    @MethodSource("jsonFiles")
    fun holdsNoRiskScoringOrAutomaticTargets(file: String) {
        val text = swiftLowercased(Paths.jsonText(file))
        for (banned in listOf("\"severity\"", "\"risk\"", "targetsounds", "\"emotion", "am simțit", "expresivitate", "logopedul din buzunar", "hincks", "pvq")) {
            assertFalse(text.contains(banned), "$file.json holds $banned")
        }
    }

    @ParameterizedTest
    @MethodSource("jsonFiles")
    fun usesOnlyCommaBelowAndNoEmDash(file: String) {
        val text = Paths.jsonText(file)
        val forbidden = listOf(0x015E, 0x015F, 0x0162, 0x0163, 0x2014)
        val found = text.codePoints().toArray().filter { it in forbidden }
        assertTrue(found.isEmpty(), "$file.json holds ${found.map { String(Character.toChars(it)) }}")
    }

    companion object {
        @JvmStatic
        fun families(): List<Arguments> = listOf(
            Arguments.of(SoundID.S, SoundFamily.ColdAir), Arguments.of(SoundID.Z, SoundFamily.ColdAir), Arguments.of(SoundID.Ts, SoundFamily.ColdAir),
            Arguments.of(SoundID.Sh, SoundFamily.WarmAir), Arguments.of(SoundID.J, SoundFamily.WarmAir), Arguments.of(SoundID.Ce, SoundFamily.WarmAir), Arguments.of(SoundID.Ge, SoundFamily.WarmAir),
            Arguments.of(SoundID.R, SoundFamily.TongueTip), Arguments.of(SoundID.L, SoundFamily.TongueTip),
        )

        @JvmStatic
        fun jsonFiles(): List<String> = Paths.jsonFiles

        @JvmStatic
        fun defaultOrder(): List<SoundID> = SoundID.defaultOrder
    }
}

/**
 * iOS checks `Pictures.xcassets`. fonia-content holds the same pictures as `images/pictures/<name>.png`, and the
 * imagesets' `Contents.json` properties in `images/catalog.json` (written by `tools/sync_from_ios.py`), so the checks
 * point there.
 */
@DisplayName("Pictures")
class PictureTests {
    /** Every picture name in the JSON, read from the files so that no field can be overlooked. */
    private fun picturesInJSON(): Set<String> {
        val names = mutableSetOf<String>()
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    (node["picture"] as? JsonPrimitive)?.takeIf { it.isString }?.let { names += it.content }
                    (node["pictures"] as? JsonArray)
                        ?.takeIf { array -> array.all { it is JsonPrimitive && it.isString } }
                        ?.let { array -> names += array.map { it.jsonPrimitive.content } }
                    node.values.forEach(::walk)
                }
                is JsonArray -> node.forEach(::walk)
                else -> Unit
            }
        }
        for (file in Paths.jsonFiles) {
            walk(Json.parseToJsonElement(Paths.jsonText(file)))
        }
        return names
    }

    private fun imageCatalog(): JsonObject = Json.parseToJsonElement(Paths.imageCatalog.readText(Charsets.UTF_8)).jsonObject

    @Test
    fun everyPictureUsedIsInTheCatalog() {
        val names = picturesInJSON()
        assertFalse(names.isEmpty())
        val catalog = imageCatalog()
        for (name in names) {
            assertTrue(File(Paths.pictures, "$name.png").isFile, "$name has no PNG")
            // The imageset's Contents.json, as copied into images/catalog.json.
            val entry = catalog[name]?.jsonObject
            assertEquals("pictures", entry?.get("folder")?.jsonPrimitive?.content, "$name has no Contents.json")
            assertEquals("$name.png", entry?.get("file")?.jsonPrimitive?.content, "$name has no Contents.json")
        }
    }

    @Test
    fun catalogHoldsOnlyPicturesInUse() {
        val inCatalog = Paths.pictures.list()!!.filter { it.endsWith(".png") }.map { it.removeSuffix(".png") }
        assertEquals(picturesInJSON(), inCatalog.toSet())
        val catalogEntries = imageCatalog().filterValues { it.jsonObject["folder"]?.jsonPrimitive?.content == "pictures" }.keys
        assertEquals(picturesInJSON(), catalogEntries)
    }

    /**
     * The pictures are already small palette PNGs. Xcode's lossy pass made them bigger and frayed their cut-out edges,
     * so they ship as they are (iOS eace9ab, content 51f5cf5).
     */
    @Test
    fun imagesetsAreLossless() {
        val properties = assertNotNull(imageCatalog()["1f40d"]?.jsonObject)
        assertEquals("lossless", properties["compression"]?.jsonPrimitive?.content)
    }

    @Test
    fun namesAreLowercaseCodePoints() {
        val pattern = Regex("[0-9a-f]+(-[0-9a-f]+)*")
        for (name in picturesInJSON()) {
            assertTrue(pattern.matches(name), name)
            assertFalse(name.contains("fe0f"), name)
        }
    }
}

@DisplayName("Word lists")
class WordListTests {
    private val content = Fixture.content

    @Test
    fun tagsMatchTheSpelling() {
        for (sound in content.sounds) {
            val contrastWords = sound.contrasts.flatMap { it.pairs }.flatMap { listOf(it.a, it.b) }
            for (word in sound.allWords + contrastWords) {
                val others = Romanian.targetSounds(word.text).filter { it != sound.id }
                assertEquals(others, word.otherSounds, "${sound.letter}: ${word.text}")
                assertEquals(Romanian.clusters(word.text), word.clusters, "${sound.letter}: ${word.text}")
            }
        }
        for (pair in content.listening.check + content.listening.voicingPairs) {
            for (word in listOf(pair.a, pair.b)) {
                assertEquals(Romanian.targetSounds(word.text), word.otherSounds, word.text)
            }
        }
    }

    @Test
    fun recordedFlagMatchesTheManifest() {
        val index = Fixture.voiceIndex
        for (sound in content.sounds) {
            val contrastWords = sound.contrasts.flatMap { it.pairs }.flatMap { listOf(it.a, it.b) }
            for (word in sound.allWords + contrastWords) {
                assertEquals(index.hasClip(word.text, Language.Ro, Voice.Ro), word.recorded, word.text)
            }
        }
    }

    @Test
    fun wordsSitAtTheirPosition() {
        for (sound in content.sounds) {
            val graphemes = sound.graphemes
            for (word in sound.words.initial) {
                assertTrue(graphemes.any { word.text.swiftHasPrefix(it) }, "${word.text} in ${sound.letter} initial")
            }
            for (word in sound.words.medial) {
                assertFalse(graphemes.any { word.text.swiftHasPrefix(it) }, "${word.text} in ${sound.letter} medial")
                assertTrue(graphemes.any { word.text.swiftContains(it) }, "${word.text} in ${sound.letter} medial")
            }
            for (word in sound.words.final) {
                val ending = if (sound.id == SoundID.Ce) listOf("ci") else if (sound.id == SoundID.Ge) listOf("gi") else graphemes
                assertTrue(ending.any { word.text.swiftHasSuffix(it) }, "${word.text} in ${sound.letter} final")
            }
            if (sound.id != SoundID.Ce && sound.id != SoundID.Ge) {
                for (word in sound.words.medial) {
                    assertFalse(graphemes.any { word.text.swiftHasSuffix(it) }, "${word.text} in ${sound.letter} medial")
                }
            }
        }
    }

    @Test
    fun simpleListsKeepClustersOutWhenTheyCan() {
        for (sound in content.sounds) {
            for (list in listOf(sound.words.initial, sound.words.medial, sound.words.final)) {
                val clean = list.filter { it.clusters.isEmpty() }
                if (clean.size != list.size) {
                    assertTrue(clean.size < 3, "${sound.letter}: ${list.map { it.text }}")
                }
            }
        }
    }

    @Test
    fun clusterRungHoldsOnlyClusters() {
        for (sound in content.sounds) {
            assertFalse(sound.words.clusters.isEmpty(), sound.letter)
            for (word in sound.words.clusters) {
                assertFalse(word.clusters.isEmpty(), word.text)
            }
        }
    }

    @Test
    fun listsAreLongEnoughToPractise() {
        for (sound in content.sounds) {
            assertTrue(sound.words.initial.size >= 3, "${sound.letter} initial")
            assertTrue(sound.words.medial.size >= 3, "${sound.letter} medial")
            assertTrue(sound.words.final.size >= 3, "${sound.letter} final")
            assertTrue(sound.sentences.size >= 3, "${sound.letter} sentences")
        }
    }

    @Test
    fun hardToPictureWordsAreOutOfNaming() {
        val unpicturable = setOf("sar", "sap", "cât", "dată", "ajutor", "peisaj")
        val everyWord = content.sounds.flatMap { sound ->
            sound.allWords + sound.contrasts.flatMap { it.pairs }.flatMap { listOf(it.a, it.b) }
        } + (content.listening.check + content.listening.voicingPairs).flatMap { listOf(it.a, it.b) }
        val found = everyWord.filter { it.text in unpicturable }
        assertTrue(found.size >= unpicturable.size)
        for (word in found) {
            assertFalse(word.namable, word.text)
        }
    }

    @Test
    fun namableWordsHaveAPicture() {
        for (sound in content.sounds) {
            for (word in sound.allWords.filter { it.namable }) {
                assertTrue(word.picture != null, word.text)
            }
        }
    }

    @Test
    fun listsCanBeFilteredForAProfile() {
        val sound = content.sound(SoundID.S)
        val words = sound.words(WordLists::initial, avoiding = setOf(SoundID.R, SoundID.L))
        assertEquals(listOf("sanie", "supă", "suc"), words.map { it.text })
    }

    @Test
    fun filteringNeverEmptiesAList() {
        val sound = content.sound(SoundID.J)
        val words = sound.words(WordLists::initial, avoiding = SoundID.entries.toSet())
        assertFalse(words.isEmpty())
    }
}

@DisplayName("Minimal pairs")
class MinimalPairTests {
    private val content = Fixture.content

    @Test
    fun romanianPairsDifferByOneSound() {
        val all = content.sounds.flatMap { it.contrasts }.flatMap { it.pairs } + content.listening.check + content.listening.voicingPairs
        assertFalse(all.isEmpty())
        for (pair in all) {
            assertTrue(Romanian.differByOneSound(pair.a.text, pair.b.text), "${pair.a.text}/${pair.b.text}")
        }
    }

    @Test
    fun eachPairSitsUnderTheRightSounds() {
        for (sound in content.sounds) {
            for (contrast in sound.contrasts) {
                for (pair in contrast.pairs) {
                    assertTrue(sound.id in Romanian.targetSounds(pair.a.text), pair.a.text)
                    assertTrue(contrast.with in Romanian.targetSounds(pair.b.text), pair.b.text)
                }
            }
        }
    }

    @Test
    fun falsePairsAreGone() {
        val words = content.sounds.flatMap { it.contrasts }.flatMap { it.pairs }.map { "${it.a.text}/${it.b.text}" }
        for (pair in listOf("soare/șoarece", "șoarece/soare", "ceas/țap", "arici/ursuleț", "girafă/cireșe", "covrigi/arici")) {
            assertFalse(pair in words, pair)
        }
    }

    @Test
    fun proposedPairsAreMarkedForReview() {
        val ce = content.sound(SoundID.Ce).contrasts.flatMap { it.pairs }
        val cer = assertNotNull(ce.firstOrNull { it.a.text == "cer" })
        val ceapa = assertNotNull(ce.firstOrNull { it.a.text == "ceapă" })
        assertTrue(cer.b.text == "ger" && cer.isSuggested)
        assertTrue(ceapa.b.text == "țeapă" && ceapa.isSuggested)
    }

    @Test
    fun englishPairsDifferByOnePhoneme() {
        for (contrast in Fixture.content.english.contrasts.filter { it.kind == EnglishContrast.Kind.MinimalPairs }) {
            for (pair in contrast.pairs) {
                val ipa = assertNotNull(pair.ipa, "${pair.a}/${pair.b}")
                assertTrue(IPA.differByOnePhoneme(ipa[0], ipa[1]), "${pair.a}/${pair.b}: $ipa")
            }
        }
    }

    @Test
    fun ipaTokensKeepLongVowelsAndDiphthongsWhole() {
        assertEquals(listOf("ʃ", "iː", "p"), IPA.phonemes("ʃiːp"))
        assertEquals(listOf("p", "r", "aɪ", "z"), IPA.phonemes("praɪz"))
        assertEquals(listOf("tʃ", "ɪ", "p"), IPA.phonemes("tʃɪp"))
    }

    @Test
    fun badEnglishPairsAreReplaced() {
        val pairs = content.english.contrasts.flatMap { it.pairs }
        assertFalse(pairs.any { it.a == "hurt" || it.b == "heart" })
        assertFalse(pairs.any { it.a == "wary" })
        assertTrue(pairs.any { it.a == "fur" && it.b == "four" })
        assertTrue(pairs.any { it.a == "wow" && it.b == "vow" })
    }
}

@DisplayName("English course")
class EnglishCourseTests {
    private val english = Fixture.content.english

    @Test
    fun orderFollowsThePriorities() {
        val order = english.contrasts.map { it.id }
        assertEquals("stress", order.firstOrNull())
        assertEquals("th", order.lastOrNull())
        val indices = listOf("stress", "ship-sheep", "bad-bed", "work-walk", "pin-bin", "bed-bet", "w-v", "th")
            .mapNotNull { id -> order.indexOf(id).takeIf { it >= 0 } }
        assertEquals(indices.sorted(), indices)
    }

    @Test
    fun contrastsWithoutRecordingsAreFlaggedAndOff() {
        for (contrast in english.contrasts.filter { it.kind == EnglishContrast.Kind.MinimalPairs }) {
            assertEquals(!contrast.pairs.any { it.recorded }, contrast.needsRecording, contrast.id)
        }
    }

    @Test
    fun recordedFlagMatchesAllFourVoices() {
        val index = Fixture.voiceIndex
        for (contrast in english.contrasts.filter { it.kind == EnglishContrast.Kind.MinimalPairs }) {
            for (pair in contrast.pairs) {
                val hasAll = listOf(pair.a, pair.b).all { word ->
                    Voice.english.all { index.hasClip(word, Language.En, it) }
                }
                assertEquals(hasAll, pair.recorded, "${pair.a}/${pair.b}")
            }
        }
    }

    @Test
    fun retestWordsStayOutOfTrainingAndSentences() {
        for (contrast in english.contrasts.filter { !it.needsRecording && it.kind != EnglishContrast.Kind.ReducedVowel }) {
            val trained = contrast.trainingPairs.flatMap { listOf(swiftLowercased(it.a), swiftLowercased(it.b)) }.toSet()
            val retest = contrast.retestPairs.flatMap { listOf(swiftLowercased(it.a), swiftLowercased(it.b)) }
            assertEquals(4, retest.size, contrast.id)
            assertTrue(retest.none { it in trained }, contrast.id)
            val sentenceWords = contrast.sentences.flatMap { sentence -> swiftLowercased(sentence).splitCharacters { !it.isLetterCharacter } }
            assertTrue(retest.none { it in sentenceWords.toSet() }, contrast.id)
        }
    }

    @Test
    fun noNegativeParallelOnTH() {
        val th = assertNotNull(english.contrast("th"))
        assertFalse(th.cue.contains("Nu e"))
    }

    @Test
    fun schwaUsesRecordedWords() {
        val schwa = assertNotNull(english.contrast("schwa"))
        val words = assertNotNull(schwa.words)
        assertFalse(schwa.needsRecording)
        for (word in words) {
            assertTrue(Fixture.voiceIndex.hasClip(word, Language.En, Voice.En), word)
        }
    }
}

@DisplayName("Rewritten practice")
class PracticeRuleTests {
    private val content = Fixture.content

    private fun sentencesIn(text: String): List<String> = text.splitCharacters { it == "." || it == "!" || it == "?" }

    private fun wordCount(text: String): Int = text.splitCharacters { it.isWhitespaceCharacter }.size

    @Test
    fun childInstructionsStayUnderEightWords() {
        val lines = content.sounds.flatMap { sound ->
            sound.how + sound.fixes.map { it.tip } + sound.missions.map { it.text } + (sound.facilitation?.let { listOf(it.say) } ?: emptyList())
        }.toMutableList()
        lines += content.warmups.map { it.say }
        lines += content.coachPlans.filter { it.language == Language.Ro }.flatMap { plan -> plan.steps.map { it.say } + plan.faults.map { it.tip } }
        for (line in lines) {
            for (sentence in sentencesIn(line)) {
                assertTrue(wordCount(sentence) < 8, line)
            }
        }
    }

    @Test
    fun rIsNoLongerASustainedTrill() {
        val plan = assertNotNull(content.coachPlan("r"))
        val sound = assertNotNull(plan.steps.firstOrNull { it.id == "sound" })
        assertEquals(CoachMicJudge.Rhotic, sound.mic)
        assertEquals(3, sound.bursts)
        assertFalse(sound.isOptional)
        val trill = assertNotNull(plan.steps.firstOrNull { it.id == "trill" })
        assertTrue(trill.isOptional)
        assertEquals(CoachMicJudge.Rhotic, plan.syllables?.judge)
        assertEquals("Spune: ra", plan.syllables?.prompt("ra"))
        assertTrue(content.sound(SoundID.R).facilitation != null)
    }

    @Test
    fun onlyROffersAFacilitation() {
        for (sound in content.sounds.filter { it.id != SoundID.R }) {
            assertEquals(null, sound.facilitation, sound.letter)
        }
    }

    @Test
    fun coachPlansCoverEverySoundAndKeepTheirSteps() {
        for (id in SoundID.entries) {
            val plan = assertNotNull(content.coachPlan(id.rawValue))
            assertEquals(Language.Ro, plan.language)
            assertFalse(plan.steps.isEmpty())
            assertTrue(plan.steps.any { it.id == "sound" })
        }
        for (key in listOf("en-th", "en-w", "en-v", "en-ee", "en-i", "en-ae")) {
            val plan = assertNotNull(content.coachPlan(key))
            assertEquals(Language.En, plan.language)
            assertTrue(plan.word != null)
        }
    }

    @Test
    fun warmupsAreShort() {
        for (warmup in content.warmups) {
            assertTrue(warmup.holdMs <= 1500, warmup.id)
            assertTrue(warmup.reps <= 4, warmup.id)
        }
    }

    @Test
    fun soundWarmupsPointAtRealExercises() {
        for (sound in content.sounds) {
            for (id in sound.warmups) {
                assertTrue(content.warmup(id) != null, "${sound.letter}: $id")
            }
        }
    }

    @Test
    fun dictionDropsSourcelessThresholds() {
        assertEquals(false, content.diction.rateGuide.romanian.calibrated)
        assertEquals(3.0, content.diction.hesitationsPer100WordsBeforeComment)
        assertTrue("ăă" in content.diction.fillers.ro.hesitations)
        assertFalse("deci" in content.diction.fillers.ro.hesitations)
        assertTrue("deci" in content.diction.fillers.ro.discourseMarkers)
        assertFalse("well" in content.diction.fillers.en.hesitations)
    }

    @Test
    fun expressionKeepsItsRecordingsAndUsesNeutralWords() {
        val verdicts = content.diction.expressionVerdicts
        assertEquals("Vocea ta a urcat și a coborât.", verdicts.moved)
        for (line in content.diction.expressionLines) {
            assertTrue(Fixture.voiceIndex.hasClip(line.text, Language.Ro, Voice.Ro), line.text)
            assertFalse(swiftLowercased(line.cue).contains("emoți"))
        }
    }

    @Test
    fun retellAndMissionsExistForEverySound() {
        for (sound in content.sounds) {
            assertEquals(2, sound.retell.size, sound.letter)
            assertEquals(3, sound.describe.size, sound.letter)
            assertEquals(3, sound.missions.size, sound.letter)
            assertEquals(listOf(sound.hero.story), sound.retell[0].lines, sound.letter)
        }
    }
}

@DisplayName("Clip report")
class ClipReportTests {
    private val content = Fixture.content

    private data class ModelText(val language: Language, val voices: List<Voice>, val text: String)

    private fun modelTexts(): List<ModelText> {
        val texts = mutableListOf<ModelText>()
        fun add(strings: List<String>, language: Language = Language.Ro, voices: List<Voice>? = null) {
            val chosen = voices ?: listOf(Voice.defaultVoice(language))
            texts += strings.map { ModelText(language, chosen, it) }
        }
        fun addPairs(pairs: List<WordPair>) {
            add(pairs.flatMap { listOf(it.a.text, it.b.text) })
            add(pairs.mapNotNull { it.spoken })
        }
        for (sound in content.sounds) {
            add(listOf(sound.hero.says, sound.hero.story))
            add(sound.hero.chapters.orEmpty().keys.mapNotNull { sound.hero.chapter(it) })
            add(listOfNotNull(sound.burstPrompt))
            add(sound.syllables.direct + sound.syllables.inverse + sound.syllables.between + sound.syllables.repeated)
            add(sound.allWords.map { it.text })
            addPairs(sound.contrasts.flatMap { it.pairs })
            add(sound.sentences + sound.twisters.map { it.text } + sound.retell.flatMap { it.lines })
        }
        for (contrast in content.english.contrasts) {
            when (contrast.kind) {
                EnglishContrast.Kind.MinimalPairs -> add(contrast.pairs.flatMap { listOf(it.a, it.b) }, Language.En, voices = Voice.english)
                EnglishContrast.Kind.Stress -> add(contrast.pairs.map { it.a }, Language.En)
                EnglishContrast.Kind.ReducedVowel -> add(contrast.words ?: emptyList(), Language.En)
            }
            add(contrast.sentences, Language.En)
        }
        add(content.english.twisters + content.english.melody.lessons.flatMap { it.lines }, Language.En)
        val diction = content.diction
        add(diction.finalIWords + diction.intonationPairs.flatMap { listOf(it.statement, it.question) })
        add(diction.expressionLines.map { it.text } + diction.paceTexts.map { it.text } + diction.pauseTexts.map { it.spoken })
        add(listOf(Session.goodbye))
        addPairs(content.listening.check + content.listening.voicingPairs)
        return texts
    }

    @Test
    fun reportListsExactlyTheTextsWithNoClip() {
        val index = Fixture.voiceIndex
        val expected = mutableSetOf<String>()
        for (item in modelTexts()) {
            for (voice in item.voices) {
                if (!index.hasClip(item.text, item.language, voice)) {
                    expected += "${item.language.rawValue}|${voice.rawValue}|${VoiceIndex.normalised(item.text)}"
                }
            }
        }

        val report = Paths.missingClipsText()
        val listed = mutableSetOf<String>()
        // Swift's split(separator: "\n") drops empty lines.
        for (line in report.split("\n").map { it.removeSuffix("\r") }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
            val parts = line.split(" | ")
            val head = parts[0].split(" ").filter { it.isNotEmpty() }
            for (voice in head[1].split(",").filter { it.isNotEmpty() }) {
                listed += "${head[0]}|$voice|${VoiceIndex.normalised(parts[1])}"
            }
        }
        assertEquals(expected, listed)
    }

    @Test
    fun recordedPracticeTextsKeepTheirClips() {
        val index = Fixture.voiceIndex
        // iOS wraps the sentence, twister and hero checks in `withKnownIssue("rewritten practice texts wait for their
        // recordings")`. On Android the ElevenLabs clips (tools/voice) cover them all, so the check is strict: no
        // rewritten text may lose its clip.
        for (sound in content.sounds) {
            for (text in sound.sentences + sound.twisters.map { it.text } + listOf(sound.hero.says, sound.hero.story)) {
                assertTrue(index.hasClip(text, Language.Ro, Voice.Ro), "no clip: $text")
            }
        }
        for (sound in content.sounds) {
            for (text in sound.syllables.direct + sound.syllables.inverse + sound.syllables.between + sound.syllables.repeated) {
                assertTrue(index.hasClip(text, Language.Ro, Voice.Ro), text)
            }
        }
    }

    @Test
    fun childFacingRomanianTextsHaveClipsOrWaitForAPerson() {
        // Android has no device voice (no TextToSpeech): a Romanian text with no clip is silent. So every practice text
        // and every instruction a child hears needs its clip, unless ElevenLabs could not say it right and it waits on
        // human-needed.txt for a person to record it. An entry that has a clip by now must leave the list.
        val index = Fixture.voiceIndex
        val waiting = Paths.humanNeededText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split(" | ") }.filter { it[1] == "ro ro" }.map { nfc(it[2]) }.toSet()
        val instructions = mutableListOf<String>()
        val usedWarmups = mutableSetOf<String>()
        for (sound in content.sounds) {
            instructions += sound.how + sound.fixes.map { it.tip } + sound.missions.map { it.text } +
                sound.retell.map { it.prompt } + sound.describe.map { it.prompt }
            usedWarmups += sound.warmups
        }
        instructions += content.warmups.filter { it.id in usedWarmups }.map { it.say }
        instructions += content.coachPlans.flatMap { plan -> plan.steps.map { it.say } }
        val practice = modelTexts().filter { it.language == Language.Ro }.map { it.text }
        val silent = (practice + instructions).distinct().filter { !index.hasClip(it, Language.Ro, Voice.Ro) }
        for (text in silent) {
            assertTrue(nfc(text) in waiting, "no clip and not in human-needed.txt: $text")
        }
        for (text in waiting) {
            assertFalse(index.hasClip(text, Language.Ro, Voice.Ro), "has a clip now, take it off human-needed.txt: $text")
        }
    }

    @Test
    fun pauseTextsMarkTheirPauses() {
        for (text in content.diction.pauseTexts) {
            assertTrue(text.text.contains("|"))
        }
    }
}
