package tech.fonia.features.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.flow.drop
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.ContinuousTopRoundedRectangle
import tech.fonia.design.EaseInOutCurve
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.UIWeight
import tech.fonia.design.swiftSpring
import tech.fonia.design.ui
import kotlin.math.roundToInt

// SwiftUI's presentations: `.sheet` with detents, `.fullScreenCover`, `.alert` and `.confirmationDialog`. Sheets,
// alerts and dialogs are windows of their own (a Compose `Dialog`), so they sit over everything, the tab bar and the
// covers included, and the back gesture closes the top one first. Covers stay in the screen's own tree.

/** The standard iOS colours the system pieces use: label, secondary label, separator, fills, destructive red. */
internal object SystemColors {
    val label = Color(0xFF000000)
    val secondaryLabel = Color(0x993C3C43)
    val tertiaryLabel = Color(0x4D3C3C43)
    val separator = Color(0x4A3C3C43)
    val fill = Color(0x29787880)
    val red = Color(0xFFFF3B30)
    val secondarySystemBackground = Color(0xFFF2F2F7)
    val systemGray = Color(0xFF8E8E93)
}

/** `presentationDetents`: half the screen, or nearly all of it. */
@Suppress("EnumEntryName")
enum class SheetDetent { medium, large }

private enum class SheetValue { Hidden, Medium, Large }

private val SheetDetent.value: SheetValue get() = if (this == SheetDetent.medium) SheetValue.Medium else SheetValue.Large

/** The springy rise of a sheet. */
private val sheetSpring = swiftSpring<Float>(0.42, 0.0)

/** Makes a `Dialog` window a clear, full-screen layer with no animation of its own, and dark icons on its bars. */
@Composable
private fun ClearDialogWindow(dim: Float = 0f) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    SideEffect {
        if (window != null) {
            window.setDimAmount(dim)
            window.setWindowAnimations(0)
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = true
            controller.isAppearanceLightNavigationBars = true
        }
    }
}

/**
 * `.sheet(isPresented:)`. The sheet rises from the bottom, leaves a gap at the top on the large detent, and goes away
 * when it's dragged down, when the dimmed area is tapped or with the back gesture; each of those calls [onDismiss],
 * and the caller then sets [isPresented] to false, which plays the way down. [detents] start at the first one;
 * a scrolling sheet moves between them before its content scrolls, as on iOS.
 */
@Composable
fun FoniaSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    detents: List<SheetDetent> = listOf(SheetDetent.large),
    showsDragIndicator: Boolean = false,
    background: Color = Palette.paper,
    content: @Composable () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(isPresented) { if (isPresented) shown = true }
    if (!shown) return
    val dismiss by rememberUpdatedState(onDismiss)
    val presented by rememberUpdatedState(isPresented)
    Dialog(
        onDismissRequest = { dismiss() },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        ClearDialogWindow()
        BackHandler { dismiss() }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val full = constraints.maxHeight.toFloat()
            val gap = WindowInsets.statusBars.getTop(density) + with(density) { 10.dp.toPx() }
            val sheetHeight = (full - gap).coerceAtLeast(1f)
            val state = remember { AnchoredDraggableState(SheetValue.Hidden) }
            LaunchedEffect(sheetHeight, detents) {
                state.updateAnchors(
                    DraggableAnchors {
                        SheetValue.Hidden at sheetHeight
                        if (SheetDetent.medium in detents) SheetValue.Medium at (sheetHeight - full * 0.5f).coerceAtLeast(0f)
                        if (SheetDetent.large in detents) SheetValue.Large at 0f
                    },
                )
            }
            LaunchedEffect(isPresented) {
                if (isPresented) {
                    state.animateTo(detents.first().value, sheetSpring)
                } else {
                    state.animateTo(SheetValue.Hidden, sheetSpring)
                    shown = false
                }
            }
            LaunchedEffect(state) {
                // Dragged all the way down: the sheet asks to go.
                snapshotFlow { state.settledValue }.drop(1).collect { if (it == SheetValue.Hidden && presented) dismiss() }
            }
            val offset = state.offset.takeUnless { it.isNaN() } ?: sheetHeight
            val shown01 = (1f - offset / sheetHeight).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f * shown01))
                    .pointerInput(Unit) { detectTapAnywhere { if (presented) dismiss() } },
            )
            val scrolls = remember(state) { sheetScrollConnection(state) }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(with(density) { (sheetHeight - offset).coerceAtLeast(0f).toDp() })
                    .clip(ContinuousTopRoundedRectangle(28.dp))
                    .background(background)
                    // The sheet starts below the status bar, so the pages in it don't pad for it again.
                    .consumeWindowInsets(WindowInsets.statusBars)
                    .anchoredDraggable(state, Orientation.Vertical)
                    .nestedScroll(scrolls)
                    .pointerInput(Unit) { detectTapAnywhere { } },
            ) {
                content()
                if (showsDragIndicator) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 5.dp)
                            .size(36.dp, 5.dp)
                            .background(Color(0xFFC7C7CC), RoundedCornerShape(50)),
                    )
                }
            }
        }
    }
}

/** Swallows taps where nothing else takes them, so they don't fall through. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapAnywhere(onTap: () -> kotlin.Unit) =
    detectTapGestures(onTap = { onTap() })

/** Drags the sheet up before a list scrolls, and down once the list is back at its top. */
private fun sheetScrollConnection(state: AnchoredDraggableState<SheetValue>) = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val delta = available.y
        return if (delta < 0 && source == NestedScrollSource.UserInput) Offset(0f, state.dispatchRawDelta(delta)) else Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput) Offset(0f, state.dispatchRawDelta(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        val velocity = available.y
        val offset = state.offset
        val top = state.anchors.minPosition()
        return if (velocity < 0 && !offset.isNaN() && offset > top) {
            state.settleBy(velocity)
            available
        } else {
            Velocity.Zero
        }
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        state.settleBy(available.y)
        return available
    }
}

/**
 * Settles on the next anchor in the fling's direction, or the nearest one for a slow release. `settle(velocity)` is the
 * deprecated overload that throws when the state was built without thresholds (QA 2026-11-21: flinging a settings sheet
 * crashed the app).
 */
private suspend fun AnchoredDraggableState<SheetValue>.settleBy(velocity: Float) {
    val at = offset
    if (at.isNaN()) return
    val target = when {
        velocity > 600f -> anchors.closestAnchor(at, searchUpwards = true)
        velocity < -600f -> anchors.closestAnchor(at, searchUpwards = false)
        else -> anchors.closestAnchor(at)
    } ?: return
    animateTo(target, sheetSpring)
}

/**
 * `.fullScreenCover`: rises from the bottom over everything in the screen that holds it. [animatesOut] false
 * removes it at once (`Transaction.disablesAnimations`). [onDismissed] runs once it has gone, as `onDismiss` does.
 * Touches never reach what is under it.
 */
@Composable
fun FullScreenCover(
    isPresented: Boolean,
    modifier: Modifier = Modifier,
    animatesOut: Boolean = true,
    onDismissed: () -> kotlin.Unit = {},
    content: @Composable () -> kotlin.Unit,
) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = isPresented
    val dismissed by rememberUpdatedState(onDismissed)
    var wasShown by remember { mutableStateOf(false) }
    LaunchedEffect(isPresented) { if (isPresented) wasShown = true }
    LaunchedEffect(visibility.isIdle, visibility.currentState) {
        if (visibility.isIdle && !visibility.currentState && wasShown) {
            wasShown = false
            dismissed()
        }
    }
    val rise = tween<androidx.compose.ui.unit.IntOffset>(400, easing = EaseInOutCurve)
    AnimatedVisibility(
        visibility,
        modifier = modifier.fillMaxSize(),
        enter = slideInVertically(rise) { it },
        exit = if (animatesOut) slideOutVertically(rise) { it } else ExitTransition.None,
    ) {
        Box(Modifier.fillMaxSize().pointerInput(Unit) {}) { content() }
    }
}

/** What a button of an alert or a confirmation dialog does to the look. */
enum class ActionRole { Default, Cancel, Destructive }

class DialogAction(val title: String, val role: ActionRole = ActionRole.Default, val action: () -> kotlin.Unit = {})

/** `.alert(_:isPresented:actions:message:)`: a card in the middle; any button closes it, then does its job. */
@Composable
fun FoniaAlert(
    isPresented: Boolean,
    onDismiss: () -> kotlin.Unit,
    title: String,
    message: String? = null,
    actions: List<DialogAction>,
) {
    if (!isPresented) return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ClearDialogWindow(dim = 0.2f)
        val appear = remember { MutableTransitionState(false) }.apply { targetState = true }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AnimatedVisibility(appear, enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 1.08f)) {
                Column(
                    Modifier
                        .width(300.dp)
                        .background(Palette.sheet, ContinuousRoundedRectangle(34.dp))
                        .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FoniaText(title, Modifier.semantics { heading() }, style = ui(17, UIWeight.semibold), color = SystemColors.label, textAlign = TextAlign.Center)
                    if (message != null) {
                        FoniaText(message, style = ui(15), color = SystemColors.label, textAlign = TextAlign.Center)
                    }
                    // The cancel button goes first, on the left, as UIKit lays out two buttons.
                    val ordered = actions.filter { it.role == ActionRole.Cancel } + actions.filter { it.role != ActionRole.Cancel }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (item in ordered) {
                            CapsuleButton(item, Modifier.weight(1f)) {
                                onDismiss()
                                item.action()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CapsuleButton(item: DialogAction, modifier: Modifier, onClick: () -> kotlin.Unit) {
    val color = when (item.role) {
        ActionRole.Cancel -> SystemColors.label
        ActionRole.Destructive -> Palette.ink // never red (owner): "stop" is said calmly
        ActionRole.Default -> Palette.accentText
    }
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(SystemColors.fill.copy(alpha = 0.12f))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        FoniaText(item.title, style = ui(17, if (item.role == ActionRole.Cancel) UIWeight.medium else UIWeight.semibold), color = color, maxLines = 1, minimumScaleFactor = 0.8f)
    }
}

/**
 * `.confirmationDialog(_:isPresented:titleVisibility: .visible)`: the action sheet from the bottom, with its title,
 * message and buttons on one card and the cancel button on its own card under it.
 */
@Composable
fun FoniaConfirmationDialog(
    isPresented: Boolean,
    onDismiss: () -> kotlin.Unit,
    title: String,
    message: String? = null,
    actions: List<DialogAction>,
) {
    if (!isPresented) return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ClearDialogWindow(dim = 0.2f)
        val appear = remember { MutableTransitionState(false) }.apply { targetState = true }
        Box(
            Modifier
                .fillMaxSize()
                // A tap outside cancels. A gesture detector, not `clickable`, so the dialog isn't read as one button.
                .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(appear, enter = slideInVertically(tween(300, easing = EaseInOutCurve)) { it }) {
                Column(
                    Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        // Taps on the card stay on the card. A gesture detector, not `clickable`, so the title and the
                        // message stay plain texts for accessibility instead of one button.
                        .pointerInput(Unit) { detectTapGestures { } },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.fillMaxWidth().background(Palette.sheet, ContinuousRoundedRectangle(16.dp))) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FoniaText(title, Modifier.semantics { heading() }, style = ui(13, UIWeight.semibold), color = SystemColors.secondaryLabel, textAlign = TextAlign.Center)
                            if (message != null) FoniaText(message, style = ui(13), color = SystemColors.secondaryLabel, textAlign = TextAlign.Center)
                        }
                        for (item in actions.filter { it.role != ActionRole.Cancel }) {
                            Box(Modifier.fillMaxWidth().height(0.5.dp).background(SystemColors.separator))
                            SheetAction(item) {
                                onDismiss()
                                item.action()
                            }
                        }
                    }
                    for (item in actions.filter { it.role == ActionRole.Cancel }) {
                        Box(Modifier.fillMaxWidth().background(Palette.sheet, ContinuousRoundedRectangle(16.dp))) {
                            SheetAction(item) {
                                onDismiss()
                                item.action()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetAction(item: DialogAction, onClick: () -> kotlin.Unit) {
    val color = if (item.role == ActionRole.Destructive) Palette.ink else Palette.accentText
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        FoniaText(item.title, style = ui(19, if (item.role == ActionRole.Cancel) UIWeight.semibold else UIWeight.regular), color = color)
    }
}
