package tech.fonia.app.mirror

import android.Manifest
import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.fonia.app.AppGraph
import tech.fonia.app.VariantHooks
import tech.fonia.app.text.T
import tech.fonia.app.ui.FoniaSheet
import tech.fonia.app.ui.RoundGlyphButton
import tech.fonia.app.ui.SheetBody
import tech.fonia.app.ui.SheetTitle
import tech.fonia.app.ui.SpeechBubble
import tech.fonia.designsystem.components.ContinueButton
import tech.fonia.designsystem.components.ContinueStyle
import tech.fonia.designsystem.components.QuietButton
import tech.fonia.designsystem.letter.LetterCue
import tech.fonia.designsystem.letter.LiveLetter
import tech.fonia.designsystem.letter.LiveLetterState
import tech.fonia.designsystem.letter.StickerText
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaCorner
import tech.fonia.designsystem.tokens.FoniaSpace
import tech.fonia.designsystem.tokens.FoniaSticker
import tech.fonia.face.AbstainReason
import tech.fonia.face.EvidenceStatus
import tech.fonia.face.FaceLevels
import tech.fonia.face.FaceQuality
import tech.fonia.face.MirrorSnapshot
import tech.fonia.face.PoseSpec
import tech.fonia.face.RestSource
import tech.fonia.facecamera.CameraFaceSource
import tech.fonia.facecamera.LipTargetOverlay
import tech.fonia.facecamera.MirrorColors
import tech.fonia.facecamera.MirrorView
import tech.fonia.facecamera.MouthTracker
import tech.fonia.live.Earcon
import tech.fonia.model.SoundId

private enum class Cam { Ask, On, Off }

/**
 * MIRROR (chapter 01 §8, docs/v2/04 §4, §11): the mirrored front camera with the lip guide for the chosen sound on
 * the top ~58 %, Foni's line and a small living letter underneath, the nine sounds to pick from, „Am făcut” always.
 *
 * - The camera is asked for after the adult's pre-prompt; a refusal never blocks anything (the letter and the shape
 *   in words stay, „Vreau să mă văd” asks again on the adult's gesture).
 * - The first second is the calm face (the rest, in RAM only); the ring around the letter shows it filling.
 * - Then the camera's advice in ≤ 8 words, quality hints, a glow when the shape matches and the letter celebrates
 *   once it is held ~1 s ([MirrorCoach]). The tongue is never judged: a quiet note says so.
 * - [inLesson]: opened from a lesson's lips button; the mouth values go to the live band (`LivePipeline.postMouth`).
 * - Screenshots are blocked (`FLAG_SECURE`) in release builds, allowed in debug / demo builds (Q12).
 * - Debug `--es face fixture`: still portraits through the real landmark pipeline instead of the camera.
 */
@Composable
fun MirrorScreen(graph: AppGraph, startSound: SoundId?, inLesson: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val colors = FoniaTheme.colors
    val reduce = FoniaTheme.reduceMotion
    val perms = graph.permissions
    val fixture = remember { VariantHooks.faceFixture(context, graph.flags) }
    var sound by remember { mutableStateOf(startSound ?: graph.settings.chosenSound ?: SoundId.S) }
    var cam by remember {
        mutableStateOf(
            when {
                fixture != null || perms.cameraGranted -> Cam.On
                perms.cameraDeclinedForNow -> Cam.Off
                else -> Cam.Ask
            },
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        perms.onCameraResult(granted, activity)
        cam = if (granted) Cam.On else Cam.Off
    }

    BackHandler { onClose() }
    if (!VariantHooks.debug) {
        DisposableEffect(activity) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }

    val source = remember { if (fixture == null) CameraFaceSource(context) else null }
    val mouth: MouthTracker = remember { fixture?.mouth ?: MouthTracker(source!!) }
    val faceLevels = remember { FaceLevels() }
    val pose = remember(sound) { PoseSpec.bundled(sound) }
    val coach = remember(sound) { MirrorCoach(pose.lips.klass) }
    val letter = remember(sound) { LiveLetterState() }
    var line by remember(sound) { mutableStateOf(MirrorLine(coach.shapeLine, MirrorTone.Look)) }
    val ring = remember { mutableFloatStateOf(0f) }

    SideEffect { mouth.sound = sound }
    DisposableEffect(mouth, inLesson) {
        mouth.engine.tongueEnabled = false // no tongue gymnastics here: saves ~1.5 ms a frame
        mouth.onMouth = if (inLesson) graph::postMouth else null
        onDispose { mouth.onMouth = null }
    }

    // one frame loop reads the camera's latest snapshot (lock-free) and feeds the coach; state changes only on a new line
    if (cam == Cam.On) {
        LaunchedEffect(mouth, coach) {
            val snap = MirrorSnapshot()
            val r = MirrorReading()
            while (true) withFrameNanos { now ->
                r.hasData = mouth.read(snap)
                if (r.hasData) {
                    r.good = snap.hasFace && snap.state.quality == FaceQuality.Good
                    r.issueKey = if (snap.hasFace) snap.state.issue.key else "noFace"
                    // the rest is not taken yet (quality may flicker meanwhile; the line stays on the calm face)
                    r.calibrating = snap.state.rest == RestSource.Default ||
                        (snap.evidence.status == EvidenceStatus.Abstain && snap.evidence.abstain == AbstainReason.Calibrating)
                    r.restProgress = snap.restProgress
                    r.matches = snap.evidence.status == EvidenceStatus.Matches
                    r.adviceKey = snap.evidence.advice?.key
                }
                val next = coach.update(r, now)
                if (next != line) line = next
                ring.floatValue = coach.ring
                if (coach.consumeCheer()) {
                    letter.cue(LetterCue.Correct)
                    graph.audio.earcon(Earcon.Star) // through FxGate: dropped if a mic is open
                }
            }
        }
    } else {
        SideEffect {
            if (line.tone != MirrorTone.Look) line = MirrorLine(coach.shapeLine, MirrorTone.Look)
            ring.floatValue = 0f
        }
    }

    val matched = cam == Cam.On && (line.tone == MirrorTone.Matched || line.tone == MirrorTone.Cheer)
    val glow by animateFloatAsState(if (matched) 1f else 0f, if (reduce) snap() else tween(320), label = "glow")
    val mirrorColors = MirrorColors(
        target = colors.accent, matched = colors.parrot, childLips = colors.surface, neutral = colors.inkSecondary,
        hintBackground = colors.ink.copy(alpha = 0.55f), hintText = colors.surface,
    )

    // M3: the 3D mouth next to the child's face (front view only here; the cut-away lives in „Vezi gura”)
    var showMouth by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            // ---- the mirror, edge to edge at the top
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(0.62f)
                    .clip(RoundedCornerShape(bottomStart = FoniaCorner.sheet, bottomEnd = FoniaCorner.sheet))
                    .background(colors.fill)
                    .semantics { contentDescription = T.mirrorA11y },
            ) {
                when {
                    cam != Cam.On -> NoCamera(sound, forever = perms.cameraDeniedForever) {
                        if (perms.cameraDeniedForever) activity?.let(perms::openSettings) else cam = Cam.Ask
                    }
                    fixture != null -> {
                        DisposableEffect(mouth) {
                            mouth.attach()
                            onDispose { mouth.detach() }
                        }
                        LaunchedEffect(fixture) { fixture.run() }
                        fixture.picture.value?.let { pic ->
                            // FILL_CENTER and mirrored, exactly as the camera preview, so the guide sits on the lips
                            Image(pic, null, Modifier.fillMaxSize().graphicsLayer { scaleX = -1f }, contentScale = ContentScale.Crop)
                        }
                        LipTargetOverlay(mouth.levels, mirrorColors, Modifier.fillMaxSize(), reducedMotion = reduce, hintText = { null })
                    }
                    else -> MirrorView(
                        source = source!!, faceLevels = faceLevels, mouth = mouth, sound = sound, colors = mirrorColors,
                        modifier = Modifier.fillMaxSize(), reducedMotion = reduce, hintText = { null },
                        onFailure = { perms.onCameraResult(false, activity); cam = Cam.Off },
                    )
                }
                // the match glow: a soft parrot-green frame (never red), drawn over the picture
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        if (glow > 0.01f) {
                            val w = 10.dp.toPx()
                            drawRoundRect(
                                colors.parrot.copy(alpha = 0.85f * glow), Offset(w / 2, w / 2), Size(size.width - w, size.height - w),
                                CornerRadius(FoniaCorner.sheet.toPx()), style = Stroke(w),
                            )
                        }
                    },
                )
                if (showMouth) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(FoniaSpace.s).fillMaxWidth(0.44f).aspectRatio(1f)
                            .clip(RoundedCornerShape(FoniaCorner.card)).background(colors.surface),
                    ) { tech.fonia.app.mouth.MouthView(sound, inside = false, modifier = Modifier.fillMaxSize()) }
                }
                // top row: X, then the camera pill (tap = camera off) or the fixture label
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = FoniaSpace.pageKid, vertical = FoniaSpace.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundGlyphButton("✕", T.mirrorClose, onClick = onClose)
                    Spacer(Modifier.width(FoniaSpace.xs))
                    QuietButton(if (showMouth) T.mouthHide else T.mouthTitle, onClick = { showMouth = !showMouth }, emphasised = showMouth)
                    Spacer(Modifier.weight(1f))
                    if (cam == Cam.On) {
                        Pill(
                            if (fixture != null) fixture.label else T.mirrorCameraOn,
                            description = if (fixture != null) fixture.label else T.mirrorCameraOff,
                            onClick = if (fixture != null) null else ({ cam = Cam.Off; perms.cameraDeclinedForNow = true }),
                        )
                    }
                }
            }

            // ---- Foni's line, the small letter, the sounds, „Am făcut”
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(0.38f)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = FoniaSpace.pageKid)
                    .padding(top = FoniaSpace.m, bottom = FoniaSpace.m),
                verticalArrangement = Arrangement.spacedBy(FoniaSpace.s),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RingedLetter(sound, letter, ring, line.tone)
                    Spacer(Modifier.width(FoniaSpace.xs))
                    AnimatedContent(
                        targetState = line.text,
                        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                        modifier = Modifier.weight(1f),
                        label = "mirrorLine",
                    ) { text -> SpeechBubble(text, Modifier.fillMaxWidth().heightIn(min = 84.dp)) }
                }
                if (!pose.visible.tongue) {
                    BasicText(
                        T.mirrorTongue,
                        style = FoniaTheme.type.caption.copy(color = colors.inkSecondary, textAlign = TextAlign.Center),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SoundPicker(sound) { sound = it }
                Spacer(Modifier.weight(1f))
                ContinueButton(T.mirrorDone, onClick = onClose, modifier = Modifier.fillMaxWidth(), arrow = false)
            }
        }

        FoniaSheet(cam == Cam.Ask, onDismiss = {}, dismissible = false) {
            SheetTitle(T.camTitle)
            SheetBody(T.camBody)
            ContinueButton(T.camAllow, onClick = { launcher.launch(Manifest.permission.CAMERA) }, arrow = false)
            ContinueButton(T.camLater, style = ContinueStyle.Outlined, arrow = false, onClick = {
                perms.cameraDeclinedForNow = true
                cam = Cam.Off
            })
        }
    }
}

/** The small living letter inside a ring: the rest's progress, then the hold; it turns Sun when the hold is reached. */
@Composable
private fun RingedLetter(sound: SoundId, state: LiveLetterState, ring: androidx.compose.runtime.MutableFloatState, tone: MirrorTone) {
    val c = FoniaTheme.colors
    Box(
        Modifier.size(96.dp).drawBehind {
            val w = 6.dp.toPx()
            val d = size.minDimension - w
            val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
            drawArc(c.separator, 0f, 360f, false, tl, Size(d, d), style = Stroke(w))
            val p = ring.floatValue
            if (p > 0.001f) {
                val col = when (tone) {
                    MirrorTone.Cheer -> c.sun
                    MirrorTone.Matched -> c.parrot
                    else -> c.accent
                }
                drawArc(col, -90f, 360f * p, false, tl, Size(d, d), style = Stroke(w, cap = StrokeCap.Round))
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        LiveLetter(sound.letter, state, height = 66.dp, contentDescription = T.letterA11y(sound.letter))
    }
}

/** The nine sounds; the chosen one is the filled colour. */
@Composable
private fun SoundPicker(selected: SoundId, onPick: (SoundId) -> Unit) {
    val c = FoniaTheme.colors
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = T.mirrorPickA11y },
        horizontalArrangement = Arrangement.spacedBy(FoniaSpace.xxs),
    ) {
        for (s in SoundId.entries) {
            val on = s == selected
            Box(
                Modifier
                    .weight(if (s.letter.length > 1) 1.45f else 1f)
                    .height(52.dp)
                    .clip(RoundedCornerShape(FoniaCorner.chip))
                    .background(if (on) c.accent else c.surface)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onPick(s) }
                    .semantics { this.selected = on; contentDescription = T.letterA11y(s.letter) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(s.letter, style = FoniaTheme.type.headline.copy(color = if (on) c.surface else c.ink))
            }
        }
    }
}

/** „Camera e pornită”: the camera's own indicator (docs/v2/04 §11); a tap turns the camera off. */
@Composable
private fun Pill(text: String, description: String, onClick: (() -> Unit)?) {
    val c = FoniaTheme.colors
    Row(
        Modifier
            .clip(CircleShape)
            .background(c.ink.copy(alpha = 0.55f))
            .then(if (onClick != null) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick) else Modifier)
            .semantics { contentDescription = description }
            .padding(horizontal = FoniaSpace.s, vertical = FoniaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(c.parrot, CircleShape))
        Spacer(Modifier.width(FoniaSpace.xs))
        BasicText(text, style = FoniaTheme.type.label.copy(color = c.surface))
        if (onClick != null) {
            Spacer(Modifier.width(FoniaSpace.xs))
            BasicText("✕", style = FoniaTheme.type.label.copy(color = c.surface))
        }
    }
}

/** Without the camera: the sound's letter, big, and „Vreau să mă văd” (asks again only on this gesture). */
@Composable
private fun NoCamera(sound: SoundId, forever: Boolean, onWant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 56.dp, bottom = FoniaSpace.m),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StickerText(sound.letter, FoniaSticker.Voice, fontSize = 150.sp)
        Spacer(Modifier.height(FoniaSpace.s))
        BasicText(T.mirrorNoCamera, style = FoniaTheme.type.body.copy(color = FoniaTheme.colors.inkSecondary))
        Spacer(Modifier.height(FoniaSpace.m))
        QuietButton(if (forever) T.camOpenSettings else T.mirrorWantToSee, onClick = onWant)
    }
}
