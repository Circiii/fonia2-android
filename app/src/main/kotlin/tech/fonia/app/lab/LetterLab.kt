package tech.fonia.app.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tech.fonia.designsystem.letter.LetterCue
import tech.fonia.designsystem.letter.LetterDrive
import tech.fonia.designsystem.letter.LetterFrameStats
import tech.fonia.designsystem.letter.LiveLetter
import tech.fonia.designsystem.letter.LiveLetterState
import tech.fonia.designsystem.letter.LiveWord
import tech.fonia.designsystem.letter.StickerText
import tech.fonia.designsystem.theme.FoniaStage
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.theme.LocalReduceMotion
import tech.fonia.designsystem.tokens.FoniaCorner
import tech.fonia.designsystem.tokens.FoniaSpace
import tech.fonia.designsystem.tokens.FoniaSticker
import tech.fonia.model.ErrorKind
import kotlin.math.sin

/** A practice word and the indices of its target letters. */
data class LabWord(val text: String, val targets: List<IntRange>, val hero: String, val syllables: List<IntRange>)

val LAB_LETTERS = listOf("S", "Ș", "Z", "J", "Ț", "R", "L", "C")
val LAB_WORDS = listOf(
    LabWord("soare", listOf(0..0), "S", listOf(0..1, 2..4)),
    LabWord("șarpe", listOf(0..0), "Ș", listOf(0..2, 3..4)),
    LabWord("rață", listOf(0..0), "R", listOf(0..1, 2..3)),
    LabWord("mașină", listOf(2..2), "Ș", listOf(0..1, 2..3, 4..5)),
)

/** The letter lab: drive the hero letter with sliders, fire every named animation, or run the scripted demo. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LetterLab(startDemo: Boolean, startOnPaper: Boolean = false, startLetter: String? = null) {
    val c = FoniaTheme.colors
    val drive = remember { LetterDrive().apply { inflation = 0.55f } }
    val hero = remember { LiveLetterState() }
    val wordState = remember { LiveLetterState() }
    var grapheme by remember { mutableStateOf(startLetter ?: "S") }
    var word by remember { mutableStateOf<LabWord?>(null) }
    var heard by remember { mutableStateOf<String?>("J") }
    var stage by remember { mutableStateOf(!startOnPaper) }
    val systemReduce = LocalReduceMotion.current
    var reduce by remember { mutableStateOf(systemReduce) }
    var demoJob by remember { mutableStateOf<Job?>(null) }
    var tick by remember { mutableIntStateOf(0) } // 10 Hz refresh of the readouts and sliders
    val scope = rememberCoroutineScope()

    fun cue(cue: LetterCue) {
        hero.cue(cue)
        if (word != null) wordState.cue(cue)
    }

    fun startDemo() {
        demoJob?.cancel()
        demoJob = scope.launch {
            while (isActive) {
                runDemo(
                    drive, hero, wordState,
                    setLetter = { grapheme = it; word = null },
                    setWord = { w -> word = w; grapheme = w.hero },
                    setHeard = { heard = it },
                )
                delay(1200)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (startDemo) startDemo()
        while (true) { delay(100); tick++ }
    }

    CompositionLocalProvider(LocalReduceMotion provides reduce) {
        Column(
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            // ---- the scene
            val sceneBg = if (stage) c.accent else c.surface
            Box(
                Modifier
                    .padding(horizontal = FoniaSpace.m, vertical = FoniaSpace.xs)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FoniaCorner.sheet))
                    .background(sceneBg),
            ) {
                val content: @Composable () -> Unit = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = FoniaSpace.xs)) {
                        LiveLetter(
                            grapheme = grapheme,
                            state = hero,
                            drive = drive,
                            heard = heard,
                            height = if (word == null) 320.dp else 230.dp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val w = word
                        if (w != null) {
                            LiveWord(
                                text = w.text,
                                target = w.targets,
                                state = wordState,
                                drive = drive,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = FoniaSpace.m),
                            )
                        } else {
                            Spacer(Modifier.height(FoniaSpace.xs))
                        }
                    }
                }
                if (stage) FoniaStage(content) else content()
            }

            // ---- readout
            FrameReadout(tick, hero)

            // ---- controls
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = FoniaSpace.m),
                verticalArrangement = Arrangement.spacedBy(FoniaSpace.s),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StickerText("Laboratorul literei", FoniaSticker.Sun, fontSize = FoniaTheme.type.title2.fontSize)
                }
                Section("Literă")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(FoniaSpace.xs), verticalArrangement = Arrangement.spacedBy(FoniaSpace.xs)) {
                    LAB_LETTERS.forEach { g -> LabChip(g, word == null && grapheme == g) { grapheme = g; word = null; hero.cue(LetterCue.Rest); hero.cue(LetterCue.DropIn) } }
                }
                Section("Cuvânt")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(FoniaSpace.xs), verticalArrangement = Arrangement.spacedBy(FoniaSpace.xs)) {
                    LAB_WORDS.forEach { w ->
                        LabChip(w.text, word == w) { word = w; grapheme = w.hero; hero.cue(LetterCue.Rest); wordState.cue(LetterCue.DropIn) }
                    }
                }

                Section("Vocea (LetterDrive)")
                tick.let { } // re-read the drive at 10 Hz
                LabSlider("umflare", drive.inflation) { drive.inflation = it; drive.inflateWithVoice = false }
                LabSlider("ascuțit S↔Ș", if (drive.sharpness.isNaN()) 0.5f else drive.sharpness, valueText = if (drive.sharpness.isNaN()) "auto" else null) { drive.sharpness = it }
                LabSlider("nivel", drive.level) { drive.level = it; drive.speaking = it > 0.05f }
                LabSlider("tremur", drive.trembling) { drive.trembling = it }
                LabSlider("tril", drive.trill) { drive.trill = it }
                LabSlider("suflat", drive.blow) { drive.blow = it }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(FoniaSpace.xs), verticalArrangement = Arrangement.spacedBy(FoniaSpace.xs)) {
                    LabToggle("ascultă", drive.listening) { drive.listening = it }
                    LabToggle("vorbește", drive.speaking) { drive.speaking = it; drive.level = if (it) 0.6f else 0f }
                    LabToggle("umflă cu vocea", drive.inflateWithVoice) { drive.inflateWithVoice = it }
                    LabToggle("S↔Ș auto", drive.sharpness.isNaN()) { drive.sharpness = if (it) Float.NaN else 0.5f }
                    LabToggle("scenă", stage) { stage = it }
                    LabToggle("mișcare redusă", reduce) { reduce = it }
                }

                Section("Vocabular (§5.1)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(FoniaSpace.xs), verticalArrangement = Arrangement.spacedBy(FoniaSpace.xs)) {
                    LabButton("▶ demo", strong = true) { if (demoJob?.isActive == true) { demoJob?.cancel(); demoJob = null } else startDemo() }
                    LetterCue.vocabulary.forEach { v -> LabButton(v.name) { if (v is LetterCue.SwapGlide) heard = v.heard; cue(v) } }
                    if (word != null) {
                        LabButton("slot-drop") { wordState.cue(LetterCue.SlotDrop(word!!.targets.first().first)) }
                        word!!.syllables.forEachIndexed { i, r -> LabButton("silaba ${i + 1}") { wordState.cue(LetterCue.Syllable(r, stressed = i == 0)) } }
                    }
                }
                Section("Instrucțiuni (ErrorKind, §5.2)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(FoniaSpace.xs), verticalArrangement = Arrangement.spacedBy(FoniaSpace.xs)) {
                    ErrorKind.entries.forEach { k -> LabButton(k.raw) { heard = null; cue(LetterCue.Instruct(k)) } }
                }
                Spacer(Modifier.height(FoniaSpace.xxl))
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    BasicText(text, Modifier.padding(top = FoniaSpace.xs), style = FoniaTheme.type.title2.copy(color = FoniaTheme.colors.ink))
}

/** fps / frame time (vsync intervals), the letter's draw time, LiveLetter recompositions, active cues. */
@Composable
private fun FrameReadout(tick: Int, state: LiveLetterState) {
    val intervals = remember { FloatArray(120) }
    var n by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var last = 0L
        var i = 0
        while (true) {
            val t = withFrameNanos { it }
            if (last != 0L) { intervals[i % intervals.size] = (t - last) / 1e6f; i++; n = minOf(i, intervals.size) }
            last = t
        }
    }
    val text = remember(tick) {
        val k = n
        if (k == 0) "…" else {
            val s = intervals.copyOf(k).sorted()
            val mean = s.average()
            val p90 = s[(k * 0.9f).toInt().coerceAtMost(k - 1)]
            "%.0f fps · cadru %.1f ms (p90 %.1f) · desen %.2f ms · recompuneri %d · %s".format(
                1000.0 / mean, mean, p90, LetterFrameStats.avgDrawNanos / 1e6, LetterFrameStats.recompositions, state.activeCues.ifEmpty { "—" },
            )
        }
    }
    BasicText(
        text,
        Modifier.padding(horizontal = FoniaSpace.m, vertical = FoniaSpace.xxs),
        style = FoniaTheme.type.caption.copy(color = FoniaTheme.colors.inkSecondary),
    )
}

/** The scripted demo (≈ 29 s): simulated voice, inflate and POP, verdicts, S→Ș morph, every instruction animation. */
private suspend fun runDemo(
    d: LetterDrive,
    hero: LiveLetterState,
    word: LiveLetterState,
    setLetter: (String) -> Unit,
    setWord: (LabWord) -> Unit,
    setHeard: (String?) -> Unit,
) {
    suspend fun voice(seconds: Float, base: Float = 0.55f) {
        d.speaking = true
        var start = 0L
        while (true) {
            val t = withFrameNanos { it }
            if (start == 0L) start = t
            val s = (t - start) / 1e9f
            if (s >= seconds) break
            d.level = (base + 0.25f * sin(s * 9f) + 0.12f * sin(s * 23f + 1f)).coerceIn(0f, 1f)
            d.holdSeconds = s
        }
        d.speaking = false; d.level = 0f; d.holdSeconds = 0f
    }

    suspend fun ramp(seconds: Float, set: (Float) -> Unit) {
        var start = 0L
        while (true) {
            val t = withFrameNanos { it }
            if (start == 0L) start = t
            val u = ((t - start) / 1e9f / seconds).coerceIn(0f, 1f)
            set(u)
            if (u >= 1f) break
        }
    }

    fun letter(g: String) { setLetter(g); hero.cue(LetterCue.Rest) }
    suspend fun wait(s: Float) = delay((s * 1000).toLong())

    d.quiet(); d.inflateWithVoice = false; d.sharpness = Float.NaN; d.listening = false; d.inflation = 0.3f
    letter("S"); hero.cue(LetterCue.DropIn); wait(1.0f)
    // listen, then inflate with the voice until it pops
    d.listening = true; wait(0.4f)
    d.inflateWithVoice = true; d.holdGoalSeconds = 2.0f; d.autoPop = true
    voice(2.25f)
    d.listening = false; wait(0.35f)
    d.inflateWithVoice = false; d.inflation = 0.6f
    hero.cue(LetterCue.Correct); wait(1.3f)
    // S → Ș with the voice: the letter rounds and the comma grows
    letter("Ș"); d.sharpness = 1f
    ramp(1.5f) { d.sharpness = 1f - it; d.level = 0.35f * sin(it * 3.14f) }
    wait(0.4f)
    d.sharpness = Float.NaN
    hero.cue(LetterCue.Almost); wait(1.2f)
    hero.cue(LetterCue.Unsure); wait(1.7f)
    hero.cue(LetterCue.Retake); wait(0.8f)
    // the instruction animations
    letter("Ș"); hero.cue(LetterCue.CommaDrop); wait(1.4f)
    letter("S"); hero.cue(LetterCue.CommaLift); wait(1.5f)
    letter("Z"); setHeard("J"); hero.cue(LetterCue.SwapGlide("J")); wait(1.4f)
    letter("R"); hero.cue(LetterCue.TrillRipple(uvular = true)); wait(1.2f)
    letter("Z"); hero.cue(LetterCue.VoicingWave); wait(1.6f)
    letter("S"); hero.cue(LetterCue.AirToCentre); wait(1.6f)
    hero.cue(LetterCue.TongueBehindTeeth); wait(1.7f)
    letter("Ț"); hero.cue(LetterCue.ClosureSnap); wait(1.1f)
    letter("S"); hero.cue(LetterCue.Stretch); wait(1.2f)
    hero.cue(LetterCue.SagAndRefill); wait(1.7f)
    // a word: drop in, two syllable beats, correct
    hero.cue(LetterCue.Rest)
    val w = LAB_WORDS[1]
    setWord(w); wait(0.05f)
    word.cue(LetterCue.DropIn); hero.cue(LetterCue.DropIn); wait(0.8f)
    word.cue(LetterCue.Syllable(w.syllables[0], stressed = true)); hero.cue(LetterCue.Beat()); wait(0.45f)
    word.cue(LetterCue.Syllable(w.syllables[1])); wait(0.55f)
    word.cue(LetterCue.Correct); hero.cue(LetterCue.Correct); wait(1.5f)
    hero.cue(LetterCue.Rest); word.cue(LetterCue.Rest)
}
