package tech.fonia.lessons

import tech.fonia.model.Rung
import tech.fonia.model.SoundId

/** The three states of the hidden adaptation (chapter 06 §2.4, N11). The child always sees the same goal. */
enum class AdaptLevel(val raw: String) {
    Easier("easier"),
    Steady("steady"),
    Harder("harder"),
}

/** What the app knows about one item from earlier sessions. [box] is the spaced-repetition box (0 = just missed). */
data class ItemStat(
    val seen: Int = 0,
    val successes: Int = 0,
    val lastSeenSession: Int = -1,
    val lastSuccess: Boolean = false,
    val box: Int = 0,
) {
    val known: Boolean get() = successes > 0
}

/** Attempts and successes of one finished block, for the "above 90 % in two blocks" rule. */
data class BlockResult(val attempts: Int, val successes: Int) {
    val rate: Double get() = if (attempts == 0) 0.0 else successes.toDouble() / attempts
}

/**
 * The child's history for one sound, kept by the app (`:core:data`) and handed back into every request.
 * [lastSessionItems] are the ids of the previous session (Deck rule: unused items first).
 */
data class LessonHistory(
    val items: Map<String, ItemStat> = emptyMap(),
    val lastSessionItems: Set<String> = emptySet(),
    /** Attempts on the current sound and rung, oldest first (only the last `adaptation.window` matter). */
    val recent: List<Boolean> = emptyList(),
    val recentBlocks: List<BlockResult> = emptyList(),
)

/** Everything the builder needs. Pure data: the same request and content give the same plan, byte for byte. */
data class LessonRequest(
    val sound: SoundId,
    val rung: Rung,
    val seed: ULong,
    /** Sessions finished on this sound; it moves the seed and the spaced-repetition clock. */
    val sessionsCompleted: Int = 0,
    val history: LessonHistory = LessonHistory(),
    /** Child's age in years; items marked 6+/7+ are left out below that age. */
    val childAge: Int = 5,
    /** Ids of items that need a therapist's check and were approved. */
    val approvedItems: Set<String> = emptySet(),
    /** Sounds the child has already worked on; words with other, unworked hard sounds go to the second half. */
    val workedSounds: Set<SoundId> = emptySet(),
    /** Days since the last session on any sound; at `longPauseDays` or more the session is two short blocks. */
    val daysSinceLast: Int = 0,
    /** "Încă una?": one block of 6 from the current rung plus the final (~90 s). */
    val oneMore: Boolean = false,
    /** Forces an adaptation state (tests, the adult's "mai ușor"); null = computed from the history. */
    val adaptation: AdaptLevel? = null,
)

enum class StepKind { Opening, Listen, Warmup, Hold, Syllables, Words, Pairs, Sentence, Twister, Retell, Mission, Move, Final }

enum class PlanKind { Normal, OneMore, AfterPause }

/**
 * One production (or one choice, at Listen). [voices] are the voice keys to play as the model, in order
 * (resolve them with [VoiceIndex]; "human-needed" keys have no synthetic clip). [variant] says how to present it:
 * Listen "target"/"foil" (what Foni says), Pairs "ab"/"ba", Twister "slow"/"step", Hold "sustain"/"burst".
 */
data class PlanItem(
    val id: String,
    val type: String,
    val text: String,
    val takes: Int = 1,
    val model: Boolean = false,
    val surprise: Boolean = false,
    val countsForProgress: Boolean = true,
    val review: Boolean = false,
    val variant: String? = null,
    val syllables: List<String> = emptyList(),
    val voices: List<String> = emptyList(),
)

/**
 * One step of the 4-minute plan. [block] is 0 outside the three practice blocks. [lineKeys] are Foni's lines for
 * the step (foni_lines.json keys, or open.<slug>.<rung>, or <slug>.h.<n>.prompt). [cutAt]: when the attention
 * signals drop, the app may stop block 3 after this many items and go to the final.
 */
data class PlanStep(
    val kind: StepKind,
    val block: Int,
    val rung: Rung?,
    val startSecond: Int,
    val seconds: Int,
    val lineKeys: List<String>,
    val items: List<PlanItem> = emptyList(),
    val holdGoalSeconds: Double? = null,
    val holdMode: String? = null,
    val warmups: List<String> = emptyList(),
    val cutAt: Int? = null,
)

data class LessonPlan(
    val contentVersion: String,
    val sound: SoundId,
    val rung: Rung,
    val seed: ULong,
    val kind: PlanKind,
    val adaptation: AdaptLevel,
    val params: AdaptParams,
    val firstOnRung: Boolean,
    val steps: List<PlanStep>,
) {
    val totalSeconds: Int get() = steps.maxOfOrNull { it.startSecond + it.seconds } ?: 0

    /** Every production the child is asked for, in order. */
    val productions: List<PlanItem> get() = steps.flatMap { it.items }
}

/** What happened with one item; the app sends these back after the session. */
data class ItemResult(val itemId: String, val success: Boolean)

object Adaptation {
    /**
     * The hidden adaptation: harder after [AdaptationSpec.harderBlocks] blocks above [AdaptationSpec.harderAbove],
     * easier when the last [AdaptationSpec.window] attempts are below [AdaptationSpec.easierBelow] (at least 6
     * attempts), steady otherwise. It never changes the rung; only `Progression` does.
     */
    fun assess(history: LessonHistory, spec: AdaptationSpec): AdaptLevel {
        val blocks = history.recentBlocks.takeLast(spec.harderBlocks)
        if (blocks.size == spec.harderBlocks && blocks.all { it.attempts > 0 && it.rate > spec.harderAbove }) {
            return AdaptLevel.Harder
        }
        val window = history.recent.takeLast(spec.window)
        if (window.size >= 6 && window.count { it }.toDouble() / window.size < spec.easierBelow) return AdaptLevel.Easier
        return AdaptLevel.Steady
    }
}

/**
 * Folds the results of a played plan into the history (pure). Successes move an item up a spaced-repetition box,
 * a miss puts it back in box 0 so it returns next session; [LessonHistory.recent] and the block results grow.
 */
fun LessonHistory.record(plan: LessonPlan, results: List<ItemResult>, sessionIndex: Int, maxBox: Int = 4): LessonHistory {
    val stats = items.toMutableMap()
    for (result in results) {
        val old = stats[result.itemId] ?: ItemStat()
        stats[result.itemId] = ItemStat(
            seen = old.seen + 1,
            successes = old.successes + if (result.success) 1 else 0,
            lastSeenSession = sessionIndex,
            lastSuccess = result.success,
            box = if (result.success) minOf(old.box + 1, maxBox) else 0,
        )
    }
    val counted = plan.steps.filter { it.block in 2..3 }.associate { step ->
        step.block to step.items.filter { it.countsForProgress }.map { it.id }.toSet()
    }
    val blocks = counted.toSortedMap().values.map { ids ->
        val mine = results.filter { it.itemId in ids }
        BlockResult(mine.size, mine.count { it.success })
    }.filter { it.attempts > 0 }
    return LessonHistory(
        items = stats,
        lastSessionItems = plan.productions.map { it.id }.toSet(),
        recent = (recent + results.map { it.success }).takeLast(50),
        recentBlocks = (recentBlocks + blocks).takeLast(10),
    )
}
