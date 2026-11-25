package tech.fonia.features.speaking.coach

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.fonia.design.CTALabel
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.Field
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.ToolButtonStyle
import tech.fonia.design.UIWeight
import tech.fonia.design.swiftSpring
import tech.fonia.design.ui
import tech.fonia.features.app.HeaderButton
import tech.fonia.speaking.coach.CoachPrompt
import kotlin.math.abs

/** The words for each kind of card and its colours (soft face, deep ink). */
private data class KindLook(val label: String, val face: Color, val ink: Color)

private fun kindLook(kind: String): KindLook = when (kind) {
    "intro" -> KindLook("Despre tine", Palette.coldSoft, Palette.cold)
    "pitch" -> KindLook("Pitch", Palette.tipSoft, Palette.tipDeep)
    "explain" -> KindLook("Explică", Palette.rightSoft, Palette.right)
    "story" -> KindLook("Poveste", Palette.peach, Palette.tipDeep)
    else -> KindLook("Liber", Palette.nightSoft, Palette.nightDeep)
}

/**
 * One screen, almost no words: the card to talk about (swipe for another, or the dice), how long, and „Începe”.
 */
@Composable
internal fun CoachSetup(coach: CoachController, onClose: () -> Unit, onStart: () -> Unit) {
    val prompts = coach.prompts
    val pager = rememberPagerState(initialPage = prompts.indexOf(coach.prompt).coerceAtLeast(0)) { prompts.size }
    val scope = rememberCoroutineScope()
    val push = tech.fonia.features.home.rememberPush()
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { coach.prompt = prompts[it] }
    }
    LaunchedEffect(coach.prompt) {
        val i = prompts.indexOf(coach.prompt)
        if (i >= 0 && i != pager.currentPage) pager.scrollToPage(i)
    }
    Box(Modifier.fillMaxSize().background(Palette.night)) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                HeaderButton(closes = true, onClick = onClose)
                Spacer(Modifier.weight(1f))
                coach.previous?.let { LastTime(it) }
            }
            // Small secondary entries: his gaze calibration, and the older exercises („Tu, fără ăăă”, the minute).
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                StyledButton(
                    onClick = { coach.calibrate(thenStart = false) },
                    style = tech.fonia.design.PressableStyle(),
                ) {
                    FoniaText(if (coach.gaze != null) "Privirea mea ✓" else "Privirea mea", style = ui(15, UIWeight.semibold, TextRole.footnote), color = Palette.nightSoft)
                }
                StyledButton(
                    onClick = { push(tech.fonia.features.app.AppRoute.Speaking) },
                    style = tech.fonia.design.PressableStyle(),
                ) {
                    FoniaText("Alte exerciții", style = ui(15, UIWeight.semibold, TextRole.footnote), color = Palette.nightSoft)
                }
            }
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FoniaText("Vorbit în public", Modifier.semantics { heading() }, style = ui(36, UIWeight.black, TextRole.largeTitle), color = Palette.nightText)
                FoniaText("Vorbești, te vezi, afli pe loc.", style = ui(18, UIWeight.medium, TextRole.body), color = Palette.nightSoft)
            }
            tech.fonia.speaking.coach.weakestArea(coach.history)?.let { area ->
                DrillCard(area, coach.history) {
                    coach.drill = area
                    coach.seconds = 30
                    onStart()
                }
            }
            HorizontalPager(
                pager,
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 34.dp),
                pageSpacing = 14.dp,
                verticalAlignment = Alignment.CenterVertically,
            ) { page ->
                val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                PromptCard(
                    prompts[page],
                    Modifier.graphicsLayer {
                        val k = 1f - 0.06f * abs(offset).coerceAtMost(1f)
                        scaleX = k
                        scaleY = k
                        alpha = 1f - 0.35f * abs(offset).coerceAtMost(1f)
                    },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (s in listOf(30, 60, 120)) {
                    DurationPill(s, selected = coach.seconds == s, Modifier.weight(1f)) { coach.seconds = s }
                }
                StyledButton(
                    onClick = {
                        val next = (prompts.indices - pager.currentPage).random()
                        scope.launch { pager.animateScrollToPage(next) }
                    },
                    style = ToolButtonStyle(),
                    modifier = Modifier.semantics { contentDescription = "Alt subiect, la întâmplare" },
                ) {
                    FoniaIconView(FoniaIcon.retry, size = 18f)
                }
            }
            if (coach.micOff || coach.startFailed) {
                FoniaText(
                    if (coach.micOff) "Pornește microfonul din setările telefonului." else "Microfonul nu a pornit. Mai încearcă o dată.",
                    Modifier.padding(horizontal = 24.dp),
                    style = ui(15, UIWeight.medium, TextRole.footnote),
                    color = Palette.nightSoft,
                )
            }
            StyledButton(
                onClick = onStart,
                style = FoniButtonStyle(field = Field.night),
                modifier = Modifier.fillMaxWidth().wrapContentWidth().widthIn(max = 560.dp).padding(horizontal = 24.dp),
            ) {
                CTALabel("Începe")
            }
        }
    }
}

/**
 * What to practise next, from his last talks: the area with the lowest average, its one tip, how it moved, and a
 * 30-second drill where only that area's cues show.
 */
@Composable
private fun DrillCard(area: tech.fonia.speaking.coach.Area, history: List<tech.fonia.speaking.coach.CoachScores>, onDrill: () -> Unit) {
    val tip = when (area) {
        tech.fonia.speaking.coach.Area.Pace -> tech.fonia.speaking.coach.CoachText.tooFast
        tech.fonia.speaking.coach.Area.Voice -> tech.fonia.speaking.coach.CoachText.voice
        tech.fonia.speaking.coach.Area.Gaze -> tech.fonia.speaking.coach.CoachText.gaze
        tech.fonia.speaking.coach.Area.Calm -> tech.fonia.speaking.coach.CoachText.calm
        tech.fonia.speaking.coach.Area.Lively -> tech.fonia.speaking.coach.CoachText.lively
    }.tip
    val values = history.takeLast(5).mapNotNull { it[area] }
    val moved = if (values.size >= 2) values.last() - values.first() else 0
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .background(Palette.nightDeep, RoundedCornerShape(24.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(14.dp).background(area.color(), RoundedCornerShape(50)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FoniaText(
                "De lucrat: ${area.title}" + if (moved > 0) "  (+$moved)" else "",
                style = ui(17, UIWeight.bold, TextRole.headline),
                color = Palette.nightText,
            )
            FoniaText(tip, style = ui(15, UIWeight.medium, TextRole.footnote), color = Palette.nightSoft)
        }
        StyledButton(
            onClick = onDrill,
            style = tech.fonia.design.PressableStyle(),
            modifier = Modifier.semantics { contentDescription = "Exercițiu de 30 de secunde: ${area.title}" },
        ) {
            // The white pill is inside the button, so all of it takes the tap, not only the words.
            Box(Modifier.background(Palette.sheet, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                FoniaText("30 s", style = ui(16, UIWeight.bold, TextRole.headline), color = Palette.ink)
            }
        }
    }
}

/** The card: what kind of talk, the subject in big letters, one line of help. */
@Composable
private fun PromptCard(prompt: CoachPrompt, modifier: Modifier) {
    val look = kindLook(prompt.kind)
    val shape = remember { ContinuousRoundedRectangle(32.dp) }
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp)
            .background(Palette.sheet, shape)
            .padding(24.dp)
            .semantics { contentDescription = "${look.label}: ${prompt.title}. ${prompt.hint}" },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FoniaText(
            look.label,
            Modifier.background(look.face, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
            style = ui(15, UIWeight.bold, TextRole.footnote),
            color = look.ink,
        )
        Spacer(Modifier.height(28.dp))
        FoniaText(prompt.title, style = ui(31, UIWeight.black, TextRole.title), color = Palette.ink, minimumScaleFactor = 0.7f)
        FoniaText(prompt.hint, style = ui(19, UIWeight.medium, TextRole.body), color = Palette.inkSoft)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DurationPill(seconds: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val face by animateColorAsState(if (selected) Palette.sheet else Palette.nightDeep, swiftSpring(0.3, 0.1), label = "pill")
    val ink by animateColorAsState(if (selected) Palette.ink else Palette.nightText, swiftSpring(0.3, 0.1), label = "pillInk")
    Box(
        modifier
            .heightIn(min = 52.dp)
            .background(face, RoundedCornerShape(50))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        FoniaText(if (seconds < 60) "$seconds s" else "${seconds / 60} min", style = ui(18, UIWeight.bold, TextRole.headline), color = ink)
    }
}

/** Last time, in five dots of the areas' colours sized by score: progress at a glance, no numbers. */
@Composable
private fun LastTime(previous: PreviousAttempt) {
    Row(
        Modifier
            .background(Palette.nightDeep, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = "Data trecută" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FoniaText("data trecută", style = ui(13, UIWeight.semibold, TextRole.caption), color = Palette.nightSoft)
        for (a in tech.fonia.speaking.coach.Area.entries) {
            val s = previous.scores[a]
            val d = if (s == null) 6.dp else (6 + 8 * s / 100f).dp
            Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(d).background(if (s == null) Palette.nightSoft.copy(alpha = 0.3f) else a.color(), RoundedCornerShape(50)))
            }
        }
    }
}

