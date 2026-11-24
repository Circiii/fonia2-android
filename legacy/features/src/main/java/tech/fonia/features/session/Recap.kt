package tech.fonia.features.session

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.fonia.design.CTALabel
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.Field
import tech.fonia.design.Foni
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniMood
import tech.fonia.design.FoniaText
import tech.fonia.design.LocalReduceMotion
import tech.fonia.design.Palette
import tech.fonia.design.RisingPanel
import tech.fonia.design.SceneBackdrop
import tech.fonia.design.SceneName
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.monospacedDigit
import tech.fonia.design.swiftSpring
import tech.fonia.design.ui
import tech.fonia.designsystem.letter.StickerText
import tech.fonia.designsystem.motion.BurstStyle
import tech.fonia.designsystem.motion.CelebrationBurst
import tech.fonia.designsystem.motion.rememberFoniaHaptics
import tech.fonia.designsystem.motion.staggeredEntrance
import tech.fonia.domain.Session
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.field
import tech.fonia.features.app.isChild
import tech.fonia.features.app.shortLetter
import tech.fonia.features.app.title
import java.util.Locale

/**
 * The end of a session, in his card style: the sound, how many came out well (a counter that rolls), the longest held
 * sound, what comes next, and exactly two choices: „Încă una” and „Gata”.
 */
@Composable
fun RecapScreen(run: SessionRun, app: AppModel, next: String?, onMore: () -> Unit, onDone: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val settled = remember { Animatable(if (reduceMotion) 1f else 0f) }
    var hasSettled by remember { mutableStateOf(false) }
    val sound = run.ticket.sound?.let { app.content.sound(it) }
    LaunchedEffect(Unit) {
        hasSettled = true
        if (!reduceMotion) {
            delay(100)
            settled.animateTo(1f, swiftSpring(0.55, 0.3))
        }
    }
    LaunchedEffect(Unit) {
        if (run.profile.isChild && run.attempts > 0) app.audio.sayIfRecorded(Session.goodbye)
    }
    Box(Modifier.fillMaxSize()) {
        SceneBackdrop(SceneName.lesson)
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Spacer(Modifier.weight(1f).heightIn(min = 12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Foni(
                        Modifier
                            .height(190.dp)
                            .graphicsLayer {
                                val v = settled.value
                                scaleX = 0.8f + 0.2f * v
                                scaleY = 0.8f + 0.2f * v
                                transformOrigin = TransformOrigin(0.5f, 1f)
                                alpha = v.coerceIn(0f, 1f)
                            },
                        mood = if (run.goodAttempts > 0) FoniMood.happy else FoniMood.idle,
                        reactsToTap = true,
                    )
                    if (sound != null) {
                        StickerText(
                            sound.shortLetter,
                            stickerFor(sound.id.family.field),
                            Modifier.padding(bottom = 24.dp).staggeredEntrance(0),
                            fontSize = 84.sp,
                            weight = 900f,
                        )
                    }
                }
                if (run.attempts > 0) {
                    CelebrationBurst(
                        trigger = if (hasSettled) run.sessionID else null,
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                        style = BurstStyle.Confetti,
                        origin = androidx.compose.ui.geometry.Offset(0.5f, 0.55f),
                        power = 1.2f,
                    )
                }
            }
            RisingPanel(color = Palette.sheet) {
                Column(
                    Modifier.padding(horizontal = 18.dp).padding(top = 20.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FoniaText(
                        if (run.attempts == 0) "Ne vedem mâine" else if (run.goodAttempts > 0) "Bravo!" else "Ai exersat!",
                        style = ui(30, UIWeight.black, TextRole.title),
                        color = Palette.ink,
                        maxLines = 1,
                    )
                    // No "0 din N": a run with nothing right yet shows no count, only what comes next. A child sees no
                    // count at all, only the one word above and the longest sound; the count is for adults.
                    val showsCount = RecapRules.showsCount(run.profile.isChild, run.attempts, run.goodAttempts)
                    val showsHold = run.attempts > 0 && run.goodAttempts > 0 && run.longestHold >= 0.5
                    if (showsCount || showsHold) {
                        Row(Modifier.fillMaxWidth().staggeredEntrance(1), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (showsCount) RecapTile(Modifier.weight(1f), "au ieșit bine", "din ${run.attempts}") { RollingCount(run.goodAttempts) }
                            if (showsHold) {
                                RecapTile(Modifier.weight(1f), "cel mai lung sunet", null) {
                                    FoniaText(
                                        String.format(Locale.ROOT, "%.1f s", run.longestHold).replace('.', ','),
                                        style = ui(30, UIWeight.black, TextRole.title).monospacedDigit(),
                                        color = Palette.ink,
                                    )
                                }
                            }
                        }
                    }
                    // His S dots on the axis, the first time and today, and how many sat in the zone.
                    if (sound != null && run.attempts > 0) {
                        SoundProgressCard(sound.id, app, run.profile.id, Modifier.staggeredEntrance(2), showsCount = !run.profile.isChild)
                    }
                    val opened = run.movedUpTo
                    val line = when {
                        opened != null && sound != null -> "Ai deschis: ${opened.title(sound.shortLetter)}"
                        run.needsAdultNext != null -> RecapRules.WAITS_FOR_ADULT
                        next != null -> "Urmează: $next"
                        else -> null
                    }
                    if (line != null) {
                        FoniaText(line, Modifier.staggeredEntrance(2), style = ui(17, UIWeight.semibold, TextRole.body), color = Palette.ink, textAlign = TextAlign.Center)
                    }
                    Row(Modifier.fillMaxWidth().staggeredEntrance(3), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StyledButton(
                            onClick = onDone,
                            style = FoniButtonStyle(field = Field.paper, size = FoniButtonStyle.Size.regular, kind = FoniButtonStyle.Kind.secondary),
                            modifier = Modifier.weight(1f),
                        ) { CTALabel("Gata") }
                        StyledButton(
                            onClick = onMore,
                            style = FoniButtonStyle(field = Field.paper, size = FoniButtonStyle.Size.regular),
                            modifier = Modifier.weight(1f),
                        ) { CTALabel("Încă una") }
                    }
                }
            }
        }
    }
}

/** What the end of a session shows, apart from the screen. */
object RecapRules {
    /** "N au ieșit bine din M": for adults only, and never "0 din M". */
    fun showsCount(isChild: Boolean, attempts: Int, good: Int): Boolean = !isChild && attempts > 0 && good > 0

    /** The plain sound came out well enough; the lock waits for an adult to listen. */
    const val WAITS_FOR_ADULT = "Un adult te ascultă, apoi mergi mai departe."
}

@Composable
private fun RecapTile(modifier: Modifier, label: String, under: String?, value: @Composable () -> Unit) {
    Column(
        modifier
            .background(Palette.accentSoft.copy(alpha = 0.6f), ContinuousRoundedRectangle(20.dp))
            .padding(vertical = 10.dp, horizontal = 8.dp)
            .clearAndSetSemantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            value()
            if (under != null) FoniaText(under, Modifier.padding(bottom = 5.dp), style = ui(15, UIWeight.semibold, TextRole.body), color = Palette.inkSoft)
        }
        FoniaText(label, style = ui(14, UIWeight.medium, TextRole.footnote), color = Palette.inkSoft, maxLines = 1)
    }
}

/** A number that rolls up from zero, with a light tick per step; still under reduce motion. */
@Composable
fun RollingCount(value: Int) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberFoniaHaptics()
    var shown by remember { mutableIntStateOf(if (reduceMotion) value else 0) }
    val bump = remember { Animatable(1f) }
    LaunchedEffect(value) {
        if (reduceMotion) {
            shown = value
            return@LaunchedEffect
        }
        delay(450)
        val step = (700L / maxOf(value, 1)).coerceIn(40L, 140L)
        while (shown < value) {
            shown += 1
            haptics.tick()
            launch { bump.snapTo(1.18f); bump.animateTo(1f, tween(120)) }
            delay(step)
        }
    }
    FoniaText(
        "$shown",
        Modifier.graphicsLayer { scaleX = bump.value; scaleY = bump.value },
        style = ui(34, UIWeight.black, TextRole.title).monospacedDigit(),
        color = Palette.ink,
    )
}
