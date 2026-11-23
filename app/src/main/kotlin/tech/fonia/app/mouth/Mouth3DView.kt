package tech.fonia.app.mouth

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tech.fonia.app.text.T
import tech.fonia.app.ui.Foni
import tech.fonia.app.ui.FoniMood
import tech.fonia.app.ui.RoundGlyphButton
import tech.fonia.designsystem.components.ContinueButton
import tech.fonia.designsystem.components.ContinueStyle
import tech.fonia.designsystem.components.QuietButton
import tech.fonia.designsystem.theme.FoniaColors
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaCorner
import tech.fonia.designsystem.tokens.FoniaSpace
import tech.fonia.model.SoundId
import tech.fonia.mouth3d.Mouth3D
import tech.fonia.mouth3d.Mouth3DAssets
import tech.fonia.mouth3d.Mouth3DColors
import tech.fonia.mouth3d.Mouth3DData

/** The 3D mouth's colours, derived from the design tokens only (pink lips, white teeth, the Stage's deep blue inside). */
fun mouthColors(c: FoniaColors) = Mouth3DColors(
    patch = c.fill,
    lips = c.voice,
    teeth = c.surface,
    gums = lerp(c.voice, c.surface, 0.62f),
    tongue = lerp(c.voice, c.surface, 0.28f),
    lining = c.accentDeep,
    section = lerp(c.voice, c.surface, 0.8f),
    rim = lerp(c.sun, c.surface, 0.5f),
)

/** The pose data, loaded off the main thread once per process. */
@Composable
fun rememberMouthData(): Mouth3DData? {
    val context = LocalContext.current
    val data by produceState<Mouth3DData?>(null) { value = runCatching { withContext(Dispatchers.IO) { Mouth3DAssets.load(context) } }.getOrNull() }
    return data
}

/**
 * The mouth moving rest → the sound's pose and back, on a loop (or once per „Încă o dată” with reduced motion); the
 * cut-away only when [inside] and the pose is approved (N17). Used by „Vezi gura” and by MIRROR's toggle.
 */
@Composable
fun MouthView(sound: SoundId, inside: Boolean, modifier: Modifier, replay: Int = 0) {
    val data = rememberMouthData()
    val colors = FoniaTheme.colors
    val progress = remember(sound) { Animatable(0f) }
    val reduce = FoniaTheme.reduceMotion
    LaunchedEffect(sound, replay, reduce) {
        do {
            progress.animateTo(1f, tween(700)); delay(1600); progress.animateTo(0f, tween(600)); delay(700)
        } while (!reduce)
        progress.animateTo(1f, tween(700))
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (data == null) {
            Foni(FoniMood.Thinking, size = 72.dp)
        } else {
            Mouth3D(
                sound = sound,
                progress = progress.value,
                inside = inside && data.insideAvailable(sound),
                colors = mouthColors(colors),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** „Vezi gura” (M3, H8): the 3D mouth for the sound, front view; „Vezi pe dinăuntru” only where approved; the caption from the pose spec. */
@Composable
fun Mouth3DScreen(sound: SoundId, onBack: () -> Unit) {
    val colors = FoniaTheme.colors
    val data = rememberMouthData()
    var inside by remember { mutableStateOf(false) }
    var replay by remember { mutableIntStateOf(0) }
    val canInside = data?.insideAvailable(sound) == true
    Column(
        Modifier.fillMaxSize().background(colors.bg).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = FoniaSpace.pageKid),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = FoniaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
            RoundGlyphButton("←", T.houseBackA11y, onClick = onBack)
            Spacer(Modifier.weight(1f))
            BasicText(T.mouthTitle + " · " + T.soundName(sound), style = FoniaTheme.type.title2.copy(color = colors.ink))
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
        }
        Spacer(Modifier.height(FoniaSpace.m))
        MouthView(
            sound, inside,
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(FoniaCorner.sheet)).background(colors.surface, RoundedCornerShape(FoniaCorner.sheet)),
            replay,
        )
        Spacer(Modifier.height(FoniaSpace.m))
        BasicText(
            data?.spec(sound)?.caption.orEmpty(),
            style = FoniaTheme.type.title2.copy(color = colors.ink, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!canInside && data != null) {
            BasicText(T.mouthNoInside, style = FoniaTheme.type.caption.copy(color = colors.inkSecondary, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        Spacer(Modifier.height(FoniaSpace.m))
        Row(horizontalArrangement = Arrangement.spacedBy(FoniaSpace.s), modifier = Modifier.fillMaxWidth()) {
            ContinueButton(T.mouthPlay, onClick = { replay++ }, arrow = false, modifier = Modifier.weight(1f))
            if (canInside) ContinueButton(if (inside) T.mouthFront else T.mouthInside, onClick = { inside = !inside }, style = ContinueStyle.Outlined, arrow = false, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(FoniaSpace.l))
    }
}
