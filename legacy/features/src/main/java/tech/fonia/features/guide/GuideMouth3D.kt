package tech.fonia.features.guide

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.FacePatch
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.LocalReduceMotion
import tech.fonia.design.Palette
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.hex
import tech.fonia.design.swiftSpring
import tech.fonia.design.ui
import tech.fonia.designsystem.motion.rememberFoniaHaptics
import tech.fonia.domain.CoachDiagram
import tech.fonia.domain.CoachPlan
import tech.fonia.domain.CoachStep
import tech.fonia.domain.SoundDef
import tech.fonia.features.app.shape
import tech.fonia.features.session.MouthCard
import tech.fonia.model.SoundId
import tech.fonia.mouth3d.Mouth3D
import tech.fonia.mouth3d.Mouth3DAssets
import tech.fonia.mouth3d.Mouth3DColors
import tech.fonia.mouth3d.Mouth3DSupport
import tech.fonia.mouth3d.PoseGroup
import tech.fonia.mouth3d.PoseStep

/**
 * The picture at the top of the sound guide. For the nine sounds of the 3D mouth, and where GL ES 3 runs, the 3D mouth
 * from the front explains the sound: when the page opens it moves from rest one part at a time (the lips, then the
 * teeth and jaw, then the tongue and the pose's own movement), each part lighting its chip (an icon and at most two
 * words, from the pose file's `steps`). One tap on the replay button, a double tap on the mouth or a tap on a chip plays
 * it again; a drag turns it. A step about the tongue in the list below (a side view) shows the original side drawing
 * over it, since the 3D cut-away stays hidden until a speech therapist approves it; its toggle is already in place,
 * disabled until `insideAvailable`. Anything else keeps the original 2D drawings.
 */
@Composable
internal fun GuidePicture(sound: SoundDef, plan: CoachPlan?, step: CoachStep?) {
    val context = LocalContext.current
    val sound3d = remember(sound.id) { SoundId.of(sound.id.rawValue) }
    val can3d = remember(context) { Mouth3DSupport.canRender3D(context) }

    if (sound3d == null || !can3d) {
        val frame = Modifier.fillMaxWidth().height(220.dp).clip(ContinuousRoundedRectangle(22.dp))
        if (plan != null && step != null) {
            Crossfade(step, animationSpec = swiftSpring(0.3, 0.1), label = "diagram") { shown ->
                key(shown.id) { MouthDiagram(shown.diagram, frame) }
            }
        } else {
            MouthCard(sound.shape, sound.viseme, height = 220.dp)
        }
        return
    }

    val data by produceState<tech.fonia.mouth3d.Mouth3DData?>(null, context) {
        value = withContext(Dispatchers.IO) { runCatching { Mouth3DAssets.load(context) }.getOrNull() }
    }
    val steps = data?.spec(sound3d)?.steps ?: emptyList()
    // The side view (tongue, air, voice) waits for a speech therapist's approval in release builds; debug builds show it for review.
    tech.fonia.mouth3d.Mouth3DReview.showUnapprovedInside = tech.fonia.features.IS_DEBUG_BUILD
    val insideAvailable = data?.insideAvailable(sound3d) == true
    val side = step?.diagram?.takeIf { it.view == CoachDiagram.View.Side }
    GuidedMouth(sound3d, steps, insideAvailable, side)
}

/** The 3D mouth, its guided sequence and its chips. */
@Composable
private fun GuidedMouth(sound: SoundId, steps: List<PoseStep>, insideAvailable: Boolean, side: CoachDiagram?) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberFoniaHaptics()
    // -1 = rest; i = the parts of steps 0…i have moved. `run` restarts the sequence; `jump` shows one step and stops.
    var reached by remember(sound) { mutableIntStateOf(-1) }
    var run by remember { mutableIntStateOf(0) }
    var jump by remember { mutableStateOf<Int?>(null) }
    var inside by remember { mutableStateOf(false) }

    LaunchedEffect(sound, steps, run, jump) {
        val target = jump
        if (target != null) {
            reached = target
            return@LaunchedEffect
        }
        if (steps.isEmpty()) {
            reached = -1
            delay(if (run == 0) 420 else 260)
            reached = 0
            return@LaunchedEffect
        }
        reached = -1
        delay(if (run == 0) 520L else 320L)
        for (i in steps.indices) {
            reached = i
            if (i > 0 || run > 0) {
                haptics.tick()
                tech.fonia.design.FoniaSfx.play(tech.fonia.design.SfxCue.GuideStep)
            }
            delay(if (reduceMotion) 500L else 1100L)
        }
    }

    val groups: Set<PoseGroup> = when {
        steps.isEmpty() -> PoseGroup.ALL
        reached < 0 -> emptySet()
        reached >= steps.lastIndex -> PoseGroup.ALL
        else -> steps.take(reached + 1).flatMap { it.reveals }.toSet()
    }
    val last = if (steps.isEmpty()) 0 else steps.lastIndex
    Box(
        Modifier
            .fillMaxWidth()
            .height(250.dp)
            .clip(ContinuousRoundedRectangle(22.dp)),
    ) {
        FacePatch(Modifier.fillMaxSize())
        Mouth3D(
            sound = sound,
            progress = if (reached >= 0) 1f else 0f,
            inside = inside && insideAvailable,
            colors = GuideMouthColors,
            modifier = Modifier.fillMaxSize().padding(bottom = 36.dp),
            playing = reached >= last,
            autoFallback = false,
            onDoubleTap = { jump = null; run += 1 },
            groups = groups,
            highlight = steps.getOrNull(reached)?.articulator,
        )
        Crossfade(side, animationSpec = swiftSpring(0.3, 0.1), label = "side") { shown ->
            if (shown != null) MouthDiagram(shown, Modifier.fillMaxSize())
        }
        if (side == null) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                steps.forEachIndexed { index, st ->
                    StepChip(st, lit = index <= reached, current = index == reached) {
                        jump = index
                        haptics.tap()
                    }
                }
            }
            RoundIcon(
                description = "Încă o dată",
                enabled = true,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                onClick = { jump = null; run += 1 },
            ) { FoniaIconView(FoniaIcon.retry, size = 16f, tint = Palette.ink) }
            // The cut-away's place: shown, but off until a speech therapist approves the pose (`insideAvailable`).
            RoundIcon(
                description = "Vezi în interior",
                enabled = insideAvailable,
                selected = inside,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                onClick = { inside = !inside },
            ) { Glyph("inside", Palette.ink, 18.dp) }
        }
    }
}

@Composable
private fun StepChip(step: PoseStep, lit: Boolean, current: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(if (lit) Palette.accentSoft else Palette.sheet.copy(alpha = 0.85f), swiftSpring(0.3, 0.1), label = "chip")
    val ink by animateColorAsState(if (lit) Palette.accentText else Palette.inkSoft, swiftSpring(0.3, 0.1), label = "chipInk")
    val scale by animateFloatAsState(if (current) 1.06f else 1f, swiftSpring(0.35, 0.35), label = "chipScale")
    Row(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .semantics { stateDescription = if (lit) "arătat" else "" }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(step.icon, ink, 18.dp)
        FoniaText(step.label, style = ui(14, if (current) UIWeight.bold else UIWeight.semibold, TextRole.footnote), color = ink, maxLines = 1, minimumScaleFactor = 0.7f)
    }
}

@Composable
private fun RoundIcon(
    description: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(34.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
            .background(if (selected) Palette.accentSoft else Palette.sheet.copy(alpha = 0.9f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The small line icons of the chips: lips, teeth, tongue, air, and the cut-away's half head. */
@Composable
internal fun Glyph(name: String, tint: Color, size: Dp) {
    Canvas(Modifier.size(size)) { drawGlyph(name, tint) }
}

private fun DrawScope.drawGlyph(name: String, tint: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (name) {
        "lips" -> {
            val upper = Path().apply {
                moveTo(0.05f * w, 0.5f * h)
                cubicTo(0.25f * w, 0.2f * h, 0.4f * w, 0.18f * h, 0.5f * w, 0.32f * h)
                cubicTo(0.6f * w, 0.18f * h, 0.75f * w, 0.2f * h, 0.95f * w, 0.5f * h)
            }
            val lower = Path().apply {
                moveTo(0.05f * w, 0.5f * h)
                cubicTo(0.3f * w, 0.88f * h, 0.7f * w, 0.88f * h, 0.95f * w, 0.5f * h)
            }
            drawPath(upper, tint, style = stroke)
            drawPath(lower, tint, style = stroke)
            drawLine(tint, Offset(0.18f * w, 0.52f * h), Offset(0.82f * w, 0.52f * h), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
        }
        "teeth" -> {
            drawRoundRect(tint, topLeft = Offset(0.1f * w, 0.22f * h), size = Size(0.8f * w, 0.56f * h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.18f * w), style = stroke)
            drawLine(tint, Offset(0.1f * w, 0.5f * h), Offset(0.9f * w, 0.5f * h), strokeWidth = w * 0.08f)
            for (x in listOf(0.37f, 0.63f)) drawLine(tint, Offset(x * w, 0.22f * h), Offset(x * w, 0.78f * h), strokeWidth = w * 0.08f)
        }
        "tongue" -> {
            val p = Path().apply {
                moveTo(0.22f * w, 0.18f * h)
                lineTo(0.22f * w, 0.55f * h)
                cubicTo(0.22f * w, 0.95f * h, 0.78f * w, 0.95f * h, 0.78f * w, 0.55f * h)
                lineTo(0.78f * w, 0.18f * h)
            }
            drawPath(p, tint, style = stroke)
            drawLine(tint, Offset(0.5f * w, 0.3f * h), Offset(0.5f * w, 0.62f * h), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
        }
        "air" -> {
            for ((i, y) in listOf(0.28f, 0.5f, 0.72f).withIndex()) {
                val p = Path().apply {
                    moveTo(0.08f * w, y * h)
                    cubicTo(0.3f * w, (y - 0.14f) * h, 0.5f * w, (y + 0.14f) * h, (0.72f + 0.1f * (i % 2)) * w, y * h)
                }
                drawPath(p, tint, style = stroke)
            }
        }
        else -> {
            // The cut-away: a head in profile cut in half.
            drawArc(tint, startAngle = 90f, sweepAngle = 180f, useCenter = false, topLeft = Offset(0.12f * w, 0.1f * h), size = Size(0.76f * w, 0.8f * h), style = stroke)
            drawLine(tint, Offset(0.5f * w, 0.06f * h), Offset(0.5f * w, 0.94f * h), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
            drawLine(tint, Offset(0.3f * w, 0.62f * h), Offset(0.5f * w, 0.62f * h), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
        }
    }
}

/** `Mouth3DColors` tuned to `MouthView`'s palette, so the 3D mouth sits on `FacePatch` like the drawings do. */
internal val GuideMouthColors = Mouth3DColors(
    patch = Color.hex(0xF9D2B4),
    lips = Color.hex(0xDE6178),
    teeth = Color.hex(0xFFFDF8),
    gums = Color.hex(0xF28C9A),
    tongue = Color.hex(0xFA9CA8),
    lining = Color.hex(0x571C2E),
)
