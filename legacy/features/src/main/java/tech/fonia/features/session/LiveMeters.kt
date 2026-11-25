package tech.fonia.features.session

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import tech.fonia.design.Palette
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.ui
import tech.fonia.designsystem.feedback.MeterColors
import tech.fonia.designsystem.feedback.PlaceMeter
import tech.fonia.designsystem.feedback.TrillMeter
import tech.fonia.designsystem.feedback.VoiceMeter
import tech.fonia.designsystem.letter.LetterDrive
import tech.fonia.domain.FeedbackLevel
import tech.fonia.domain.SoundID
import tech.fonia.features.app.field

/** Which live meters a sound gets: at most two, so the screen stays calm. */
internal data class MeterPlan(
    /** 1 = the sound sits on the sharp S side, 0 = on the round Ș side; null: no place meter. */
    val targetSharp: Float? = null,
    val left: String = "Ș",
    val right: String = "S",
    /** Null: no voice meter; true: the voice must be on; false: a hum means the voice crept in. */
    val wantVoice: Boolean? = null,
    val trill: Boolean = false,
) {
    companion object {
        /**
         * Live meters only in a block that answers at once; a silent or self-judged take keeps its own counsel. None at
         * automatizare either, where a meter would also tell which takes are about to be answered.
         */
        fun shows(feedback: FeedbackLevel, automatizare: Boolean = false): Boolean =
            !automatizare && (feedback == FeedbackLevel.Teach || feedback == FeedbackLevel.Quick)

        fun of(id: SoundID): MeterPlan = when (id) {
            SoundID.S -> MeterPlan(targetSharp = 1f, left = "Ș", right = "S")
            SoundID.Z -> MeterPlan(targetSharp = 1f, left = "J", right = "Z", wantVoice = true)
            SoundID.Ts -> MeterPlan(targetSharp = 1f, left = "CE", right = "Ț")
            SoundID.Sh -> MeterPlan(targetSharp = 0f, left = "Ș", right = "S")
            SoundID.J -> MeterPlan(targetSharp = 0f, left = "J", right = "Z", wantVoice = true)
            SoundID.Ce -> MeterPlan(targetSharp = 0f, left = "CE", right = "Ț")
            SoundID.Ge -> MeterPlan(targetSharp = 0f, left = "GE", right = "Z", wantVoice = true)
            SoundID.R -> MeterPlan(wantVoice = true, trill = true)
            SoundID.L -> MeterPlan(wantVoice = true)
        }
    }
}

/**
 * The live meters of a take, in the sheet at the bottom, fed by the same [LetterDrive] as the living letter: where the
 * hiss sits between its two letters, whether the voice is on, the R flutter. The tracks stay put in every phase; what
 * moves on them shows only while the microphone is open and fades with the take, so nothing jumps. Neutral colours:
 * the zone lights green, nothing turns red.
 */
@Composable
fun LiveMeters(sound: SoundID?, drive: LetterDrive, listening: Boolean, modifier: Modifier = Modifier) {
    if (sound == null) return
    val plan = MeterPlan.of(sound)
    val colors = MeterColors(
        track = Palette.progressTrack,
        zone = Palette.accentSoft,
        zoneOn = Palette.progress,
        marker = sound.family.field.deep,
        ink = Palette.ink,
        gloss = Palette.sheet,
    )
    // The bead, the voice line and the flutter follow `drive.listening` and fade out by themselves when it closes.
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val label = ui(17, UIWeight.black, TextRole.headline)
        plan.targetSharp?.let { target ->
            PlaceMeter(drive, target, colors, label, Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 6.dp), leftLabel = plan.left, rightLabel = plan.right)
        }
        if (plan.trill) TrillMeter(drive, colors, Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 6.dp))
        if (plan.targetSharp == null || !plan.trill) {
            plan.wantVoice?.let { want -> VoiceMeter(drive, want, colors, Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 6.dp)) }
        }
    }
}
