package tech.fonia.features.speaking.coach

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import tech.fonia.design.FoniaText
import tech.fonia.design.LocalReduceMotion
import tech.fonia.design.Palette
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.ui
import tech.fonia.designsystem.letter.LetterCue
import tech.fonia.designsystem.letter.LetterDrive
import tech.fonia.designsystem.letter.LiveLetterState
import tech.fonia.designsystem.letter.LiveWord
import tech.fonia.designsystem.letter.StickerText
import tech.fonia.designsystem.tokens.FoniaClassicSticker
import tech.fonia.designsystem.tokens.StickerSet
import tech.fonia.face.FaceLevels
import tech.fonia.features.app.ActivityIndicator
import tech.fonia.facecamera.CameraFaceSource
import tech.fonia.facecamera.FaceCameraView
import tech.fonia.speaking.coach.CoachLevels
import tech.fonia.speaking.coach.Cue
import tech.fonia.speaking.coach.Envelope
import tech.fonia.speaking.coach.FaceSample
import tech.fonia.speaking.coach.ScriptedFaceTrack
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** The light of the overlay: white on the picture, green when it is right, the warm yellow for "a bit more". */
private val HudInk = Color.White
private val HudSoft = Color.White.copy(alpha = 0.26f)
private val HudGood = Palette.progress
private val HudNudge = Palette.tip

/**
 * The stage: his own picture, large, and over it the countdown, the live cue and the gauges, or the "thinking" veil.
 * One stage for the three phases, so the camera never restarts between them.
 */
@Composable
internal fun CoachStage(coach: CoachController) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CoachCameraLayer(coach, Modifier.fillMaxSize())
        // Calm shade at the top and bottom, where the gauges sit; the face in the middle stays clear.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.42f), 0.2f to Color.Transparent, 0.62f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f),
                ),
            ),
        )
        val lens = rememberLensCenter()
        // The quality hint: small, under the lens, only while something keeps the coach from measuring.
        val hint = if (coach.phase == CoachPhase.Countdown || coach.phase == CoachPhase.Live) coach.hint else null
        androidx.compose.animation.AnimatedVisibility(
            visible = hint != null,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 58.dp),
            enter = fadeIn(spring(stiffness = 200f)),
            exit = fadeOut(spring(stiffness = 200f)),
        ) {
            var last by remember { mutableStateOf(hint) }
            if (hint != null) last = hint
            FoniaText(
                last?.hint ?: "",
                Modifier
                    .background(Color.Black.copy(alpha = 0.46f), androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = ui(18, UIWeight.semibold, TextRole.body),
                color = Color.White,
            )
        }
        when (coach.phase) {
            CoachPhase.Calibrate -> Calibrate(coach, lens)
            CoachPhase.Countdown -> Countdown(coach, lens)
            CoachPhase.Live -> Live(coach, lens)
            else -> Thinking()
        }
    }
}

// MARK: The camera

/** The front camera, mirrored, filling the screen; in debug builds with `foniaFace=coach[:script]`, a scripted face. */
@Composable
internal fun CoachCameraLayer(coach: CoachController, modifier: Modifier) {
    val script = coach.faceScript
    val video = coach.app.arguments.coachVideo
    when {
        video != null -> VideoFace(coach, video, modifier)
        script != null -> FixtureFace(coach, script, modifier)
        else -> LiveCamera(coach, modifier)
    }
}

/**
 * Debug builds, `foniaCoachVideo=<mp4 in the app's files folder>`: a real recorded face through the same face model.
 * The video runs on the coach's clock from the start of the countdown, so a sound file cut 3 s later lines up.
 */
@Composable
private fun VideoFace(coach: CoachController, name: String, modifier: Modifier) {
    val context = LocalContext.current
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(name) {
        withContext(Dispatchers.Default) {
            val file = if (name.startsWith("/")) java.io.File(name) else java.io.File(context.getExternalFilesDir(null), name)
            val source = runCatching { tech.fonia.facecamera.VideoFaceSource(context, file, maxSide = 360) }.getOrNull() ?: return@withContext
            val sample = FaceSample()
            var frames = 0
            try {
                while (isActive) {
                    val session = coach.session
                    val t = if (session != null && (coach.phase == CoachPhase.Countdown || coach.phase == CoachPhase.Live)) coach.clock() else 0.0
                    val frame = source.frameAt(t.coerceAtMost(source.durationSeconds - 0.1), System.nanoTime())
                    if (frame != null && coach.calibrator != null) {
                        sample.fill(frame, coach.clock())
                        coach.onCalibrationFrame(sample)
                    }
                    if (frame != null && session != null) {
                        sample.fill(frame, coach.clock())
                        session.face.onFrame(sample)
                        if (frames++ % 8 == 0) {
                            android.util.Log.i(
                                "FoniaCoach",
                                "video t=%.1f face=%s yaw=%.1f pitch=%.1f eyeH=%.2f eyeV=%.2f deviation=%.2f looking=%s away=%.2f motion=%.2f".format(
                                    t, sample.present, sample.yawDeg, sample.pitchDeg, sample.eyeH, sample.eyeV,
                                    session.face.gazeDeviation, session.face.lookingNow, session.face.awayFraction, session.face.motionIndex,
                                ),
                            )
                        }
                    }
                    source.picture?.let { picture = it.asImageBitmap() }
                    delay(70)
                }
            } finally {
                source.close()
            }
        }
    }
    Box(modifier.background(Color(0xFF2A2F3A))) {
        picture?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun LiveCamera(coach: CoachController, modifier: Modifier) {
    val context = LocalContext.current
    val source = remember { CameraFaceSource(context) }
    val levels = remember { FaceLevels() }
    DisposableEffect(source) {
        // Camera thread: one sample, refilled per frame. Frames stay in RAM; nothing is kept.
        val sample = FaceSample()
        source.tracker.meshListener = { frame ->
            coach.session?.let { s ->
                sample.fill(frame, coach.clockOf(frame.tNanos))
                s.face.onFrame(sample)
            }
            if (coach.calibrator != null) {
                sample.fill(frame, coach.clockOf(frame.tNanos))
                coach.onCalibrationFrame(sample)
            }
        }
        onDispose { source.tracker.meshListener = null }
    }
    FaceCameraView(source, levels, modifier)
}

/** What the scripted face shows (the portrait is moved the way the script moves the head). */
private class FixturePose {
    @Volatile var dx = 0f
    @Volatile var dy = 0f
    @Volatile var roll = 0f
    @Volatile var yaw = 0f
    @Volatile var pitch = 0f
}

@Composable
private fun FixtureFace(coach: CoachController, script: String, modifier: Modifier) {
    val context = LocalContext.current
    val pose = remember { FixturePose() }
    val portrait by produceState<ImageBitmap?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("guide/small_smile.png").use { BitmapFactory.decodeStream(it).asImageBitmap() } }.getOrNull()
        }
    }
    LaunchedEffect(script) {
        val track = ScriptedFaceTrack(script)
        val sample = FaceSample()
        val start = System.nanoTime()
        withContext(Dispatchers.Default) {
            while (isActive) {
                val t = (System.nanoTime() - start) / 1e9
                track.sample(t, coach.clock(), sample)
                pose.dx = ((sample.posX - 3.0) * 120).toFloat()
                pose.dy = (-sample.pitchDeg * 9).toFloat()
                pose.pitch = sample.pitchDeg.toFloat()
                pose.roll = sample.rollDeg.toFloat()
                pose.yaw = sample.yawDeg.toFloat()
                coach.session?.face?.onFrame(sample)
                delay(66)
            }
        }
    }
    val tick = rememberFrameTick()
    Box(modifier.background(Color(0xFF2A2F3A))) {
        portrait?.let { img ->
            Image(
                img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    tick()
                    translationX = pose.dx * density / 2
                    translationY = pose.dy * density / 2
                    rotationZ = pose.roll
                    rotationY = pose.yaw * 0.6f
                    rotationX = pose.pitch * 1.6f
                    scaleX = 1.12f
                    scaleY = 1.12f
                },
            )
        }
    }
}

/**
 * Six seconds for his own gaze: the lens, then a line to read at the bottom of the screen, then one at the top. The
 * coach learns where "into the lens" sits for him and how far down his reading goes. It can be skipped.
 */
@Composable
private fun Calibrate(coach: CoachController, lens: (Size) -> Offset) {
    val tick = rememberFrameTick()
    val step = coach.calibrationStep
    val shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), 8f)
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            tick()
            val c = lens(size)
            val r = 30.dp.toPx()
            val on = step == tech.fonia.speaking.coach.GazeCalibrator.Step.Lens
            drawCircle(if (on) HudGood else HudInk.copy(alpha = 0.5f), r, c, style = Stroke((if (on) 5 else 3).dp.toPx()))
        }
        val text = when (step) {
            tech.fonia.speaking.coach.GazeCalibrator.Step.Lens -> "Privește în lentilă"
            tech.fonia.speaking.coach.GazeCalibrator.Step.Bottom -> "Citește: „Azi vorbesc limpede și rar.”"
            tech.fonia.speaking.coach.GazeCalibrator.Step.Top -> "Citește: „Mă uit la cei care mă ascultă.”"
        }
        val place = when (step) {
            tech.fonia.speaking.coach.GazeCalibrator.Step.Lens -> Modifier.align(Alignment.TopCenter).padding(top = 132.dp)
            tech.fonia.speaking.coach.GazeCalibrator.Step.Bottom -> Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp)
            tech.fonia.speaking.coach.GazeCalibrator.Step.Top -> Modifier.align(Alignment.TopCenter).padding(top = 84.dp)
        }
        FoniaText(
            text,
            place
                .padding(horizontal = 20.dp)
                .background(Color.Black.copy(alpha = 0.4f), androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            style = ui(22, UIWeight.bold, TextRole.title2).copy(shadow = shadow),
            color = HudInk,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        // Three small dots: where he is in the six seconds.
        androidx.compose.foundation.layout.Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 84.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (s in tech.fonia.speaking.coach.GazeCalibrator.Step.entries) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(if (s.ordinal <= step.ordinal) HudGood else HudInk.copy(alpha = 0.35f)))
            }
        }
        tech.fonia.design.StyledButton(
            onClick = { coach.cancel() },
            style = tech.fonia.design.PressableStyle(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 30.dp),
        ) {
            FoniaText("Sari peste", style = ui(17, UIWeight.semibold, TextRole.body).copy(shadow = shadow), color = HudInk)
        }
    }
}

/** Where the front camera's lens is, in this screen's pixels: the display cutout at the top, else the top middle. */
@Composable
private fun rememberLensCenter(): (Size) -> Offset {
    val view = LocalView.current
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    return remember(view, top) {
        val cutout = view.rootWindowInsets?.displayCutout?.boundingRects?.firstOrNull { it.top < 200 }
        val location = IntArray(2).also { view.getLocationInWindow(it) }
        // No cutout reported (emulators, some phones): just under the top edge, fully visible.
        val fallbackY = with(density) { (top / 2).toPx().coerceAtLeast(36.dp.toPx()) }
        val fn: (Size) -> Offset = { size ->
            if (cutout != null) {
                Offset(cutout.exactCenterX() - location[0], cutout.exactCenterY() - location[1])
            } else {
                Offset(size.width / 2f, fallbackY)
            }
        }
        fn
    }
}

/** Redraws every frame while on screen; read only in the draw phase. */
@Composable
private fun rememberFrameTick(): () -> Long {
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) androidx.compose.runtime.withFrameNanos { tick.longValue = it }
    }
    return { tick.longValue }
}

// MARK: Countdown

@Composable
private fun Countdown(coach: CoachController, lens: (Size) -> Offset) {
    val tick = rememberFrameTick()
    val reduce = LocalReduceMotion.current
    Box(Modifier.fillMaxSize()) {
        // The lens ring pulses: "look here".
        Canvas(Modifier.fillMaxSize()) {
            tick()
            val c = lens(size)
            val t = (System.nanoTime() % 1_400_000_000L) / 1.4e9f
            val r = 30.dp.toPx()
            drawCircle(HudInk, r, c, style = Stroke(3.dp.toPx()))
            if (!reduce) drawCircle(HudInk.copy(alpha = 0.5f * (1 - t)), r + 26.dp.toPx() * t, c, style = Stroke(2.dp.toPx()))
            // An arrow from the words up to the lens.
            val y0 = c.y + r + 70.dp.toPx()
            val y1 = c.y + r + 14.dp.toPx()
            drawLine(HudInk.copy(alpha = 0.85f), Offset(c.x, y0), Offset(c.x, y1), 3.dp.toPx(), StrokeCap.Round)
            drawLine(HudInk.copy(alpha = 0.85f), Offset(c.x, y1), Offset(c.x - 9.dp.toPx(), y1 + 10.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
            drawLine(HudInk.copy(alpha = 0.85f), Offset(c.x, y1), Offset(c.x + 9.dp.toPx(), y1 + 10.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
        }
        FoniaText(
            "Privește aici",
            Modifier.align(Alignment.TopCenter).padding(top = 150.dp),
            style = ui(24, UIWeight.bold, TextRole.title2).copy(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 2f), 8f)),
            color = HudInk,
        )
        AnimatedContent(
            coach.countdown,
            Modifier.align(Alignment.Center),
            transitionSpec = {
                (scaleIn(spring(0.55f, 260f), initialScale = 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 1.3f) + fadeOut())
            },
            label = "count",
        ) { n ->
            StickerText("$n", FoniaClassicSticker.Cold, fontSize = 150.sp, weight = 900f)
        }
        FoniaText(
            coach.drill?.let { "Exersezi: ${it.title}" } ?: coach.prompt.title,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()).padding(horizontal = 28.dp),
            style = ui(20, UIWeight.semibold, TextRole.title3).copy(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 2f), 8f)),
            color = HudInk,
            textAlign = TextAlign.Center,
        )
    }
}

// MARK: Live

@Composable
private fun Live(coach: CoachController, lens: (Size) -> Offset) {
    val levels = coach.session?.levels ?: CoachLevels()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxSize()) {
        CoachHud(levels, coach.seconds.toDouble(), lens, Modifier.fillMaxSize())
        // The one live cue, in the lower third on a soft shade: large, never over his eyes. The lens ring at the top
        // shows where to look.
        val cue = coach.cue
        AnimatedContent(
            cue,
            Modifier
                .fillMaxSize()
                .padding(top = top + 64.dp, bottom = bottom + 150.dp),
            transitionSpec = {
                (fadeIn(spring(stiffness = 260f)) + scaleIn(spring(dampingRatio = 0.62f, stiffness = 240f), initialScale = 0.82f)) togetherWith
                    (fadeOut(spring(stiffness = 400f)) + scaleOut(spring(stiffness = 400f), targetScale = 0.92f))
            },
            contentAlignment = Alignment.Center,
            label = "cue",
        ) { shown ->
            Box(Modifier.fillMaxSize()) {
                if (shown != null) {
                    CueWord(
                        shown,
                        levels,
                        Modifier
                            .align(Alignment.BottomCenter)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
        StopButton(
            onClick = { coach.stop() },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + 26.dp),
        )
    }
}

/** The sticker set of each cue: blue for where to look and how to stand, yellow for the voice, green for "Așa!". Never red. */
private fun stickerOf(cue: Cue): StickerSet = when (cue) {
    Cue.Look, Cue.Calmer -> FoniaClassicSticker.Cold
    Cue.Louder, Cue.Livelier -> FoniaClassicSticker.Star
    Cue.Slower, Cue.Faster -> FoniaClassicSticker.Tip
    Cue.Good -> FoniaClassicSticker.Good
}

/** A cue in short lines (the living word keeps its letters large). */
private fun linesOf(cue: Cue): List<String> = when (cue) {
    Cue.Look -> listOf("Uită-te", "la cameră")
    Cue.Calmer -> listOf("Stai mai", "liniștit")
    else -> listOf(cue.word)
}

/**
 * The cue as one Allo sticker (the letters touch, as in a title), on a soft dark shade so it reads on any room. It
 * swells a little with his voice, read while drawing; "Așa!" gives one extra pop.
 */
@Composable
private fun CueWord(cue: Cue, levels: CoachLevels, modifier: Modifier) {
    val tick = rememberFrameTick()
    val swell = remember { FloatArray(1) }
    val pop = remember(cue) { androidx.compose.animation.core.Animatable(if (cue == Cue.Good) 0.7f else 1f) }
    LaunchedEffect(cue) {
        if (cue == Cue.Good) pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 260f))
    }
    val lines = linesOf(cue)
    val size = if (lines.maxOf { it.length } > 9) 44.sp else 54.sp
    Column(
        modifier
            .padding(horizontal = 18.dp)
            .graphicsLayer {
                tick()
                val loud = levels.loudness01
                val want = if (levels.speaking && !loud.isNaN()) loud.toFloat() else 0f
                swell[0] += (want - swell[0]) * 0.08f
                val k = (1f + 0.035f * swell[0]) * pop.value
                scaleX = k
                scaleY = k
            }
            .background(Color.Black.copy(alpha = 0.34f), androidx.compose.foundation.shape.RoundedCornerShape(34.dp))
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .semantics { contentDescription = cue.word },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy((-26).dp),
    ) {
        for (line in lines) {
            tech.fonia.designsystem.letter.StickerText(line, stickerOf(cue), fontSize = size, weight = 900f, contentDescription = line)
        }
    }
}

/** "Gata": a round button with a soft square, no sound (the microphone is open). */
@Composable
private fun StopButton(onClick: () -> Unit, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(70.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.25f))
                .border(4.dp, HudInk, CircleShape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = "Gata, am terminat" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(24.dp).background(HudInk, RoundedCornerShape(7.dp)))
        }
        FoniaText("Gata", style = ui(15, UIWeight.semibold, TextRole.footnote), color = HudInk)
    }
}

/**
 * The gauges, all in one Canvas that redraws every frame and reads [levels] only while drawing (no recomposition):
 * the lens ring (lights when he looks at it), the time ring, pace on the left edge with its target band, voice on the
 * right edge, and at the bottom a bubble level for stillness and a living wave for liveliness. Every value goes
 * through an [Envelope] (fast up, slow down), so nothing trembles.
 */
@Composable
private fun CoachHud(levels: CoachLevels, totalSeconds: Double, lens: (Size) -> Offset, modifier: Modifier) {
    val tick = rememberFrameTick()
    val measurer = rememberTextMeasurer()
    val label = remember { TextStyle(color = HudInk, fontSize = 13.sp).merge(ui(13, UIWeight.semibold, TextRole.caption)) }
    val digits = remember { ui(17, UIWeight.bold, TextRole.headline).copy(color = HudInk) }
    val st = remember { HudState() }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val labels = remember(measurer) {
        listOf("ritm", "voce", "calm", "viu").associateWith { measurer.measure(it, label) }
    }
    Canvas(modifier.semantics { contentDescription = "Indicatori: ritm, voce, privire, calm, viu" }) {
        val now = tick()
        val dt = if (st.last == 0L) 0.016 else ((now - st.last) / 1e9).coerceIn(0.0, 0.1)
        st.last = now
        st.phase += dt
        val pace = st.pace.step(levels.pace01, dt)
        val paceShown = st.paceShown.step(if (levels.pace01.isNaN()) 0.0 else 1.0, dt)
        val voice = st.voice.step(if (levels.loudness01.isNaN()) 0.0 else levels.loudness01, dt)
        val look = st.look.step(if (levels.looking.isNaN()) 0.0 else levels.looking, dt)
        val still = st.still.step(levels.still01, dt)
        val lively = st.lively.step(levels.lively01, dt)
        val elapsed = levels.seconds

        val sidePad = 14.dp.toPx()
        val gaugeTop = top.toPx() + 110.dp.toPx()
        val gaugeH = size.height * 0.36f
        val w = 10.dp.toPx()

        // Lens ring: dim when he looks away, a green halo when he looks at the lens.
        val c = lens(size)
        val r = 26.dp.toPx()
        drawCircle(HudGood.copy(alpha = (0.35 * look).toFloat()), r + 12.dp.toPx(), c)
        drawCircle(lerp(HudSoft, HudGood, look.toFloat()), r, c, style = Stroke((3 + 2 * look).dp.toPx()))

        // Time ring, top right: what is left.
        val tc = Offset(size.width - sidePad - 24.dp.toPx(), top.toPx() + 34.dp.toPx())
        val tr = 22.dp.toPx()
        val left = (1.0 - elapsed / totalSeconds).coerceIn(0.0, 1.0)
        drawCircle(Color.Black.copy(alpha = 0.25f), tr + 3.dp.toPx(), tc)
        drawCircle(HudSoft, tr, tc, style = Stroke(4.dp.toPx()))
        drawArc(HudInk, -90f, (360 * left).toFloat(), false, Offset(tc.x - tr, tc.y - tr), Size(tr * 2, tr * 2), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        val remaining = max(0, kotlin.math.ceil(totalSeconds - elapsed).toInt())
        val txt = measurer.measure(if (remaining >= 60) "${remaining / 60}:${"%02d".format(remaining % 60)}" else "$remaining", digits)
        drawText(txt, topLeft = Offset(tc.x - txt.size.width / 2f, tc.y - txt.size.height / 2f))

        // Pace, left edge: slow at the bottom, fast at the top, the target band lit.
        val px = sidePad + w / 2
        verticalTrack(px, gaugeTop, gaugeH, w)
        val lo = st.bandLow
        val hi = st.bandHigh
        val inBand = pace in lo..hi
        drawRoundRect(
            HudGood.copy(alpha = if (inBand && paceShown > 0.5) 0.85f else 0.4f),
            Offset(px - w / 2, gaugeTop + gaugeH * (1 - hi).toFloat()),
            Size(w, gaugeH * (hi - lo).toFloat()),
            CornerRadius(w / 2),
        )
        if (paceShown > 0.02) {
            val by = gaugeTop + gaugeH * (1 - pace.coerceIn(0.0, 1.0)).toFloat()
            val bead = if (inBand) HudGood else HudNudge
            drawCircle(Color.Black.copy(alpha = 0.25f * paceShown.toFloat()), w * 1.25f, Offset(px, by + 1.dp.toPx()))
            drawCircle(HudInk.copy(alpha = paceShown.toFloat()), w * 1.15f, Offset(px, by))
            drawCircle(bead.copy(alpha = paceShown.toFloat()), w * 0.8f, Offset(px, by))
        }
        labels["ritm"]?.let { drawText(it, topLeft = Offset(px - it.size.width / 2f, gaugeTop + gaugeH + 8.dp.toPx())) }

        // Voice, right edge: fills from the bottom; the notch is his own level.
        val vx = size.width - sidePad - w / 2
        verticalTrack(vx, gaugeTop, gaugeH, w)
        val fill = gaugeH * voice.toFloat()
        val notch = 0.71f
        val ok = voice >= notch - 0.12
        drawRoundRect(if (ok) HudGood else HudNudge, Offset(vx - w / 2, gaugeTop + gaugeH - fill), Size(w, fill), CornerRadius(w / 2))
        drawLine(HudInk, Offset(vx - w, gaugeTop + gaugeH * (1 - notch)), Offset(vx - w * 0.55f, gaugeTop + gaugeH * (1 - notch)), 2.dp.toPx(), StrokeCap.Round)
        labels["voce"]?.let { drawText(it, topLeft = Offset(vx - it.size.width / 2f, gaugeTop + gaugeH + 8.dp.toPx())) }

        // Bottom: the bubble level (calm) and the living wave (viu), either side of "Gata".
        val by = size.height - bottom.toPx() - 70.dp.toPx()
        val gw = 76.dp.toPx()
        val gh = 26.dp.toPx()
        val cx1 = size.width * 0.2f
        val cx2 = size.width * 0.8f
        // Calm: a pill with a bubble that drifts off-centre as he moves.
        drawRoundRect(Color.Black.copy(alpha = 0.22f), Offset(cx1 - gw / 2, by - gh / 2), Size(gw, gh), CornerRadius(gh / 2))
        drawRoundRect(HudSoft, Offset(cx1 - gw / 2, by - gh / 2), Size(gw, gh), CornerRadius(gh / 2), style = Stroke(2.dp.toPx()))
        drawLine(HudSoft, Offset(cx1, by - gh / 2 + 4.dp.toPx()), Offset(cx1, by + gh / 2 - 4.dp.toPx()), 2.dp.toPx())
        if (!still.isNaN() && !levels.still01.isNaN() || st.still.value > 0) {
            val off = (1 - still).toFloat() * (gw / 2 - gh / 2) * sin(st.phase * 2.3).toFloat()
            drawCircle(if (still > 0.45) HudGood else HudNudge, gh * 0.34f, Offset(cx1 + off, by))
        }
        labels["calm"]?.let { drawText(it, topLeft = Offset(cx1 - it.size.width / 2f, by + gh / 2 + 4.dp.toPx())) }
        // Viu: a wave whose height is the liveliness.
        drawRoundRect(Color.Black.copy(alpha = 0.22f), Offset(cx2 - gw / 2, by - gh / 2), Size(gw, gh), CornerRadius(gh / 2))
        drawRoundRect(HudSoft, Offset(cx2 - gw / 2, by - gh / 2), Size(gw, gh), CornerRadius(gh / 2), style = Stroke(2.dp.toPx()))
        val amp = (0.08 + 0.92 * (if (lively.isNaN()) 0.0 else lively)).toFloat() * gh * 0.36f
        val path = st.path
        path.reset()
        val x0 = cx2 - gw / 2 + gh / 2
        val x1 = cx2 + gw / 2 - gh / 2
        var x = x0
        while (x <= x1) {
            val u = (x - x0) / (x1 - x0)
            val y = by + amp * sin(u * 4 * PI + st.phase * 4).toFloat() * sin(u * PI).toFloat()
            if (x == x0) path.moveTo(x, y) else path.lineTo(x, y)
            x += 2.dp.toPx()
        }
        drawPath(path, if (lively > 0.45) HudGood else HudInk, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        labels["viu"]?.let { drawText(it, topLeft = Offset(cx2 - it.size.width / 2f, by + gh / 2 + 4.dp.toPx())) }
    }
}

private fun DrawScope.verticalTrack(x: Float, top: Float, h: Float, w: Float) {
    drawRoundRect(Color.Black.copy(alpha = 0.22f), Offset(x - w / 2 - 2.dp.toPx(), top - 2.dp.toPx()), Size(w + 4.dp.toPx(), h + 4.dp.toPx()), CornerRadius(w))
    drawRoundRect(HudSoft, Offset(x - w / 2, top), Size(w, h), CornerRadius(w / 2))
}

private fun lerp(a: Color, b: Color, t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    return Color(a.red + (b.red - a.red) * k, a.green + (b.green - a.green) * k, a.blue + (b.blue - a.blue) * k, a.alpha + (b.alpha - a.alpha) * k)
}

/** The followers of the gauges (draw thread only). */
private class HudState {
    private val spec = tech.fonia.speaking.coach.CoachSpec.v1
    var last = 0L
    var phase = 0.0
    val pace = Envelope.of(spec, 0.5)
    val paceShown = Envelope.of(spec)
    val voice = Envelope.of(spec)
    val look = Envelope.of(spec)
    val still = Envelope.of(spec, 1.0)
    val lively = Envelope.of(spec, 0.5)
    val path = Path()
    val bandLow = (spec.pace.bandLow - spec.pace.gaugeMin) / (spec.pace.gaugeMax - spec.pace.gaugeMin)
    val bandHigh = (spec.pace.bandHigh - spec.pace.gaugeMin) / (spec.pace.gaugeMax - spec.pace.gaugeMin)
}

// MARK: Thinking

@Composable
private fun Thinking() {
    Box(Modifier.fillMaxSize().background(Palette.night.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ActivityIndicator(size = 32.dp, color = HudInk)
            FoniaText("Mă uit la ce ai spus", style = ui(20, UIWeight.semibold, TextRole.title3), color = HudInk)
        }
    }
}

