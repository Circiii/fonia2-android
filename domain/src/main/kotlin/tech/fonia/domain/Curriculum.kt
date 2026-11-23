package tech.fonia.domain

/**
 * The ladder for one sound, easiest first. Differentiation comes before sentences. Kotlin compares enum entries by
 * declaration order, which is Swift's `allCases` index order.
 */
enum class Rung(override val rawValue: String) : StringRawValue {
    Isolated("isolated"),
    Syllables("syllables"),
    WordsInitial("wordsInitial"),
    WordsMedial("wordsMedial"),
    WordsFinal("wordsFinal"),
    Clusters("clusters"),
    Pairs("pairs"),
    Sentences("sentences"),
    Retell("retell"),
    Everyday("everyday"),
    ;

    val next: Rung? get() = entries.getOrNull(ordinal + 1)

    val previous: Rung? get() = if (ordinal > 0) entries[ordinal - 1] else null

    val stage: Stage
        get() = when (this) {
            Isolated -> Stage.Establish
            Syllables, WordsInitial, WordsMedial, WordsFinal -> Stage.ConsolidateEarly
            Clusters -> Stage.ConsolidateLate
            Pairs -> Stage.Differentiate
            Sentences, Retell -> Stage.Automate
            Everyday -> Stage.Generalise
        }

    companion object {
        fun fromRawValue(rawValue: String): Rung? = entries.byRawValue(rawValue)
    }
}

enum class Track(override val rawValue: String) : StringRawValue {
    Sounds("sounds"),
    English("english"),
    Diction("diction"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): Track? = entries.byRawValue(rawValue)
    }
}

/** Therapy stage. Each one sets how much the learner is told in the moment. */
enum class Stage(override val rawValue: String) : StringRawValue {
    /** Impostare: isolated sound, then four syllable variants, with an adult present. */
    Establish("establish"),

    /** Consolidare, early: syllables and simple words. */
    ConsolidateEarly("consolidateEarly"),

    /** Consolidare, late: clusters and long words. */
    ConsolidateLate("consolidateLate"),

    /** Diferențiere: true minimal pairs, perception then production. */
    Differentiate("differentiate"),

    /** Automatizare: sentences, retelling and description, with the live meter hidden. */
    Automate("automate"),

    /** Real-life tasks, watched by a parent. */
    Generalise("generalise"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): Stage? = entries.byRawValue(rawValue)
    }
}

enum class FeedbackLevel(override val rawValue: String) : StringRawValue {
    /** Live visual plus a cue. */
    Teach("teach"),

    /** Under a second, no tap needed. */
    Quick("quick"),

    /** The learner judges first. */
    SelfJudge("selfJudge"),

    /** Nothing in the moment. The block ends with a summary. */
    Silent("silent"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): FeedbackLevel? = entries.byRawValue(rawValue)
    }
}

object FeedbackSchedule {
    /**
     * One feedback level per trial for a block of six.
     *
     * | Stage | Trials with a response | Kind |
     * |---|---|---|
     * | establish | 5 of 6 | teach |
     * | consolidateEarly | 5, then 4 of 6 | teach on one block, quick on the next |
     * | consolidateLate | 4, then 3 of 6 | three self-judged, the rest quick |
     * | differentiate | 3 of 6 | quick, with a contrast replay when wrong |
     * | automate, generalise | none | block summary |
     */
    fun levels(stage: Stage, blockIndex: Int, seed: ULong): List<FeedbackLevel> {
        val random = SeededRandom(seed)
        val everyOtherBlock = blockIndex % 2 == 0
        return when (stage) {
            Stage.Establish ->
                listOf(FeedbackLevel.Teach) + random.shuffled(
                    listOf(FeedbackLevel.Teach, FeedbackLevel.Teach, FeedbackLevel.Teach, FeedbackLevel.Teach, FeedbackLevel.Silent),
                )
            Stage.ConsolidateEarly -> {
                val level = if (everyOtherBlock) FeedbackLevel.Teach else FeedbackLevel.Quick
                val answered = if (everyOtherBlock) 5 else 4
                listOf(level) + random.shuffled(List(answered - 1) { level } + List(6 - answered) { FeedbackLevel.Silent })
            }
            Stage.ConsolidateLate -> {
                val quick = if (everyOtherBlock) 1 else 0
                val rest = List(quick) { FeedbackLevel.Quick } + List(3 - quick) { FeedbackLevel.Silent }
                random.shuffled(List(3) { FeedbackLevel.SelfJudge } + rest)
            }
            Stage.Differentiate ->
                random.shuffled(
                    listOf(FeedbackLevel.Quick, FeedbackLevel.Quick, FeedbackLevel.Quick, FeedbackLevel.Silent, FeedbackLevel.Silent, FeedbackLevel.Silent),
                )
            Stage.Automate, Stage.Generalise -> List(6) { FeedbackLevel.Silent }
        }
    }
}
