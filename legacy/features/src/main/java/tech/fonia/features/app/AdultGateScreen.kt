package tech.fonia.features.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.TileButtonStyle
import tech.fonia.design.UIWeight
import tech.fonia.design.fBody
import tech.fonia.design.fHeadline
import tech.fonia.design.fPrompt
import tech.fonia.design.monospacedDigit
import tech.fonia.design.ui

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
 * The check (the new app's `AdultGateScreen`, in this app's look): hold three seconds, then write with digits the
 * number given in words. A wrong answer brings a new number; nothing says "wrong". A page that is all for adults
 * (Setări) shows it in place of its content while [needsAdultGate]; [AdultGate.isOpen] then brings the content back.
 */
@Composable
fun AdultGateBody(gate: AdultGate, onPass: () -> Unit, modifier: Modifier = Modifier) {
    var held by remember { mutableStateOf(false) }
    var pressing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var typed by remember { mutableStateOf("") }
    var isRetry by remember { mutableStateOf(false) }

    LaunchedEffect(pressing) {
        if (!pressing) {
            if (!held) progress = 0f
            return@LaunchedEffect
        }
        val start = withFrameMillis { it }
        while (pressing && !held) {
            val now = withFrameMillis { it }
            progress = ((now - start).toFloat() / AdultGate.HOLD_MILLIS).coerceIn(0f, 1f)
            if (gate.holdDone(now - start)) held = true
        }
    }

    fun type(digit: Char) {
        if (typed.length >= 2) return
        typed += digit
        if (typed.length < 2) return
        if (gate.check(typed)) {
            onPass()
        } else {
            typed = ""
            isRetry = true
        }
    }

    Column(modifier.fillMaxWidth().background(Palette.paper)) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FoniaText("Doar pentru adulți", Modifier.semantics { heading() }, style = fHeadline, color = Palette.ink, textAlign = TextAlign.Center)
            if (!held) {
                val accent = Palette.accent
                val track = Palette.line
                Box(
                    Modifier
                        .widthIn(max = 360.dp)
                        .fillMaxWidth()
                        .height(140.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Ține apăsat trei secunde pentru zona adultului"
                            // A screen reader can't time a hold: its double tap passes this step, and the number still guards.
                            onClick(label = "Mai departe") { held = true; true }
                        }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown()
                                pressing = true
                                do {
                                    val event = awaitPointerEvent()
                                } while (event.changes.any { it.pressed })
                                pressing = false
                            }
                        }
                        .drawBehind {
                            val width = 8.dp.toPx()
                            val diameter = size.height - width - 8.dp.toPx()
                            val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                            drawArc(track, 0f, 360f, false, topLeft, Size(diameter, diameter), style = Stroke(width))
                            if (progress > 0f) {
                                drawArc(accent, -90f, 360f * progress, false, topLeft, Size(diameter, diameter), style = Stroke(width, cap = StrokeCap.Round))
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // The ring's own label says it; the drawn 3 isn't read a second time.
                    FoniaText("3", Modifier.clearAndSetSemantics {}, style = ui(34, UIWeight.black, TextRole.largeTitle).monospacedDigit(), color = Palette.accentText)
                }
                FoniaText("Ține apăsat 3 secunde", style = fBody, color = Palette.inkSoft, textAlign = TextAlign.Center)
            } else {
                Column(
                    Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FoniaText(if (isRetry) "Un număr nou. Scrie-l cu cifre:" else "Scrie cu cifre:", style = fBody, color = Palette.inkSoft)
                    FoniaText(gate.words, style = fPrompt, color = Palette.ink, textAlign = TextAlign.Center)
                }
                Row(
                    Modifier.clearAndSetSemantics {
                        contentDescription = if (typed.isEmpty()) "Nicio cifră scrisă" else "Ai scris ${typed.toList().joinToString(" ")}"
                    },
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (index in 0 until 2) {
                        val shape = ContinuousRoundedRectangle(14.dp)
                        Box(
                            Modifier
                                .size(width = 52.dp, height = 60.dp)
                                .background(Palette.sheet, shape)
                                .border(2.dp, if (index == typed.length) Palette.ink else Palette.line, shape),
                            contentAlignment = Alignment.Center,
                        ) {
                            FoniaText(typed.getOrNull(index)?.toString() ?: "", style = ui(28, UIWeight.bold, TextRole.title).monospacedDigit(), color = Palette.ink)
                        }
                    }
                }
                Keypad(
                    canDelete = typed.isNotEmpty(),
                    onDigit = ::type,
                    onDelete = { typed = typed.dropLast(1) },
                )
            }
        }
    }
}

@Composable
private fun Keypad(canDelete: Boolean, onDigit: (Char) -> Unit, onDelete: () -> Unit) {
    val rows = listOf("123", "456", "789")
    Column(Modifier.widthIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (digit in row) Key(digit, Modifier.weight(1f)) { onDigit(digit) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).heightIn(min = 58.dp))
            Key('0', Modifier.weight(1f)) { onDigit('0') }
            StyledButton(
                onClick = onDelete,
                style = TileButtonStyle(face = Palette.cloud),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Șterge o cifră" },
                enabled = canDelete,
            ) {
                Box(Modifier.fillMaxWidth().heightIn(min = 58.dp), contentAlignment = Alignment.Center) {
                    FoniaIconView(FoniaIcon.back, size = 20f, tint = Palette.ink)
                }
            }
        }
    }
}

@Composable
private fun Key(digit: Char, modifier: Modifier, onClick: () -> Unit) {
    StyledButton(onClick = onClick, style = TileButtonStyle(), modifier = modifier) {
        Box(Modifier.fillMaxWidth().heightIn(min = 58.dp), contentAlignment = Alignment.Center) {
            FoniaText(digit.toString(), style = ui(26, UIWeight.semibold, TextRole.title2).monospacedDigit(), color = Palette.ink)
        }
    }
}
