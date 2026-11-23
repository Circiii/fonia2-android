package tech.fonia.app.adult

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tech.fonia.app.text.T
import tech.fonia.app.ui.FoniMascot
import tech.fonia.app.ui.FoniPose
import tech.fonia.app.ui.SpeechBubble
import tech.fonia.designsystem.components.ContinueButton
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaCorner
import tech.fonia.designsystem.tokens.FoniaSpace

/**
 * `AdultGate` (§9): hold 3 s, then write the number given in words. A child who taps the corner sees Foni say
 * „Cheamă un adult!”. [autoPass] (the demo film only) fills the gate by itself.
 */
@Composable
fun AdultGateScreen(gate: AdultGate, reduceMotion: Boolean, onPass: () -> Unit, onBack: () -> Unit, autoPass: Boolean = false, title: String = T.gateTitle) {
    var held by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var pressing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var number by remember { mutableIntStateOf(gate.number) }

    LaunchedEffect(pressing) {
        if (!pressing) { if (!held) progress = 0f; return@LaunchedEffect }
        val start = withFrameMillis { it }
        while (pressing && !held) {
            val now = withFrameMillis { it }
            progress = ((now - start).toFloat() / AdultGate.HOLD_MILLIS).coerceIn(0f, 1f)
            if (gate.holdDone(now - start)) held = true
        }
    }
    LaunchedEffect(autoPass) {
        if (!autoPass) return@LaunchedEffect
        delay(600); pressing = true; delay(AdultGate.HOLD_MILLIS + 150); pressing = false
        delay(500)
        for (ch in gate.number.toString()) { input += ch; delay(350) }
        delay(400)
        if (gate.check(input)) onPass()
    }

    AdultPage(title, onBack, reduceMotion) {
        val c = FoniaTheme.colors
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FoniaSpace.xs)) {
            tech.fonia.app.ui.Foni(tech.fonia.app.ui.FoniMood.Unsure, size = 56.dp)
            SpeechBubble(T.gateKid)
        }
        if (!held) {
            AdultCard {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .semantics { contentDescription = T.gateHoldA11y }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown()
                                pressing = true
                                do {
                                    val e = awaitPointerEvent()
                                } while (e.changes.any { it.pressed })
                                pressing = false
                            }
                        }
                        .drawBehind {
                            val w = 8.dp.toPx()
                            val d = size.height - w - 8.dp.toPx()
                            val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
                            drawArc(c.fill, 0f, 360f, false, tl, Size(d, d), style = Stroke(w))
                            if (progress > 0f) drawArc(c.accent, -90f, 360f * progress, false, tl, Size(d, d), style = Stroke(w, cap = StrokeCap.Round))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("3", style = FoniaTheme.type.counter.copy(color = c.accentDeep))
                }
                BasicText(T.gateHold, style = FoniaTheme.type.headline.copy(color = c.ink, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
            }
        } else {
            AdultCard {
                AdultText(T.gateWrite, secondary = true)
                BasicText(RomanianNumbers.words(number), style = FoniaTheme.type.title2.copy(color = c.ink))
                Box(
                    Modifier.fillMaxWidth().height(56.dp).background(c.fill, RoundedCornerShape(FoniaCorner.field)).padding(horizontal = FoniaSpace.m),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicText(input.ifEmpty { "··" }, style = FoniaTheme.type.counter.copy(color = if (input.isEmpty()) c.inkSecondary else c.ink))
                }
                if (wrong) AdultCaption(T.gateWrong)
            }
            Keypad(
                onDigit = { if (input.length < 2) { input += it; wrong = false } },
                onDelete = { input = input.dropLast(1) },
                onOk = {
                    if (gate.check(input)) onPass() else { wrong = true; input = ""; number = gate.number }
                },
            )
        }
    }
}

@Composable
internal fun Keypad(onDigit: (Char) -> Unit, onDelete: () -> Unit, onOk: () -> Unit, okLabel: String = T.gateOk) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(FoniaSpace.xs)) {
        for (r in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(FoniaSpace.xs)) {
                for (d in r) Key(d.toString(), Modifier.weight(1f)) { onDigit(d) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(FoniaSpace.xs)) {
            Key(T.gateDelete, Modifier.weight(1f), small = true, onClick = onDelete)
            Key("0", Modifier.weight(1f)) { onDigit('0') }
            Key(okLabel, Modifier.weight(1f), small = true, accent = true, onClick = onOk)
        }
    }
}

@Composable
private fun Key(label: String, modifier: Modifier, small: Boolean = false, accent: Boolean = false, onClick: () -> Unit) {
    val c = FoniaTheme.colors
    Box(
        modifier
            .height(56.dp)
            .background(if (accent) c.accent else c.surface, RoundedCornerShape(FoniaCorner.chip))
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = (if (small) FoniaTheme.type.headline else FoniaTheme.type.title2).copy(color = if (accent) c.surface else c.ink))
    }
}
