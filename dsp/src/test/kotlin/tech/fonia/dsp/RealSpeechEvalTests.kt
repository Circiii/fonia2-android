package tech.fonia.dsp

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * False rejection on the bundled voice clips, a known-good adult voice. Writes `eval-report.md`
 * (iOS writes it next to its tests; here it goes to `dsp/build/eval-report.md`, outside the sources).
 */
class RealSpeechEvalTests {
    @Test
    fun knownGoodClipsPassAndTheReportIsWritten() {
        val library = VoiceClipLibrary.open()
        assumeTrue(library != null, "The voice clips live in the content submodule (fonia.content/voice), which is missing")
        assumeTrue(Ffmpeg.available, "ffmpeg is not on PATH: the JVM has no MP3 decoder, so RealSpeechEvalTests cannot decode the clips")
        val evaluation = RealSpeechEvaluation(assertNotNull(library))
        val report = evaluation.markdown()
        println(report)
        val build = System.getProperty("fonia.dsp.build") ?: "build"
        File(build).mkdirs()
        File(build, "eval-report.md").writeText(report, Charsets.UTF_8)

        for (row in evaluation.wordRows) {
            assertTrue(row.falseRejection <= 0.15f, "${row.name}: ${row.rejectedWords}")
        }
        val swap = evaluation.swapTally()
        assertTrue(swap.wrongPlace.toFloat() / swap.total.toFloat() >= 0.85f, "wrongPlace ${swap.wrongPlace} of ${swap.total}")
        assertTrue(swap.accepted.toFloat() / swap.total.toFloat() <= 0.1f, "accepted ${swap.accepted} of ${swap.total}")
    }
}

private class Sound(val name: String, val graphemes: List<String>)

private val sibilantSounds = listOf(
    Sound("S", listOf("s")), Sound("Ș", listOf("ș")), Sound("Z", listOf("z")),
    Sound("J", listOf("j")), Sound("Ț", listOf("ț")), Sound("CE/CI", listOf("ce", "ci")),
    Sound("GE/GI", listOf("ge", "gi")),
)

private val vowelLetters = "aăâeiîou".map { it.toString() }.toSet()

class EvalRow(val name: String) {
    var total = 0
    var accepted = 0
    var abstained = 0
    var rejected = 0
    val outcomes = LinkedHashMap<String, Int>()
    val rejectedWords = ArrayList<String>()
    val abstainedWords = ArrayList<String>()

    val falseRejection: Float get() = if (total > 0) rejected.toFloat() / total.toFloat() else 0f
    val abstention: Float get() = if (total > 0) abstained.toFloat() / total.toFloat() else 0f

    fun add(outcome: SoundOutcome, text: String) {
        total += 1
        val key = label(outcome)
        outcomes[key] = (outcomes[key] ?: 0) + 1
        if (outcome.isAccepted()) {
            accepted += 1
        } else if (outcome.isAbstention) {
            abstained += 1
            abstainedWords.add(text)
        } else {
            rejected += 1
            rejectedWords.add(text)
        }
    }

    val line: String
        get() {
            // Swift sorts a Dictionary, whose order is randomised; ties here keep first-seen order.
            val breakdown = outcomes.entries.sortedWith { a, b -> b.value.compareTo(a.value) }
                .joinToString(", ") { "${it.key} ${it.value}" }
            val failing = if (rejectedWords.isEmpty()) "" else rejectedWords.joinToString(", ")
            return "| $name | $total | $accepted | $abstained | $rejected | ${percent(falseRejection)} | ${percent(abstention)} | $breakdown | $failing |"
        }

    companion object {
        val header = """
            | Sound | Clips | Accepted | Abstained | Rejected | False rejection | Abstention | Outcomes | Rejected clips |
            |---|---|---|---|---|---|---|---|---|
        """.trimIndent()
    }
}

private fun label(outcome: SoundOutcome): String = when (outcome) {
    SoundOutcome.NotHeard -> "notHeard"
    SoundOutcome.TooShort -> "tooShort"
    SoundOutcome.Clipped -> "clipped"
    is SoundOutcome.Unclear -> "unclear(${outcome.reason.rawValue})"
    SoundOutcome.Good -> "good"
    is SoundOutcome.WrongPlace -> "wrongPlace"
    SoundOutcome.Missing -> "missing"
    is SoundOutcome.Partial -> "partial"
    SoundOutcome.Weak -> "weak"
    SoundOutcome.Unvoiced -> "unvoiced"
    SoundOutcome.LateralHint -> "lateralHint"
    SoundOutcome.Tap -> "tap"
    SoundOutcome.Trill -> "trill"
}

/**
 * C's printf `%.Nf`, which Swift's `String(format:)` uses: the exact binary value, ties to even.
 * Java's `%.Nf` rounds ties up.
 */
private fun format(value: Double, digits: Int): String =
    BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()

private fun format(value: Float, digits: Int): String = format(value.toDouble(), digits)

private fun percent(value: Float): String = format(value * 100f, 1) + "%"

/** Syllable drills, prolongations and repeated chains, as opposed to real words. */
private fun isDrill(text: String): Boolean {
    if (text.contains("-")) return true
    val letters = characters(text)
    if (letters.size >= 4 && letters.map { nfc(it) }.toSet().size == 1) return true
    if (letters.size <= 2) return true
    return letters.size <= 4 && nfc(letters[0]) in vowelLetters && nfc(letters[letters.size - 1]) in vowelLetters
}

private fun rhoticContext(text: String): String {
    val letters = characters(text)
    val index = letters.indexOfFirst { nfc(it) == "r" }
    if (index < 0) return "none"
    val positions = rhoticPositions(text)
    if (positions.any { it.isIntervocalic }) return "intervocalic"
    if (index == 0) return "word-initial"
    if (index == letters.size - 1) return "word-final"
    return "consonant cluster"
}

private fun sibilantContext(text: String, graphemes: List<String>): String {
    val target = expectedFricatives(text).firstOrNull { it.grapheme in graphemes } ?: return "none"
    if (target.position == 0f) return "word-initial"
    return if (target.isWordFinal) "word-final" else "word-medial"
}

class SwapTally(
    val total: Int,
    val wrongPlace: Int,
    val otherRejection: Int,
    val accepted: Int,
    val abstained: Int,
    val missed: List<String>,
)

class RealSpeechEvaluation(private val library: VoiceClipLibrary) {
    private val group = VoiceGroup.FEMALE
    private val words: List<String>
    private val drills: List<String>
    private val phrases: List<String>
    private val clips = LinkedHashMap<String, VoiceClipLibrary.Clip>()
    private val summaries = LinkedHashMap<String, ClipSummary>()
    private var analysisSeconds = 0.0

    init {
        val texts = library.romanianTexts
        val singles = texts.filter { text -> characters(text).all { isLetter(it) || it == "-" } }
        words = singles.filter { !isDrill(it) }
        drills = singles.filter { isDrill(it) }
        val singleSet = singles.toSet()
        phrases = texts.filter { text -> text !in singleSet && characterCount(text) <= 60 }
        library.prefetch(words + drills + phrases)
        for (text in words + drills + phrases) {
            val clip = library.decode(text) ?: continue
            clips[text] = clip
            val began = System.nanoTime()
            summaries[text] = analyzeClip(clip.pcm, sampleRate = clip.sampleRate, group = group, calibration = null)
            analysisSeconds += (System.nanoTime() - began).toDouble() / 1e9
        }
    }

    // MARK: Tables

    private fun rows(texts: List<String>): List<EvalRow> {
        val result = sibilantSounds.map { sound ->
            val row = EvalRow(sound.name)
            for (text in texts) {
                if (!expectedFricatives(text).any { it.grapheme in sound.graphemes }) continue
                val summary = summaries[text] ?: continue
                row.add(judgeSibilant(text = text, graphemes = sound.graphemes, summary = summary), text)
            }
            row
        }.toMutableList()
        val rhotic = EvalRow("R")
        for (text in texts) {
            if (!text.contains("r")) continue
            val summary = summaries[text] ?: continue
            rhotic.add(judgeRhotic(text = text, summary = summary), text)
        }
        result.add(rhotic)
        return result.filter { it.total > 0 }
    }

    val wordRows: List<EvalRow> get() = rows(words)

    private fun contextRows(): List<EvalRow> {
        val table = LinkedHashMap<String, EvalRow>()
        for (sound in sibilantSounds.take(2)) {
            for (text in words) {
                if (!expectedFricatives(text).any { it.grapheme in sound.graphemes }) continue
                val summary = summaries[text] ?: continue
                val name = "${sound.name}, ${sibilantContext(text, sound.graphemes)}"
                table.getOrPut(name) { EvalRow(name) }.add(judgeSibilant(text = text, graphemes = sound.graphemes, summary = summary), text)
            }
        }
        for (text in words) {
            if (!text.contains("r")) continue
            val summary = summaries[text] ?: continue
            val name = "R, ${rhoticContext(text)}"
            table.getOrPut(name) { EvalRow(name) }.add(judgeRhotic(text = text, summary = summary), text)
        }
        return table.values.sortedWith { a, b -> swiftStringOrder.compare(a.name, b.name) }
    }

    /**
     * Judging an S clip as if Ș were the target, and the reverse. A proxy for false acceptance: the
     * voice really produced the other sound.
     */
    fun swapTally(): SwapTally {
        val pairs = listOf("s" to "ș", "ș" to "s", "z" to "j", "j" to "z")
        var total = 0
        var wrongPlace = 0
        var otherRejection = 0
        var accepted = 0
        var abstained = 0
        val missed = ArrayList<String>()
        for ((from, to) in pairs) {
            for (text in words) {
                if (!(expectedFricatives(text).any { it.grapheme == from } && !expectedFricatives(text).any { it.grapheme == to })) continue
                val summary = summaries[text] ?: continue
                val swapped = text.replace(from, to)
                val outcome = judgeSibilant(text = swapped, graphemes = listOf(to), summary = summary)
                total += 1
                if (outcome is SoundOutcome.WrongPlace) {
                    wrongPlace += 1
                } else if (outcome.isAccepted()) {
                    accepted += 1
                    missed.add(text)
                } else if (outcome.isAbstention) {
                    abstained += 1
                } else {
                    otherRejection += 1
                }
            }
        }
        return SwapTally(total, wrongPlace, otherRejection, accepted, abstained, missed)
    }

    /** Words with no fricative judged as if their first letter were S: a burst must not pass as a hiss. */
    private fun omissionTally(): Triple<Int, Int, List<String>> {
        var total = 0
        val acceptedWords = ArrayList<String>()
        for (text in words) {
            if (expectedFricatives(text).isNotEmpty()) continue
            val summary = summaries[text] ?: continue
            val target = "s" + characters(text).drop(1).joinToString("")
            total += 1
            if (judgeSibilant(text = target, graphemes = listOf("s"), summary = summary).isAccepted()) acceptedWords.add(text)
        }
        return Triple(total, acceptedWords.size, acceptedWords)
    }

    private fun rhoticFalseEvents(): Triple<Int, List<String>, List<String>> {
        val withTap = ArrayList<String>()
        val withTrill = ArrayList<String>()
        var total = 0
        for (text in words) {
            if (text.contains("r")) continue
            val summary = summaries[text] ?: continue
            total += 1
            if (summary.taps.isNotEmpty()) withTap.add(text)
            if (summary.trills.isNotEmpty()) withTrill.add(text)
        }
        return Triple(total, withTap, withTrill)
    }

    private fun priorSensitivity(): List<Pair<VoiceGroup, EvalRow>> =
        VoiceGroup.entries.map { candidate ->
            val row = EvalRow(candidate.rawValue)
            for (sound in sibilantSounds.take(2)) {
                for (text in words) {
                    if (!expectedFricatives(text).any { it.grapheme in sound.graphemes }) continue
                    val clip = clips[text] ?: continue
                    val summary = analyzeClip(clip.pcm, sampleRate = clip.sampleRate, group = candidate, calibration = null)
                    row.add(judgeSibilant(text = text, graphemes = sound.graphemes, summary = summary), text)
                }
            }
            candidate to row
        }

    /** Fits a boundary from 5 + 5 tokens of this voice, then judges the remaining clips. */
    private fun calibratedRun(): Triple<SibilantNorm, EvalRow, List<String>> {
        val calibration = TalkerCalibration()
        val anchorWords = ArrayList<String>()
        for ((grapheme, place) in listOf("s" to SibilantPlace.S, "ș" to SibilantPlace.SH)) {
            var used = 0
            for (text in words) {
                if (used >= 5) continue
                val summary = summaries[text] ?: continue
                val match = alignFricatives(text = text, summary = summary).firstOrNull { it.expected.grapheme == grapheme }
                val detected = match?.detected ?: continue
                calibration.add(detected, place = place)
                anchorWords.add(text)
                used += 1
            }
        }
        val norm = calibration.norm(group)
        val row = EvalRow("fitted")
        for (sound in sibilantSounds.take(2)) {
            for (text in words) {
                if (anchorWords.contains(text) || !expectedFricatives(text).any { it.grapheme in sound.graphemes }) continue
                val clip = clips[text] ?: continue
                val summary = analyzeClip(clip.pcm, sampleRate = clip.sampleRate, group = group, calibration = calibration)
                row.add(judgeSibilant(text = text, graphemes = sound.graphemes, summary = summary), text)
            }
        }
        return Triple(norm, row, anchorWords)
    }

    /** The research warning made concrete: the same clips after decimation to 16 kHz. */
    private fun decimatedRun(): EvalRow {
        val row = EvalRow("16 kHz")
        for (sound in sibilantSounds.take(2)) {
            for (text in words) {
                if (!expectedFricatives(text).any { it.grapheme in sound.graphemes }) continue
                val clip = clips[text] ?: continue
                val lower = resample(clip.pcm, from = clip.sampleRate, to = 16000.0)
                val summary = analyzeClip(lower, sampleRate = 16000.0, group = group, calibration = null)
                row.add(judgeSibilant(text = text, graphemes = sound.graphemes, summary = summary), text)
            }
        }
        return row
    }

    private fun liveAgreement(): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        fun run(grapheme: String, wantsHigh: Boolean): Pair<Int, Int> {
            var right = 0
            var total = 0
            for (text in words) {
                val expected = expectedFricatives(text)
                if (!(expected.any { it.grapheme == grapheme } && expected.mapNotNull { it.place }.toSet().size == 1)) continue
                val clip = clips[text] ?: continue
                val analyzer = LiveAnalyzer(sampleRate = clip.sampleRate, group = group, calibration = null)
                // Room tone first, so the live floor has settled as it would in use.
                val padded = withNoise(silence(1.2, clip.sampleRate), amp = 0.0005f) + clip.pcm
                val readings = ArrayList<Float>()
                val hop = (clip.sampleRate / 100).toInt()
                var start = 0
                while (start < padded.size - hop) {
                    val frame = analyzer.process(padded, start, hop)
                    if (frame.fricativePresent) readings.add(frame.sNess)
                    start += hop
                }
                if (readings.isEmpty()) continue
                total += 1
                val centre = readings.sorted()[readings.size / 2]
                if (if (wantsHigh) centre >= 0.5f else centre < 0.5f) right += 1
            }
            return right to total
        }
        val s = run("s", wantsHigh = true)
        val sh = run("ș", wantsHigh = false)
        return s to sh
    }

    private fun bandwidthLines(): List<String> {
        val carriers = words.filter { word -> expectedFricatives(word).any { it.place != null } }
        val values = carriers.mapNotNull { summaries[it]?.quality?.bandwidthHz }.sorted().toFloatArray()
        val rates = clips.values.map { it.sampleRate.toInt() }.toSet().sorted()
        val levels = ArrayList<Float>()
        for (text in carriers) {
            val clip = clips[text] ?: continue
            val summary = summaries[text] ?: continue
            val frame = FrameSpectrum(2048)
            for (segment in summary.fricatives) {
                val centre = ((segment.start + segment.end) / 2 * clip.sampleRate).toInt() - 1024
                if (!(centre >= 0 && centre + 2048 <= clip.pcm.size)) continue
                frame.compute(clip.pcm, centre)
                val binHz = clip.sampleRate.toFloat() / 2048f
                fun energy(low: Float, high: Float): Float {
                    var sum = 0f
                    for (k in (low / binHz).toInt() until minOf(frame.binCount, (high / binHz).toInt())) sum += frame.power[k]
                    return sum
                }
                levels.add(toDb(energy(16000f, 20000f)) - toDb(energy(5000f, 11000f)))
            }
        }
        val sortedLevels = levels.sorted().toFloatArray()
        return listOf(
            "- Sample rate of every decoded clip: ${rates.joinToString(", ") { "$it Hz" }}. Source: ${clips.size} MP3s at about 128 kbps. None is 48 kHz.",
            "- Measured bandwidth on words with a sibilant (frication's average spectrum falling ${format(DSPThresholds.v1.sibilant.bandwidthDropDb, 0)} dB under its peak): min ${format(values.firstOrNull() ?: 0f, 0)} Hz, median ${format(percentile(values, 0.5f), 0)} Hz, max ${format(values.lastOrNull() ?: 0f, 0)} Hz.",
            "- Energy in 16-20 kHz relative to 5-11 kHz during frication: median ${format(percentile(sortedLevels, 0.5f), 1)} dB. This is the encoder's lowpass, so the top of the band is absent.",
            "- S/Ș can be tested here, because both places sit well under the lowpass. These clips are not the 16 kHz files the research warns about. They still cannot test anything above about 15 kHz: the 11-20 kHz band ratio is measured on a truncated band, centroids read lower than a full-band capture would, and the lateral hint is off on all of them (it needs 16 kHz of bandwidth).",
        )
    }

    private fun siNote(): String {
        val centroids = (summaries["si"]?.fricatives ?: emptyList()).map { it.centroidHz } +
            (summaries["si-si-si"]?.fricatives ?: emptyList()).map { it.centroidHz }
        return "`si` and `si-si-si`, for example, measure as Ș (centroids ${format((centroids.minOrNull() ?: 0f) / 1000f, 1)} to ${format((centroids.maxOrNull() ?: 0f) / 1000f, 1)} kHz)."
    }

    private fun syllableLine(): String {
        var exact = 0
        var over = 0
        var under = 0
        for (text in words) {
            val summary = summaries[text] ?: continue
            val expected = splitCount(text)
            if (summary.syllables.size == expected) {
                exact += 1
            } else if (summary.syllables.size > expected) {
                over += 1
            } else {
                under += 1
            }
        }
        return "- Syllable count against written vowel groups (an estimate; diphthongs make it stricter than it should be): $exact exact, $over over, $under under, of ${words.size} words."
    }

    /** Swift's `text.split { !vowelLetters.contains($0) }.count`: runs of vowels, empty runs dropped. */
    private fun splitCount(text: String): Int {
        var count = 0
        var inRun = false
        for (letter in characters(text)) {
            val vowel = nfc(letter) in vowelLetters
            if (vowel && !inRun) count += 1
            inRun = vowel
        }
        return count
    }

    private fun qualityLine(): String {
        val all = summaries.values
        return "- Capture checks: ${all.count { it.quality.isClipped }} clipped, ${all.count { it.quality.isTooQuiet }} too quiet, ${all.count { it.quality.isTooShort }} too short, of ${all.size} clips."
    }

    private fun pitchLine(): String {
        val medians = words.mapNotNull { summaries[it]?.pitch?.medianHz }.filter { it > 0f }.sorted().toFloatArray()
        return "- Voice: median F0 per word ${format(medians.firstOrNull() ?: 0f, 0)} to ${format(medians.lastOrNull() ?: 0f, 0)} Hz (overall ${format(percentile(medians, 0.5f), 0)} Hz), which is why the `female` prior is used."
    }

    // MARK: Report

    fun markdown(): String {
        val swap = swapTally()
        val omission = omissionTally()
        val events = rhoticFalseEvents()
        val calibrated = calibratedRun()
        val decimated = decimatedRun()
        val live = liveAgreement()
        val priorRows = priorSensitivity()
        val lines = ArrayList<String>()
        lines.add("# FoniaDSP evaluation on the bundled voice clips")
        lines.add("")
        lines.add("Written by `./gradlew :dsp:test` (`RealSpeechEvalTests`), the Kotlin port of the iOS report. Do not edit by hand.")
        lines.add("")
        lines.add("## What this can and cannot show")
        lines.add("")
        lines.add("- One adult voice from text-to-speech (the site's clip generator uses ElevenLabs), taken as correct. This measures false rejection only on easy, clean audio. It says nothing about children, about disordered speech, or about any phone microphone.")
        lines.add("- Thresholds were adjusted while looking at these same clips (see `Thresholds.swift`), with no held-out set. Expect real use to do worse than these figures.")
        lines.add("- Multitaper statistics ran at each clip's real rate, 44.1 kHz. The 48 kHz capture path was not exercised on real speech.")
        lines.add("- Clips decoded with the ffmpeg command line, aligned as AVAudioFile delivers them (decoder delay dropped, encoder delay kept), not with AVAudioFile itself, so the samples can differ slightly from the iOS run.")
        lines.add(pitchLine())
        lines.add("")
        lines.add("## Sample rate and bandwidth")
        lines.add("")
        lines.addAll(bandwidthLines())
        lines.add("")
        lines.add("## False rejection on words (${words.size} clips, `${group.rawValue}` prior)")
        lines.add("")
        lines.add("Accepted counts a pass. Abstained counts `unclear`, `tooShort`, `notHeard` and `clipped`, which say nothing about pronunciation. Rejected counts `wrongPlace`, `missing`, `partial` under 60%, `weak` and `unvoiced`. A word counts once per sound.")
        lines.add("")
        lines.add(EvalRow.header)
        lines.addAll(wordRows.map { it.line })
        lines.add("")
        lines.add("### By position in the word")
        lines.add("")
        lines.add(EvalRow.header)
        lines.addAll(contextRows().map { it.line })
        lines.add("")
        lines.add("R is detected from the envelope, so its absence only counts against the speaker at an intervocalic R. Elsewhere the judge abstains, which keeps false rejection low at the price of never catching an error there.")
        lines.add("")
        lines.add("## Drills and short phrases")
        lines.add("")
        lines.add("Syllable drills and prolongations (${drills.size} clips). These are nonsense syllables and the voice is text-to-speech, so a clip may not hold the sound its spelling asks for. ${siNote()} They were not checked by ear for this report, so read this table as a lead, not as a rate:")
        lines.add("")
        lines.add(EvalRow.header)
        lines.addAll(rows(drills).map { it.line })
        lines.add("")
        lines.add("Phrases of up to 60 characters (${phrases.size} clips). Alignment is harder here, and every occurrence of a sound must match:")
        lines.add("")
        lines.add(EvalRow.header)
        lines.addAll(rows(phrases).map { it.line })
        lines.add("")
        lines.add("## False acceptance proxies")
        lines.add("")
        lines.add("These are stand-ins. No clip holds a real child's error, so real false acceptance is unmeasured.")
        lines.add("")
        lines.add("- S against Ș and Z against J, text swapped so the voice produced the other place: ${swap.wrongPlace} of ${swap.total} reported as `wrongPlace`, ${swap.otherRejection} rejected another way, ${swap.abstained} abstained, ${swap.accepted} wrongly accepted${if (swap.missed.isEmpty()) "" else " (${swap.missed.joinToString(", ")})"}.")
        lines.add("- Words with no fricative, judged as if they started with S (a stop burst must not pass as a hiss): ${omission.second} of ${omission.first} wrongly accepted${if (omission.third.isEmpty()) "" else " (${omission.third.joinToString(", ")})"}.")
        lines.add("- Words without R that still produced an event: ${events.second.size} of ${events.first} with a false tap${if (events.second.isEmpty()) "" else " (${events.second.joinToString(", ")})"}, ${events.third.size} with a false trill${if (events.third.isEmpty()) "" else " (${events.third.joinToString(", ")})"}. A substituted R can pass on such an event.")
        lines.add("")
        lines.add("## Calibration and priors")
        lines.add("")
        lines.add("Fitted from 5 S and 5 Ș tokens of this voice (${calibrated.third.joinToString(", ")}): boundary at ${format(calibrated.first.centroidHz, 0)} Hz centroid, ${format(calibrated.first.peakHz, 0)} Hz peak, ${format(calibrated.first.bandRatioDb, 1)} dB band ratio. Judged on the other S and Ș words:")
        lines.add("")
        lines.add(EvalRow.header)
        lines.add(calibrated.second.line)
        lines.add("")
        lines.add("The same S and Ș words under each group prior, none calibrated. This shows what a wrong voice group costs an adult female voice:")
        lines.add("")
        lines.add(EvalRow.header)
        lines.addAll(priorRows.map { it.second.line })
        lines.add("")
        lines.add("## At 16 kHz")
        lines.add("")
        lines.add("The same S and Ș words decimated to 16 kHz. The research warns that a 48 kHz boundary gives wrong answers there, so the judge must abstain:")
        lines.add("")
        lines.add(EvalRow.header)
        lines.add(decimated.line)
        lines.add("")
        lines.add("## Other checks")
        lines.add("")
        lines.add(qualityLine())
        lines.add(syllableLine())
        lines.add("- Live path, median `sNess` per clip on the correct side of 0.5: ${live.first.first} of ${live.first.second} S words, ${live.second.first} of ${live.second.second} Ș words.")
        lines.add("- `analyzeClip`: ${format(analysisSeconds / maxOf(1, summaries.size).toDouble() * 1000, 1)} ms per clip on average, JVM, this machine.")
        lines.add("")
        lines.add("## Not measured here")
        lines.add("")
        lines.add("- Real errors: lateral, interdental or substituted sounds by real talkers. The lateral hint has only been run on synthetic noise.")
        lines.add("- Trills on real speech: `rrrrrr` is the only clip that asks for one, and it produced ${summaries["rrrrrr"]?.taps?.size ?: 0} tap(s) and ${summaries["rrrrrr"]?.trills?.size ?: 0} trill(s), so the trill path has no confirmed real example here.")
        lines.add("- F0 on real speech: these clips have no reference track, so YIN's accuracy was only checked on synthetic signals.")
        lines.add("- Any device: phone microphones, the live hop time on a phone, and children's voices.")
        lines.add("")
        return lines.joinToString("\n")
    }
}
