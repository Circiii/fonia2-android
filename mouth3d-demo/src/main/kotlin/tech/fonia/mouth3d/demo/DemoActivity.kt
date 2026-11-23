package tech.fonia.mouth3d.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.fonia.model.SoundId
import tech.fonia.mouth3d.Articulator
import tech.fonia.mouth3d.Mouth3D
import tech.fonia.mouth3d.Mouth3DColors
import tech.fonia.mouth3d.Mouth3DFrameStats
import tech.fonia.mouth3d.poseKey

/**
 * Extras: `sound` (rest, s, sh, z, j, ts, ce, ge, r, l), `inside`, `spin`, `progress` (float), `play`, `fallback`,
 * `autofallback` (default false, so SwiftShader on the emulator still shows 3D), `ui` (false = only the mouth),
 * `stage` (`face` = the sound guide's skin card and colours, default; `blue` = docs/v2/04 defaults), `highlight`
 * (lips, teeth, tongue, air).
 */
class DemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val x = intent.extras
        val startSound = x?.getString("sound")?.let { k -> SoundId.entries.firstOrNull { it.poseKey == k } }
        setContent {
            Demo(
                startSound = startSound,
                startInside = x?.getBoolean("inside", false) ?: false,
                startSpin = x?.getBoolean("spin", false) ?: false,
                startProgress = x?.getFloat("progress", 1f) ?: 1f,
                startPlay = x?.getBoolean("play", true) ?: true,
                startFallback = x?.getBoolean("fallback", false) ?: false,
                autoFallback = x?.getBoolean("autofallback", false) ?: false,
                showUi = x?.getBoolean("ui", true) ?: true,
                face = (x?.getString("stage") ?: "face") == "face",
                startHighlight = x?.getString("highlight")?.let { Articulator.of(it) },
                guideBox = x?.getBoolean("box", false) ?: false,
            )
        }
    }
}

private val Stage = Color(0xFF3B78E7)
private val Chip = Color(0x33FFFFFF)
private val ChipOn = Color(0xFFFFD84D)
private val Ink = Color(0xFF14254A)
private val Face = Color(0xFFF8D6BA)

/** The sound guide's colours (legacy/features GuideMouth3D.kt), so the demo shows what the app shows. */
private val GuideColors = Mouth3DColors(
    patch = Color(0xFFF9D2B4),
    lips = Color(0xFFDE6178),
    teeth = Color(0xFFFFFDF8),
    gums = Color(0xFFF28C9A),
    tongue = Color(0xFFFA9CA8),
    lining = Color(0xFF571C2E),
)

@Composable
private fun Demo(
    startSound: SoundId?,
    startInside: Boolean,
    startSpin: Boolean,
    startProgress: Float,
    startPlay: Boolean,
    startFallback: Boolean,
    autoFallback: Boolean,
    showUi: Boolean,
    face: Boolean,
    startHighlight: Articulator?,
    guideBox: Boolean,
) {
    var highlight by remember { mutableStateOf(startHighlight) }
    var sound by remember { mutableStateOf(startSound) }
    var inside by remember { mutableStateOf(startInside) }
    var spin by remember { mutableStateOf(startSpin) }
    var play by remember { mutableStateOf(startPlay) }
    var fallback by remember { mutableStateOf(startFallback) }
    var review by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(startProgress) }
    var stats by remember { mutableStateOf<Mouth3DFrameStats?>(null) }

    Column(Modifier.fillMaxSize().background(if (face) Face else Stage).safeDrawingPadding()) {
        val box = if (guideBox) {
            // the sound guide's picture: 250 dp tall, rounded, the chips' 36 dp kept free at the bottom
            Modifier.padding(16.dp).fillMaxWidth().height(250.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFFF9D9C0)).padding(bottom = 36.dp)
        } else Modifier.fillMaxWidth().weight(1f)
        Mouth3D(
            sound = sound,
            progress = progress,
            inside = inside,
            colors = if (face) GuideColors else Mouth3DColors.Default,
            modifier = box,
            playing = play,
            autoRotate = spin,
            allowUnapprovedInside = review,
            onDoubleTap = { inside = !inside },
            onFrameStats = { stats = it },
            forceFallback = fallback,
            autoFallback = autoFallback,
            highlight = highlight,
        )
        if (!showUi) return@Column
        val label = (sound?.letter ?: "Repaus") + if (inside) "  ·  secțiune" else "  ·  față"
        BasicText(label, Modifier.padding(horizontal = 16.dp), style = TextStyle(color = if (face) Ink else Color.White, fontSize = 20.sp))
        BasicText(
            stats?.let { "cadru %.2f ms (max %.1f), MSAA %d×".format(it.averageMs, it.maxMs, it.msaaSamples) } ?: "cadru: …",
            Modifier.padding(horizontal = 16.dp),
            style = TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp),
        )
        FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Toggle("Repaus", sound == null) { sound = null }
            SoundId.entries.forEach { s -> Toggle(s.letter, sound == s) { sound = s } }
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("repaus", style = TextStyle(color = Color.White, fontSize = 12.sp))
            Slider(progress, { progress = it }, Modifier.weight(1f).padding(horizontal = 10.dp))
            BasicText("poză", style = TextStyle(color = Color.White, fontSize = 12.sp))
        }
        FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Toggle("Secțiune", inside) { inside = !inside }
            Toggle("Rotire", spin) { spin = !spin }
            Toggle("Animație", play) { play = !play }
            Toggle("Revizie", review) { review = !review }
            Toggle("2D", fallback) { fallback = !fallback }
            Articulator.entries.forEach { a -> Toggle(a.name, highlight == a) { highlight = if (highlight == a) null else a } }
        }
    }
}

@Composable
private fun Toggle(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (on) ChipOn else Chip).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        BasicText(text, style = TextStyle(color = if (on) Ink else Color.White, fontSize = 15.sp))
    }
}

@Composable
private fun Slider(value: Float, onChange: (Float) -> Unit, modifier: Modifier) {
    var width by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current
    Box(
        modifier.height(36.dp).onSizeChanged { width = it.width.toFloat() }
            .pointerInput(Unit) {
                detectTapGestures { onChange((it.x / width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> onChange((change.position.x / width).coerceIn(0f, 1f)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Chip))
        val x = with(density) { (value * width).toDp() } - 12.dp
        Box(Modifier.offset(x = x).size(24.dp).clip(CircleShape).background(ChipOn))
    }
}
