package tech.fonia.features.check

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.ContentSource
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.SoundID
import tech.fonia.store.Attempt.Outcome
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

class QuickCheckRulesTests {
    private val s = SoundID.S
    private val good = Outcome.Good
    private val almost = Outcome.Almost

    private fun group(vararg takes: CheckTake) = QuickCheckRules.group(takes.toList(), listOf(s))[s]!!.verdict

    @Test
    fun twoGoodTakesComeOutWell() {
        assertEquals(CheckVerdict.Good, group(CheckTake(s, good, 0.9f), CheckTake(s, good, 0.4f)))
    }

    @Test
    fun oneSureGoodTakeIsEnough_oneUnsureIsNot() {
        assertEquals(CheckVerdict.Good, group(CheckTake(s, good, 0.8f), CheckTake(s, Outcome.Unclear)))
        assertEquals(CheckVerdict.Unsure, group(CheckTake(s, good, 0.4f), CheckTake(s, Outcome.Unclear)))
    }

    @Test
    fun missesAreToPractise_unlessASingleMissWasUnsure() {
        assertEquals(CheckVerdict.Practice, group(CheckTake(s, almost, 0.3f), CheckTake(s, almost, 0.3f)))
        assertEquals(CheckVerdict.Practice, group(CheckTake(s, almost, 0.8f)))
        assertEquals(CheckVerdict.Unsure, group(CheckTake(s, almost, 0.3f)))
    }

    @Test
    fun aSoundThatComesAndGoesIsToPractise() {
        assertEquals(CheckVerdict.Practice, group(CheckTake(s, good, 0.9f), CheckTake(s, almost, 0.9f)))
    }

    @Test
    fun silenceOrNoTakeIsHeardAgain() {
        assertEquals(CheckVerdict.Unsure, group(CheckTake(s, Outcome.Unclear), CheckTake(s, Outcome.NotJudged)))
        assertEquals(CheckVerdict.Unsure, group())
    }

    @Test
    fun aTakeWithNoCertaintyCountsAsFairlySure() {
        assertEquals(CheckVerdict.Good, group(CheckTake(s, good, null)))
    }

    private fun record(vararg pairs: Pair<SoundID, CheckVerdict>) =
        CheckRecord(Instant.EPOCH, pairs.associate { (id, v) -> id to SoundCheck(v, 0.7f) })

    @Test
    fun planFollowsTheLogopedicOrder() {
        val r = record(
            SoundID.R to CheckVerdict.Practice,
            SoundID.Sh to CheckVerdict.Practice,
            SoundID.S to CheckVerdict.Practice,
            SoundID.Z to CheckVerdict.Good,
            SoundID.L to CheckVerdict.Unsure,
        )
        assertEquals(listOf(SoundID.S, SoundID.Sh, SoundID.R), QuickCheckRules.plan(r))
    }

    @Test
    fun theSoundHeChoseGoesFirst() {
        val r = record(SoundID.R to CheckVerdict.Practice, SoundID.S to CheckVerdict.Practice, SoundID.Ce to CheckVerdict.Practice)
        assertEquals(listOf(SoundID.R, SoundID.S, SoundID.Ce), QuickCheckRules.plan(r, chose = SoundID.R))
        // A sound not in the plan cannot be chosen.
        assertEquals(listOf(SoundID.S, SoundID.Ce, SoundID.R), QuickCheckRules.plan(r, chose = SoundID.Z))
    }

    @Test
    fun withNothingToPractiseThePlanIsWhatToHearAgain_elseItStays() {
        assertEquals(listOf(SoundID.J), QuickCheckRules.plan(record(SoundID.S to CheckVerdict.Good, SoundID.J to CheckVerdict.Unsure)))
        assertEquals(listOf(SoundID.R), QuickCheckRules.plan(record(SoundID.S to CheckVerdict.Good), current = listOf(SoundID.R)))
    }

    @Test
    fun thenAndNowNamesTheSoundsThatMovedToGood() {
        val before = record(SoundID.S to CheckVerdict.Practice, SoundID.R to CheckVerdict.Practice, SoundID.Z to CheckVerdict.Good)
        val now = record(SoundID.S to CheckVerdict.Good, SoundID.R to CheckVerdict.Practice, SoundID.Z to CheckVerdict.Good, SoundID.L to CheckVerdict.Good)
        assertEquals(listOf(SoundID.S), QuickCheckRules.movedToGood(before, now))
        assertEquals(emptyList<SoundID>(), QuickCheckRules.movedToGood(null, now))
    }

    @Test
    fun wordsAreShortInitialPictureWordsWithAClip_twoPerSound_atMost18() {
        val content = FoniaContent.load(ContentSource.directory(File(System.getProperty("fonia.content"))))
        val words = QuickCheckRules.words(content, hasClip = { true })
        assertTrue(words.size <= QuickCheckRules.MAX_WORDS)
        assertEquals(QuickCheckRules.logopedicOrder.toSet(), words.map { it.first }.toSet())
        for ((id, word) in words) {
            val sound = content.sound(id)
            assertTrue(word.text, word.picture != null && word.clusters.isEmpty() && word.otherSounds.size <= 1)
            assertTrue(word.text, sound.graphemes.any { word.text.lowercase().startsWith(it) })
        }
        assertEquals(2, words.count { it.first == SoundID.S })
        // Without a clip a word is never used.
        assertEquals(0, QuickCheckRules.words(content, hasClip = { false }).size)
    }

    @Test
    fun theFileStoreKeepsTheHistoryInOrder() = runBlocking {
        val store = FileCheckStore(Files.createTempDirectory("fonia-check").toFile())
        val id = UUID.randomUUID()
        store.add(id, CheckRecord(Instant.ofEpochMilli(1000), mapOf(SoundID.S to SoundCheck(CheckVerdict.Practice, 0.6f))))
        store.add(id, CheckRecord(Instant.ofEpochMilli(2000), mapOf(SoundID.S to SoundCheck(CheckVerdict.Good, 0.9f))))
        val back = store.history(id)
        assertEquals(2, back.size)
        assertEquals(CheckVerdict.Good, back.last().results[SoundID.S]?.verdict)
        assertEquals(0.9f, back.last().results[SoundID.S]!!.confidence, 0.001f)
        store.keepOnly(emptySet())
        assertEquals(0, store.history(id).size)
    }
}
