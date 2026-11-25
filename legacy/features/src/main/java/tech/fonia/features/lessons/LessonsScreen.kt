package tech.fonia.features.lessons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import tech.fonia.designsystem.motion.staggeredEntrance
import tech.fonia.design.ArtTileMark
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaImage
import tech.fonia.design.FoniaLabel
import tech.fonia.design.FoniaText
import tech.fonia.design.GridCard
import tech.fonia.design.MascotPose
import tech.fonia.design.MascotView
import tech.fonia.design.Palette
import tech.fonia.design.PillButtonStyle
import tech.fonia.design.PracticeWeight
import tech.fonia.design.PressableStyle
import tech.fonia.design.RisingPanel
import tech.fonia.design.SceneName
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.WideCard
import tech.fonia.design.WordPicture
import tech.fonia.design.fBody
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fHeadline
import tech.fonia.design.practice
import tech.fonia.design.scene
import tech.fonia.design.ui
import tech.fonia.domain.CourseUnit
import tech.fonia.domain.EnglishContrast
import tech.fonia.domain.Lesson
import tech.fonia.domain.SoundDef
import tech.fonia.domain.Track
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.isChild
import tech.fonia.features.app.AppRoute
import tech.fonia.features.app.HeaderPage
import tech.fonia.features.app.PageHeader
import tech.fonia.features.app.ScenePage
import tech.fonia.features.app.ScenePanel
import tech.fonia.features.app.characterCount
import tech.fonia.features.app.field
import tech.fonia.features.app.orderedSounds
import tech.fonia.features.app.pullUp
import tech.fonia.features.app.shape
import tech.fonia.features.app.shortLetter
import tech.fonia.features.home.CardRow
import tech.fonia.features.home.DoneDot
import tech.fonia.features.home.LessonState
import tech.fonia.features.home.ModelNotice
import tech.fonia.features.home.PathModel
import tech.fonia.features.home.rememberPush
import tech.fonia.features.session.MouthCard

/** A plain page of whole-width cards under a fixed title. */
@Composable
private fun CardPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    HeaderPage(title) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            content = content,
        )
    }
}

/** A unit as a whole-width card: its scene, what stands for it, and how much of it is finished. */
@Composable
fun UnitCard(unit: CourseUnit, path: PathModel, modifier: Modifier = Modifier) {
    val push = rememberPush()
    StyledButton(onClick = { push(AppRoute.Unit(unit.id)) }, style = PressableStyle(), modifier = modifier) {
        // A child sees the bar fill, never "N din M" (ChildCounts).
        WideCard(path.scene(unit), unit.title, done = path.doneCount(unit), total = unit.lessons.size, detail = unit.subtitle, showsCount = !path.profile.isChild) {
            UnitArt(unit, Modifier.height(96.dp))
        }
    }
}

/** What stands for a unit in its scene: the hero's picture, or its letters on a white disc. */
@Composable
fun UnitArt(unit: CourseUnit, modifier: Modifier = Modifier) {
    val art = unit.art
    if (art != null) {
        // The hero floats a little, each at its own pace, and a sound's hero stands next to its letter as a sticker.
        val reduce = tech.fonia.design.LocalReduceMotion.current
        val bob = androidx.compose.animation.core.rememberInfiniteTransition(label = "hero").animateFloat(
            0f,
            1f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(2600 + (unit.id.hashCode() and 0x3FF), easing = androidx.compose.animation.core.LinearEasing),
            ),
            label = "bob",
        )
        val floats = Modifier.graphicsLayer {
            if (!reduce) {
                val a = bob.value * 2f * Math.PI.toFloat()
                translationY = kotlin.math.sin(a) * 4.dp.toPx()
                rotationZ = kotlin.math.sin(a + 1.3f) * 2.2f
            }
        }
        val family = unit.sound?.family
        if (family != null) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            ) {
                WordPicture(art, modifier.then(floats))
                tech.fonia.designsystem.letter.StickerText(
                    unit.mark,
                    tech.fonia.features.session.stickerFor(family.field),
                    fontSize = if (unit.mark.characterCount() > 2) 40.sp else 68.sp,
                    weight = 900f,
                )
            }
        } else {
            WordPicture(art, modifier.then(floats))
        }
    } else {
        Box(
            modifier
                .size(84.dp)
                .background(Color.White, CircleShape)
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            FoniaText(
                unit.mark,
                style = practice(if (unit.mark.characterCount() > 2) 26 else 34, PracticeWeight.bold, TextRole.title),
                color = Palette.ink,
                maxLines = 1,
                minimumScaleFactor = 0.5f,
            )
        }
    }
}

@Composable
private fun CardHeading(title: String) {
    FoniaText(
        title,
        Modifier
            .padding(start = 4.dp)
            .semantics { heading() },
        style = fHeadline,
        color = Palette.ink,
    )
}

/** Sunete: a child's own sounds first, then the rest. */
@Composable
fun SoundsScreen(path: PathModel) {
    val chosen = path.chosenSounds
    val others = path.profile.orderedSounds(path.content).filter { sound -> chosen.none { it.id == sound.id } }
    CardPage("Sunete") {
        if (chosen.isNotEmpty()) {
            CardHeading("Sunetele tale")
            for ((i, sound) in chosen.withIndex()) key(sound.id) { UnitCard(path.catalog.unit(sound), path, Modifier.staggeredEntrance(i)) }
            CardHeading("Celelalte sunete")
        }
        for ((i, sound) in others.withIndex()) key(sound.id) { UnitCard(path.catalog.unit(sound), path, Modifier.staggeredEntrance(chosen.size + i)) }
    }
}

/** English: the road through the sounds that matter most, then the melody of a sentence and free talk. */
@Composable
fun EnglishScreen(path: PathModel) {
    val push = rememberPush()
    CardPage("Engleză") {
        FoniaText(
            "Întâi accentul și vocalele. Câte 12 minute, de 4 ori pe săptămână.",
            Modifier.padding(horizontal = 4.dp),
            style = fBody,
            color = Palette.inkSoft,
        )
        for ((i, unit) in path.catalog.contrastUnits.withIndex()) key(unit.id) { UnitCard(unit, path, Modifier.staggeredEntrance(i)) }
        CardHeading("Când sunetele ies bine")
        UnitCard(path.catalog.melodyUnit, path)
        StyledButton(onClick = { push(AppRoute.Speaking) }, style = PressableStyle()) {
            CardRow(ArtTileMark.Art("art-bubble"), "Vorbesc liber în engleză", "Un subiect, un minut, apoi te asculți") {
                FoniaIconView(FoniaIcon.forward, size = 14f, tint = Palette.inkSoft)
            }
        }
    }
}

/** Încălzirea: a scene, then a panel rising over it where you choose what to warm up and begin. */
@Composable
fun WarmupScreen(path: PathModel, app: AppModel) {
    val unit = path.catalog.warmup
    var lessonID by rememberSaveable { mutableStateOf<String?>(null) }
    val lesson = unit.lessons.firstOrNull { it.id == lessonID } ?: unit.lessons.firstOrNull()
    val density = LocalDensity.current
    val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.sage)) {
        // 42% of the page down to the system's navigation area, the status bar included, as on iOS.
        val sceneHeight = (maxHeight - bottomInset) * 0.42f
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(sceneHeight).clipToBounds()) {
                FoniaImage("scene-warmup-tall", Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            }
            RisingPanel(Modifier.pullUp(24.dp).weight(1f)) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(top = 22.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FoniaText(
                            "Tragi aer pe nas, apoi sufli încet.",
                            style = ui(18, UIWeight.medium, TextRole.title3),
                            color = Palette.ink,
                            textAlign = TextAlign.Center,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(44.dp)) {
                            BreathFigure(MascotPose.inhale, "Inspiri")
                            BreathFigure(MascotPose.exhale, "Sufli")
                        }
                        LessonMenu(unit.lessons, lesson) { lessonID = it.id }
                    }
                    StyledButton(
                        onClick = { if (lesson != null) app.start(lesson, unit) },
                        style = FoniButtonStyle(),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) { FoniaText("Începem") }
                }
            }
        }
        PageHeader("Încălzirea", Modifier.windowInsetsPadding(WindowInsets.statusBars))
    }
}

@Composable
private fun BreathFigure(pose: MascotPose, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MascotView(pose, Modifier.size(84.dp, 74.dp))
        FoniaText(label, style = ui(15, UIWeight.regular, TextRole.subheadline), color = Palette.ink)
    }
}

/**
 * `Menu { Picker("Exercițiul") }`: a white capsule with the chosen title; a tap opens the list under it, with a tick at
 * the one chosen.
 */
@Composable
private fun LessonMenu(lessons: List<Lesson>, chosen: Lesson?, onPick: (Lesson) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .background(Palette.sheet, RoundedCornerShape(50))
                .clickable { open = true }
                .heightIn(min = 52.dp)
                .padding(horizontal = 18.dp)
                .semantics {
                    contentDescription = "Exercițiul"
                    stateDescription = chosen?.title ?: ""
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FoniaText(chosen?.title ?: "", style = fBodyStrong, color = Palette.ink, maxLines = 1, minimumScaleFactor = 0.8f)
            FoniaIconView(FoniaIcon.down, size = 14f, tint = Palette.ink)
        }
        if (open) {
            Popup(alignment = Alignment.TopCenter, offset = androidx.compose.ui.unit.IntOffset(0, 0), onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
                Column(
                    Modifier
                        .width(250.dp)
                        .shadow(18.dp, ContinuousRoundedRectangle(22.dp))
                        .background(Palette.sheet, ContinuousRoundedRectangle(22.dp))
                        .padding(vertical = 6.dp),
                ) {
                    for (item in lessons) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPick(item)
                                    open = false
                                }
                                .heightIn(min = ChildTarget)
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.width(18.dp)) {
                                if (item.id == chosen?.id) FoniaIconView(FoniaIcon.tick, size = 15f, tint = Palette.ink)
                            }
                            FoniaText(item.title, style = fBody, color = Palette.ink)
                        }
                    }
                }
            }
        }
    }
}

/** Jocuri: games for the ear and the mouth, with nothing else on the page. */
@Composable
fun PlayScreen(path: PathModel, app: AppModel) {
    val voicing = path.catalog.voicing
    val push = rememberPush()
    ScenePage(
        scene = SceneName.play,
        title = "Jocuri",
        sentence = "Câteva minute de joacă cu sunetele.",
        panel = ScenePanel.attached,
        heroHeight = 300.dp,
    ) {
        val cards = buildList {
            voicing.lessons.firstOrNull()?.let { listen -> add(PlayCard(voicing.title, SceneName.play) { app.start(listen, voicing) }) }
            voicing.lessons.drop(1).firstOrNull()?.let { say -> add(PlayCard(say.title, SceneName.speaking) { app.start(say, voicing) }) }
            (path.continuing?.unit?.sound ?: path.chosenSounds.firstOrNull()?.id)?.let { sound ->
                add(PlayCard("În oglindă", SceneName.guide) { push(AppRoute.Mirror(sound)) })
            }
        }
        Grid(cards, columnSpacing = 10, rowSpacing = 14) { card, modifier -> LessonCard(modifier, card.title, card.scene, card.action) }
    }
}

private class PlayCard(val title: String, val scene: SceneName, val action: () -> Unit)

@Composable
private fun LessonCard(modifier: Modifier, title: String, scene: SceneName, onClick: () -> Unit) {
    StyledButton(onClick = onClick, style = PressableStyle(), modifier = modifier) {
        GridCard(scene, title)
    }
}

/** `LazyVGrid` with two flexible columns. */
@Composable
private fun <T> Grid(items: List<T>, columnSpacing: Int, rowSpacing: Int, cell: @Composable (T, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(rowSpacing.dp)) {
        for ((row, pair) in items.chunked(2).withIndex()) {
            Row(horizontalArrangement = Arrangement.spacedBy(columnSpacing.dp)) {
                // Cards come in one after another, row by row (v2 polish).
                for ((col, item) in pair.withIndex()) cell(item, Modifier.weight(1f).staggeredEntrance(row * 2 + col))
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Cum spun? Every sound, each leading to how it's made. */
@Composable
fun GuidesScreen(path: PathModel) {
    val sounds = path.profile.orderedSounds(path.content)
    val push = rememberPush()
    ScenePage(scene = SceneName.guide, title = "Cum spun?", sentence = "Gura și limba, la fiecare sunet.") {
        Grid(sounds, columnSpacing = 10, rowSpacing = 18) { sound, modifier ->
            StyledButton(onClick = { push(AppRoute.Guide(sound.id)) }, style = PressableStyle(), modifier = modifier) {
                GridCard(sound.family.field.scene, "Cum spun ${sound.shortLetter}?", detail = sound.hero.name) {
                    WordPicture(sound.hero.picture, Modifier.height(70.dp))
                }
            }
        }
    }
}

/** Povești: the same page at night. */
@Composable
fun StoriesScreen(path: PathModel, app: AppModel) {
    val unit = path.catalog.stories
    ScenePage(
        scene = SceneName.stories,
        title = "Povești",
        sentence = "Asculți povestea, apoi o spui și tu.",
        isNight = true,
    ) {
        Grid(unit.lessons, columnSpacing = 10, rowSpacing = 18) { lesson, modifier ->
            StyledButton(onClick = { app.start(lesson, unit) }, style = PressableStyle(), modifier = modifier) {
                GridCard(SceneName.night, lesson.title, detail = lesson.detail) {
                    WordPicture(lesson.art, Modifier.height(70.dp))
                }
            }
        }
    }
}

private val coachKeys = mapOf("th" to "en-th", "w-v" to "en-w", "ship-sheep" to "en-ee", "bad-bed" to "en-ae")

/** One sound, one English contrast or one part of diction: what it is, and its lessons in order. */
@Composable
fun UnitScreen(unit: CourseUnit, path: PathModel, app: AppModel) {
    val push = rememberPush()
    val sound = unit.sound?.let { app.content.sound(it) }
    val contrast: EnglishContrast? = if (unit.track == Track.English && unit.id != path.catalog.melodyUnit.id) {
        app.content.english.contrast(unit.id.removePrefix("en-"))
    } else {
        null
    }
    val sentence = contrast?.cue ?: unit.subtitle
    ScenePage(
        scene = path.scene(unit),
        title = unit.title,
        sentence = sentence,
        onPaper = true,
        heroHeight = 250.dp,
        art = { UnitArt(unit, Modifier.height(104.dp).offset(y = 16.dp)) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (sound != null) Tools(sound, push)
            // The second path next to his lessons: the diction training of this sound.
            if (sound != null) tech.fonia.features.diction.TrainingCard(sound.id, app, path.profile.id, push)
            // His progress on this sound: the dots of his takes, the first time and today (session/ProgressDots.kt).
            if (sound != null) tech.fonia.features.session.SoundProgressCard(sound.id, app, path.profile.id, showsCount = !path.profile.isChild)
            val pose = contrast?.pose
            if (pose != null) MouthCard(shape = pose.mouth.shape, height = 170.dp)
            val key = contrast?.let { coachKeys[it.id] }
            if (key != null) {
                StyledButton(onClick = { push(AppRoute.Coach(key)) }, style = PillButtonStyle(), modifier = Modifier.padding(bottom = 6.dp).heightIn(min = ChildTarget)) {
                    FoniaLabel("Pune gura la locul ei", FoniaIcon.mirror, 20f)
                }
            }
            // The lock is explained once, on the first closed lesson; the rest keep saying what they are (QA 2026-11-21).
            val firstLocked = unit.lessons.firstOrNull { path.state(it, unit) == LessonState.locked }?.id
            for ((i, lesson) in unit.lessons.withIndex()) key(lesson.id) {
                LessonRow(lesson, unit, path, app, explainsLock = lesson.id == firstLocked, modifier = Modifier.staggeredEntrance(i))
            }
            if (contrast != null && contrast.kind != EnglishContrast.Kind.Stress) ModelNotice(app.speech)
        }
    }
}

/** port-android.md §3: a child's buttons are at least 56 dp tall (iOS `PillButtonStyle(minHeight: 56)`). */
private val ChildTarget = 56.dp

@Composable
private fun Tools(sound: SoundDef, push: (AppRoute) -> Unit) {
    Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StyledButton(onClick = { push(AppRoute.Guide(sound.id)) }, style = PillButtonStyle(), modifier = Modifier.weight(1f).heightIn(min = ChildTarget)) {
            FoniaLabel("Cum spun ${sound.shortLetter}?", FoniaIcon.mouth, 20f)
        }
        StyledButton(onClick = { push(AppRoute.Mirror(sound.id)) }, style = PillButtonStyle(), modifier = Modifier.weight(1f).heightIn(min = ChildTarget)) {
            FoniaLabel("Încearcă", FoniaIcon.mirror, 20f)
        }
    }
}

@Composable
private fun LessonRow(lesson: Lesson, unit: CourseUnit, path: PathModel, app: AppModel, explainsLock: Boolean, modifier: Modifier = Modifier) {
    val state = path.state(lesson, unit)
    val canOpen = path.canOpen(lesson, unit)
    val card = ContinuousRoundedRectangle(16.dp)
    StyledButton(
        onClick = { app.start(lesson, unit) },
        style = PressableStyle(),
        enabled = canOpen,
        modifier = modifier.semantics {
            stateDescription = when (state) {
                LessonState.done -> "Terminată"
                LessonState.current -> "Urmează"
                LessonState.locked -> "Închisă"
                LessonState.later -> ""
            }
        },
    ) {
        CardRow(
            ArtTileMark.Art(lesson.art),
            lesson.title,
            if (state == LessonState.locked && explainsLock) tech.fonia.features.session.OwnSuccess.lockLine(unit.sound?.let { path.ownSuccessEstablished(it) } ?: 0) else lesson.detail,
            Modifier
                .then(if (state == LessonState.current) Modifier.border(2.dp, Palette.accent, card) else Modifier)
                .alpha(if (canOpen) 1f else 0.6f),
        ) { Trailing(state, canOpen, unit.sound?.let { path.ownSuccessDots(it) } ?: 0) }
    }
}

@Composable
private fun Trailing(state: LessonState, canOpen: Boolean, ownDots: Int = 0) {
    when (state) {
        LessonState.done -> DoneDot(true)
        LessonState.current -> Box(Modifier.size(34.dp).background(Palette.accent, CircleShape), contentAlignment = Alignment.Center) {
            FoniaIconView(FoniaIcon.play, size = 16f, tint = Palette.buttonLabel)
        }
        // Opened by his own success: one dot per good plain sound, of five.
        LessonState.locked -> androidx.compose.foundation.layout.Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp),
            modifier = Modifier.semantics { stateDescription = tech.fonia.features.session.OwnSuccess.spokenDots(ownDots) },
        ) {
            repeat(tech.fonia.features.session.OwnSuccess.TARGET) { i ->
                Box(Modifier.size(8.dp).background(if (i < ownDots) Palette.progress else Palette.progressTrack, CircleShape))
            }
        }
        LessonState.later -> Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(22.dp).border(2.dp, Palette.ink.copy(alpha = if (canOpen) 0.3f else 0.15f), CircleShape))
        }
    }
}
