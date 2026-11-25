package tech.fonia.features.adaptive

import kotlinx.coroutines.CancellationException
import tech.fonia.domain.AttemptsSummary
import tech.fonia.domain.Exercise
import tech.fonia.domain.ProgressionState
import tech.fonia.domain.ProgressionStep
import tech.fonia.domain.Rung
import tech.fonia.domain.Session
import tech.fonia.domain.SessionBlock
import tech.fonia.domain.SessionFocus
import tech.fonia.domain.SoundID
import tech.fonia.domain.Trial
import tech.fonia.domain.adaptive.AdaptiveDecision
import tech.fonia.domain.adaptive.AdaptivePlanner
import tech.fonia.domain.adaptive.AdaptiveSession
import tech.fonia.domain.adaptive.BlockTally
import tech.fonia.domain.adaptive.Evidence
import tech.fonia.domain.adaptive.ItemPosition
import tech.fonia.domain.adaptive.PlannedSession
import tech.fonia.domain.adaptive.TakeOutcome
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.SessionTicket
import tech.fonia.features.app.domainAge
import tech.fonia.features.app.index
import tech.fonia.features.pilot.AdaptiveRecord
import tech.fonia.features.pilot.PilotHooks
import tech.fonia.features.pilot.SoundPositions
import tech.fonia.features.pilot.itemLevel
import tech.fonia.features.pilot.practisedSound
import tech.fonia.store.Attempt
import tech.fonia.store.Profile
import tech.fonia.store.SoundProgress
import tech.fonia.store.localDay
import java.time.Instant
import java.util.UUID

/**
 * The adaptive pilot inside one `SessionRun` (docs/PLAN.md §7.4). It holds the engine of `:domain` (`AdaptiveSession`),
 * hands it what the session settles, logs every decision through the pilot log, and saves the state beside FoniaStore
 * after each block. A run whose profile is not in the pilot never gets one, so its session, progression and feedback
 * are iOS's.
 */
class AdaptiveRun internal constructor(
    private val engine: AdaptiveSession,
    private val app: AppModel,
    private val profileID: UUID,
    private val sessionID: UUID,
    /** `SessionRun.later`: the saves run in the order asked for, with the session's own. */
    private val save: (suspend () -> Unit) -> Unit,
) {
    private var planned: PlannedSession? = null

    /** For each trial of the session, the rung of its material (null outside the adaptive blocks). */
    private var rungs: List<Rung?> = emptyList()
    private var ended = false

    val sound: SoundID get() = engine.sound

    /** The session to run instead of [base]: the same opening, listening and mission, adaptive blocks between. */
    fun plan(base: Session): Session {
        val (plan, decisions) = engine.plan(base)
        planned = plan
        rungs = plan.itemRungs
        decisions.forEach(::log)
        return plan.session
    }

    /**
     * One settled take of trial [index] (retries included): evidence for its position × rung when someone could judge
     * it (T1). Only productions of the session's sound in the adaptive blocks count.
     */
    fun take(index: Int, exercise: Exercise, machine: Attempt.Outcome, adult: Attempt.Outcome?) {
        val rung = rungs.getOrNull(index) ?: return
        if (!exercise.isProduction) return
        val practised = exercise.practisedSound
        if (practised != null && practised != engine.sound) return
        val position = SoundPositions.of(exercise.itemText, PilotHooks.graphemes(app.content, engine.sound), exercise.itemLevel)
        engine.take(rung, ItemPosition.fromRawValue(position.code), Evidence.of(machine.take, adult?.take))
    }

    /** What the session does after an adaptive block: the trials that replace the rest, and the rung the block practised. */
    data class BlockEnd(val remaining: List<Trial>, val rung: Rung)

    /**
     * The block of trial [index] ended with [good] of [total] takes judged good: the engine decides the next block (T2,
     * T3) and plans the rest again. [canGoEasier] is false for a child's block no adult rated. Null for a block that is
     * not one of the adaptive ones.
     */
    fun blockEnded(index: Int, session: Session, good: Int, total: Int, canGoEasier: Boolean = true): BlockEnd? {
        val plan = planned ?: return null
        val trial = session.trials.getOrNull(index) ?: return null
        if (trial.block.kind != SessionBlock.Kind.TargetA || index < plan.firstBlockAt) return null
        val practised = engine.challenge.step.rung
        val items = session.trials.filter { it.block == trial.block }.mapNotNull { it.exercise.itemText }
        val (decision, remaining) = engine.blockEnded(trial.block.index, BlockTally(good, total), items, canGoEasier)
        log(decision)
        rungs = rungs.take(index + 1) + remaining.flatMap { it.itemRungs } + plan.tail.map { null }
        persist()
        return BlockEnd(remaining.flatMap { it.trials } + plan.tail, practised)
    }

    /**
     * The session ended (or was left, [finished] false): the mode rule gets its success, the keys their day, and the
     * rung is passed or kept for an adult (T1). Runs once.
     */
    fun end(finished: Boolean, progression: ProgressionState): ProgressionStep {
        if (ended) return ProgressionStep.Stay
        ended = true
        val (step, decisions) = engine.end(localDay(Instant.now()), progression, finished)
        decisions.forEach(::log)
        persist()
        return step
    }

    private fun persist() {
        val store = app.adaptive ?: return
        val sound = engine.sound
        val state = engine.state
        save { store.update(profileID) { it.with(sound, state) } }
    }

    private fun log(decision: AdaptiveDecision) {
        runCatching { app.pilot?.adaptive(AdaptiveRecord(profileID, sessionID, Instant.now(), decision)) }
    }

    companion object {
        /**
         * The pilot's part in a session and the session to show instead of [base], or null to keep [base] as iOS built
         * it: no store, a profile not switched on, a session that is not a sound's ladder, a session the debug switches
         * cut down, or any error on the way (the pilot can't stop a session from starting).
         */
        suspend fun start(
            app: AppModel,
            profile: Profile,
            ticket: SessionTicket,
            sessionID: UUID,
            progress: SoundProgress?,
            history: AttemptsSummary,
            base: Session,
            save: (suspend () -> Unit) -> Unit,
        ): Pair<AdaptiveRun, Session>? = try {
            begin(app, profile, ticket, sessionID, progress, history, save)?.let { run -> run to run.plan(base) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

        private suspend fun begin(
            app: AppModel,
            profile: Profile,
            ticket: SessionTicket,
            sessionID: UUID,
            progress: SoundProgress?,
            history: AttemptsSummary,
            save: (suspend () -> Unit) -> Unit,
        ): AdaptiveRun? {
            val store = app.adaptive ?: return null
            val focus = ticket.focus as? SessionFocus.Sound ?: return null
            if (app.arguments.speakOnly || app.arguments.short) return null
            val saved = store.load(profile.id)
            if (!saved.enabled) return null
            val engine = AdaptiveSession(
                planner = AdaptivePlanner(app.content),
                sound = focus.id,
                ceiling = focus.rung,
                age = profile.domainAge,
                // As the session's own builder: word lists leave out the other sounds the child works on.
                avoiding = profile.targetSounds.mapNotNull { SoundID.fromRawValue(it) }.toSet() - focus.id,
                lastItems = history.recentItems,
                seed = AdaptiveSession.seed(sessionID.toString()),
                saved = saved.sound(focus.id),
                // A lesson under the rung reached is review, as in `SessionRun`.
                isReview = focus.rung.index < (progress?.rung ?: 0),
            )
            return AdaptiveRun(engine, app, profile.id, sessionID, save)
        }

        private val Attempt.Outcome.take: TakeOutcome get() = TakeOutcome.fromRawValue(rawValue) ?: TakeOutcome.NotJudged
    }
}
