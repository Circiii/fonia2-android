package tech.fonia.domain.adaptive

import tech.fonia.domain.Rung
import tech.fonia.domain.StringRawValue
import tech.fonia.domain.byRawValue

/** One block's takes with a verdict (adult, else phone), retries left out, as `SessionRun` counts them for a block. */
data class BlockTally(val good: Int, val total: Int) {
    val rate: Double get() = if (total <= 0) 0.0 else good.toDouble() / total

    operator fun plus(other: BlockTally) = BlockTally(good + other.good, total + other.total)
}

/**
 * How hard a block is: the rung whose material it practises (syllable → word → sentence, and the positions in
 * between), and whether the items are said fast (T2: "silabă → cuvânt → propoziție, altă poziție, ritm").
 */
data class Step(val rung: Rung, val fast: Boolean = false) {
    /** `wordsInitial`, `wordsInitial+fast`: the step as the log and the export write it. */
    val code: String get() = if (fast) "${rung.rawValue}+fast" else rung.rawValue
}

/** Where the controller stands for one sound. */
data class ChallengeState(
    /** The rung reached on the path: the steps never go above it, since the rungs above wait for mastery (T1). */
    val ceiling: Rung,
    val step: Step = Step(ceiling),
    /** Blocks at this step since the last move, oldest first (at most [ChallengePoint.HARDER_AFTER_BLOCKS] kept). */
    val sinceMove: List<BlockTally> = emptyList(),
    /** The last blocks at any step, oldest first: what the feedback fades on (T3). */
    val recent: List<BlockTally> = emptyList(),
    /** Knowledge of performance on every take, after a block under half (T2), until a block is back in the band. */
    val kp: Boolean = false,
) {
    /** The success the next block's feedback is set from: the last two blocks together, or null before any. */
    val recentSuccess: Double? get() = recent.takeIf { it.isNotEmpty() }?.reduce(BlockTally::plus)?.rate
}

enum class Move(override val rawValue: String) : StringRawValue {
    Harder("harder"),
    Easier("easier"),
    Stay("stay"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): Move? = entries.byRawValue(rawValue)
    }
}

/**
 * Challenge Point (Guadagnoli & Lee 2004, G §6.2 [79]; T2 in 03 §1.6; G §6.4 item 3): learning is best when
 * practice is hard enough that it does not reach 100 %, so the next block's difficulty follows the last ones. The target
 * is 60–80 % success per block; over 85 % in 2 blocks makes it harder, under 50 % makes it one step easier and turns
 * on knowledge of performance. The thresholds are [S], to be set with the logopeds.
 */
object ChallengePoint {
    /** T2: the target success per block is 60–80 % ... */
    const val TARGET_LOW = 0.60
    const val TARGET_HIGH = 0.80

    /** ... over 85 % ... */
    const val HARDER_ABOVE = 0.85

    /**
     * ... in 2 blocks makes the next block harder. The two blocks are counted together (11 of 12 takes): on blocks of
     * six, "each over 85 %" would mean twelve good takes in a row. [inf]
     */
    const val HARDER_AFTER_BLOCKS = 2

    /** Under 50 % in a block: one step easier, and knowledge of performance. */
    const val EASIER_BELOW = 0.50

    /**
     * A block decides only when at least this many of its six takes got a verdict: fewer (a noisy room, takes nobody
     * could judge) say too little. iOS lets only full blocks move a rung (`Progression.afterBlock`). [inf]
     */
    const val MIN_JUDGED_TAKES = 4

    enum class Reason(override val rawValue: String) : StringRawValue {
        /** Under [EASIER_BELOW]. */
        BelowHalf("below50"),

        /** Over [HARDER_ABOVE] in the last [HARDER_AFTER_BLOCKS] blocks. */
        AboveTwice("above85in2"),

        /** Between the two: the block stays at the same difficulty. */
        Held("held"),

        /** Too few takes with a verdict: the block decides nothing. */
        TooFewTakes("tooFewTakes"),

        /** Harder was due, but this is the hardest step under the ceiling. */
        AtTop("atTop"),

        /** Easier was due, but this is the easiest step. */
        AtBottom("atBottom"),
        ;

        companion object {
            fun fromRawValue(rawValue: String): Reason? = entries.byRawValue(rawValue)
        }
    }

    /** What the controller did after a block. */
    data class Outcome(val state: ChallengeState, val move: Move, val reason: Reason, val block: BlockTally)

    /**
     * The steps under [ceiling], easiest first: every rung up to it, then the ceiling said fast when [fastAllowed]
     * (an age whose prosodies include fast, and a rung whose items take a prosody).
     */
    fun steps(ceiling: Rung, fastAllowed: Boolean): List<Step> =
        Rung.entries.filter { it <= ceiling }.map { Step(it) } + if (fastAllowed) listOf(Step(ceiling, fast = true)) else emptyList()

    /** The controller at the start of a session on [ceiling]: where it stood, if it stood under this ceiling. */
    fun resume(saved: ChallengeState?, ceiling: Rung, fastAllowed: Boolean): ChallengeState {
        if (saved == null || saved.ceiling != ceiling) return ChallengeState(ceiling)
        val steps = steps(ceiling, fastAllowed)
        return if (saved.step in steps) saved else saved.copy(step = Step(saved.step.rung), sinceMove = emptyList())
    }

    /** The decision itself, before the ends of the ladder are applied. */
    fun decide(sinceMove: List<BlockTally>, block: BlockTally): Pair<Move, Reason> {
        if (block.total < MIN_JUDGED_TAKES) return Move.Stay to Reason.TooFewTakes
        if (block.rate < EASIER_BELOW) return Move.Easier to Reason.BelowHalf
        val window = (sinceMove + block).takeLast(HARDER_AFTER_BLOCKS)
        if (window.size == HARDER_AFTER_BLOCKS && window.reduce(BlockTally::plus).rate > HARDER_ABOVE) return Move.Harder to Reason.AboveTwice
        return Move.Stay to Reason.Held
    }

    /** One block ended at [state]'s step: the step of the next block. */
    fun afterBlock(state: ChallengeState, block: BlockTally, fastAllowed: Boolean): Outcome {
        val (wanted, reason) = decide(state.sinceMove, block)
        if (reason == Reason.TooFewTakes) return Outcome(state, Move.Stay, reason, block)
        val steps = steps(state.ceiling, fastAllowed)
        val at = steps.indexOf(state.step).let { if (it < 0) steps.indexOfFirst { step -> step.rung == state.step.rung } else it }
        val recent = (state.recent + block).takeLast(HARDER_AFTER_BLOCKS)
        // Knowledge of performance stays on from a block under half until a block reaches the target band again.
        val kp = when {
            block.rate < EASIER_BELOW -> true
            block.rate >= TARGET_LOW -> false
            else -> state.kp
        }
        return when (wanted) {
            Move.Easier -> if (at <= 0) {
                Outcome(state.copy(sinceMove = emptyList(), recent = recent, kp = kp), Move.Stay, Reason.AtBottom, block)
            } else {
                Outcome(state.copy(step = steps[at - 1], sinceMove = emptyList(), recent = recent, kp = kp), Move.Easier, reason, block)
            }
            Move.Harder -> if (at >= steps.lastIndex) {
                Outcome(state.copy(sinceMove = (state.sinceMove + block).takeLast(HARDER_AFTER_BLOCKS), recent = recent, kp = kp), Move.Stay, Reason.AtTop, block)
            } else {
                Outcome(state.copy(step = steps[at + 1], sinceMove = emptyList(), recent = recent, kp = kp), Move.Harder, reason, block)
            }
            Move.Stay -> Outcome(state.copy(sinceMove = (state.sinceMove + block).takeLast(HARDER_AFTER_BLOCKS), recent = recent, kp = kp), Move.Stay, reason, block)
        }
    }
}
