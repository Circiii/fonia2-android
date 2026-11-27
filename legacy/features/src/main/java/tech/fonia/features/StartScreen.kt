package tech.fonia.features

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import kotlinx.coroutines.delay
import tech.fonia.design.Foni
import tech.fonia.design.FoniMood
import tech.fonia.design.FoniaImage
import tech.fonia.design.SkyLife
import tech.fonia.designsystem.letter.LetterCue
import tech.fonia.designsystem.letter.LiveLetter
import tech.fonia.designsystem.letter.LiveLetterState
import tech.fonia.designsystem.tokens.StickerSet
import tech.fonia.designsystem.tokens.VerdictStickers

/** The sky colour at the top of the start scene; the window's own splash uses the same one (themes.xml, v31). */
internal val StartSky = Color(0xFFBCDDF0)

/**
 * The sound logo (`sfx/logo.ogg`, built by `tools/sfx/make_logo.py hop`), in seconds: four quick rising mallet
 * notes and a bell chord, one for each letter. The chord is the last letter's.
 */
private val LogoNotes = floatArrayOf(0.00f, 0.09f, 0.18f, 0.27f, 0.45f)
private const val LogoLast = 0.45f
private const val LogoSoundOn = false

/** One name, one colour family: Foni's green running into the sky's blue. Light face, full-colour body, deep rim. */
private val NameFrom = Color(0xFF22B455)
private val NameTo = Color(0xFF1B86E0)

private fun nameSticker(i: Int, count: Int): StickerSet {
    val body = lerp(NameFrom, NameTo, i / (count - 1f))
    return StickerSet("Name$i", lerp(body, Color.White, 0.86f), body, lerp(body, Color(0xFF06223A), 0.62f))
}

/**
 * Where Fonia opens: Foni says the name. His whole head is on screen, in the lower half; on each note of the sound
 * logo he opens his beak and a letter flies out of it, up in an arc, and lands in its place in the name, growing as
 * it goes. On the last, high note the name swells and stars burst from it; then the letters float.
 */
@Composable
internal fun StartScreen() {
    val context = LocalContext.current
    val letters = remember { "FONIA".map { it.toString() } }
    val states = remember { List(letters.size) { LiveLetterState() } }
    val stickers = remember { List(letters.size) { nameSticker(it, letters.size) } }
    val flights = remember { List(letters.size) { Animatable(0f) } }
    // What Foni's beak follows: a pulse on each note that fades by itself. Read only while drawing.
    val song = remember { FloatArray(1) }
    // Seconds since the screen opened: the letters float on it, each a little after the one before. Read in draw only.
    val clock = remember { mutableFloatStateOf(0f) }
    val rise = remember { Animatable(0f) }
    val pop = remember { Animatable(1f) }
    var burst by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val player = logoPlayer(context)
        onDispose { runCatching { player?.release() } }
    }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos {
            song[0] *= 0.90f
            clock.floatValue = (it - start) / 1e9f
        }
    }
    LaunchedEffect(Unit) {
        rise.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 120f))
    }
    LaunchedEffect(Unit) {
        // The player needs a moment to start. A letter leaves the beak a little before its note and lands on it.
        val lead = 520L
        val flight = 230L
        var at = 0L
        for (i in states.indices) {
            val due = lead + (LogoNotes[i] * 1000).toLong() - flight
            delay((due - at).coerceAtLeast(0))
            at = due
            song[0] = 1f
            launch { flights[i].animateTo(1f, spring(dampingRatio = 0.58f, stiffness = 170f)) }
        }
        delay(lead + (LogoLast * 1000).toLong() - at)
        song[0] = 1f
        burst += 1
        pop.animateTo(1.1f, spring(dampingRatio = 0.9f, stiffness = 900f))
        pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 240f))
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(StartSky)
            .clipToBounds()
            .semantics { contentDescription = "Fonia" },
    ) {
        val w = maxWidth
        val h = maxHeight
        // The name: five cells across 88 % of the screen, whatever its width.
        val cell = w * 0.19f
        val thin = w * 0.115f
        val total = cell * 4 + thin
        val nameTop = h * 0.105f
        val letterHeight = cell * 2.15f
        // Foni: the whole head inside the screen, centred, its top just under the middle. The art's head sits at
        // 41 % across and 18–48 % down its square; the beak's tip at 20 % across, 40 % down.
        val bird = minOf(w * 1.62f, h * 0.74f)
        val birdLeft = w * 0.5f - bird * 0.41f
        val birdTop = h * 0.50f - bird * 0.18f
        // Where the beak ends up on screen after the zoom below (measured on the art).
        val beakX = w * 0.10f
        val beakY = h * 0.57f

        FoniaImage("scene-start-page", Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.BottomCenter)
        SkyLife(modifier = Modifier.fillMaxSize())
        // Zoomed on the eye so the head fills the lower half and stays whole inside the screen.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.47f, 0.46f)
                scaleX = 1.43f
                scaleY = 1.43f
                translationX = size.width * 0.03f
                translationY = size.height * 0.09f
            },
        ) {
            Foni(
                Modifier
                    .requiredSize(bird)
                    .align(Alignment.TopStart)
                    .offset(x = birdLeft + (bird - w) / 2, y = birdTop + (bird - h) / 2)
                    .graphicsLayer { translationY = (1f - rise.value) * h.toPx() * 0.6f },
                mood = FoniMood.happy,
                level = { song[0] },
            )
        }
        var x = (w - total) / 2
        for (i in letters.indices) {
            val width = if (letters[i] == "I") thin else cell
            val homeX = x + width / 2
            val homeY = nameTop + letterHeight / 2
            x += width
            // A living letter keeps room around itself for its motion; the name sits on a tighter grid.
            Box(
                Modifier
                    .offset(x = homeX - width / 2, y = nameTop)
                    .requiredWidth(width)
                    .graphicsLayer {
                        val f = flights[i].value
                        val away = (1f - f).coerceIn(0f, 1f)
                        val phase = clock.floatValue * 1.7f - i * 0.75f
                        val grow = (0.12f + 0.88f * f) * pop.value
                        alpha = (f * 5f).coerceIn(0f, 1f)
                        scaleX = grow
                        scaleY = grow
                        translationX = away * (beakX - homeX).toPx()
                        // Up in an arc out of the beak, then a slow float once home.
                        translationY = away * (beakY - homeY).toPx() - kotlin.math.sin(away * Math.PI.toFloat()) * 70.dp.toPx() +
                            (1f - away) * kotlin.math.sin(phase) * 5.dp.toPx()
                        rotationZ = away * -50f + (1f - away) * kotlin.math.sin(phase * 0.5f + i) * 1.6f
                    }
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                LiveLetter(
                    grapheme = letters[i],
                    state = states[i],
                    modifier = Modifier.wrapContentWidth(unbounded = true),
                    sticker = stickers[i],
                    height = letterHeight,
                    verdicts = VerdictStickers.Classic,
                )
            }
        }
        tech.fonia.designsystem.motion.CelebrationBurst(
            trigger = burst,
            modifier = Modifier.matchParentSize(),
            style = tech.fonia.designsystem.motion.BurstStyle.Stars,
            origin = androidx.compose.ui.geometry.Offset(0.5f, 0.2f),
        )
    }
}

/** Starts the sound logo, unless the phone is on silent or vibrate (the rule of every effect in Fonia). */
private fun logoPlayer(context: Context): MediaPlayer? = runCatching {
    val audio = context.getSystemService(AudioManager::class.java)
    if (audio != null && audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return null
    // The logo plays no sound while LogoSoundOn is off; the animation keeps the same timing.
    if (!LogoSoundOn) return null
    val file = context.assets.openFd("sfx/logo.ogg")
    MediaPlayer().apply {
        setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
        )
        setDataSource(file.fileDescriptor, file.startOffset, file.length)
        file.close()
        setVolume(0.9f, 0.9f)
        setOnPreparedListener { it.start() }
        prepareAsync()
    }
}.getOrNull()
