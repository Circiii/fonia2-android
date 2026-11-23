package tech.fonia.domain.adaptive

import tech.fonia.domain.AgeBand
import tech.fonia.domain.Exercise
import tech.fonia.domain.FeedbackLevel
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.Language
import tech.fonia.domain.Progression
import tech.fonia.domain.Prosody
import tech.fonia.domain.RepeatPrompt
import tech.fonia.domain.Rung
import tech.fonia.domain.SeededRandom
import tech.fonia.domain.Session
import tech.fonia.domain.SessionBlock
import tech.fonia.domain.SessionBuilder
import tech.fonia.domain.SoundID
import tech.fonia.domain.Trial
import tech.fonia.domain.nfc

/** What the blocks of a session share, and what the engine moves between them. */
data class BlockSetup(
    val sound: SoundID,
    val step: Step,
    val mode: PracticeMode,
    /** Knowledge of performance on every take (T2, after a block under half). */
    val kp: Boolean,
    /** The success the feedback fades on (T3); null before the first block. */
    val recentSuccess: Double?,
    val age: AgeBand,
    /** Other sounds the child works on: word lists leave them out where they can, as in iOS sessions. */
    val avoiding: Set<SoundID>,
    /** Items of the last session and of this one so far: fresh items come first. */
    val recentItems: Set<String>,
    val seed: ULong,
)

/** One planned block: its trials, and for each one the rung of its material (null for a listening item). */
data class PlannedBlock(val trials: List<Trial>, val itemRungs: List<Rung?>)

/** A sound session with adaptive blocks: the session, the rung of each trial's material, and where the blocks are. */
data class PlannedSession(
    val session: Session,
    val itemRungs: List<Rung?>,
    /** Index of the first trial of the first block. */
    val firstBlockAt: Int,
    val blocks: Int,
    /** Trials after the blocks (the real-life mission of the last rung), kept when the blocks are planned again. */
    val tail: List<Trial>,
)

/**
 * Lays out the blocks the engine asks for (P5, T2, T3). A block is six productions, as iOS's: one step's material,
 * in the mode's order and with the mode's feedback.
 *
 * - Acquisition: blocked practice with few targets (two items, each said three times in a row), the model heard
 *   before every take (a picture to name becomes "repeat after me"), knowledge of performance on every take.
 * - Generalization: random practice: two takes in three from the step's material and one from the second target
 *   below it (iOS's target B), unused items first, a prosody from the age's spinner, knowledge of results on 50 % → 20 %
 *   of the takes.
 * - A fast step says every item that takes a prosody fast ("Repede"), the pace of T2.
 * - The minimal-pair rung keeps iOS's three listening items before each block's productions.
 */
class AdaptivePlanner(content: FoniaContent) {
    private val material = AdaptiveMaterial(content)

    /** True when [ceiling] has a fast step for [age]: the age's spinner has "fast" and the rung's items take a prosody. */
    fun fastAllowed(sound: SoundID, ceiling: Rung, age: AgeBand, avoiding: Set<SoundID>): Boolean =
        Prosody.Fast in age.prosodies && material.primary(sound, ceiling, avoiding).exercises.any(::takesProsody)

    /** Blocks [from] until [until] of a session, all from [setup]: the engine plans again after each block. */
    fun blocks(setup: BlockSetup, from: Int, until: Int): List<PlannedBlock> {
        val used = setup.recentItems.toMutableSet()
        return (from until until).map { index ->
            block(setup.copy(recentItems = used.toSet()), index).also { planned ->
                planned.trials.mapNotNullTo(used) { it.exercise.itemText }
            }
        }
    }

    /**
     * [base] with its target and mixed blocks replaced by [blocks] adaptive blocks of six: the opening, the listening
     * warm-up and the mission stay as iOS built them, and the dose ([Session.targetAttempts]) stays the same.
     */
    fun session(base: Session, setup: BlockSetup, blocks: Int = base.targetAttempts / Progression.blockSize): PlannedSession {
        val practice = setOf(SessionBlock.Kind.TargetA, SessionBlock.Kind.TargetB, SessionBlock.Kind.Mixed)
        val firstBlockAt = base.trials.indexOfFirst { it.block.kind in practice }.let { if (it < 0) base.trials.size else it }
        val head = base.trials.take(firstBlockAt)
        // The builder's easy closing item is dropped too: the planned blocks already fill the whole dose.
        val tail = base.trials.drop(firstBlockAt).filter { it.block.kind !in practice && it.block.kind != SessionBlock.Kind.Closing }
        val planned = blocks(setup, 0, blocks)
        val trials = head + planned.flatMap { it.trials } + tail
        val rungs = List<Rung?>(head.size) { null } + planned.flatMap { it.itemRungs } + List<Rung?>(tail.size) { null }
        return PlannedSession(Session(trials = trials, targetAttempts = base.targetAttempts, timeLimitSeconds = base.timeLimitSeconds), rungs, firstBlockAt, blocks, tail)
    }

    fun block(setup: BlockSetup, index: Int): PlannedBlock {
        val random = SeededRandom(setup.seed xor SeededRandom.hash("adaptive|block|$index"))
        val primary = material.primary(setup.sound, setup.step.rung, setup.avoiding)
        val secondary = material.secondary(setup.sound, setup.step.rung, setup.avoiding)
        val own = primary.takeIf { it.exercises.isNotEmpty() } ?: secondary
        if (own.exercises.isEmpty()) return PlannedBlock(emptyList(), emptyList())

        val productions: List<Pair<Exercise, Rung>> = when (setup.mode) {
            PracticeMode.Acquisition -> blocked(own, setup.recentItems, random)
            PracticeMode.Generalization -> mixed(own, secondary.takeIf { it.exercises.isNotEmpty() } ?: own, setup.recentItems, random)
        }
        val levels = FeedbackFading.levels(setup.mode, setup.kp, setup.recentSuccess, productions.map { it.first }, random.next())
        val kind = SessionBlock(SessionBlock.Kind.TargetA, index)
        val trials = mutableListOf<Trial>()
        val rungs = mutableListOf<Rung?>()
        if (setup.step.rung == Rung.Pairs) {
            // iOS's minimal-pair blocks start with a few listening items: hear the difference, then make it.
            val perception = Deck(material.perception(setup.sound), emptySet(), random)
            repeat(minOf(SessionBuilder.perceptionPerPairBlock, perception.size)) {
                trials += Trial(exercise = perception.draw(random), feedback = FeedbackLevel.Quick, block = kind)
                rungs += null
            }
        }
        productions.forEachIndexed { at, (exercise, rung) ->
            trials += Trial(exercise = exercise, feedback = levels[at], block = kind, prosody = prosody(exercise, setup, random))
            rungs += rung
        }
        return PlannedBlock(trials, rungs)
    }

    /** Acquisition: few targets, each said in a row (blocked practice), with the model before each take. */
    private fun blocked(material: Material, recent: Set<String>, random: SeededRandom): List<Pair<Exercise, Rung>> {
        val deck = Deck(material.exercises.map(::withModel).distinct(), recent, random)
        val targets = List(minOf(PracticeModes.ACQUISITION_TARGETS_PER_BLOCK, deck.size)) { deck.draw(random) }
        val each = Progression.blockSize / targets.size
        val extra = Progression.blockSize % targets.size
        return targets.flatMapIndexed { at, exercise -> List(each + if (at < extra) 1 else 0) { exercise to material.rung } }
    }

    /** Generalization: random practice over the step's material and the one below, two takes in three from the step. */
    private fun mixed(own: Material, below: Material, recent: Set<String>, random: SeededRandom): List<Pair<Exercise, Rung>> {
        val ownDeck = Deck(own.exercises, recent, random)
        val belowDeck = Deck(below.exercises, recent, random)
        return List(Progression.blockSize) {
            if (random.below(GENERALIZATION_PARTS) < GENERALIZATION_OWN_PARTS) ownDeck.draw(random) to own.rung else belowDeck.draw(random) to below.rung
        }
    }

    /** Fast on a fast step; in generalization a prosody from the age's spinner (varied contexts); none in acquisition. */
    private fun prosody(exercise: Exercise, setup: BlockSetup, random: SeededRandom): Prosody? {
        if (!takesProsody(exercise)) return null
        if (setup.step.fast) return Prosody.Fast
        if (setup.mode == PracticeMode.Generalization) return setup.age.prosodies[random.below(setup.age.prosodies.size)]
        return null
    }

    companion object {
        /** Generalization draws [GENERALIZATION_OWN_PARTS] of every [GENERALIZATION_PARTS] takes from the step's own material. [inf] */
        const val GENERALIZATION_PARTS = 3
        const val GENERALIZATION_OWN_PARTS = 2

        /** `SessionBuilder.takesProsody`: what can be said loud, slow, fast or as a question. */
        fun takesProsody(exercise: Exercise): Boolean = when (exercise) {
            is Exercise.Repeat, is Exercise.Name, is Exercise.Twister -> true
            else -> false
        }

        /** Acquisition's "model audio des": a picture to name becomes the same word repeated after the model. */
        fun withModel(exercise: Exercise): Exercise = when (exercise) {
            is Exercise.Name -> Exercise.Repeat(
                RepeatPrompt(text = exercise.item.text, language = Language.Ro, sound = exercise.sound, picture = exercise.item.picture, level = RepeatPrompt.Level.Word),
            )
            else -> exercise
        }
    }
}

/**
 * `SessionBuilder`'s deck: items in shuffled rounds, the ones not used recently first, never the same item twice in a
 * row when the list allows it. An empty deck is never drawn from here.
 */
private class Deck(private val items: List<Exercise>, recent: Set<String>, random: SeededRandom) {
    private var queue: MutableList<Exercise>
    private var last: Exercise? = null

    val size: Int get() = items.size

    init {
        val recentText = recent.mapTo(HashSet()) { nfc(it) }
        val isRecent = { exercise: Exercise -> exercise.itemText?.let { nfc(it) in recentText } ?: false }
        queue = (random.shuffled(items.filter { !isRecent(it) }) + random.shuffled(items.filter(isRecent))).toMutableList()
    }

    fun draw(random: SeededRandom): Exercise {
        if (queue.isEmpty()) {
            queue = random.shuffled(items).toMutableList()
            if (queue.size > 1 && queue[0] == last) {
                val held = queue[0]
                queue[0] = queue[1]
                queue[1] = held
            }
        }
        val next = queue.removeAt(0)
        last = next
        return next
    }
}
