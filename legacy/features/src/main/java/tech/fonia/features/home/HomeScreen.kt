package tech.fonia.features.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import tech.fonia.design.FoniaImage
import tech.fonia.design.LocalForeground
import tech.fonia.design.SceneArt
import tech.fonia.design.SkyLife
import tech.fonia.designsystem.letter.LetterCue
import tech.fonia.designsystem.letter.LiveLetter
import tech.fonia.designsystem.tokens.VerdictStickers
import tech.fonia.features.app.field
import tech.fonia.features.progress.ChildCounts
import tech.fonia.features.session.rememberLetterLink
import tech.fonia.features.session.stickerFor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.CountPill
import tech.fonia.design.Foni
import tech.fonia.design.FoniMood
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaLabel
import tech.fonia.design.FoniaText
import tech.fonia.design.HomeTile
import tech.fonia.design.LocalReduceMotion
import tech.fonia.design.MiniPillStyle
import tech.fonia.design.Palette
import tech.fonia.design.Radius
import tech.fonia.design.PressableStyle
import tech.fonia.design.ScrollViewport
import tech.fonia.design.ProgressPill
import tech.fonia.design.SceneName
import tech.fonia.design.StyledButton
import tech.fonia.design.ToolButtonStyle
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.fBody
import tech.fonia.design.monospacedDigit
import tech.fonia.design.softShadow
import tech.fonia.design.swiftSpring
import tech.fonia.design.scrollSettle
import tech.fonia.design.scrollViewport
import tech.fonia.design.ui
import tech.fonia.domain.Plural
import tech.fonia.domain.SoundDef
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.AppRoute
import tech.fonia.features.app.TallyFocus
import tech.fonia.features.app.isChild
import tech.fonia.features.app.lowercasedFirstLetter
import tech.fonia.features.app.shortLetter
import tech.fonia.features.app.zoomSource
import tech.fonia.store.Profile
import tech.fonia.features.app.rememberStoredBoolean
import tech.fonia.store.localDay
import java.time.Instant

internal data class Door(val id: String, val scene: SceneName, val title: String, val route: AppRoute)

/** The first door is the main one and gets the whole width. */
internal fun doors(profile: Profile): List<Door> {
    val sounds = Door("sounds", SceneName.sounds, "Sunete", AppRoute.AllSounds)
    // A child gets the same five doors as on iOS. The speech coach (the camera, live cues, the report) is for adults,
    // so a child has no door to it.
    if (profile.isChild) {
        return listOf(
            sounds,
            Door("guides", SceneName.guide, "Cum spun?", AppRoute.Guides),
            Door("warmup", SceneName.warmup, "Încălzirea", AppRoute.Warmup),
            Door("play", SceneName.play, "Jocuri", AppRoute.Play),
            Door("stories", SceneName.stories, "Povești", AppRoute.Stories),
        )
    }
    // „Vorbit în public” opens its exercises, as on iOS; the speech coach in front of the camera is a row there, once
    // an adult turns it on in Setări → Experimental.
    val speaking = Door("speaking", SceneName.speaking, "Vorbit în public", AppRoute.Speaking)
    val english = Door("english", SceneName.english, "Engleză", AppRoute.English)
    return when (profile.track) {
        Profile.Track.En -> listOf(english, speaking)
        Profile.Track.Dictie -> listOf(speaking, english)
        Profile.Track.Ro -> listOf(sounds, english, speaking)
    }
}

/**
 * Where the app opens. A sky with Foni and today's letter, alive; one card with what comes next; and the doors to
 * everything else as pictures. Almost no text: the pictures, the letter and the yellow button say it.
 */
@Composable
fun HomeScreen(profile: Profile, app: AppModel, path: PathModel, bottomInset: Dp) {
    var hasArrived by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { hasArrived = true }
    val push = rememberPush()
    val scroll = rememberScrollState()
    // What the doors measure themselves against: the page, less the bar over its foot.
    val viewport = remember { ScrollViewport() }
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .scrollViewport(viewport, coveredBottom = bottomInset)
            .verticalScroll(scroll),
    ) {
        Hero(app, path, hasArrived, onSettings = { push(AppRoute.Settings) }, onTally = { push(AppRoute.Tally(it)) }) { scroll.value }
        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentWidth()
                .widthIn(max = 620.dp)
                .offset(y = (-38).dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TodayCard(app, path, Modifier.arrives(hasArrived, 2))
            // The quick check: what to practise, and later what moved (sounds track only).
            if (profile.track == Profile.Track.Ro) {
                tech.fonia.features.check.CheckCard(path.lastCheck, app, Modifier.arrives(hasArrived, 3), isChild = path.profile.isChild)
            }
            val list = doors(profile)
            if (!profile.isChild) {
                list.forEachIndexed { index, item -> DoorButton(item, 2.3f, large = true, index + 3, hasArrived, push, viewport) }
            } else {
                list.firstOrNull()?.let { DoorButton(it, 2.15f, large = true, 3, hasArrived, push, viewport) }
                val grid = list.drop(1)
                grid.chunked(2).forEachIndexed { row, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        pair.forEachIndexed { column, item ->
                            DoorButton(item, 1.12f, large = false, row * 2 + column + 4, hasArrived, push, viewport, Modifier.weight(1f))
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
    // A band of the page colour behind the status bar, so the cards never run under the clock. The sky shows
    // through it at the top and the band comes in as soon as the page moves.
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        Modifier
            .fillMaxWidth()
            .height(top)
            .graphicsLayer { alpha = (scroll.value / 24.dp.toPx()).coerceIn(0f, 1f) }
            .background(Palette.paper)
            .clearAndSetSemantics {},
    )
    }
}

/** The sky at the top: the two counters, the greeting, and Foni next to today's letter. It drifts as the page scrolls. */
@Composable
private fun Hero(app: AppModel, path: PathModel, hasArrived: Boolean, onSettings: () -> Unit, onTally: (TallyFocus) -> Unit, scrolled: () -> Int) {
    val unit = path.continuing?.unit
    val sound = unit?.sound?.let { app.content.sound(it) }
    val didPracticeToday = path.week.firstOrNull { it.isToday }?.hasPractice == true
    val name = (path.profile.name ?: "").trim()
    val greeting = if (name.isEmpty()) "Salut!" else "Salut, $name!"
    val shape = remember { RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        Modifier
            .fillMaxWidth()
            .height(332.dp + top)
            .clip(shape)
            .background(Palette.sheet),
    ) {
        // The start scene, anchored on its hills, sliding at half the speed of the page.
        Box(Modifier.matchParentSize().graphicsLayer { translationY = scrolled() * 0.45f; scaleX = 1.06f; scaleY = 1.06f }) {
            FoniaImage("scene-start-page", Modifier.matchParentSize(), contentScale = ContentScale.Crop, alignment = Alignment.BottomCenter)
            SkyLife(modifier = Modifier.matchParentSize())
        }
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            drawOval(Palette.progress.copy(alpha = 0.55f), Offset(-w * 0.35f, h - 118.dp.toPx()), Size(w * 1.15f, 260.dp.toPx()))
            drawOval(Palette.accentSoft, Offset(w * 0.2f, h - 96.dp.toPx()), Size(w * 1.25f, 250.dp.toPx()))
            drawOval(Palette.progress.copy(alpha = 0.35f), Offset(w * 0.45f, h - 70.dp.toPx()), Size(w * 0.9f, 200.dp.toPx()))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = top + 8.dp)
                .padding(horizontal = 16.dp)
                .arrives(hasArrived, 0),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // As on iOS: a check and "N zile", no flame; a day missed breaks nothing. The counts wait for the first
            // lesson; two zeros greet nobody.
            if (path.totalDays > 0) {
                // Each count opens its page: the days on a calendar, the stars on a wall.
                StyledButton(onClick = { onTally(TallyFocus.Days) }, style = PressableStyle(), onClickLabel = "Deschide zilele tale") {
                    CountPill(
                        FoniaIcon.check, Palette.accentText, path.totalDays, Plural.format(path.totalDays, Plural.Noun.Zi) + " în care ai exersat",
                        unit = Plural.form(path.totalDays, Plural.Noun.Zi),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (path.totalLessons > 0) {
                StyledButton(onClick = { onTally(TallyFocus.Stars) }, style = PressableStyle(), onClickLabel = "Deschide stelele tale") {
                    CountPill(FoniaIcon.star, Palette.starTint, path.totalLessons, Plural.format(path.totalLessons, Plural.Noun.Stea))
                }
                Spacer(Modifier.width(8.dp))
            }
            // The grown-ups' area, behind the same gate as from Progres.
            StyledButton(
                onClick = onSettings,
                style = ToolButtonStyle(diameter = 44.dp, spins = true),
                modifier = Modifier.semantics { contentDescription = "Setări" },
            ) {
                FoniaIconView(FoniaIcon.settings, size = 22f)
            }
        }
        FoniaText(
            greeting,
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = top + 52.dp)
                .padding(horizontal = 20.dp)
                .arrives(hasArrived, 1)
                .semantics { heading() },
            style = ui(33, UIWeight.black, TextRole.largeTitle),
            color = Palette.ink,
            maxLines = 1,
            minimumScaleFactor = 0.6f,
        )
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 50.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Foni(
                Modifier.size(164.dp).arrives(hasArrived, 1),
                mood = if (didPracticeToday) FoniMood.happy else FoniMood.hello,
                flipped = sound != null,
                reactsToTap = true,
            )
            if (sound != null) HeroLetter(app, sound, hasArrived)
        }
    }
}

/** Today's letter, as a living sticker in the sound's own colour. It drops in, beats now and then, and says its sound when tapped. */
@Composable
private fun HeroLetter(app: AppModel, sound: SoundDef, hasArrived: Boolean) {
    val link = rememberLetterLink(sound.id)
    val scope = rememberCoroutineScope()
    val letter = sound.shortLetter.take(1)
    val running = tech.fonia.design.LocalAmbientMotion.current
    var dropped by remember(sound.id) { mutableStateOf(false) }
    LaunchedEffect(hasArrived, sound.id, running) {
        if (!hasArrived) return@LaunchedEffect
        if (!dropped) {
            delay(260)
            link.state.cue(LetterCue.DropIn)
            dropped = true
        }
        // Never frozen while in view: a little beat now and then.
        while (running) {
            delay(5200)
            link.state.cue(LetterCue.Stretch)
        }
    }
    StyledButton(
        onClick = {
            link.state.cue(LetterCue.Beat())
            scope.launch { app.audio.say(sound.hero.says) }
        },
        style = PressableStyle(),
        modifier = Modifier
            .offset(x = (-34).dp, y = 46.dp)
            .semantics { contentDescription = "Ascultă sunetul ${sound.shortLetter}" },
    ) {
        LiveLetter(
            letter,
            link.state,
            drive = link.drive,
            sticker = stickerFor(sound.id.family.field),
            height = 190.dp,
            verdicts = VerdictStickers.Classic,
        )
    }
}

@Composable
private fun DoorButton(
    item: Door,
    aspect: Float,
    large: Boolean,
    order: Int,
    hasArrived: Boolean,
    push: (AppRoute) -> Unit,
    viewport: ScrollViewport,
    modifier: Modifier = Modifier,
) {
    // A door half out of view gives a little and fades, then settles as it comes fully in.
    StyledButton(onClick = { push(item.route) }, style = PressableStyle(), modifier = modifier.scrollSettle(viewport).arrives(hasArrived, order)) {
        Box {
            DoorTile(item.scene, item.title, aspect, large, Modifier.zoomSource(item.route))
        }
    }
}

/** A door: the whole tile is its picture, with the name written on it. */
@Composable
private fun DoorTile(scene: SceneName, title: String, aspect: Float, large: Boolean, modifier: Modifier = Modifier) {
    val shape = remember { ContinuousRoundedRectangle(Radius.card) }
    Box(
        modifier
            .softShadow(shape)
            .clip(shape)
            .clearAndSetSemantics { contentDescription = title },
    ) {
        SceneArt(scene, aspect, Modifier.fillMaxWidth())
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(0.5f to Palette.ink.copy(alpha = 0f), 1f to Palette.ink.copy(alpha = 0.5f))),
        )
        FoniaText(
            title,
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = if (large) 18.dp else 14.dp, vertical = if (large) 12.dp else 9.dp),
            style = ui(if (large) 27 else 20, UIWeight.black, TextRole.title2)
                .copy(shadow = Shadow(Palette.ink.copy(alpha = 0.45f), Offset(0f, 2f), 6f)),
            color = Palette.sheet,
            maxLines = 1,
            minimumScaleFactor = 0.7f,
        )
    }
}

/** What comes next, in one line, how far the lessons have come as dots, and two round helpers: listen, and how to say it. */
@Composable
private fun TodayCard(app: AppModel, path: PathModel, modifier: Modifier = Modifier) {
    val step = path.continuing
    val unit = step?.unit
    val sound = unit?.sound?.let { app.content.sound(it) }
    val title = step?.lesson?.title ?: "Ai terminat tot, bravo!"
    // An adult's „Încă o rundă mai târziu” (DailyRounds), kept per child. `now` moves on when the pause after the first
    // lesson is over, so the offer shows without leaving the page.
    val roundsOn by rememberStoredBoolean(app.preferences, DailyRounds.key(path.profile.id))
    var now by remember { mutableStateOf(Instant.now()) }
    val round = if (roundsOn && DailyRounds.isOffered(path.profile) && step != null && path.hasStarted) {
        DailyRounds.round(path.sessions, localDay(now), now)
    } else {
        null
    }
    val restingUntil = (round as? DailyRounds.Round.Resting)?.until
    LaunchedEffect(restingUntil) {
        val until = restingUntil ?: return@LaunchedEffect
        delay(java.time.Duration.between(Instant.now(), until).toMillis().coerceAtLeast(0) + 1_000)
        now = Instant.now()
    }
    val roundLine = when (round) {
        DailyRounds.Round.Second -> "Încă o rundă mică?"
        DailyRounds.Round.Done -> "Ai exersat de două ori azi. Bravo!"
        else -> null
    }
    val push = rememberPush()
    val scope = rememberCoroutineScope()
    val card = remember { ContinuousRoundedRectangle(28.dp) }
    Row(
        modifier
            .softShadow(card)
            .background(Palette.sheet, card)
            .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Today's sound, said plainly: the one the yellow button continues.
            if (sound != null) {
                FoniaText(
                    "Sunetul de azi: ${sound.shortLetter}",
                    style = ui(14, UIWeight.semibold, TextRole.footnote),
                    color = sound.id.family.field.deep,
                    maxLines = 1,
                )
            }
            FoniaText(title, style = ui(21, UIWeight.bold, TextRole.headline), color = Palette.ink, maxLines = 2, minimumScaleFactor = 0.75f)
            if (roundLine != null) {
                FoniaText(roundLine, style = ui(15, UIWeight.medium, TextRole.subheadline), color = Palette.inkSoft)
            }
            if (unit != null) {
                val done = path.doneCount(unit)
                val total = unit.lessons.size
                // A child sees the dots fill, never the count, and TalkBack doesn't read one either (ChildCounts).
                val label = ChildCounts.todayLabel(done, total, path.profile.isChild)
                LessonDots(done, total, Modifier.clearAndSetSemantics { if (label != null) contentDescription = label })
            }
        }
        if (sound != null) {
            RoundHelper(FoniaIcon.speaker, "Ascultă sunetul ${sound.shortLetter}") { scope.launch { app.audio.say(sound.hero.says) } }
            RoundHelper(FoniaIcon.mouth, "Cum spun ${sound.shortLetter}?") { push(AppRoute.Guide(sound.id)) }
        } else if (unit != null) {
            RoundHelper(FoniaIcon.book, "Vezi lecțiile") { push(AppRoute.Unit(unit.id)) }
        }
    }
}

/** A round button with only a picture on it. */
@Composable
private fun RoundHelper(icon: FoniaIcon, label: String, onClick: () -> Unit) {
    StyledButton(onClick = onClick, style = PressableStyle(), modifier = Modifier.semantics { contentDescription = label }) {
        Box(Modifier.size(54.dp).background(Palette.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalForeground provides Palette.accentText) { FoniaIconView(icon, size = 28f) }
        }
    }
}

/** The lessons of the unit as a row of dots that fill one after another; a bar when there are too many to count. */
@Composable
private fun LessonDots(done: Int, total: Int, modifier: Modifier = Modifier) {
    if (total > 16) {
        ProgressPill(done, total, modifier)
        return
    }
    val reduceMotion = LocalReduceMotion.current
    val fill = remember { Animatable(0f) }
    LaunchedEffect(done) {
        if (reduceMotion) fill.snapTo(done.toFloat()) else fill.animateTo(done.toFloat(), tween(220 + 90 * done))
    }
    Canvas(modifier.fillMaxWidth().height(16.dp)) {
        val gap = 6.dp.toPx()
        val d = ((size.width - gap * (total - 1)) / total).coerceAtMost(size.height)
        for (i in 0 until total) {
            val center = Offset(d / 2 + i * (d + gap), size.height / 2)
            drawCircle(Palette.progressTrack, d / 2, center)
            val share = (fill.value - i).coerceIn(0f, 1f)
            if (share > 0f) drawCircle(Palette.progress, d / 2 * share, center)
        }
    }
}

/**
 * `ViewThatFits(in: .horizontal)`: the first option whose natural width fits, else the last one squeezed into the
 * width there is. Options that don't fit are measured but never placed, as SwiftUI does.
 */
@Composable
fun ViewThatFits(vararg options: @Composable () -> Unit) {
    SubcomposeLayout { constraints ->
        val loose = Constraints(maxHeight = constraints.maxHeight)
        var placeables = emptyList<androidx.compose.ui.layout.Placeable>()
        var fitted = false
        for (index in options.indices) {
            val measured = subcompose(index) { options[index]() }.map { it.measure(loose) }
            if ((measured.maxOfOrNull { it.width } ?: 0) <= constraints.maxWidth) {
                placeables = measured
                fitted = true
                break
            }
        }
        if (!fitted) {
            placeables = subcompose("squeezed") { options.last()() }.map { it.measure(constraints.copy(minWidth = 0)) }
        }
        val width = placeables.maxOfOrNull { it.width } ?: 0
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) { placeables.forEach { it.place(0, 0) } }
    }
}

/** Springs up into place the first time the page opens, one thing after another, with a little overshoot. */
@Composable
private fun Modifier.arrives(hasArrived: Boolean, order: Int): Modifier {
    val reduceMotion = LocalReduceMotion.current
    val progress = remember { Animatable(if (hasArrived) 1f else 0f) }
    LaunchedEffect(hasArrived) {
        if (hasArrived && progress.value < 1f) {
            delay(order * 55L)
            progress.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 240f))
        }
    }
    return graphicsLayer {
        val shown = if (reduceMotion) 1f else progress.value
        alpha = shown.coerceIn(0f, 1f)
        translationY = (1f - shown) * 28.dp.toPx()
        val scale = 0.9f + 0.1f * shown
        scaleX = scale
        scaleY = scale
    }
}
