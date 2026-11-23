package tech.fonia.features.speaking.emphasis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.fonia.audio.CapturedClip
import tech.fonia.domain.Language
import tech.fonia.dsp.ClipSummary
import tech.fonia.dsp.EmphasisAnalysis
import tech.fonia.dsp.EmphasisCue
import tech.fonia.dsp.EmphasisState
import tech.fonia.dsp.EmphasisTally
import tech.fonia.dsp.TimeSpan
import tech.fonia.dsp.WordEmphasis
import tech.fonia.dsp.analyzeEmphasis
import tech.fonia.dsp.heardSpeech
import tech.fonia.features.session.romanianDecimal
import tech.fonia.speech.SpeechJudge
import tech.fonia.speech.SpeechLanguage
import tech.fonia.speech.SpeechResampler
import tech.fonia.speech.Transcriber
import tech.fonia.speech.WordStatus
import tech.fonia.speech.judgeSentence
import kotlin.math.abs
import kotlin.math.roundToInt

/** How a sentence went for its marked words. Only results (KR): never "greșit", and nothing about the reader. */
enum class EmphasisVerdict {
    /** Every marked word that could be measured stood out. */
    Stressed,

    /** At least one marked word cleared one bar or more, not all of them stood out. */
    Almost,

    /** The marked words were as long and as loud as the rest. */
    NotYet,

    /** No marked word could be measured: nothing heard, or not those words. */
    NotHeard,
}

/** One reading of one sentence. */
data class EmphasisTake(
    val item: EmphasisItem,
    /** Null when there was nothing to measure. */
    val analysis: EmphasisAnalysis?,
    /** What the recogniser wrote, for "Ce am auzit". */
    val heardText: String?,
) {
    val keys: List<WordEmphasis> get() = analysis?.keyWords ?: emptyList()

    val verdict: EmphasisVerdict
        get() {
            val measured = keys.filter { it.isMeasured }
            return when {
                measured.isEmpty() -> EmphasisVerdict.NotHeard
                measured.all { it.isStressed } -> EmphasisVerdict.Stressed
                measured.any { it.state == EmphasisState.STRESSED || it.state == EmphasisState.CLOSE } -> EmphasisVerdict.Almost
                else -> EmphasisVerdict.NotYet
            }
        }

    /** What this sentence adds to the set's counts. */
    val tally: EmphasisTally
        get() = analysis?.tally ?: EmphasisTally(keyWords = item.text.keyIndices.size, measuredKeyWords = 0, stressedKeyWords = 0, measuredWords = 0, stressedWords = 0)

    companion object {
        /**
         * Judges one take: the clip analysis gives the track, Whisper teacher-forced on the sentence gives where each
         * of its words is (`scoreSentence` and the iOS `judgeSentence`), and the words the recogniser did not hear
         * as written are left out. Runs the heavy parts off the main thread.
         */
        suspend fun judge(
            item: EmphasisItem,
            clip: CapturedClip,
            summary: ClipSummary,
            transcriber: Transcriber,
        ): EmphasisTake {
            if (!heardSpeech(summary)) return EmphasisTake(item, analysis = null, heardText = null)
            val language = if (item.language == Language.Ro) SpeechLanguage.RO else SpeechLanguage.EN
            val samples = withContext(Dispatchers.Default) { SpeechResampler.resample(clip.samples, clip.sampleRate) }
            val score = transcriber.scoreSentence(samples, item.text.plain, language)
            return withContext(Dispatchers.Default) {
                val outcome = SpeechJudge.judgeSentence(score)
                val spans = item.text.words.map { word ->
                    outcome.words
                        .firstOrNull { it.index == word.index && it.status != WordStatus.MISSED && it.end > it.start }
                        ?.let { TimeSpan(it.start, it.end) }
                }
                EmphasisTake(item, analyzeEmphasis(summary, spans, item.text.keyIndices), heardText = outcome.heard)
            }
        }
    }
}

/** The sentences the exercise says, in Romanian, as on the rest of the speaking page. Physical words only. */
object EmphasisWords {
    fun quoted(word: String): String = "„$word”"

    fun headline(take: EmphasisTake): String = when (take.verdict) {
        EmphasisVerdict.Stressed -> if (take.keys.size == 1) "Da! Cuvântul marcat a ieșit în față." else "Da! Cuvintele marcate au ieșit în față."
        EmphasisVerdict.Almost -> "Aproape."
        EmphasisVerdict.NotYet -> "Încă nu."
        EmphasisVerdict.NotHeard -> "Nu am auzit clar propoziția. Ține telefonul mai aproape și mai încearcă."
    }

    private fun adjective(cue: EmphasisCue): String = when (cue) {
        EmphasisCue.DURATION -> "mai lung"
        EmphasisCue.LOUDNESS -> "mai tare"
        EmphasisCue.PITCH -> "mai sus"
    }

    /** "mai lung", "mai lung și mai tare", "mai lung, mai tare și mai sus". */
    private fun joined(cues: List<EmphasisCue>): String {
        val words = cues.map(::adjective)
        return when (words.size) {
            0 -> ""
            1 -> words[0]
            else -> words.dropLast(1).joinToString(", ") + " și " + words.last()
        }
    }

    /** What was measured on one marked word: "„azi” a fost mai lung și mai tare decât cuvintele din jur." */
    fun keyLine(word: WordEmphasis, shown: String): String {
        val name = quoted(shown)
        val passed = EmphasisCue.entries.filter { it in word.passed }
        return when (word.state) {
            EmphasisState.STRESSED -> "$name a fost ${joined(passed)} decât cuvintele din jur."
            EmphasisState.CLOSE -> when (passed.single()) {
                EmphasisCue.DURATION -> "$name a fost mai lung decât cuvintele din jur, dar nu și mai tare."
                EmphasisCue.LOUDNESS -> "$name a fost mai tare decât cuvintele din jur, dar nu și mai lung."
                EmphasisCue.PITCH -> "Vocea a urcat pe $name, dar el a fost cam la fel de lung și de tare ca vecinii lui."
            }
            EmphasisState.LEVEL -> "$name a fost cam la fel de lung și de tare ca vecinii lui."
            EmphasisState.NOT_MEASURED -> "Nu am auzit clar $name."
        }
    }

    /** One thing to try next, for the first marked word that did not stand out. Length and level come first (H F2). */
    fun tip(take: EmphasisTake): String? {
        val word = take.keys.firstOrNull { !it.isStressed } ?: return null
        return when (word.state) {
            EmphasisState.STRESSED -> null
            EmphasisState.NOT_MEASURED -> "Citește toată propoziția, cu telefonul aproape."
            EmphasisState.CLOSE -> when (word.passed.single()) {
                EmphasisCue.DURATION -> "Spune-l și puțin mai tare."
                EmphasisCue.LOUDNESS -> "Lungește-l puțin."
                EmphasisCue.PITCH -> "Lungește-l puțin și spune-l mai tare."
            }
            EmphasisState.LEVEL -> "Lungește-l puțin și spune-l mai tare."
        }
    }

    /** Words nobody marked that stood out too: "A ieșit în față și „trenul”." Null when there are none. */
    fun extraLine(take: EmphasisTake): String? {
        val analysis = take.analysis ?: return null
        val names = analysis.extraStressed.map { quoted(take.item.text.words[it.index].bare) }
        return when (names.size) {
            0 -> null
            1 -> "A ieșit în față și ${names[0]}."
            else -> "Au ieșit în față și " + names.dropLast(1).joinToString(", ") + " și " + names.last() + "."
        }
    }

    /** The measured value of one cue against the neighbours: "+35 %", "+4,2 dB", "+2,5 semitonuri". */
    fun cueValue(cue: EmphasisCue, word: WordEmphasis): String? = when (cue) {
        EmphasisCue.DURATION -> word.durationGain?.let { signed((it * 100).roundToInt().toDouble(), decimals = false) + " %" }
        EmphasisCue.LOUDNESS -> word.loudnessGainDb?.let { signed(it.toDouble()) + " dB" }
        EmphasisCue.PITCH -> word.pitchGainSt?.let { signed(it.toDouble()) + " semitonuri" }
    }

    /** "Mai lung", "Mai tare", "Mai sus": the label of a cue's chip. */
    fun cueTitle(cue: EmphasisCue): String = adjective(cue).replaceFirstChar { it.uppercase() }

    /** The bar each cue is measured against, for the chips' description. */
    fun cueBar(cue: EmphasisCue): String = when (cue) {
        EmphasisCue.DURATION -> "bara: +30 %"
        EmphasisCue.LOUDNESS -> "bara: +3 dB"
        EmphasisCue.PITCH -> "bara: +2 semitonuri"
    }

    /** The end of a set: how many marked words stood out, the 80 % reference, and too much stress if there was. */
    fun summary(tally: EmphasisTally): List<String> {
        val lines = mutableListOf<String>()
        if (tally.measuredKeyWords == 0) {
            lines += "Nu am putut măsura cuvintele marcate."
            return lines
        }
        lines += "Cuvinte marcate care au ieșit în față: ${tally.stressedKeyWords} din ${tally.measuredKeyWords}."
        lines += if (tally.meetsKeyTarget) {
            "Reperul de 80 % e atins."
        } else {
            "Reperul e de cel puțin 80 % din cuvintele marcate."
        }
        if (tally.everythingStressed) {
            val share = ((tally.stressedShare ?: 0f) * 100).roundToInt()
            lines += "Au ieșit în față $share % din toate cuvintele. Peste 40 %, cele importante nu se mai deosebesc."
        }
        return lines
    }

    private fun signed(value: Double, decimals: Boolean = true): String {
        val text = if (decimals) romanianDecimal(abs(value)) else abs(value).roundToInt().toString()
        return when {
            text == "0" || text == "0,0" -> text
            value < 0 -> "−$text"
            else -> "+$text"
        }
    }
}
