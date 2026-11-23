package tech.fonia.phones

import tech.fonia.domain.FoniaContent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Trials for the evaluation on the recorded content clips (one adult ElevenLabs voice, taken as correct).
 *
 * - [Kind.Correct]: each clip against its own text, for every practice sound in it. Measures false alarms.
 * - [Kind.ContentPair]: the minimal pairs `sounds.json` teaches whose two words both have clips (lac/rac, cal/car…):
 *   each clip judged against the other word, i.e. a speaker who said „rac” when asked for „lac”.
 * - [Kind.AnyPair]: every pair of clips (words and syllable drills) whose pronunciations differ only by a target
 *   becoming one of its alternatives, found through the G2P (sa/șa, ța/cea, ra/la, asa/aza…).
 * - [Kind.Simulated]: every clip judged against its own word with one target sound replaced by a sound that lists
 *   the produced one as an alternative („sanie” heard against „șanie”), the "simulated errors" protocol of
 *   Cao et al. (CMU Kids, their tab. I). Needs no second clip, so it covers every sound.
 * - [Kind.OtherWord]: a word clip against a different word with the same sound: should not be called correct.
 */
enum class Kind { Correct, ContentPair, AnyPair, Simulated, OtherWord }

data class Trial(
    val kind: Kind,
    /** Text of the clip whose audio is judged. */
    val clip: String,
    /** What the scorer is told the child was asked to say. */
    val intended: Pronunciation,
    val sound: TargetSound,
    /** Id of the alternative the clip actually holds at the target ([Alternative.id]); null when it is correct. */
    val truth: String?,
    /** The clip is a syllable drill (ra, asa…), not a word. */
    val drill: Boolean,
) {
    val mispronounced: Boolean get() = truth != null
}

data class Outcome(val trial: Trial, val score: WordScore) {
    /** The decisive LPR: the lowest over the occurrences of the sound (the word's verdict follows it). */
    val lpr: Double get() = score.targets.minOfOrNull { it.lpr } ?: Double.NaN
}

object TrialBuilder {
    private val sustained = Regex("""^(.)\1{3,}$""")

    /** Single-token Romanian clips: no spaces, hyphens or punctuation, and not a held sound (ssssss). */
    fun clipTexts(clips: VoiceClips): List<String> =
        clips.romanianTexts.filter { text -> text.all { it.isLetter() } && !sustained.matches(text) }

    fun drills(content: FoniaContent): Set<String> = content.sounds.flatMap { s ->
        s.syllables.direct + s.syllables.inverse + s.syllables.between
    }.toSet()

    fun build(content: FoniaContent, texts: List<String>): List<Trial> {
        val drills = drills(content)
        val pron = texts.associateWith { RomanianG2P.pronounce(it) }
        val byLabels = texts.groupBy { pron.getValue(it).labels }
        val trials = ArrayList<Trial>()

        for (text in texts) {
            for (sound in pron.getValue(text).sounds) trials += Trial(Kind.Correct, text, pron.getValue(text), sound, null, text in drills)
        }

        // Pairs of clips one substitution apart. The content's taught pairs are kept apart as their own set.
        val taught = content.sounds.flatMap { s -> s.contrasts.flatMap { c -> c.pairs.filter { !it.isSuggested }.map { setOf(it.a.text, it.b.text) } } }.toSet()
        for (text in texts) {
            val intended = pron.getValue(text)
            for ((index, phone) in intended.phones.withIndex()) {
                val sound = phone.sound ?: continue
                for (alternative in Alternatives.of(sound)) {
                    for (variant in alternative.variants) {
                        val heard = intended.replacing(index, variant, alternative.sound)
                        for (other in byLabels[heard.labels].orEmpty()) {
                            if (other == text) continue
                            val kind = if (setOf(text, other) in taught) Kind.ContentPair else Kind.AnyPair
                            trials += Trial(kind, other, intended, sound, alternative.id, other in drills)
                        }
                    }
                }
            }
        }

        // Simulated substitutions: the clip's own phone becomes an alternative of another target. Besides the practice
        // sounds, the consonants and glides that are alternatives themselves count ([t] for Ț and CE, [j] for R and
        // L, [w] for L); vowels do not, so no consonant is ever replaced by a vowel nucleus.
        val alsoAlternatives = setOf(listOf("t"), listOf("j"), listOf("w"))
        for (text in texts) {
            val said = pron.getValue(text)
            for ((index, phone) in said.phones.withIndex()) {
                if (phone.sound == null && phone.tokens !in alsoAlternatives) continue
                val produced = phone.sound
                for (target in TargetSound.entries) {
                    if (target == produced) continue
                    val alternative = Alternatives.of(target).firstOrNull { alt -> alt.variants.any { it == phone.tokens } } ?: continue
                    val intended = said.replacing(index, target.canonical, target)
                    trials += Trial(Kind.Simulated, text, intended, target, alternative.id, text in drills)
                }
            }
        }

        // Another word with the same sound, at least three phones away.
        val words = texts.filter { it !in drills }.sorted()
        for (text in words) {
            val said = pron.getValue(text)
            for (sound in said.sounds) {
                val others = words.filter { it != text && sound in pron.getValue(it).sounds && distance(pron.getValue(it), said) >= 3 }
                if (others.isEmpty()) continue
                val other = others[Math.floorMod(text.hashCode() * 31 + sound.ordinal, others.size)]
                trials += Trial(Kind.OtherWord, text, pron.getValue(other), sound, "another word", false)
            }
        }
        return trials.distinct()
    }

    /** Edit distance between phone sequences. */
    fun distance(a: Pronunciation, b: Pronunciation): Int {
        val x = a.phones.map { it.tokens }
        val y = b.phones.map { it.tokens }
        var previous = IntArray(y.size + 1) { it }
        for (i in 1..x.size) {
            val current = IntArray(y.size + 1)
            current[0] = i
            for (j in 1..y.size) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1)
            }
            previous = current
        }
        return previous[y.size]
    }
}

object Metrics {
    /**
     * Area under the ROC curve of [positives] against [negatives] (higher score = positive), ties counted half:
     * the probability that a random mispronounced trial scores above a random correct one (Mann–Whitney U).
     */
    fun auc(positives: List<Double>, negatives: List<Double>): Double {
        if (positives.isEmpty() || negatives.isEmpty()) return Double.NaN
        val all = (positives.map { it to true } + negatives.map { it to false }).sortedBy { it.first }
        var rankSum = 0.0
        var i = 0
        while (i < all.size) {
            var j = i
            while (j + 1 < all.size && all[j + 1].first == all[i].first) j++
            val rank = (i + j) / 2.0 + 1.0
            for (k in i..j) if (all[k].second) rankSum += rank
            i = j + 1
        }
        val n = positives.size.toDouble()
        return (rankSum - n * (n + 1) / 2) / (n * negatives.size)
    }

    /** AUC with a 95 % percentile interval from resampling clips (all trials of a clip move together). */
    fun aucWithInterval(positives: List<Outcome>, negatives: List<Outcome>, rounds: Int = 1000, seed: Int = 7): Triple<Double, Double, Double> {
        val score = { o: Outcome -> -o.lpr }
        val point = auc(positives.map(score), negatives.map(score))
        val clips = (positives + negatives).map { it.trial.clip }.distinct()
        val posBy = positives.groupBy { it.trial.clip }
        val negBy = negatives.groupBy { it.trial.clip }
        val random = Random(seed)
        val values = ArrayList<Double>()
        repeat(rounds) {
            val pos = ArrayList<Double>()
            val neg = ArrayList<Double>()
            repeat(clips.size) {
                val clip = clips[random.nextInt(clips.size)]
                posBy[clip]?.forEach { pos += score(it) }
                negBy[clip]?.forEach { neg += score(it) }
            }
            val value = auc(pos, neg)
            if (!value.isNaN()) values += value
        }
        values.sort()
        return Triple(point, values[(0.025 * values.size).toInt()], values[minOf(values.size - 1, (0.975 * values.size).toInt())])
    }

    fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        return sorted[minOf(sorted.size - 1, maxOf(0, Math.ceil(p / 100.0 * sorted.size).toInt() - 1))]
    }
}

/**
 * The clip as the app would hand it over: the word inside a VAD segment with 250 ms of room noise on each side.
 * Several generated clips start with the word already at full level (the generator trimmed the silence), which the
 * model reads as a stop burst; the 10 ms fade-in removes that click and the noise floor (−75 dBFS, white, seeded by
 * the text) replaces the digital silence no microphone gives.
 */
fun padded(samples: FloatArray, seed: Int): FloatArray {
    val margin = 4000
    val fade = 160
    val random = Random(seed)
    val noise = 10.0.pow(-75.0 / 20.0)
    val out = FloatArray(samples.size + 2 * margin)
    for (i in out.indices) out[i] = (random.nextGaussian() * noise).toFloat()
    for (i in samples.indices) {
        val gain = when {
            i < fade -> 0.5 - 0.5 * cos(PI * i / fade)
            i >= samples.size - fade -> 0.5 - 0.5 * cos(PI * (samples.size - 1 - i) / fade)
            else -> 1.0
        }
        out[margin + i] += (samples[i] * gain).toFloat()
    }
    return out
}

private fun Random.nextGaussian(): Double {
    // Box–Muller; deterministic for a given seed, unlike java.util.Random's cached second value across calls.
    val u = nextDouble().coerceAtLeast(1e-300)
    val v = nextDouble()
    return sqrt(-2.0 * kotlin.math.ln(u)) * cos(2.0 * PI * v)
}
