package tech.fonia.features.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.LocalReduceMotion
import tech.fonia.design.Palette
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.TileButtonStyle
import tech.fonia.design.UIWeight
import tech.fonia.design.fBody
import tech.fonia.design.fCaption
import tech.fonia.design.fHeadline
import tech.fonia.design.monospacedDigit
import tech.fonia.design.ui
import kotlin.math.ceil

/** Something an adult asked for, waiting for the adult check. */
class GatedStep(val run: () -> Unit)

/** A child's profile is on the phone and the check hasn't been passed: a phone with only adults never asks. */
val AppModel.needsAdultGate: Boolean get() = profiles.any { it.isChild } && !gate.isOpen

/**
 * `app.passGate($gated) { … }`: returns the guard for this screen. A guarded step runs at once when no check is
 * needed, or after the adult passes the check in a sheet over the page; closing the sheet drops it.
 */
@Composable
fun rememberAdultGate(app: AppModel): (() -> Unit) -> Unit {
    var pending by remember { mutableStateOf<GatedStep?>(null) }
    FoniaSheet(isPresented = pending != null, onDismiss = { pending = null }) {
        AdultGateScreen(
            gate = app.gate,
            onPass = {
                val step = pending
                pending = null
                step?.run?.invoke()
            },
            onClose = { pending = null },
        )
    }
    val current by rememberUpdatedState(app)
    return remember { { step -> if (current.needsAdultGate) pending = GatedStep(step) else step() } }
}

/** The check as a page of its own, for a single step: sharing, leaving the app, a child's notes. */
@Composable
fun AdultGateScreen(gate: AdultGate, onPass: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.paper)) {
        SystemNavigationBar("Pentru adult", onBack = onClose)
        AdultGateBody(gate, onPass, Modifier.weight(1f))
    }
}

/**
 * The check itself (iOS `AdultGateView`): a multiplication and four answers to tap. A wrong answer shakes the
 * answers and brings a new question; a second wrong one in a row also rests the answers for a few seconds; nothing
 * says "wrong". A page that is all for adults (Setări) shows it in place of its content while [needsAdultGate];
 * [AdultGate.isOpen] then brings the content back. [lead] is the line under the lock.
 */
@Composable
fun AdultGateBody(gate: AdultGate, onPass: () -> Unit, modifier: Modifier = Modifier, lead: String = "Doar pentru adulți") {
    Column(modifier.fillMaxWidth().background(Palette.paper)) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AdultGateView(gate, lead, onPass, Modifier.widthIn(max = 360.dp).fillMaxWidth())
        }
    }
}

@Composable
private fun AdultGateView(gate: AdultGate, lead: String, onPass: () -> Unit, modifier: Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHapticFeedback.current
    // Grows with each wrong answer, so the answers shake once per mistake.
    var misses by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<Int?>(null) }
    val shake = remember { Animatable(0f) }

    // The rest note counts down; the answers come back by themselves when it ends.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val restsUntil = gate.restsUntil
    LaunchedEffect(restsUntil) {
        now = System.currentTimeMillis()
        while (restsUntil != null && now < restsUntil) {
            delay(250)
            now = System.currentTimeMillis()
        }
    }
    val resting = gate.isResting(now)

    LaunchedEffect(misses) {
        if (misses == 0) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.Reject)
        if (reduceMotion) return@LaunchedEffect
        shake.snapTo(0f)
        shake.animateTo(0f, keyframes {
            durationMillis = 360
            -12f at 45
            12f at 105
            -9f at 165
            9f at 225
            -4f at 285
            4f at 330
        })
    }

    fun pick(value: Int) {
        picked = value
        if (gate.answer(value)) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onPass()
        } else {
            now = System.currentTimeMillis()
            misses += 1
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(56.dp).background(Palette.accentSoft, CircleShape).clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                FoniaIconView(FoniaIcon.lock, size = 26f, tint = Palette.accentText)
            }
            FoniaText(lead, Modifier.semantics { heading() }, style = fHeadline, color = Palette.ink, textAlign = TextAlign.Center)
        }
        // The question and the line above it are read together, again after a wrong answer (iOS announces it).
        Column(
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Crossfade(gate.isRetry, label = "gateLine") { retry ->
                FoniaText(if (retry) "Altă întrebare. Alege răspunsul:" else "Alege răspunsul corect:", style = fBody, color = Palette.inkSoft, textAlign = TextAlign.Center)
            }
            AnimatedContent(
                targetState = gate.question.text to misses,
                transitionSpec = {
                    if (reduceMotion) {
                        fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                    } else {
                        val spring = spring<IntOffset>(dampingRatio = 0.8f, stiffness = 500f)
                        (slideInHorizontally(spring) { it } + fadeIn()) togetherWith (slideOutHorizontally(spring) { -it } + fadeOut())
                    }
                },
                label = "gateQuestion",
            ) { (text, _) ->
                FoniaText(
                    text,
                    Modifier.semantics { contentDescription = "Cât face $text?"; testTag = "adultGatePrompt" },
                    style = ui(44, UIWeight.bold, TextRole.largeTitle).monospacedDigit(),
                    color = Palette.ink,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationX = shake.value.dp.toPx() }
                    .alpha(if (resting) 0.45f else 1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (row in gate.question.choices.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (value in row) {
                            StyledButton(
                                onClick = { pick(value) },
                                style = TileButtonStyle(face = if (picked == value && gate.isOpen) Palette.accentSoft else Palette.sheet),
                                modifier = Modifier.weight(1f).semantics { contentDescription = "$value" },
                                enabled = !resting,
                            ) {
                                Box(Modifier.fillMaxWidth().heightIn(min = 72.dp), contentAlignment = Alignment.Center) {
                                    FoniaText("$value", Modifier.clearAndSetSemantics {}, style = ui(30, UIWeight.bold, TextRole.title).monospacedDigit(), color = Palette.ink)
                                }
                            }
                        }
                    }
                }
            }
            val left = restsUntil?.let { ceil((it - now) / 1000.0).toInt() }?.coerceAtLeast(0) ?: 0
            Row(
                Modifier
                    .alpha(if (resting) 1f else 0f)
                    .then(if (resting) Modifier.semantics(mergeDescendants = true) {} else Modifier.clearAndSetSemantics {}),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Only while resting: a spinner kept under a zero alpha would run forever.
                if (resting) RestSpinner(reduceMotion) else Box(Modifier.size(16.dp))
                FoniaText("O clipă de pauză… $left", style = fCaption.monospacedDigit(), color = Palette.inkSoft)
            }
        }
    }
}

/** `ProgressView().controlSize(.small)`: a short arc going round. */
@Composable
private fun RestSpinner(reduceMotion: Boolean) {
    val color = Palette.inkSoft
    val turn = if (reduceMotion) {
        0f
    } else {
        val spin = rememberInfiniteTransition(label = "rest")
        spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "restTurn").value
    }
    Canvas(Modifier.size(16.dp)) {
        rotate(turn) {
            val width = 2.dp.toPx()
            drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, style = Stroke(width))
            drawArc(color, -90f, 100f, false, style = Stroke(width, cap = StrokeCap.Round))
        }
    }
}
