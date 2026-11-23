package tech.fonia.domain.adaptive

import tech.fonia.domain.AgeBand
import tech.fonia.domain.FeedbackLevel
import tech.fonia.domain.Progression
import tech.fonia.domain.ProgressionState
import tech.fonia.domain.ProgressionStep
import tech.fonia.domain.Rung
import tech.fonia.domain.SeededRandom
import tech.fonia.domain.Session
import tech.fonia.domain.SessionBuilder
import tech.fonia.domain.SessionFocus
import tech.fonia.domain.SessionRequest
import tech.fonia.domain.SoundID
import tech.fonia.domain.StringRawValue
import tech.fonia.domain.Trial
import tech.fonia.domain.byRawValue

/** Everything the adaptive pilot keeps about one sound of one child. */
data class SoundAdaptive(
    val setting: ModeSetting = ModeSetting.Auto,
    /** The mode of the last session: "automat" keeps it while the success is between the two thresholds. */
    val mode: PracticeMode? = null,
    /** The last sessions on the sound, oldest first (at most [PracticeModes.KEPT_SESSIONS]). */
    val sessions: List<SessionTally> = emptyList(),
    /** Where the controller stood at the end of the last session on the rung reached. */
    val challenge: ChallengeState? = null,
    val mastery: List<KeyMastery> = emptyList(),
) {
    fun key(position: ItemPosition, rung: Rung): KeyMastery =
        mastery.firstOrNull { it.position == position && it.rung == rung } ?: KeyMastery(position, rung)

    fun withKey(key: KeyMastery): SoundAdaptive =
        copy(mastery = (mastery.filterNot { it.position == key.position && it.rung == key.rung } + key).sortedWith(compareBy({ it.rung }, { it.position })))
}

/** The adaptive pilot of one profile: off by default, and nothing changes while it is off. */
data class AdaptiveProfile(
    /** "Mod adaptiv (pilot)", switched on by an adult for the pilot. */
    val enabled: Boolean = false,
    val sounds: Map<SoundID, SoundAdaptive> = emptyMap(),
) {
    fun sound(id: SoundID): SoundAdaptive = sounds[id] ?: SoundAdaptive()

    fun with(id: SoundID, state: SoundAdaptive): AdaptiveProfile = copy(sounds = sounds + (id to state))
}

/** One decision of the engine, as the pilot log keeps it: codes and numbers, never text. */
data class AdaptiveDecision(
    val kind: Kind,
    /** The sound decided about; null only for the profile's switch, which is about every sound. */
    val sound: SoundID?,
    /** What was decided, coded (`harder`, `generalization`, `advance`, `on`...). */
    val choice: String,
    /** Why, coded (`below50`, `atLeast80in2`, `mastered2days`...). */
    val reason: String,
    /** The block's index in the session, for block decisions. */
    val block: Int? = null,
    /** The step decided (block decisions), the key (mastery) or the rung (rung decisions). */
    val rung: Rung? = null,
    val fast: Boolean? = null,
    val position: ItemPosition? = null,
    val mode: PracticeMode? = null,
    /** The evidence behind it: takes judged good, takes judged, takes skipped as unsure. */
    val good: Int? = null,
    val total: Int? = null,
    val skipped: Int? = null,
    /** A success rate or a P(mastered), and the value before it (for mastery). */
    val value: Double? = null,
    val previous: Double? = null,
    /** The next block's feedback: knowledge of performance on every take, or knowledge of results on [kr] takes. */
    val kp: Boolean? = null,
    val kr: Int? = null,
) {
    enum class Kind(override val rawValue: String) : StringRawValue {
        /** An adult switched the pilot or chose a mode in the grown-ups' area. */
        Setting("setting"),

        /** The mode of a session (P5). */
        Mode("mode"),

        /** The step and the feedback of the next block (T2, T3); the first one at the session's start. */
        Block("block"),

        /** One position × rung at the end of a session (T1). */
        Mastery("mastery"),

        /** Whether the rung is passed (T1, and the locks of `Progression`). */
        Rung("rung"),
        ;

        companion object {
            fun fromRawValue(rawValue: String): Kind? = entries.byRawValue(rawValue)
        }
    }
}

/**
 * The adaptive engine for one sound session: pure (no clock, no I/O), so every rule runs in the JVM tests. The
 * session gives it every judged take and every finished block; it answers with the next blocks, the decisions to log,
 * and at the end whether the rung is passed. [state] is what to save.
 */
class AdaptiveSession(
    private val planner: AdaptivePlanner,
    val sound: SoundID,
    /** The rung of the lesson: the steps stay at or under it. */
    val ceiling: Rung,
    private val age: AgeBand,
    private val avoiding: Set<SoundID>,
    /** Items of the last session on this focus, which come last. */
    private val lastItems: Set<String>,
    private val seed: ULong,
    saved: SoundAdaptive,
    /** A lesson under the rung reached is review: it practises adaptively but moves nothing and keeps no controller. */
    private val isReview: Boolean,
) {
    var state: SoundAdaptive = saved
        private set

    private val fastAllowed = planner.fastAllowed(sound, ceiling, age, avoiding)

    /** P5: the mode of this session, and why. */
    val mode: PracticeModes.Choice = PracticeModes.choose(saved.setting, saved.mode, saved.sessions)

    var challenge: ChallengeState = ChallengePoint.resume(saved.challenge, ceiling, fastAllowed)
        private set

    private var planned: PlannedSession? = null
    private var sessionTally = BlockTally(0, 0)
    private val usedItems = lastItems.toMutableSet()

    /** Per key, what this session saw: P at its first take, takes judged, good, and unsure takes skipped. */
    private class KeyRun(val before: Double, var takes: Int = 0, var good: Int = 0, var skipped: Int = 0)

    private val keys = linkedMapOf<Pair<ItemPosition, Rung>, KeyRun>()

    init {
        state = state.copy(mode = mode.mode, challenge = if (isReview) state.challenge else challenge)
    }

    private fun setup(): BlockSetup = BlockSetup(
        sound = sound,
        step = challenge.step,
        mode = mode.mode,
        kp = challenge.kp,
        recentSuccess = challenge.recentSuccess,
        age = age,
        avoiding = avoiding,
        recentItems = usedItems.toSet(),
        seed = seed,
    )

    /** The session to run: [base]'s opening and mission around adaptive blocks. Logs the mode and the first block. */
    fun plan(base: Session): Pair<PlannedSession, List<AdaptiveDecision>> {
        val result = planner.session(base, setup())
        planned = result
        val first = result.session.trials.drop(result.firstBlockAt).take(blockLength(result))
        return result to listOf(
            AdaptiveDecision(AdaptiveDecision.Kind.Mode, sound, mode.mode.rawValue, mode.reason.rawValue, rung = ceiling, mode = mode.mode),
            blockDecision(0, "start", "session", null, first),
        )
    }

    /**
     * One take of the session's sound in an adaptive block, from [itemRung]'s material. Updates P(mastered) of its key
     * (T1), or counts it as skipped when no one could judge it.
     */
    fun take(itemRung: Rung, position: ItemPosition?, evidence: Evidence?) {
        val keyPosition = Bkt.keyPosition(itemRung, position) ?: return
        val current = state.key(keyPosition, itemRung)
        val run = keys.getOrPut(keyPosition to itemRung) { KeyRun(current.pKnown) }
        if (evidence == null) {
            run.skipped += 1
            return
        }
        run.takes += 1
        if (evidence.correct) run.good += 1
        state = state.withKey(current.copy(pKnown = Bkt.update(current.pKnown, evidence), takes = current.takes + 1))
    }

    /**
     * Block [index] ended with [tally] after practising [items]: the controller decides the next step (T2) and the next
     * feedback (T3), and the blocks still to come are planned again from them. Returns the decision to log and those
     * blocks.
     */
    fun blockEnded(index: Int, tally: BlockTally, items: Collection<String>): Pair<AdaptiveDecision, List<PlannedBlock>> {
        sessionTally += tally
        usedItems += items
        val outcome = ChallengePoint.afterBlock(challenge, tally, fastAllowed)
        challenge = outcome.state
        if (!isReview) state = state.copy(challenge = challenge)
        val total = planned?.blocks ?: 0
        val remaining = if (index + 1 < total) planner.blocks(setup(), index + 1, total) else emptyList()
        val next = remaining.firstOrNull()?.trials.orEmpty()
        return blockDecision(index + 1, outcome.move.rawValue, outcome.reason.rawValue, tally, next) to remaining
    }

    /**
     * The session ended on [day] (`YYYY-MM-DD`): its success joins the mode rule (P5), every key it practised enough and
     * left at P ≥ 0.9 gets the day (T1), and the rung is passed when its keys hold on two days, unless a lock of
     * [progression] keeps it for an adult (`Progression.isLocked`, and only a person moves on past sentences).
     */
    fun end(day: String, progression: ProgressionState, finished: Boolean): Pair<ProgressionStep, List<AdaptiveDecision>> {
        val decisions = mutableListOf<AdaptiveDecision>()
        if (sessionTally.total > 0) {
            state = state.copy(sessions = (state.sessions + SessionTally(day, sessionTally.good, sessionTally.total)).takeLast(PracticeModes.KEPT_SESSIONS))
        }
        for ((key, run) in keys) {
            val (position, rung) = key
            var mastery = state.key(position, rung)
            val counts = run.takes >= Bkt.MIN_TAKES_IN_SESSION && mastery.pKnown >= Bkt.MASTERED_AT
            if (counts && day !in mastery.masteredOn) {
                mastery = mastery.copy(masteredOn = (mastery.masteredOn + day).takeLast(Bkt.KEPT_DAYS))
                state = state.withKey(mastery)
            }
            decisions += AdaptiveDecision(
                AdaptiveDecision.Kind.Mastery, sound,
                choice = if (mastery.isMastered) "mastered" else if (counts) "aboveOnDay" else "practising",
                reason = when {
                    run.takes < Bkt.MIN_TAKES_IN_SESSION -> "fewTakes"
                    mastery.pKnown >= Bkt.MASTERED_AT -> "atLeast90"
                    else -> "under90"
                },
                rung = rung, position = position, good = run.good, total = run.takes, skipped = run.skipped,
                value = mastery.pKnown, previous = run.before,
            )
        }
        val (step, reason) = rungStep(progression, finished)
        val keysOfCeiling = state.mastery.filter { it.rung == ceiling && it.takes >= Bkt.MIN_TAKES_IN_SESSION }
        decisions += AdaptiveDecision(
            AdaptiveDecision.Kind.Rung, sound,
            choice = when (step) {
                is ProgressionStep.Advance -> "advance"
                is ProgressionStep.NeedsAdult -> "needsAdult"
                else -> "stay"
            },
            reason = reason, rung = ceiling, mode = mode.mode, good = sessionTally.good, total = sessionTally.total,
            value = keysOfCeiling.minOfOrNull { it.pKnown },
        )
        return step to decisions
    }

    /** T1 on the rung of the lesson: ready when every key of it with enough takes held P ≥ 0.9 on 2 days. */
    fun isReady(): Boolean {
        val keys = state.mastery.filter { it.rung == ceiling && it.takes >= Bkt.MIN_TAKES_IN_SESSION }
        return keys.isNotEmpty() && keys.all { it.isMastered }
    }

    private fun rungStep(progression: ProgressionState, finished: Boolean): Pair<ProgressionStep, String> {
        if (isReview) return ProgressionStep.Stay to "review"
        if (!finished) return ProgressionStep.Stay to "leftEarly"
        if (!isReady()) return ProgressionStep.Stay to "notMastered"
        val next = ceiling.next ?: return ProgressionStep.Stay to "lastRung"
        // The iOS locks stay: an adult listens before the syllables (03 §1.2 P8), and past sentences the phone stays
        // quiet, so only a person moves on (`Progression.adultDecides`, which is private there).
        if (Progression.isLocked(next, progression)) return ProgressionStep.NeedsAdult(next) to "locked"
        if ((ceiling == Rung.Sentences || ceiling == Rung.Retell) && !progression.openedByParent) return ProgressionStep.NeedsAdult(next) to "adultDecides"
        return ProgressionStep.Advance(next) to "mastered2days"
    }

    private fun blockLength(plan: PlannedSession): Int =
        plan.session.trials.drop(plan.firstBlockAt).takeWhile { it.block == plan.session.trials.getOrNull(plan.firstBlockAt)?.block }.size

    /** The next block as the log keeps it: its step, its mode, and how much it tells (all `teach`, or `quick` on some). */
    private fun blockDecision(index: Int, choice: String, reason: String, tally: BlockTally?, next: List<Trial>): AdaptiveDecision {
        val productions = next.filter { it.exercise.isProduction }
        return AdaptiveDecision(
            AdaptiveDecision.Kind.Block, sound, choice, reason,
            block = index, rung = challenge.step.rung, fast = challenge.step.fast, mode = mode.mode,
            good = tally?.good, total = tally?.total, value = tally?.rate,
            kp = productions.isNotEmpty() && productions.all { it.feedback == FeedbackLevel.Teach },
            kr = productions.count { it.feedback == FeedbackLevel.Quick },
        )
    }

    companion object {
        /** The seed of a session's blocks, from something unique to the session (its row id). */
        fun seed(sessionKey: String): ULong = SeededRandom.hash("adaptive|$sessionKey")

        /** A sound session with adaptive blocks when [profile] has the pilot on; otherwise exactly what [builder] builds. */
        fun build(builder: SessionBuilder, planner: AdaptivePlanner, request: SessionRequest, profile: AdaptiveProfile?, sessionKey: String = "vector"): Session {
            val base = builder.build(request)
            val focus = request.focus as? SessionFocus.Sound ?: return base
            if (profile == null || !profile.enabled) return base
            val session = AdaptiveSession(
                planner, focus.id, focus.rung, request.age, request.avoidingSounds, request.history.recentItems, seed(sessionKey),
                profile.sound(focus.id), isReview = false,
            )
            return session.plan(base).first.session
        }
    }
}
