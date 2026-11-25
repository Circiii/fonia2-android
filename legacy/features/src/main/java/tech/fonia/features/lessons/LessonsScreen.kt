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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
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
import tech.fonia.design.QuietButtonStyle
import tech.fonia.design.Surface
import tech.fonia.design.RisingPanel
import tech.fonia.design.SceneName
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.WideCard
import tech.fonia.design.WordPicture
import tech.fonia.design.fBody
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fCaption
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
            "Ca să fii înțeles, contează cel mai mult accentul și vocalele, așa că începem cu ele. Merge cel mai bine câte 12 minute, de 4 ori pe săptămână.",
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

/** Încălzirea: the same scene page as the other doors, then every warm-up as its own row, started with one tap. */
@Composable
fun WarmupScreen(path: PathModel, app: AppModel) {
    val unit = path.catalog.warmup
    ScenePage(
        scene = SceneName.warmup,
        title = "Încălzirea",
        sentence = "Înainte de sunete, încălzim gura. Tragi aer pe nas, apoi sufli încet.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp).semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(44.dp, Alignment.CenterHorizontally),
            ) {
                BreathFigure(MascotPose.inhale, "Inspiri")
                BreathFigure(MascotPose.exhale, "Sufli")
            }
            for (lesson in unit.lessons) key(lesson.id) {
                StyledButton(onClick = { app.start(lesson, unit) }, style = PressableStyle()) {
                    CardRow(ArtTileMark.Art(lesson.art), lesson.title, lesson.detail) {
                        Box(Modifier.size(34.dp).background(Palette.accentSoft, CircleShape).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                            FoniaIconView(FoniaIcon.play, size = 14f, tint = Palette.accentText)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BreathFigure(pose: MascotPose, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MascotView(pose, Modifier.size(84.dp, 74.dp))
        FoniaText(label, style = ui(15, UIWeight.regular, TextRole.subheadline), color = Palette.ink)
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
    // A sound's page says who teaches it, as a sentence, not a name standing alone.
    val sentence = contrast?.cue ?: sound?.let { "${it.hero.name} te învață ${it.shortLetter}" } ?: unit.subtitle
    ScenePage(
        scene = path.scene(unit),
        title = unit.title,
        sentence = sentence,
        onPaper = true,
        heroHeight = 250.dp,
        art = { UnitArt(unit, Modifier.height(104.dp)) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (sound != null) Tools(sound, push)
            // The second path next to his lessons: the diction training of this sound.
            if (sound != null) tech.fonia.features.diction.TrainingCard(sound.id, app, path.profile.id, push, showsCount = !path.profile.isChild)
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
            // The lessons that wait for an adult are gathered under one line instead of each saying the same.
            val locked = unit.lessons.filter { path.state(it, unit) == LessonState.locked }
            for ((i, lesson) in unit.lessons.withIndex()) if (lesson !in locked) key(lesson.id) {
                LessonRow(lesson, unit, path, app, number = i + 1, modifier = Modifier.staggeredEntrance(i))
            }
            if (locked.isNotEmpty()) LockedRow(unit.sound?.let { path.ownSuccessDots(it) }, push)
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
private fun LessonRow(lesson: Lesson, unit: CourseUnit, path: PathModel, app: AppModel, number: Int, modifier: Modifier = Modifier) {
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
            lesson.detail,
            Modifier.then(if (state == LessonState.current) Modifier.border(2.dp, Palette.accent, card) else Modifier),
        ) { Trailing(state, number) }
    }
}

/**
 * One row for every lesson an adult has to open, saying once what opens them and where the adult does it. It says
 * there are more, never how many: the count is not the child's to carry. Android keeps his own-success dots on it.
 */
@Composable
private fun LockedRow(ownDots: Int?, push: (AppRoute) -> Unit) {
    Surface(padding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).background(Palette.cloud, CircleShape), contentAlignment = Alignment.Center) {
                    FoniaIconView(FoniaIcon.lock, size = 20f, tint = Palette.ink)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FoniaText("Urmează și alte lecții", style = fBodyStrong, color = Palette.ink)
                    FoniaText("Se deschid după ce te ascultă un adult.", style = fCaption, color = Palette.inkSoft)
                }
                if (ownDots != null) OwnDots(ownDots)
            }
            StyledButton(
                onClick = { push(AppRoute.Settings) },
                style = QuietButtonStyle(Palette.accentText),
                modifier = Modifier.padding(start = 44.dp).semantics { onClick(label = "Adultul ascultă sunetul și deschide lecțiile", action = null) },
            ) { FoniaText("Pentru adult") }
        }
    }
}

/** Opened by his own success: one dot per good plain sound, of five. */
@Composable
private fun OwnDots(ownDots: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.semantics { stateDescription = tech.fonia.features.session.OwnSuccess.spokenDots(ownDots) },
    ) {
        repeat(tech.fonia.features.session.OwnSuccess.TARGET) { i ->
            Box(Modifier.size(8.dp).background(if (i < ownDots) Palette.progress else Palette.progressTrack, CircleShape))
        }
    }
}

@Composable
private fun Trailing(state: LessonState, number: Int) {
    when (state) {
        LessonState.done -> DoneDot(true)
        LessonState.current -> Box(Modifier.size(34.dp).background(Palette.accent, CircleShape), contentAlignment = Alignment.Center) {
            FoniaIconView(FoniaIcon.play, size = 16f, tint = Palette.buttonLabel)
        }
        // Locked lessons are gathered in [LockedRow]; none reaches here.
        LessonState.locked -> Unit
        // The lesson's place in the unit, on a soft disc: a number, not an empty circle that reads as a choice.
        LessonState.later -> Box(
            Modifier.size(28.dp).background(Palette.cloud, CircleShape).clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            FoniaText("$number", style = ui(13, UIWeight.bold, TextRole.caption), color = Palette.inkSoft)
        }
    }
}
