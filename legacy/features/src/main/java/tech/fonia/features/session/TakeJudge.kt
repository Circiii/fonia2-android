package tech.fonia.features.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.fonia.audio.CapturedClip
import tech.fonia.design.Rating
import tech.fonia.domain.EnglishContrast
import tech.fonia.domain.Exercise
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.IntonationAsk
import tech.fonia.domain.Language
import tech.fonia.domain.RepeatPrompt
import tech.fonia.domain.SoundDetector
import tech.fonia.domain.SoundID
import tech.fonia.dsp.ClipSummary
import tech.fonia.dsp.DSPThresholds
import tech.fonia.dsp.TalkerCalibration
import tech.fonia.dsp.VoiceGroup
import tech.fonia.dsp.alignFricatives
import tech.fonia.dsp.analyzeClip
import tech.fonia.dsp.analyzeStress
import tech.fonia.dsp.compareProsody
import tech.fonia.dsp.heardSpeech
import tech.fonia.dsp.judgeRhotic
import tech.fonia.dsp.judgeSibilant
import tech.fonia.dsp.judgeStress
import tech.fonia.speech.SentenceScore
import tech.fonia.speech.SpeechJudge
import tech.fonia.speech.SpeechLanguage
import tech.fonia.speech.SpeechResampler
import tech.fonia.speech.Transcriber
import tech.fonia.speech.judgeSentence
import tech.fonia.store.Attempt
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Turns a finished take into a verdict. The heavy work runs off the main thread, and every path ends in a verdict: if
 * the phone can't judge, it says so and hands it to a person.
 *
 * [thresholds] is iOS's `DSPThresholds.v1` unless the take came from a narrow-band microphone, when the S/Ș analysis
 * is off (`AudioHub.analysisThresholds`).
 */
class TakeJudge(
    val content: FoniaContent,
    val group: VoiceGroup,
    val calibration: TalkerCalibration?,
    val transcriber: Transcriber?,
    val thresholds: DSPThresholds = DSPThresholds.v1,
) {
    suspend fun judge(exercise: Exercise, take: CapturedClip): TakeVerdict {
        val summary = summarise(take, group, calibration, thresholds)
        return when (exercise) {
            is Exercise.Repeat -> {
                val prompt = exercise.prompt
                speech(prompt.text, prompt.language, prompt.sound, prompt.rival, prompt.englishContrast, prompt.cue, prompt.level, take, summary)
            }
            is Exercise.Name -> speech(exercise.item.text, Language.Ro, exercise.sound, null, null, null, RepeatPrompt.Level.Word, take, summary)
            is Exercise.Twister -> speech(exercise.text, exercise.language, exercise.sound, null, null, null, RepeatPrompt.Level.Sentence, take, summary)
            Exercise.Volume -> volume(summary)
            Exercise.Glide -> glide(summary)
            is Exercise.Intonation -> intonation(exercise.ask, summary)
            is Exercise.Expression -> expression(summary)
            is Exercise.Pace -> pace(exercise.targetSyllablesPerSecond, summary)
            is Exercise.Pauses -> pauses(exercise.text, summary)
            is Exercise.Retell, is Exercise.Describe -> VerdictWriter.personJudges(heard = heard(summary), tip = null, pose = null)
            else -> VerdictWriter.personJudges(heard = heard(summary), tip = null, pose = null)
        }
    }

    suspend fun shadow(model: CapturedClip, take: CapturedClip): TakeVerdict {
        val modelSummary = summarise(model, group, null, thresholds)
        val takeSummary = summarise(take, group, calibration, thresholds)
        return VerdictWriter.prosody(compareProsody(modelSummary, takeSummary, thresholds))
    }

    private fun heard(summary: ClipSummary): Boolean = heardSpeech(summary, thresholds)

    private suspend fun speech(
        text: String,
        language: Language,
        sound: SoundID?,
        rival: String?,
        contrast: String?,
        cue: String?,
        level: RepeatPrompt.Level,
        take: CapturedClip,
        summary: ClipSummary,
    ): TakeVerdict {
        if (language == Language.En) {
            if (contrast != null && content.english.contrast(contrast)?.kind == EnglishContrast.Kind.Stress) {
                return VerdictWriter.stress(judgeStress(analyzeStress(summary, thresholds), marked = text, thresholds = thresholds), marked = text)
            }
            return english(text, rival, contrast, cue, take, summary)
        }
        if (sound == null) return romanianFreeWords(text, level, take, summary)
        val def = content.sound(sound)
        var verdict = when (def.detector) {
            SoundDetector.Sibilant -> {
                val outcome = judgeSibilant(text, def.graphemes, summary, thresholds)
                val judged = if (level == RepeatPrompt.Level.Syllable && text == def.burstPrompt) {
                    VerdictWriter.bursts(outcome, def)
                } else {
                    VerdictWriter.sibilant(outcome, def)
                }
                val target = alignFricatives(text, summary, thresholds).firstOrNull { match ->
                    def.graphemes.contains(match.expected.grapheme) && match.detected != null
                }
                judged.copy(anchor = target?.detected, anchorPlace = target?.expected?.place)
            }
            SoundDetector.Rhotic -> VerdictWriter.rhotic(judgeRhotic(text, summary, thresholds), def)
            SoundDetector.None -> VerdictWriter.personJudges(heard = heard(summary), tip = def.how.drop(1).firstOrNull() ?: def.how.firstOrNull(), pose = def.viseme)
        }
        // A sentence also shows which words were understood, but the word check never overrules the sound.
        val transcriber = transcriber
        if (level == RepeatPrompt.Level.Sentence && transcriber != null && (verdict.outcome != Attempt.Outcome.Unclear || heard(summary))) {
            val scored = attempt { scoreSentence(transcriber, text, SpeechLanguage.RO, take) }
            if (scored != null) verdict = verdict.copy(words = SpeechJudge.judgeSentence(scored).words)
        }
        return verdict
    }

    private suspend fun romanianFreeWords(text: String, level: RepeatPrompt.Level, take: CapturedClip, summary: ClipSummary): TakeVerdict {
        val transcriber = transcriber
        if (level == RepeatPrompt.Level.Sentence && transcriber != null) {
            val scored = attempt { scoreSentence(transcriber, text, SpeechLanguage.RO, take) }
            if (scored != null) return VerdictWriter.sentence(SpeechJudge.judgeSentence(scored))
        }
        return VerdictWriter.personJudges(heard = heard(summary), tip = "Ascultă-te, apoi ascultă exemplul.", pose = null)
    }

    private suspend fun english(text: String, rival: String?, contrast: String?, cue: String?, take: CapturedClip, summary: ClipSummary): TakeVerdict {
        val transcriber = transcriber
            ?: return VerdictWriter.personJudges(heard = heard(summary), tip = "Ascultă-te, apoi ascultă exemplul.", pose = null)
        return try {
            withContext(Dispatchers.Default) {
                val samples = SpeechResampler.resample(take.samples, take.sampleRate)
                if (rival != null) {
                    val score = transcriber.scorePair(samples, target = text, rival = rival, language = SpeechLanguage.EN)
                    VerdictWriter.pair(SpeechJudge.judgePair(score, contrast), target = text, rival = rival, cue = cue)
                } else {
                    val score = transcriber.scoreSentence(samples, target = text, language = SpeechLanguage.EN)
                    VerdictWriter.sentence(SpeechJudge.judgeSentence(score))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            VerdictWriter.personJudges(heard = heard(summary), tip = null, pose = null)
        }
    }

    private suspend fun scoreSentence(transcriber: Transcriber, text: String, language: SpeechLanguage, take: CapturedClip): SentenceScore =
        withContext(Dispatchers.Default) {
            val samples = SpeechResampler.resample(take.samples, take.sampleRate)
            transcriber.scoreSentence(samples, target = text, language = language)
        }

    /** Swift's `try?`: a failure gives null, a cancellation still ends the caller. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    // Voice tasks. Facts about the take, read from the same clip analysis.

    private fun volume(summary: ClipSummary): TakeVerdict {
        if (!heard(summary)) return VerdictWriter.notHeard
        val spread = summary.peakDb - summary.floorDb
        if (spread >= 30) return VerdictWriter.facts(Rating.yes, "Te aud și din ultimul rând")
        if (spread >= 20) return VerdictWriter.facts(Rating.nearly, "Încă puțin mai tare", "Trage aer și trimite vocea departe, fără să strigi.")
        return VerdictWriter.facts(Rating.notYet, "A sunat încet", "Ia mai mult aer și vorbește mai tare, fără să forțezi gâtul.")
    }

    private fun glide(summary: ClipSummary): TakeVerdict {
        if (!heard(summary)) return VerdictWriter.notHeard
        if (summary.pitch.rangeSt >= 6) return VerdictWriter.facts(Rating.yes, "Vocea a urcat și a coborât")
        return VerdictWriter.facts(Rating.nearly, "Urcă și coboară mai mult", "Pornește de jos și urcă încet, ca pe un deal.")
    }

    private fun intonation(ask: IntonationAsk, summary: ClipSummary): TakeVerdict {
        if (!heard(summary)) return VerdictWriter.notHeard
        val rising = summary.finalSlopeSt > 0.5f
        return when (ask) {
            IntonationAsk.Question -> if (rising) {
                VerdictWriter.facts(Rating.yes, "A sunat ca o întrebare")
            } else {
                VerdictWriter.facts(Rating.nearly, "Finalul a coborât", "La întrebare, vocea urcă la sfârșit.")
            }
            IntonationAsk.Statement -> if (rising) {
                VerdictWriter.facts(Rating.nearly, "Finalul a urcat", "La afirmație, vocea coboară la sfârșit.")
            } else {
                VerdictWriter.facts(Rating.yes, "A sunat ca o afirmație")
            }
        }
    }

    private fun expression(summary: ClipSummary): TakeVerdict {
        if (!heard(summary)) return VerdictWriter.notHeard
        val verdicts = content.diction.expressionVerdicts
        if (summary.pitch.rangeSt.toDouble() >= verdicts.pitchRangeSemitones) return VerdictWriter.facts(Rating.yes, verdicts.moved)
        return VerdictWriter.facts(Rating.nearly, verdicts.flat, verdicts.flatTip)
    }

    private fun pace(target: Double, summary: ClipSummary): TakeVerdict {
        if (!heard(summary)) return VerdictWriter.notHeard
        val rate = summary.articulationRate.toDouble()
        val shown = romanianDecimal(rate)
        if (abs(rate - target) <= target * 0.2) return VerdictWriter.facts(Rating.yes, "Ritm bun", "$shown silabe pe secundă.")
        if (rate > target) return VerdictWriter.facts(Rating.nearly, "Puțin mai rar", "$shown silabe pe secundă. Fă o mică pauză între idei.")
        return VerdictWriter.facts(Rating.nearly, "Puțin mai repede", "$shown silabe pe secundă. Leagă cuvintele din aceeași idee.")
    }

    private fun pauses(text: String, summary: ClipSummary): TakeVerdict {
        if (!heard(summary)) return VerdictWriter.notHeard
        val wanted = text.count { it == '|' }
        val made = summary.pauses.count { it.duration >= 0.25 }
        if (made >= wanted) return VerdictWriter.facts(Rating.yes, "Ai făcut toate pauzele")
        return VerdictWriter.facts(Rating.nearly, "Am auzit $made din $wanted pauze", "Oprește-te un pic mai mult la fiecare linie.")
    }

    companion object {
        /** `analyzeClip` off the main thread (`@concurrent` on iOS). */
        suspend fun summarise(
            clip: CapturedClip,
            group: VoiceGroup,
            calibration: TalkerCalibration?,
            thresholds: DSPThresholds = DSPThresholds.v1,
        ): ClipSummary = withContext(Dispatchers.Default) {
            analyzeClip(clip.samples, clip.sampleRate, group, calibration, thresholds)
        }
    }
}

/**
 * `value.formatted(.number.precision(.fractionLength(1)).locale(Locale(identifier: "ro_RO")))`: one decimal, with a
 * comma ("4,2"). The formatter rounds the shortest decimal form of the number half to even, so this does too.
 */
fun romanianDecimal(value: Double): String {
    if (!value.isFinite()) return value.toString()
    val rounded = BigDecimal(value.toString()).setScale(1, RoundingMode.HALF_EVEN)
    // `-0,0` would read as a sign with no number: the formatter writes 0.
    val text = if (rounded.signum() == 0) "0.0" else rounded.toPlainString()
    val (whole, fraction) = text.split('.').let { it[0] to it.getOrElse(1) { "0" } }
    val negative = whole.startsWith("-")
    val digits = whole.removePrefix("-")
    // ICU groups the thousands of `ro_RO` with a dot, from four digits up.
    val grouped = if (digits.length > 3) digits.reversed().chunked(3).joinToString(".").reversed() else digits
    return (if (negative) "-" else "") + grouped + "," + fraction
}
