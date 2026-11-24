package tech.fonia.features.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.ui
import tech.fonia.domain.SoundID
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.field
import java.util.TimeZone
import java.util.UUID

/**
 * "Today vs. the first time" for one sound, from the dots of his takes: two rows of the S↔Ș axis (or of good/not
 * beads, for R and L), the first day above and the latest below, so he sees the cloud move into the zone; then
 * „8 din 10 în zonă” as numbers with an icon, and the calibration badge once his own reference is in use.
 * Draws nothing before the first judged take.
 */
@Composable
fun SoundProgressCard(sound: SoundID, app: AppModel, profileId: UUID, modifier: Modifier = Modifier, showsCount: Boolean = true) {
    var progress by remember(sound, profileId) { mutableStateOf<SoundProgress?>(null) }
    var calibrated by remember(sound, profileId) { mutableStateOf(false) }
    LaunchedEffect(sound, profileId) {
        withContext(Dispatchers.IO) {
            val dots = app.voice.dots(profileId, SoundJudge.soundId(sound).raw)
            progress = SoundProgress.of(dots, TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong())
            calibrated = app.voice.calibrated(profileId)
        }
    }
    val p = progress ?: return
    val field = sound.family.field
    val (left, right) = axisLabels(sound)
    val hasAxis = MeterPlan.of(sound).targetSharp != null
    val target = MeterPlan.of(sound).targetSharp ?: 1f
    Column(
        modifier
            .fillMaxWidth()
            .background(Palette.sheet, RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .clearAndSetSemantics { contentDescription = if (showsCount) "${p.latestGood} din ${p.latestTotal} în zonă azi" else "Încercările tale de azi" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // A child sees the dots move, not "N din M".
        if (showsCount) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FoniaIconView(FoniaIcon.target, size = 22f, tint = field.deep)
                FoniaText("${p.latestGood} din ${p.latestTotal} în zonă", Modifier.weight(1f), style = ui(18, UIWeight.black, TextRole.headline), color = Palette.ink, maxLines = 1)
            }
        }
        if (calibrated) CalibratedBadge()
        val rows = if (p.sameDay) listOf("azi" to p.latest) else listOf("prima dată" to p.first, "azi" to p.latest)
        for ((label, dots) in rows) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FoniaText(label, Modifier.width(76.dp), style = ui(13, UIWeight.bold, TextRole.footnote), color = Palette.inkSoft, maxLines = 1)
                if (hasAxis) FoniaText(left, style = ui(15, UIWeight.black, TextRole.headline), color = Palette.ink)
                Canvas(Modifier.weight(1f).height(24.dp)) {
                    val h = 12.dp.toPx()
                    val top = (size.height - h) / 2f
                    val r = CornerRadius(h / 2f)
                    drawRoundRect(Palette.progressTrack, Offset(0f, top), Size(size.width, h), r)
                    val pad = h * 0.7f
                    fun x(v: Float) = pad + (size.width - 2 * pad) * v.coerceIn(0f, 1f)
                    if (hasAxis) {
                        val zone = dots.firstOrNull { it.hasPlace }
                        val lo = zone?.zoneLow ?: if (target >= 0.5f) 0.6f else 0f
                        val hi = zone?.zoneHigh ?: if (target >= 0.5f) 1f else 0.4f
                        val zx0 = if (lo <= 0.001f) 0f else x(lo)
                        val zx1 = if (hi >= 0.999f) size.width else x(hi)
                        drawRoundRect(Palette.accentSoft, Offset(zx0, top), Size(zx1 - zx0, h), r)
                        for (d in dots.filter { it.hasPlace }) {
                            drawCircle((if (d.inZone) Palette.progress else field.deep).copy(alpha = 0.55f), h * 0.42f, Offset(x(d.place), size.height / 2f))
                        }
                    } else {
                        // R, L: one bead per take, filled when it was right
                        val n = dots.size.coerceAtMost(20)
                        for ((i, d) in dots.takeLast(20).withIndex()) {
                            val cx = x((i + 0.5f) / n)
                            drawCircle(if (d.isCorrect) Palette.progress else Palette.sheet, h * 0.4f, Offset(cx, size.height / 2f))
                        }
                    }
                }
                if (hasAxis) FoniaText(right, style = ui(15, UIWeight.black, TextRole.headline), color = Palette.ink)
            }
        }
    }
}
