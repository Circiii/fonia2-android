package tech.fonia.features.check

import tech.fonia.domain.FoniaContent
import tech.fonia.domain.SoundID
import tech.fonia.domain.WordItem
import tech.fonia.store.Attempt
import java.time.Instant

/**
 * „Verificare rapidă”: one or two short words per sound, heard by the judge, grouped into three calm groups. It is an
 * orientation for the learner himself, never an assessment: no percentages, no named errors, nothing red.
 */
enum class CheckVerdict(val rawValue: String) {
    /** „Ies bine”. */
    Good("good"),

    /** „De exersat”. */
    Practice("practice"),

    /** „Mai ascultăm o dată”: the judge was unsure, or heard nothing. */
    Unsure("unsure"),
    ;

    companion object {
        fun fromRawValue(raw: String?): CheckVerdict? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** One take of the check: the sound it was for, what the session decided, and how sure the judge was (0..1). */
data class CheckTake(val sound: SoundID, val outcome: Attempt.Outcome, val certainty: Float? = null)

/** What the check says about one sound. [confidence] is the mean certainty of the judged takes (0 when none was). */
data class SoundCheck(val verdict: CheckVerdict, val confidence: Float)

/** One finished check, as stored. */
data class CheckRecord(val date: Instant, val results: Map<SoundID, SoundCheck>) {
    fun sounds(verdict: CheckVerdict): List<SoundID> =
        QuickCheckRules.logopedicOrder.filter { results[it]?.verdict == verdict }
}

/** The pure rules of the check: which words, how takes become groups, and the plan that follows. */
object QuickCheckRules {
    /** A single take decides only when the judge was at least this sure; else the sound is heard again later. */
    const val SURE = 0.55f

    /** Certainty assumed for a take decided by a judge that gives none (his older checks). */
    const val DEFAULT_CERTAINTY = 0.7f

    const val WORDS_PER_SOUND = 2

    const val MAX_WORDS = 18

    /**
     * The usual order a speech therapist works in: the front hissing sounds first (S, Z, Ț), then the hushing ones
     * (Ș, J, CE, GE), then L and R, which come latest in children.
     */
    val logopedicOrder: List<SoundID> =
        listOf(SoundID.S, SoundID.Z, SoundID.Ts, SoundID.Sh, SoundID.J, SoundID.Ce, SoundID.Ge, SoundID.L, SoundID.R)

    /**
     * Up to [perSound] words per sound, in the logopedic order: the sound at the start, with a picture and a recorded
     * clip. Clean words first (no other target sound, no consonant cluster), shortest first; then words with one other
     * target sound and no cluster, if a sound has too few clean ones.
     */
    fun words(content: FoniaContent, hasClip: (String) -> Boolean, perSound: Int = WORDS_PER_SOUND): List<Pair<SoundID, WordItem>> {
        val picked = mutableListOf<Pair<SoundID, WordItem>>()
        for (id in logopedicOrder) {
            val sound = content.sounds.firstOrNull { it.id == id } ?: continue
            val usable = sound.words.initial.filter { word ->
                word.picture != null && word.recorded && hasClip(word.text) && word.clusters.isEmpty() &&
                    sound.graphemes.any { word.text.lowercase().startsWith(it.lowercase()) }
            }
            val clean = usable.filter { it.otherSounds.isEmpty() }.sortedBy { it.text.length }
            val nearly = usable.filter { it.otherSounds.size == 1 }.sortedBy { it.text.length }
            (clean + nearly).distinctBy { it.text }.take(perSound).forEach { picked += id to it }
        }
        return picked.take(MAX_WORDS)
    }

    /** How sure a take was that its verdict is right, from the judge's probability of what it decided. */
    private fun certainty(take: CheckTake): Float = take.certainty ?: DEFAULT_CERTAINTY

    /**
     * Groups the takes of each sound:
     * - no judged take (silence, retakes, unsure) → [CheckVerdict.Unsure];
     * - only good takes → Good, but a single good take needs [SURE];
     * - only near misses → Practice, but a single miss needs [SURE];
     * - good and missed takes mixed → Practice (a sound that comes and goes is worth practising).
     */
    fun group(takes: List<CheckTake>, sounds: List<SoundID>): Map<SoundID, SoundCheck> = sounds.associateWith { id ->
        val judged = takes.filter { it.sound == id && it.outcome.isJudged }
        if (judged.isEmpty()) return@associateWith SoundCheck(CheckVerdict.Unsure, 0f)
        val goods = judged.filter { it.outcome == Attempt.Outcome.Good }
        val misses = judged - goods.toSet()
        val confidence = judged.map(::certainty).average().toFloat()
        val verdict = when {
            misses.isEmpty() && goods.size >= 2 -> CheckVerdict.Good
            misses.isEmpty() -> if (certainty(goods[0]) >= SURE) CheckVerdict.Good else CheckVerdict.Unsure
            goods.isEmpty() && misses.size >= 2 -> CheckVerdict.Practice
            goods.isEmpty() -> if (certainty(misses[0]) >= SURE) CheckVerdict.Practice else CheckVerdict.Unsure
            else -> CheckVerdict.Practice
        }
        SoundCheck(verdict, confidence)
    }

    /**
     * The sounds to work on, one at a time, in the logopedic order: the ones to practise; if there are none, the ones
     * still to hear again; if neither, [current] stays as it was. A sound he [chose] goes first when it is in the plan.
     */
    fun plan(record: CheckRecord, chose: SoundID? = null, current: List<SoundID> = emptyList()): List<SoundID> {
        val practice = record.sounds(CheckVerdict.Practice)
        val base = practice.ifEmpty { record.sounds(CheckVerdict.Unsure) }.ifEmpty { return current }
        if (chose == null || chose !in base) return base
        return listOf(chose) + (base - chose)
    }

    /** „Atunci și acum”: the sounds that were not „Ies bine” in [before] and are now. */
    fun movedToGood(before: CheckRecord?, now: CheckRecord): List<SoundID> {
        if (before == null) return emptyList()
        return now.sounds(CheckVerdict.Good).filter { before.results[it]?.verdict != CheckVerdict.Good && before.results[it] != null }
    }

    private val Attempt.Outcome.isJudged: Boolean
        get() = this == Attempt.Outcome.Good || this == Attempt.Outcome.Almost || this == Attempt.Outcome.NotYet
}
