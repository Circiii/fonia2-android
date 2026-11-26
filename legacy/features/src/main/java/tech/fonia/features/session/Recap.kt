package tech.fonia.features.session

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import tech.fonia.design.NumericText
import tech.fonia.design.Palette
import tech.fonia.design.PopIn
import tech.fonia.design.RisingPanel
import tech.fonia.design.SceneBackdrop
import tech.fonia.design.SceneName
import tech.fonia.design.Spray
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.WordPicture
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fHeadline
import tech.fonia.design.jump
import tech.fonia.design.monospacedDigit
import tech.fonia.design.shine
import tech.fonia.design.swiftSpring
import tech.fonia.design.ui
import tech.fonia.designsystem.letter.StickerText
import tech.fonia.designsystem.motion.BurstStyle
import tech.fonia.designsystem.motion.CelebrationBurst
import tech.fonia.designsystem.motion.rememberFoniaHaptics
import tech.fonia.designsystem.motion.staggeredEntrance
import tech.fonia.domain.Plural
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
 *
 * It arrives in beats, so it has one peak: 1 Foni, 2 the new star lands (with a success buzz), 3 it is counted and the
 * tiles rise in (a soft tick), 4 a light passes over the last button. With Reduce Motion all of it is there at once.
 */
@Composable
fun RecapScreen(
    run: SessionRun,
    app: AppModel,
    next: String?,
    nextSound: String? = null,
    nextKnown: Boolean = true,
    isDrill: Boolean = false,
    onMore: () -> Unit,
    onDone: () -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberFoniaHaptics()
    val settled = remember { Animatable(if (reduceMotion) 1f else 0f) }
    var hasSettled by remember { mutableStateOf(false) }
    var stage by remember { mutableIntStateOf(if (reduceMotion) 4 else 0) }
    val sound = run.ticket.sound?.let { app.content.sound(it) }
    // A lesson with something done brings a star; one left empty does not.
    val earnsStar = run.attempts > 0
    // Stars after this lesson, one per finished lesson, the same count the progress page shows.
    var stars by remember { mutableStateOf<Int?>(null) }
    val milestone = stars?.let(RecapRules::isMilestone) ?: false
    LaunchedEffect(Unit) {
        hasSettled = true
        if (reduceMotion) return@LaunchedEffect
        launch {
            delay(100)
            settled.animateTo(1f, swiftSpring(0.55, 0.3))
        }
        stage = 1
        // The session-end chime plays as the screen opens; the star lands on its rising part.
        delay(520)
        stage = 2
        delay(480)
        stage = 3
        delay(420)
        stage = 4
    }
    LaunchedEffect(earnsStar) {
        if (!earnsStar) return@LaunchedEffect
        val sessions = try { app.repository.sessions(run.profile.id) } catch (e: Exception) { emptyList() }
        // This lesson's own save may still be on its way, so it counts by its id too.
        stars = sessions.count { it.endedAt != null || it.id == run.sessionID }
    }
    // The peak is felt as well as seen: a success buzz when the star lands, a soft tick as it is counted.
    LaunchedEffect(stage) {
        if (!earnsStar || reduceMotion) return@LaunchedEffect
        when (stage) {
            2 -> haptics.success()
            3 -> haptics.tick()
        }
    }
    val risen by animateFloatAsState(if (stage >= 3) 1f else 0f, swiftSpring(0.4, 0.3), label = "tiles")
    // A child hears the goodbye once the lesson is over for today: nothing more is offered after it.
    val saysGoodbye = run.profile.isChild && nextKnown && next == null && !isDrill && run.needsAdultNext == null
    LaunchedEffect(saysGoodbye) {
        if (saysGoodbye) app.audio.sayIfRecorded(Session.goodbye)
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
                            // Foni jumps with the star; twice as high for a round number of stars.
                            .jump(stage >= 2, height = if (milestone) 30.dp else 16.dp, enabled = earnsStar)
                            .graphicsLayer {
                                val v = settled.value
                                scaleX = 0.8f + 0.2f * v
                                scaleY = 0.8f + 0.2f * v
                                transformOrigin = TransformOrigin(0.5f, 1f)
                                alpha = v.coerceIn(0f, 1f)
                            },
                        mood = FoniMood.happy,
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
                if (earnsStar) {
                    CelebrationBurst(
                        trigger = if (hasSettled) run.sessionID else null,
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                        style = BurstStyle.Confetti,
                        origin = androidx.compose.ui.geometry.Offset(0.5f, 0.55f),
                        power = 1.2f,
                    )
                    // A round number of stars gets a second burst as it is counted, so it stands out from the rest.
                    if (milestone) {
                        CelebrationBurst(
                            trigger = if (stage >= 3) run.sessionID else null,
                            modifier = Modifier.fillMaxWidth().height(360.dp),
                            style = BurstStyle.Confetti,
                            origin = androidx.compose.ui.geometry.Offset(0.5f, 0.55f),
                            power = 1.4f,
                        )
                    }
                }
            }
            RisingPanel(color = Palette.sheet) {
                Column(
                    Modifier.padding(horizontal = 18.dp).padding(top = 20.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FoniaText(
                        RecapRules.title(run.attempts, run.goodAttempts),
                        Modifier.graphicsLayer {
                            val v = 0.7f + 0.3f * settled.value
                            scaleX = v
                            scaleY = v
                        },
                        style = ui(30, UIWeight.black, TextRole.title),
                        color = Palette.ink,
                        maxLines = 1,
                    )
                    if (earnsStar) StarRow(stage, stars, milestone)
                    // No "0 din N": a run with nothing right yet shows no count, only what comes next. A child sees no
                    // count at all, only the one word above and the longest sound; the count is for adults.
                    val showsCount = RecapRules.showsCount(run.profile.isChild, run.attempts, run.goodAttempts)
                    val showsHold = run.attempts > 0 && run.goodAttempts > 0 && run.longestHold >= 0.5
                    if (showsCount || showsHold) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    alpha = risen.coerceIn(0f, 1f)
                                    translationY = (1f - risen) * 10.dp.toPx()
                                },
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (showsCount) RecapTile(Modifier.weight(1f), "au ieșit bine", "din ${run.attempts}") { RollingCount(run.goodAttempts, running = stage >= 3) }
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
                        else -> RecapRules.nextLine(next, nextSound)
                    }
                    if (line != null) {
                        FoniaText(line, Modifier.staggeredEntrance(2), style = ui(17, UIWeight.semibold, TextRole.body), color = Palette.ink, textAlign = TextAlign.Center)
                    }
                    // „Încă una” only when another lesson exists; otherwise one button closes the day.
                    if (isDrill || (next != null && run.needsAdultNext == null)) {
                        Row(Modifier.fillMaxWidth().staggeredEntrance(3), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StyledButton(
                                onClick = onDone,
                                style = FoniButtonStyle(field = Field.paper, size = FoniButtonStyle.Size.regular, kind = FoniButtonStyle.Kind.secondary),
                                // A light across the button the lesson ends on, once everything else has arrived.
                                modifier = Modifier.weight(1f).shine(stage >= 4, enabled = earnsStar),
                            ) { CTALabel("Gata") }
                            StyledButton(
                                onClick = onMore,
                                style = FoniButtonStyle(field = Field.paper, size = FoniButtonStyle.Size.regular),
                                modifier = Modifier.weight(1f),
                            ) { CTALabel("Încă una") }
                        }
                    } else {
                        StyledButton(
                            onClick = onDone,
                            style = FoniButtonStyle(field = Field.paper, size = FoniButtonStyle.Size.regular),
                            modifier = Modifier.staggeredEntrance(3).shine(stage >= 4, enabled = earnsStar),
                        ) { CTALabel(run.session.endButton) }
                    }
                }
            }
        }
    }
}

/** What the end of a session shows, apart from the screen. */
object RecapRules {
    /**
     * The end screen only shows for a lesson done to the end. One with nothing to say, a listening lesson or a
     * warm-up, was done all the same, so it gets the same praise as one that came out well.
     */
    fun title(attempts: Int, good: Int): String = if (attempts == 0 || good > 0) "Bravo!" else "Ai exersat!"

    /** What comes next, in one line, naming the sound when the lesson belongs to one. */
    fun nextLine(title: String?, sound: String? = null): String? {
        if (title == null) return null
        if (sound == null) return "Urmează: $title"
        return "Urmează la $sound: $title"
    }

    /** "N au ieșit bine din M": for adults only, and never "0 din M". */
    fun showsCount(isChild: Boolean, attempts: Int, good: Int): Boolean = !isChild && attempts > 0 && good > 0

    /** The plain sound came out well enough; the lock waits for an adult to listen. */
    const val WAITS_FOR_ADULT = "Un adult te ascultă, apoi mergi mai departe."

    private val starMilestones = setOf(5, 10, 25, 50, 75, 100)

    /**
     * The star counts that get a line of their own: the first, then round numbers. Every lesson brings a star, so the
     * line marks how much was done, never how well. Null for any other count, which shows as "N stele".
     */
    fun starLine(total: Int): String? {
        if (total == 1) return "Prima ta stea!"
        if (total !in starMilestones && !(total > 100 && total % 50 == 0)) return null
        return "Ai strâns ${Plural.format(total, Plural.Noun.Stea)}!"
    }

    /** A count with its own line also gets a second burst as it is counted. */
    fun isMilestone(total: Int): Boolean = starLine(total) != null
}

/**
 * The new star lands in its slot with a pop and a spray of small stars, then the count of all stars ticks up by one.
 * The count is of lessons finished, so it grows with trying, never with how well it went.
 */
@Composable
private fun StarRow(stage: Int, stars: Int?, milestone: Boolean) {
    val shown = stars?.let { if (stage >= 3) it else maxOf(0, it - 1) }
    val tint by animateColorAsState(if (stage >= 3 && milestone) Palette.starTint else Palette.inkSoft, swiftSpring(0.4, 0.3), label = "starLine")
    Row(
        Modifier
            .alpha(if (stage >= 1) 1f else 0f)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            PopIn(stage >= 2, ring = Palette.starTint, modifier = Modifier.size(44.dp)) {
                WordPicture("icon-star", Modifier.size(44.dp))
            }
            Spray(stage >= 2, particleSize = 14.dp, modifier = Modifier.size(44.dp)) {
                WordPicture("icon-star", Modifier.size(14.dp))
            }
        }
        Column {
            FoniaText("O stea nouă", style = fHeadline, color = Palette.accentText)
            if (shown != null && shown > 0) {
                NumericText(RecapRules.starLine(shown) ?: Plural.format(shown, Plural.Noun.Stea), style = fBodyStrong, color = tint)
            }
        }
    }
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

/**
 * A number that rolls up from zero, with a light tick per step; still under reduce motion. It waits for [running], so
 * on the end screen it counts once its tiles have risen in.
 */
@Composable
fun RollingCount(value: Int, running: Boolean = true) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberFoniaHaptics()
    var shown by remember { mutableIntStateOf(if (reduceMotion) value else 0) }
    val bump = remember { Animatable(1f) }
    LaunchedEffect(value, running) {
        if (reduceMotion) {
            shown = value
            return@LaunchedEffect
        }
        if (!running) return@LaunchedEffect
        delay(250)
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
