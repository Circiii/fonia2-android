package tech.fonia.features.app

import java.util.UUID
import tech.fonia.domain.SessionFocus
import tech.fonia.domain.SoundID
import tech.fonia.domain.Trial
import tech.fonia.features.probe.ProbePlan

/** What's on screen as a full-screen cover: a practice session. */
data class SessionTicket(
    val id: UUID = UUID.randomUUID(),
    val focus: SessionFocus,
    val title: String,
    val sound: SoundID?,
    /** Trials the app prepared itself (the quick check, a diction drill) instead of the session builder's. */
    val custom: CustomRun? = null,
) {
    /** „Cuvinte noi” before a lesson (`ProbeRules`): its items replace the builder's, and the lesson follows. */
    val probe: ProbePlan? get() = (custom?.kind as? CustomRun.Kind.Probe)?.plan
}

/** A run of trials made by the app, played through the same session screen, take cycle and judge. */
data class CustomRun(
    val kind: Kind,
    val trials: List<Trial>,
    /** Per trial, the speed the model is played at (a twister's tempo ladder); null: normal. */
    val modelSpeeds: List<Float>? = null,
) {
    sealed interface Kind {
        /** „Verificare rapidă”: one or two words per sound; ends on the three groups. */
        data object Check : Kind

        /** One drill of the diction training of [sound], the [index]-th of its ladder. */
        data class Drill(val sound: SoundID, val index: Int) : Kind

        /** „Cuvinte noi” before a lesson (iOS `SessionTicket.probe`): nothing of it is practice. */
        data class Probe(val plan: ProbePlan) : Kind
    }
}
