package tech.fonia.app.lesson

import tech.fonia.app.data.SoundPractice
import tech.fonia.app.house.Exercise
import tech.fonia.app.house.ExerciseLocks
import tech.fonia.lessons.LessonBuilder
import tech.fonia.lessons.LessonContent
import tech.fonia.lessons.LessonPlan
import tech.fonia.lessons.LessonRequest
import tech.fonia.lessons.PlanItem
import tech.fonia.lessons.PlanStep
import tech.fonia.lessons.StepKind
import tech.fonia.model.Rung
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict

/** Durations and counts of the lesson (data; versioned with every attempt). */
data class LessonTuning(
    /** Hold goal when the plan gives none (burst sounds use bursts instead). */
    val holdGoalSeconds: Float = 1.5f,
    /** End of take = end of voice + this (§6.2); longer where the child pauses between parts (bursts, trains, sentences). */
    val endTailMillis: Long = 150,
    val endTailLongMillis: Long = 650,
    /** No new frame for this long after the voice started: the source ended (fixture) or stalled. */
    val stallMillis: Long = 400,
    /** Voice in the first moments after the mic opens is the stream settling (onset click, room floor), not the child. */
    val settleMillis: Long = 1100,
    /** Silence: the capsule pulses at 4 s, the mic closes at 8 s (§6.2). */
    val quietPulseMillis: Long = 4000,
    val quietCloseMillis: Long = 8000,
    /** "Ascultă": the letter shows the model before the mic opens (when there is no clip). */
    val modelMillis: Long = 900,
    /** "Mă gândesc" shown at least this long, so the freeze reads as attention. */
    val minThinkingMillis: Long = 350,
    /** How long a verdict stays before the next item (0.8–1.5 s, §6.2). */
    val verdictMillis: Long = 1300,
    val instructMillis: Long = 2400,
    /** The adult's window after Unsure (§6.2: 6 s), and for the adult-judged steps (twister, story). */
    val unsureAdultMillis: Long = 6000,
    /** A retake does not count; after this many in a row the item moves on (never a loop). */
    val maxRetakes: Int = 2,
    val version: String = "lesson-0.2",
)

/** How the lesson plays one item. */
enum class ItemMode {
    /** Listen: Foni says it, the child taps "bine" or "încurcat" (no microphone). */
    Choice,
    /** Warm-up: do it with Foni, „Am făcut” (no microphone). */
    Together,
    /** Microphone + the judge. */
    Mic,
    /** Microphone for the letter's reaction, the adult decides (twister, story: chapter 06 §1 "Cine judecă"). */
    MicAdult,
}

/** The pure rules of the lesson: what each step and item asks, how long a take may run, what the judge is told. */
object PlanRules {
    fun mode(step: StepKind, item: PlanItem): ItemMode = when {
        step == StepKind.Listen || item.type == "listen" -> ItemMode.Choice
        step == StepKind.Warmup || item.type == "warmup" -> ItemMode.Together
        item.type == "twister" || item.type == "story" -> ItemMode.MicAdult
        else -> ItemMode.Mic
    }

    /** The rung the judge measures the take at (its `Level` and `Position` follow from it). */
    fun judgeRung(step: PlanStep, item: PlanItem): Rung = when (item.type) {
        "hold" -> Rung.Isolated
        "train", "syllable" -> if (step.rung == Rung.Back) Rung.Back else Rung.Syllables
        "word" -> step.rung?.takeIf { it in ExerciseLocks.WORD_RUNGS } ?: Rung.WordsInitial
        "pair" -> Rung.Pairs
        "sentence", "mission" -> Rung.Sentences
        "twister" -> Rung.Twister
        "story" -> Rung.Retell
        else -> step.rung ?: Rung.WordsInitial
    }

    /** The pair's word to say: the first one Foni says ("ab" → a). */
    fun pairTarget(item: PlanItem, content: LessonContent?): Pair<String, String>? {
        val c = content?.item(item.id) ?: return null
        val a = c.a ?: return null
        val b = c.b ?: return null
        return if (item.variant == "ba") b to a else a to b
    }

    /** What the judge is told the child says. */
    fun judgeText(item: PlanItem, sound: SoundId, content: LessonContent?): String = when (item.type) {
        "hold" -> sound.letter
        "train" -> item.syllables.firstOrNull() ?: item.text
        "pair" -> pairTarget(item, content)?.first ?: item.text
        else -> item.text
    }

    /** Max length of a take after the voice started (chapter 01 §6.3). */
    fun maxSeconds(item: PlanItem, holdGoal: Float): Float = when (item.type) {
        "hold" -> if (item.variant == "burst") 5f else holdGoal + 2f
        "train" -> 6f
        "word", "pair" -> 4f
        "sentence", "mission" -> 8f
        "twister" -> 15f
        "story" -> 45f
        else -> 6f
    }

    /** Parts with pauses between them get a longer tail before the take ends. */
    fun longTail(item: PlanItem): Boolean = item.type in setOf("train", "sentence", "mission", "twister", "story") || item.variant == "burst"

    /**
     * Where the sound's letters are in a word (for `LiveWord`): S → s, Ș → ș, CE → c before e/i, GE → g before e/i…
     */
    fun targets(word: String, sound: SoundId): List<IntRange> {
        val w = word.lowercase()
        val out = mutableListOf<IntRange>()
        var i = 0
        while (i < w.length) {
            val ch = w[i]
            val next = w.getOrNull(i + 1)
            val hit = when (sound) {
                SoundId.S -> ch == 's'
                SoundId.Sh -> ch == 'ș'
                SoundId.Z -> ch == 'z'
                SoundId.J -> ch == 'j'
                SoundId.Ts -> ch == 'ț'
                SoundId.Ce -> ch == 'c' && (next == 'e' || next == 'i')
                SoundId.Ge -> ch == 'g' && (next == 'e' || next == 'i')
                SoundId.R -> ch == 'r'
                SoundId.L -> ch == 'l'
            }
            if (hit) {
                val two = sound == SoundId.Ce || sound == SoundId.Ge
                out += if (two) i..(i + 1) else i..i
                i += if (two) 2 else 1
            } else {
                i++
            }
        }
        return out
    }
}

/**
 * The takes of one item (pure): a retake never counts (after [maxRetakes] in a row the item moves on), Correct moves on,
 * anything else uses up one of the item's takes; the same instruction twice moves on too (§6.4).
 */
class ItemTakes(private val takes: Int, private val maxRetakes: Int = 2) {
    private var counted = 0
    private var retakes = 0
    private var lastInstruction: Verdict? = null

    sealed interface Next {
        data object Again : Next
        data class Done(val success: Boolean, val counted: Boolean) : Next
    }

    fun onVerdict(v: Verdict): Next {
        if (v == Verdict.Retake) {
            retakes++
            return if (retakes > maxRetakes) Next.Done(success = false, counted = counted > 0) else Next.Again
        }
        retakes = 0
        counted++
        if (v == Verdict.Correct) return Next.Done(success = true, counted = true)
        if (v is Verdict.NamedError) {
            if (v == lastInstruction) return Next.Done(success = false, counted = true)
            lastInstruction = v
        }
        return if (counted >= takes.coerceAtLeast(1)) Next.Done(success = false, counted = true) else Next.Again
    }

    /** „Sărim” after two silences: skipped, not counted. */
    fun skip(): Next = Next.Done(success = false, counted = false)
}

/** Builds the plan the app plays (pure, deterministic: LessonBuilder's SplitMix64 seed). */
object PlanMaker {
    /** One seed per install would be nicer; a fixed one keeps the plans reproducible and shared with the vectors. */
    const val SEED: ULong = 0x5EED_F0A1uL

    /** The guided session at the sound's current rung (normal 4 minutes, or „Încă una?” ~90 s). */
    fun guided(content: LessonContent, sound: SoundId, p: SoundPractice, daysSinceLast: Int, oneMore: Boolean, demo: Boolean = false): LessonPlan {
        val plan = LessonBuilder(content).build(
            LessonRequest(
                sound = sound, rung = p.rung, seed = SEED, sessionsCompleted = p.sessions, history = p.history,
                workedSounds = setOf(sound), daysSinceLast = daysSinceLast, oneMore = oneMore,
            ),
        )
        return if (demo) shorten(plan, items = 2) else plan
    }

    /** One exercise card as one block of its rung (LessonBuilder's „Încă una?” shape), without the final; twisters get the tempo ladder. */
    fun exercise(content: LessonContent, sound: SoundId, exercise: Exercise, p: SoundPractice, demo: Boolean = false): LessonPlan? {
        val rung = ExerciseLocks.rungFor(exercise, p) ?: return null
        val built = LessonBuilder(content).build(
            LessonRequest(sound = sound, rung = rung, seed = SEED, sessionsCompleted = p.sessions + (p.plays[exercise] ?: 0),
                history = p.history, workedSounds = setOf(sound), oneMore = true),
        )
        var steps = built.steps.filter { it.kind != StepKind.Final }
        if (exercise == Exercise.Twister) {
            // the tempo ladder: the same twister "ca melcul", then "ca iepurașul"
            steps = steps.flatMap { s ->
                if (s.kind != StepKind.Twister) listOf(s) else listOf(
                    s.copy(items = s.items.map { it.copy(variant = "slow", takes = 1) }, lineKeys = listOf("step.twister.slow")),
                    s.copy(block = 3, items = s.items.map { it.copy(variant = "step", takes = 1, model = false) }, lineKeys = listOf("step.twister.step")),
                )
            }
        }
        if (exercise == Exercise.Mission) steps = steps.filter { it.kind == StepKind.Mission }.map { it.copy(items = it.items.take(1)) }
        val plan = built.copy(steps = steps)
        return if (demo) shorten(plan, items = 2) else plan
    }

    /** The demo film: every step keeps at most [items] items, the Move break and the warm-ups go. */
    fun shorten(plan: LessonPlan, items: Int): LessonPlan = plan.copy(
        steps = plan.steps.filter { it.kind != StepKind.Move && it.kind != StepKind.Warmup && it.kind != StepKind.Opening }
            .map { it.copy(items = it.items.take(items)) }
            .filter { it.items.isNotEmpty() }
            .take(2),
    )
}
