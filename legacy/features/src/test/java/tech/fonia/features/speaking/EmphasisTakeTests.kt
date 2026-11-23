package tech.fonia.features.speaking

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.audio.CapturedClip
import tech.fonia.domain.Language
import tech.fonia.dsp.EmphasisCue
import tech.fonia.dsp.EmphasisState
import tech.fonia.dsp.EmphasisTally
import tech.fonia.dsp.TimeSpan
import tech.fonia.dsp.VoiceGroup
import tech.fonia.dsp.analyzeClip
import tech.fonia.dsp.analyzeEmphasis
import tech.fonia.features.speaking.emphasis.EmphasisItem
import tech.fonia.features.speaking.emphasis.EmphasisTake
import tech.fonia.features.speaking.emphasis.EmphasisVerdict
import tech.fonia.features.speaking.emphasis.EmphasisWords
import tech.fonia.speech.SpeechLanguage
import tech.fonia.speech.Transcript

/**
 * One sentence of the emphasis exercise, from the take to the words shown: the clip analysis, the word times from a
 * recogniser (scripted here, with the exact times of synthetic speech), the 2-of-3 rule and the sentences that name the
 * measured cue. Then the same analysis on recorded speech with Whisper's own word times.
 */
class EmphasisTakeTests {
    private val item = EmphasisItem("t-01", Language.Ro, "Pleacă trenul mâine?", MarkedText.parse("Nu, trenul pleacă *azi*, nu mâine."))

    private fun judge(spoken: SyntheticSpeech.Spoken, recogniser: ScriptedTranscriber, sentence: EmphasisItem = item): EmphasisTake = runBlocking {
        val clip = spoken.clip
        EmphasisTake.judge(sentence, clip, analyzeClip(clip.samples, clip.sampleRate, VoiceGroup.FEMALE, null), recogniser)
    }

    private fun say(stressed: Set<Int>, rate: Double = 48000.0, dropped: Set<Int> = emptySet()) =
        SyntheticSpeech.say(item.text.words.map { it.shown }, stressed = stressed, pauseAfter = SyntheticSpeech.readerPauses(item.text.words), dropped = dropped, rate = rate)

    @Test
    fun aStressedKeyWordStandsOutAndTheResultNamesItsCues() {
        val spoken = say(stressed = setOf(3))
        val recogniser = ScriptedTranscriber().also { it.add(spoken) }
        val take = judge(spoken, recogniser)
        assertEquals(listOf("score: Nu, trenul pleacă azi, nu mâine."), recogniser.calls)
        assertEquals(EmphasisVerdict.Stressed, take.verdict)
        val key = take.keys.single()
        assertEquals(3, key.index)
        assertEquals(EmphasisState.STRESSED, key.state)
        assertTrue("${key.passed}", EmphasisCue.DURATION in key.passed && EmphasisCue.LOUDNESS in key.passed)
        assertEquals("Da! Cuvântul marcat a ieșit în față.", EmphasisWords.headline(take))
        assertEquals("„azi” a fost mai lung, mai tare și mai sus decât cuvintele din jur.", EmphasisWords.keyLine(key, "azi"))
        assertNull(EmphasisWords.tip(take))
        assertNull(EmphasisWords.extraLine(take))
        assertEquals(EmphasisTally(keyWords = 1, measuredKeyWords = 1, stressedKeyWords = 1, measuredWords = 6, stressedWords = 1), take.tally)
    }

    @Test
    fun aLevelReadingIsNotYetAndSaysWhatToTry() {
        val spoken = say(stressed = emptySet(), rate = 16000.0)
        val take = judge(spoken, ScriptedTranscriber().also { it.add(spoken) })
        assertEquals(EmphasisVerdict.NotYet, take.verdict)
        assertEquals("Încă nu.", EmphasisWords.headline(take))
        assertEquals("„azi” a fost cam la fel de lung și de tare ca vecinii lui.", EmphasisWords.keyLine(take.keys.single(), "azi"))
        assertEquals("Lungește-l puțin și spune-l mai tare.", EmphasisWords.tip(take))
    }

    @Test
    fun theWrongWordStressedIsNamed() {
        val spoken = say(stressed = setOf(1), rate = 16000.0)
        val take = judge(spoken, ScriptedTranscriber().also { it.add(spoken) })
        assertEquals(EmphasisVerdict.NotYet, take.verdict)
        assertEquals("A ieșit în față și „trenul”.", EmphasisWords.extraLine(take))
    }

    @Test
    fun aKeyWordThatWasNotSaidIsNotJudged() {
        val spoken = say(stressed = emptySet(), rate = 16000.0, dropped = setOf(3))
        val recogniser = ScriptedTranscriber().also { it.add(spoken, readWrong = setOf("azi")) }
        val take = judge(spoken, recogniser)
        assertEquals(EmphasisState.NOT_MEASURED, take.keys.single().state)
        assertEquals(EmphasisVerdict.NotHeard, take.verdict)
        assertEquals("Nu am auzit clar „azi”.", EmphasisWords.keyLine(take.keys.single(), "azi"))
        assertEquals(0, take.tally.measuredKeyWords)
    }

    @Test
    fun silenceIsNotSentToTheRecogniser() {
        val recogniser = ScriptedTranscriber()
        val take = runBlocking {
            val clip = CapturedClip(FloatArray(48000), 48000.0)
            EmphasisTake.judge(item, clip, analyzeClip(clip.samples, clip.sampleRate, VoiceGroup.FEMALE, null), recogniser)
        }
        assertTrue(recogniser.calls.isEmpty())
        assertEquals(EmphasisVerdict.NotHeard, take.verdict)
        assertNull(take.analysis)
    }

    @Test
    fun twoKeyWordsBothStressed() {
        val contrast = EmphasisItem("t-02", Language.Ro, null, MarkedText.parse("Durează *cinci* minute, nu *zece*."))
        val spoken = SyntheticSpeech.say(contrast.text.words.map { it.shown }, stressed = setOf(1, 4), pauseAfter = SyntheticSpeech.readerPauses(contrast.text.words))
        val take = judge(spoken, ScriptedTranscriber().also { it.add(spoken) }, contrast)
        assertEquals(EmphasisVerdict.Stressed, take.verdict)
        assertEquals("Da! Cuvintele marcate au ieșit în față.", EmphasisWords.headline(take))
        // Two key words out of five, both stressed, is the aim: never "everything stressed".
        assertTrue(!take.tally.everythingStressed)
    }

    @Test
    fun recordedSpeechWithWhisperTimes() {
        // "The sheep is on the ship." in an ElevenLabs voice, with the word times Whisper gave on the emulator. The voice
        // puts its accents on "sheep" (higher and longer) and on "ship" (higher): the analysis finds them, and no
        // other word stands out.
        val take = Recorded.take("en-sentence-sheep")
        val text = MarkedText.parse("The *sheep* is on the ship.")
        val heard = ReadAloud.align(text.words, Transcript(take.script, take.words, 0.0, 0.0), SpeechLanguage.EN)
        val analysis = analyzeEmphasis(take.summary, heard.map { it?.span }, text.keyIndices)
        assertTrue(analysis.words.all { it.isMeasured })
        val sheep = analysis.words[1]
        assertEquals(EmphasisState.STRESSED, sheep.state)
        assertTrue(EmphasisCue.PITCH in sheep.passed && EmphasisCue.DURATION in sheep.passed)
        assertEquals(listOf(1), analysis.words.filter { it.isStressed }.map { it.index })
        assertEquals(EmphasisCue.PITCH, analysis.words[5].passed.single())
        assertEquals(1f, analysis.tally.keyShare)
    }

    @Test
    fun recordedRomanianSpeechStressesFewWords() {
        // Free Romanian speech read from a script: a natural voice stresses a few words, far from "everything".
        val take = Recorded.take("ro-speech")
        val text = MarkedText.parse(take.script)
        val heard = ReadAloud.align(text.words, Transcript(take.script, take.words, 0.0, 0.0), SpeechLanguage.RO)
        val analysis = analyzeEmphasis(take.summary, heard.map { it?.span })
        val tally = analysis.tally
        assertEquals(29, tally.measuredWords)
        val share = checkNotNull(tally.stressedShare)
        assertTrue("$share", share in 0.03f..0.2f)
        assertTrue(!tally.everythingStressed)
        // Every measured word has a duration and a level; pitch is missing only where the word has too few voiced frames.
        for (word in analysis.words) {
            assertNotNull(word.cues)
            assertTrue(word.cues!!.durationSeconds > 0f)
        }
        assertTrue(analysis.words.count { it.cues!!.pitchSt != null } >= 27)
        // The spans are Whisper's own, so the comparison uses the recogniser's times.
        assertEquals(TimeSpan(6.54, 6.88), analysis.words[17].span)
    }
}
