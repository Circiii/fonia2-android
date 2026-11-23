package tech.fonia.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.sin

/** The pages of the gallery; the key is what `--es section` takes. */
@Suppress("EnumEntryName")
enum class GallerySection(val title: String, val key: String) {
    components("Piese", "componente"),
    home("Acasă", "acasa"),
    session("Lecția", "lectia"),
    foni("Foni", "foni"),
    mouths("Guri", "guri"),
    scenes("Scene", "scene");

    companion object {
        fun from(key: String?): GallerySection = entries.firstOrNull { it.key == key } ?: components
    }
}

/**
 * Every component, Foni in every mood and every still pose, every mouth from the front and the side, and
 * every scene. The "Guri" page starts with the iOS `MouthGallery` exactly as it is (18 sounds, front and side,
 * 76 high, three columns), so a speech therapist can compare the two apps page against page.
 */
@Composable
fun DesignGallery(initial: GallerySection = GallerySection.components) {
    var section by rememberSaveable { mutableStateOf(initial) }
    Column(Modifier.fillMaxSize().background(Palette.paper)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Palette.sheet)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (entry in GallerySection.entries) {
                StyledButton(
                    onClick = { section = entry },
                    style = GalleryTabStyle(entry == section),
                    modifier = Modifier.weight(1f),
                ) { FoniaText(entry.title, maxLines = 1, minimumScaleFactor = 0.7f) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (section) {
                GallerySection.components -> ComponentsPage()
                GallerySection.home -> HomeSamplePage()
                GallerySection.session -> SessionSamplePage()
                GallerySection.foni -> FoniPage()
                GallerySection.mouths -> MouthsPage()
                GallerySection.scenes -> ScenesPage()
            }
        }
    }
}

/** The gallery's own tabs: plain text on a pale pill. */
private class GalleryTabStyle(val selected: Boolean) : ButtonStyle {
    @Composable
    override fun MakeBody(configuration: ButtonConfiguration, modifier: Modifier) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalFont provides ui(13, if (selected) UIWeight.bold else UIWeight.medium),
            LocalForeground provides if (selected) Palette.buttonLabel else Palette.ink,
        ) {
            Box(
                modifier
                    .background(if (selected) Palette.accent else Palette.cloud, RoundedCornerShape(50))
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) { configuration.label() }
        }
    }
}

/** A voice that comes and goes, for the live views. */
private val fakeLevel: () -> Float = {
    val t = System.nanoTime() / 1e9
    ((sin(t * 2.7) * 0.5 + 0.5) * (sin(t * 0.9) * 0.5 + 0.5)).toFloat()
}

@Composable
private fun Heading(text: String) {
    FoniaText(text, style = fHeadline, color = Palette.ink, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    FoniaText(text, style = fCaption, color = Palette.inkSoft, modifier = modifier, textAlign = TextAlign.Center)
}

@Composable
private fun Page(background: Color = Palette.paper, padding: Int = 14, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = padding.dp)
            .padding(top = 12.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

// ---------------------------------------------------------------------------------------------------------
// Components

@Composable
private fun ComponentsPage() = Page {
    Heading("Paleta")
    val swatches = listOf(
        "paper" to Palette.paper, "sheet" to Palette.sheet, "cloud" to Palette.cloud, "line" to Palette.line,
        "lineStrong" to Palette.lineStrong, "ink" to Palette.ink, "inkSoft" to Palette.inkSoft, "accent" to Palette.accent,
        "accentEdge" to Palette.accentEdge, "accentText" to Palette.accentText, "accentSoft" to Palette.accentSoft, "progress" to Palette.progress,
        "progressTrack" to Palette.progressTrack, "today" to Palette.today, "starTint" to Palette.starTint, "startLid" to Palette.startLid,
        "startGlint" to Palette.startGlint, "startSkirt" to Palette.startSkirt, "chipEdge" to Palette.chipEdge, "sage" to Palette.sage,
        "peach" to Palette.peach, "night" to Palette.night, "nightSoft" to Palette.nightSoft, "nightDeep" to Palette.nightDeep,
        "nightPage" to Palette.nightPage, "cold" to Palette.cold, "coldDeep" to Palette.coldDeep, "coldSoft" to Palette.coldSoft,
        "warm" to Palette.warm, "warmDeep" to Palette.warmDeep, "warmSoft" to Palette.warmSoft, "tip" to Palette.tip,
        "tipDeep" to Palette.tipDeep, "tipSoft" to Palette.tipSoft, "right" to Palette.right, "rightSoft" to Palette.rightSoft,
        "almost" to Palette.almost, "almostDeep" to Palette.almostDeep, "almostSoft" to Palette.almostSoft, "notYet" to Palette.notYet,
        "notYetDeep" to Palette.notYetDeep, "notYetSoft" to Palette.notYetSoft,
    )
    for (row in swatches.chunked(6)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((name, color) in row) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(26.dp).background(color, RoundedCornerShape(6.dp)).background(Color.Transparent))
                    FoniaText(name, style = ui(8.5), color = Palette.inkSoft, maxLines = 1, minimumScaleFactor = 0.6f)
                }
            }
            repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (field in Field.entries) {
            Column(
                Modifier.weight(1f).background(field.background, RoundedCornerShape(8.dp)).padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FoniaText(field.name, style = ui(12, UIWeight.bold), color = field.foreground)
                Box(Modifier.size(22.dp).background(field.deep, RoundedCornerShape(5.dp)))
                Box(Modifier.size(22.dp).background(field.soft, RoundedCornerShape(5.dp)))
                Box(Modifier.size(22.dp).background(field.solid, RoundedCornerShape(5.dp)))
                Box(Modifier.fillMaxWidth().height(5.dp).background(field.track, RoundedCornerShape(3.dp)))
            }
        }
    }

    Heading("Litere")
    val styles = listOf(
        "fDisplay" to fDisplay, "fTitle" to fTitle, "fPrompt" to fPrompt, "fHeadline" to fHeadline, "fLead" to fLead,
        "fBody" to fBody, "fBodyStrong" to fBodyStrong, "fCaption" to fCaption, "fSmall" to fSmall, "fButton" to fButton,
        "fValue 0123" to fValue, "fNumber 0123" to fNumber, "fWord" to fWord, "fPhrase" to fPhrase,
    )
    for ((name, style) in styles) {
        FoniaText("$name · Șarpe Țânțar", style = style, maxLines = 1, minimumScaleFactor = 0.5f)
    }

    Heading("Butoane")
    StyledButton(onClick = {}, style = FoniButtonStyle()) { CTALabel("Mai departe") }
    StyledButton(onClick = {}, style = FoniButtonStyle(kind = FoniButtonStyle.Kind.secondary)) { CTALabel("Mai târziu") }
    StyledButton(onClick = {}, style = FoniButtonStyle(size = FoniButtonStyle.Size.regular)) { CTALabel("Începem") }
    StyledButton(onClick = {}, style = FoniButtonStyle(), enabled = false) { CTALabel("Gata (dezactivat)") }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StyledButton(onClick = {}, style = ChoicePillStyle(ChoicePillStyle.Tone.main), modifier = Modifier.weight(1f)) { FoniaText("Un copil") }
        StyledButton(onClick = {}, style = ChoicePillStyle(ChoicePillStyle.Tone.quiet), modifier = Modifier.weight(1f)) { FoniaText("Eu") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StyledButton(onClick = {}, style = PillButtonStyle(), modifier = Modifier.weight(1f)) { FoniaLabel("Cum spun S?", FoniaIcon.mouth, 20f) }
        StyledButton(onClick = {}, style = PillButtonStyle(), modifier = Modifier.weight(1f)) { FoniaLabel("Încearcă", FoniaIcon.mirror, 20f) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        StyledButton(onClick = {}, style = MiniPillStyle()) { FoniaLabel("Ascultă", FoniaIcon.speaker, 16f) }
        StyledButton(onClick = {}, style = MiniPillStyle()) { FoniaLabel("Cum spun?", FoniaIcon.mouth, 16f) }
        StyledButton(onClick = {}, style = QuietButtonStyle(Palette.accentText)) { FoniaText("Mai târziu") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (icon in listOf(FoniaIcon.back, FoniaIcon.close, FoniaIcon.help, FoniaIcon.settings)) {
            StyledButton(onClick = {}, style = ToolButtonStyle()) { FoniaIconView(icon, size = 18f) }
        }
        StyledButton(onClick = {}, style = RoundButtonStyle(diameter = 56.dp)) { FoniaIconView(FoniaIcon.microphone, size = 28f) }
        StyledButton(onClick = {}, style = PlusButtonStyle()) { FoniaIconView(FoniaIcon.plus, size = 24f) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        StyledButton(onClick = {}, style = StartButtonStyle()) {}
        Caption("StartButtonStyle (apasă-l)")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        var picked by remember { mutableIntStateOf(1) }
        listOf("L", "S", "Z").forEachIndexed { index, letter ->
            StyledButton(
                onClick = { picked = index },
                style = TileButtonStyle(border = if (picked == index) Palette.ink else null),
                modifier = Modifier.weight(1f),
            ) {
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                    FoniaText(letter, style = practice(26, PracticeWeight.bold), color = Field.cold.deep)
                }
            }
        }
    }

    Heading("Evaluare")
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        for (rating in Rating.entries) RatingMark(rating, Modifier.size(40.dp))
    }
    var selected by remember { mutableStateOf<Rating?>(Rating.nearly) }
    RatingRow(
        choices = listOf(RatingChoice(Rating.yes, "Da"), RatingChoice(Rating.nearly, "Aproape"), RatingChoice(Rating.notYet, "Încă nu")),
        onPick = { selected = it },
        suggested = Rating.yes,
        selected = selected,
    )

    Heading("Progres")
    RungStrip(total = 6, reached = 2, color = Field.cold.deep, track = Field.cold.track)
    var session by remember { mutableStateOf(0.0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1200)
            session = if (session >= 1.0) 0.0 else session + 0.25
        }
    }
    SessionBar(session, Palette.accent, Field.paper.track)
    ProgressPill(0, 13)
    ProgressPill(3, 13)
    ProgressPill(13, 13)
    Row {
        RingMedal(0.0, "0/13", Modifier.weight(1f)) { FoniaText("S", style = practice(24, PracticeWeight.bold), color = Palette.ink) }
        RingMedal(0.4, "5/13", Modifier.weight(1f)) { FoniaText("Ș", style = practice(24, PracticeWeight.bold), color = Palette.ink) }
        RingMedal(1.0, "13/13", Modifier.weight(1f)) { FoniaText("R", style = practice(24, PracticeWeight.bold), color = Palette.ink) }
    }

    Heading("Carduri")
    var count by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            count += 1
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CountPill(FoniaIcon.fire, Palette.today, count, "zile")
        CountPill(FoniaIcon.star, Palette.starTint, 0, "stele")
    }
    WideCard(SceneName.cold, "Sunetul S", done = 0, total = 13, detail = "Șarpele Sâsâilă") {
        WordPicture("1f40d", Modifier.size(80.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GridCard(SceneName.play, "Urechi de detectiv", Modifier.weight(1f))
        GridCard(SceneName.speaking, "Spune perechile", Modifier.weight(1f), detail = "Două cuvinte")
    }
    Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(16.dp))) {
        SceneArt(SceneName.warmup, 1.1f, Modifier.fillMaxWidth())
        FloatingPanel(
            "Un joc pentru ureche, unul pentru gură și unul cu oglinda.",
            Modifier.align(Alignment.BottomCenter).padding(10.dp),
        )
    }
    StatRow(art = "art-calendar", label = "Zile cu exerciții", value = "$count")
    Surface { FoniaText("Surface: un card alb pe pagină.", style = fBody) }
    PracticeSheet {
        TargetWord("Șarpele", graphemes = listOf("ș"), target = Field.warm.deep)
    }
    PracticeSheet {
        MarkedSentence(
            words = listOf(
                MarkedWord(0, "Sâsâilă", WordMark.good),
                MarkedWord(1, "e", WordMark.plain),
                MarkedWord(2, "un", WordMark.current),
                MarkedWord(3, "șarpe", WordMark.weak),
                MarkedWord(4, "vesel", WordMark.missed),
                MarkedWord(5, "care", WordMark.extra),
                MarkedWord(6, "sâsâie.", WordMark.plain),
            ),
            accent = Field.cold.deep,
            onTap = {},
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        ArtTile(ArtTileMark.Art("art-book"))
        ArtTile(ArtTileMark.Art("art-lips"))
        ArtTile(ArtTileMark.Letter("S", Field.cold))
        ArtTile(ArtTileMark.Letter("Ș", Field.warm))
        ArtTile(ArtTileMark.Letter("R", Field.tip))
        ArtTile(ArtTileMark.Letter("CE", Field.warm))
    }
    Box(Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(16.dp))) {
        SceneArt(SceneName.warmup, 2.5f, Modifier.fillMaxWidth())
        RisingPanel(Modifier.align(Alignment.BottomCenter).height(70.dp)) {
            Caption("RisingPanel (sage, 25)")
        }
    }

    Heading("Voce")
    VoiceBars(isLive = true, color = Palette.accent, level = fakeLevel, modifier = Modifier.fillMaxWidth().height(44.dp))
    HoldMeter(seconds = 3.0, isLive = true, color = Palette.accent, track = Palette.progressTrack, progress = {
        ((System.nanoTime() / 1e9) % 4.0 / 3.0).coerceAtMost(1.0)
    })
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        var out by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(3000)
                out = !out
            }
        }
        BlowFlame(isLive = true, isOut = false, strength = fakeLevel, modifier = Modifier.weight(1f).height(150.dp))
        BlowFlame(isLive = true, isOut = out, strength = { 0f }, modifier = Modifier.weight(1f).height(150.dp))
        PaceChart(
            samples = listOf(90.0, 120.0, 150.0, 140.0, 170.0, 160.0, 130.0, 145.0),
            zone = 120.0..160.0,
            modifier = Modifier.weight(1.4f).height(150.dp),
        )
    }
    MelodyOverlay(
        model = listOf(0.0, 1.0, 2.5, 3.0, 2.0, null, null, -1.0, -2.0, -1.5, 0.0),
        take = listOf(-0.5, 0.5, 2.0, 3.5, 1.0, 0.5, null, -0.5, -2.5, -1.0, 0.5),
        modelColor = Palette.cold.opacity(0.3),
        takeColor = Palette.ink,
        modifier = Modifier.fillMaxWidth().height(110.dp),
    )

    Heading("Iconițe")
    val icons = FoniaIcon.entries.map { it.rawValue } + FoniaIcon.tabs
    for (row in icons.chunked(6)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (name in row) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    TemplateImage(name, Modifier.size(28.dp), tint = Palette.ink)
                    FoniaText(name, style = ui(7.5), color = Palette.inkSoft, maxLines = 1, minimumScaleFactor = 0.6f)
                }
            }
            repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        FoniaIconView(FoniaIcon.fire, tint = Palette.today)
        FoniaIconView(FoniaIcon.star, tint = Palette.starTint)
        FoniaIconView(FoniaIcon.speaker, tint = Palette.accentText)
        for (name in listOf("icon-star", "icon-flame", "icon-zzz", "candle", "flame", "choice-adult")) {
            WordPicture(name, Modifier.size(34.dp))
        }
    }

    Heading("Mișcare")
    var burst by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp))) {
        SceneBackdrop(SceneName.lesson)
        // A fresh burst each time: the iOS view starts once and is never reset, so a new one replaces it.
        androidx.compose.runtime.key(burst) { CelebrationBurst(isActive = burst > 0, modifier = Modifier.fillMaxSize()) }
        StyledButton(
            onClick = { burst += 1 },
            style = FoniButtonStyle(size = FoniButtonStyle.Size.regular),
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 220.dp),
        ) { CTALabel("Steluțe") }
    }
    Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(16.dp))) {
        SceneBackdrop(SceneName.night)
    }
}

// ---------------------------------------------------------------------------------------------------------
// Home, built from the design pieces with the numbers of HomeScreen.swift, to hold next to an iPhone.

@Composable
private fun HomeSamplePage() {
    Box(Modifier.fillMaxSize().background(Palette.paper)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(bottom = 52.dp + 64.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 2.dp).padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CountPill(FoniaIcon.fire, Palette.today, 0, "0 zile cu exerciții")
                Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                FoniaText("Salut!", style = ui(23.5, UIWeight.bold, TextRole.title2), tracking = 0.6.sp, color = Palette.ink, maxLines = 1, minimumScaleFactor = 0.7f)
                Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                CountPill(FoniaIcon.star, Palette.starTint, 0, "0 stele")
            }
            val card = ContinuousRoundedRectangle(22.dp)
            Row(
                Modifier
                    .softShadow(card)
                    .background(Palette.sheet, card)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Foni(Modifier.size(98.dp), mood = FoniMood.hello, reactsToTap = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FoniaText("Azi lucrăm la S", style = ui(19, UIWeight.bold, TextRole.headline), color = Palette.ink, maxLines = 2, minimumScaleFactor = 0.8f)
                    FoniaText("Apasă butonul galben și începem.", style = fBody, color = Palette.inkSoft)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProgressPill(0, 13, Modifier.weight(1f))
                        FoniaText("0 din 13", style = ui(12.5, UIWeight.semibold, TextRole.caption).monospacedDigit(), color = Palette.inkSoft)
                    }
                    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StyledButton(onClick = {}, style = MiniPillStyle()) { FoniaLabel("Ascultă", FoniaIcon.speaker, 16f) }
                        StyledButton(onClick = {}, style = MiniPillStyle()) { FoniaLabel("Cum spun?", FoniaIcon.mouth, 16f) }
                    }
                }
            }
            StyledButton(onClick = {}, style = PressableStyle()) { HomeTile(SceneName.sounds, "Sunete", aspect = 2.9f) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StyledButton(onClick = {}, style = PressableStyle(), modifier = Modifier.weight(1f)) { HomeTile(SceneName.guide, "Cum spun?") }
                StyledButton(onClick = {}, style = PressableStyle(), modifier = Modifier.weight(1f)) { HomeTile(SceneName.warmup, "Încălzirea") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StyledButton(onClick = {}, style = PressableStyle(), modifier = Modifier.weight(1f)) { HomeTile(SceneName.play, "Jocuri") }
                StyledButton(onClick = {}, style = PressableStyle(), modifier = Modifier.weight(1f)) { HomeTile(SceneName.stories, "Povești") }
            }
        }
        // MainTabs' bar: two tabs either side of a 96 pt gap, the yellow button rising 25 pt out of it.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.sheet)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .height(64.dp),
            ) {
                TabItem("Acasă", "tab-home", active = true, Modifier.weight(1f))
                Spacer(Modifier.width(96.dp))
                TabItem("Progres", "tab-progress", active = false, Modifier.weight(1f))
            }
            StyledButton(onClick = {}, style = StartButtonStyle(), modifier = Modifier.align(Alignment.TopCenter).offset(y = (-25).dp)) {}
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// A lesson screen ("Cum spun S?"), with the numbers of SessionScreen.swift and ExerciseHost.swift.

@Composable
private fun SessionSamplePage() {
    Box(Modifier.fillMaxSize()) {
        SceneBackdrop(SceneName.cold)
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp)
                .padding(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                StyledButton(onClick = {}, style = ToolButtonStyle(field = Field.cold)) { FoniaIconView(FoniaIcon.close, size = 18f) }
                SessionBar(0.0, Palette.accent, Palette.sheet, Modifier.weight(1f), height = 12.dp)
            }
            FoniaText("Cum spun S?", style = fDisplay, color = Field.cold.foreground, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            PracticeSheet {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        FacePatch(Modifier.matchParentSize().clip(ContinuousRoundedRectangle(18.dp)))
                        MouthView(viseme("s"), zoom = 1.5, isLive = false)
                    }
                    Row(Modifier.fillMaxWidth().background(Palette.cloud, RoundedCornerShape(50)).padding(3.dp)) {
                        Box(Modifier.weight(1f).background(Palette.sheet, RoundedCornerShape(50)).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                            FoniaText("Din față", style = ui(13), tracking = 0.sp)
                        }
                        Box(Modifier.weight(1f).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                            FoniaText("Din lateral", style = ui(13), tracking = 0.sp)
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Zâmbește larg, ca la poză.", "Ține dinții aproape.", "Vârful limbii stă jos, după dinți.", "Suflă aer rece printre dinți: ssss.")
                    .forEachIndexed { index, line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(26.dp).background(Palette.buttonFill, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                                FoniaText("${index + 1}", style = ui(15, UIWeight.bold, TextRole.footnote).monospacedDigit(), color = Palette.buttonLabel)
                            }
                            FoniaText(line, style = ui(18, if (index == 0) UIWeight.semibold else UIWeight.regular), color = Field.cold.foreground)
                        }
                    }
            }
            Spacer(Modifier.weight(1f))
            StyledButton(onClick = {}, style = FoniButtonStyle(field = Field.cold)) { CTALabel("Încerc") }
        }
    }
}

@Composable
private fun TabItem(title: String, icon: String, active: Boolean, modifier: Modifier) {
    val ink = if (active) Palette.ink else Palette.inkSoft
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        FoniaTabIcon(if (active) "$icon-on" else icon, Modifier.size(26.dp), tint = ink)
        FoniaText(title, style = ui(14, if (active) UIWeight.semibold else UIWeight.regular, TextRole.footnote), tracking = 0.8.sp, color = ink, maxLines = 1, minimumScaleFactor = 0.8f)
    }
}

// ---------------------------------------------------------------------------------------------------------
// Foni

@Composable
private fun FoniPage() = Page {
    Heading("Foni, din 13 straturi (atinge-l)")
    for (row in FoniMood.entries.chunked(3)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mood in row) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Foni(Modifier.fillMaxWidth(), mood = mood, reactsToTap = true)
                    Caption(mood.name)
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                VoiceRings(color = FoniTone.cold.glow, isLive = true, level = fakeLevel, modifier = Modifier.fillMaxSize())
                Foni(Modifier.fillMaxSize(0.72f), mood = FoniMood.listening, level = fakeLevel)
            }
            Caption("listening + voce + VoiceRings")
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Foni(Modifier.fillMaxWidth(), mood = FoniMood.idle, flipped = true, reactsToTap = true)
            Caption("idle, flipped")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (tone in FoniTone.entries) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                VoiceRings(color = tone.glow, isLive = true, level = fakeLevel, modifier = Modifier.fillMaxWidth())
                Caption(tone.name)
            }
        }
    }
    Heading("Poze fixe (MascotView)")
    for (row in MascotPose.entries.chunked(5)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (pose in row) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    MascotView(pose, Modifier.fillMaxWidth())
                    Caption(pose.rawValue)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// Mouths

/** Every viseme of `FoniaDomain/Viseme.swift` with its mouth numbers, so the gallery needs nothing from :domain. */
internal val galleryVisemes: List<Pair<String, MouthShape>> = listOf(
    "rest" to MouthShape(open = 0.1, width = 0.55, round = 0.0, smile = 0.6, teeth = 0.25, tongue = TonguePose.rest),
    "a" to MouthShape(open = 0.85, width = 0.62, round = 0.0, smile = 0.15, teeth = 0.25, tongue = TonguePose.rest),
    "e" to MouthShape(open = 0.5, width = 0.8, round = 0.0, smile = 0.3, teeth = 0.5, tongue = TonguePose.rest),
    "i" to MouthShape(open = 0.28, width = 0.95, round = 0.0, smile = 0.5, teeth = 0.75, tongue = TonguePose.rest),
    "o" to MouthShape(open = 0.6, width = 0.38, round = 0.7, smile = 0.15, teeth = 0.15, tongue = TonguePose.rest),
    "u" to MouthShape(open = 0.25, width = 0.22, round = 1.0, smile = 0.15, teeth = 0.0, tongue = TonguePose.hidden),
    "ă" to MouthShape(open = 0.4, width = 0.5, round = 0.0, smile = 0.05, teeth = 0.3, tongue = TonguePose.rest),
    "î" to MouthShape(open = 0.3, width = 0.62, round = 0.0, smile = 0.1, teeth = 0.55, tongue = TonguePose.rest),
    "s" to MouthShape(open = 0.13, width = 0.86, round = 0.0, smile = 0.45, teeth = 1.0, tongue = TonguePose.hidden),
    "z" to MouthShape(open = 0.13, width = 0.86, round = 0.0, smile = 0.45, teeth = 1.0, tongue = TonguePose.hidden),
    "ț" to MouthShape(open = 0.14, width = 0.82, round = 0.0, smile = 0.4, teeth = 1.0, tongue = TonguePose.hidden),
    "ș" to MouthShape(open = 0.3, width = 0.3, round = 0.9, smile = 0.15, teeth = 0.85, tongue = TonguePose.hidden),
    "j" to MouthShape(open = 0.3, width = 0.3, round = 0.9, smile = 0.15, teeth = 0.85, tongue = TonguePose.hidden),
    "ce" to MouthShape(open = 0.3, width = 0.34, round = 0.8, smile = 0.15, teeth = 0.8, tongue = TonguePose.hidden),
    "ge" to MouthShape(open = 0.3, width = 0.34, round = 0.8, smile = 0.15, teeth = 0.8, tongue = TonguePose.hidden),
    "r" to MouthShape(open = 0.42, width = 0.55, round = 0.0, smile = 0.1, teeth = 0.3, tongue = TonguePose.tipUp, vibrate = true),
    "l" to MouthShape(open = 0.5, width = 0.6, round = 0.0, smile = 0.2, teeth = 0.4, tongue = TonguePose.tipUp),
    "m" to MouthShape(open = 0.0, width = 0.5, round = 0.0, smile = 0.1, teeth = 0.0, tongue = TonguePose.hidden),
    "p" to MouthShape(open = 0.0, width = 0.48, round = 0.0, smile = 0.0, teeth = 0.0, tongue = TonguePose.hidden),
    "b" to MouthShape(open = 0.0, width = 0.48, round = 0.0, smile = 0.0, teeth = 0.0, tongue = TonguePose.hidden),
    "f" to MouthShape(open = 0.14, width = 0.6, round = 0.0, smile = 0.1, teeth = 0.0, tongue = TonguePose.hidden, lipBite = true),
    "v" to MouthShape(open = 0.14, width = 0.6, round = 0.0, smile = 0.1, teeth = 0.0, tongue = TonguePose.hidden, lipBite = true),
    "t" to MouthShape(open = 0.22, width = 0.62, round = 0.0, smile = 0.15, teeth = 0.7, tongue = TonguePose.tipUp),
    "d" to MouthShape(open = 0.22, width = 0.62, round = 0.0, smile = 0.15, teeth = 0.7, tongue = TonguePose.tipUp),
    "n" to MouthShape(open = 0.22, width = 0.6, round = 0.0, smile = 0.15, teeth = 0.6, tongue = TonguePose.tipUp),
    "k" to MouthShape(open = 0.38, width = 0.58, round = 0.0, smile = 0.15, teeth = 0.4, tongue = TonguePose.up),
    "g" to MouthShape(open = 0.38, width = 0.58, round = 0.0, smile = 0.15, teeth = 0.4, tongue = TonguePose.up),
    "h" to MouthShape(open = 0.5, width = 0.6, round = 0.0, smile = 0.15, teeth = 0.3, tongue = TonguePose.rest),
    "th" to MouthShape(open = 0.2, width = 0.62, round = 0.0, smile = 0.15, teeth = 0.8, tongue = TonguePose.between),
    "w" to MouthShape(open = 0.14, width = 0.16, round = 1.0, smile = 0.15, teeth = 0.0, tongue = TonguePose.hidden),
    "smileBig" to MouthShape(open = 0.12, width = 1.0, round = 0.0, smile = 1.0, teeth = 0.9, tongue = TonguePose.hidden),
    "kiss" to MouthShape(open = 0.05, width = 0.12, round = 1.0, smile = 0.0, teeth = 0.0, tongue = TonguePose.hidden),
    "puff" to MouthShape(open = 0.0, width = 0.42, round = 0.0, smile = 0.0, teeth = 0.0, puff = 1.0, tongue = TonguePose.hidden),
    "lion" to MouthShape(open = 1.0, width = 0.7, round = 0.0, smile = 0.0, teeth = 0.6, tongue = TonguePose.rest),
    "tongueOut" to MouthShape(open = 0.55, width = 0.6, round = 0.0, smile = 0.1, teeth = 0.0, tongue = TonguePose.out),
    "tongueUp" to MouthShape(open = 0.55, width = 0.6, round = 0.0, smile = 0.1, teeth = 0.0, tongue = TonguePose.outUp),
    "tongueLeft" to MouthShape(open = 0.5, width = 0.62, round = 0.0, smile = 0.1, teeth = 0.0, tongue = TonguePose.outLeft),
    "tongueRight" to MouthShape(open = 0.5, width = 0.62, round = 0.0, smile = 0.1, teeth = 0.0, tongue = TonguePose.outRight),
    "tongueInsideUp" to MouthShape(open = 0.75, width = 0.62, round = 0.0, smile = 0.0, teeth = 0.3, tongue = TonguePose.tipUp),
    "blow" to MouthShape(open = 0.1, width = 0.18, round = 1.0, smile = 0.15, teeth = 0.0, puff = 0.35, tongue = TonguePose.hidden),
)

private fun viseme(name: String): MouthShape = galleryVisemes.first { it.first == name }.second

/** `MouthGallery.swift`: the 18 sounds a speech therapist checks, front and side. */
private val mouthGalleryPoses = listOf("s", "z", "ș", "j", "ț", "ce", "ge", "r", "l", "th", "f", "v", "w", "a", "o", "u", "i", "m")

/** The iOS `MouthGallery`, cell for cell: three columns, 6 apart, each a mouth on skin, the profile and the name. */
@Composable
fun MouthGallery(poses: List<String> = mouthGalleryPoses, isLive: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in poses.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (pose in row) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height(76.dp), contentAlignment = Alignment.Center) {
                            FacePatch(Modifier.matchParentSize().clip(RoundedCornerShape(10.dp)))
                            MouthView(viseme(pose), isLive = isLive, zoom = 1.5)
                        }
                        SagittalView(SagittalPose.named(pose), Modifier.height(76.dp), isLive = isLive)
                        // `.font(.caption2)`: the one system font in the app.
                        FoniaText(pose, style = ui(11), tracking = 0.sp, color = Color.Black)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MouthsPage() = Page(background = Color.White, padding = 6) {
    MouthGallery()
    Heading("Toate gurile (din față și din lateral, vii)")
    MouthGallery(galleryVisemes.map { it.first }.filter { it !in mouthGalleryPoses }, isLive = true)
    Heading("Din lateral, toate pozele, vii")
    val sagittal = listOf("rest", "s", "z", "ț", "ș", "j", "ce", "ge", "r", "l", "t", "k", "g", "th", "f", "v")
    for (row in sagittal.chunked(4)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (name in row) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    SagittalView(SagittalPose.named(name), Modifier.fillMaxWidth(), isLive = true)
                    Caption(name)
                }
            }
        }
    }
    Heading("Arcul (o poză nouă la 1,4 s)")
    val cycle = listOf("s", "ș", "r", "a", "f", "th", "tongueOut", "o", "l", "i")
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1400)
            index = (index + 1) % cycle.size
        }
    }
    val name = cycle[index]
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(150.dp), contentAlignment = Alignment.Center) {
            FacePatch(Modifier.matchParentSize().clip(RoundedCornerShape(18.dp)))
            MouthView(viseme(name), zoom = 1.5, driver = MouthDriver(talk = { fakeLevel().toDouble() }))
        }
        Box(Modifier.weight(1f).height(150.dp).background(Color.hex(0xFFF4E4), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            SagittalView(SagittalPose.named(name), Modifier.fillMaxSize())
        }
    }
    Caption(name, Modifier.fillMaxWidth())
}

// ---------------------------------------------------------------------------------------------------------
// Scenes

@Composable
private fun ScenesPage() = Page {
    Heading("Fiecare scenă: bază 1.89, -wide 2.5, -tall 1.1")
    for (scene in SceneName.entries) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1.89f)) { SceneArt(scene, 1.89f, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) }
            Column(Modifier.weight(2.5f)) { SceneArt(scene, 2.5f, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) }
            Column(Modifier.weight(1.1f)) { SceneArt(scene, 1.1f, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) }
        }
        Caption("${scene.rawValue}${if (scene.hasSky) "" else " · fără cer"}", Modifier.fillMaxWidth())
    }
    Heading("Paginile (-page) cu cerul viu")
    val pages = listOf(SceneName.start, SceneName.lesson, SceneName.cold, SceneName.warm, SceneName.tip, SceneName.night)
    for (row in pages.chunked(3)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (scene in row) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.46f).clip(RoundedCornerShape(10.dp))) { SceneBackdrop(scene) }
                    Caption(scene.rawValue)
                }
            }
        }
    }
}
