package tech.fonia.features.session

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.fonia.design.Field
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.SfxCue
import tech.fonia.design.FoniaSfx
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.VoiceBars
import tech.fonia.design.ui
import tech.fonia.dsp.SibilantAnchor
import tech.fonia.dsp.SibilantPlace
import tech.fonia.dsp.VoiceGroup
import tech.fonia.dsp.analyzeClip
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.AudioHub
import tech.fonia.judge.PersonalReference
import tech.fonia.judge.TalkerEstimator
import tech.fonia.judge.TalkerVoice
import tech.fonia.store.Profile
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The numbers of the guided calibration ("Calibrare"): pure, so they are tested on the PC.
 *
 * Levels are 20 ms frame RMS in dBFS, as the live analysis' floor is. The held hiss is cut into short pieces, each put
 * between two stretches of his own room's quiet, so the clip analysis finds one clean fricative per piece: one held
 * "sss" of 2–3 s gives the five anchors per place his own S/Ș boundary needs.
 */
object CalibrationMath {
    const val FRAME_SECONDS = 0.02

    /** Seconds of each step. */
    const val QUIET_SECONDS = 2.0
    const val HOLD_SECONDS = 3.0

    /** Pieces cut from one held hiss, and the shortest one. */
    const val PIECES = 6
    const val MIN_PIECE_SECONDS = 0.25

    fun frameDb(samples: FloatArray, rate: Double): FloatArray {
        val n = max(1, (rate * FRAME_SECONDS).toInt())
        val frames = samples.size / n
        return FloatArray(frames) { f ->
            var sum = 0.0
            for (i in f * n until (f + 1) * n) sum += samples[i].toDouble() * samples[i]
            (20.0 * log10(sqrt(sum / n) + 1e-9)).toFloat()
        }
    }

    private fun percentile(values: FloatArray, p: Float): Float {
        if (values.isEmpty()) return Float.NaN
        val s = values.sortedArray()
        return s[((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)]
    }

    /** The room: the quietest fifth of the quiet step (a cough or a click does not raise it). */
    fun floorDb(quiet: FloatArray, rate: Double): Float = percentile(frameDb(quiet, rate), 0.2f).coerceIn(-100f, -20f)

    /** His speaking level: the median of the frames clearly above the room in the held "aaa"; NaN if none. */
    fun speechDb(voice: FloatArray, rate: Double, floorDb: Float): Float {
        val loud = frameDb(voice, rate).filter { it > floorDb + 10f }.toFloatArray()
        return if (loud.size < 5) Float.NaN else percentile(loud, 0.5f)
    }

    /**
     * The held hiss cut into up to [PIECES] pieces of at least [MIN_PIECE_SECONDS], each with [pad] (his quiet, or a
     * near silence) before and after. Empty when less than one piece of hiss stood above the room.
     */
    fun pieces(hiss: FloatArray, rate: Double, floorDb: Float, pad: FloatArray): List<FloatArray> {
        val db = frameDb(hiss, rate)
        val n = max(1, (rate * FRAME_SECONDS).toInt())
        val on = db.indices.filter { db[it] > floorDb + 8f }
        if (on.isEmpty()) return emptyList()
        // The steady middle: skip the first and last 10 % of the loud span (onset and release).
        val first = on.first()
        val last = on.last()
        val trim = (last - first) / 10
        val start = (first + trim) * n
        val end = ((last - trim) + 1) * n
        val span = (end - start).coerceAtLeast(0)
        val minLen = (MIN_PIECE_SECONDS * rate).toInt()
        val count = (span / minLen).coerceAtMost(PIECES)
        if (count < 1) return emptyList()
        val len = span / count
        return (0 until count).map { k ->
            val from = start + k * len
            FloatArray(pad.size * 2 + len).also { out ->
                pad.copyInto(out, 0)
                hiss.copyInto(out, pad.size, from, from + len)
                pad.copyInto(out, pad.size + len)
            }
        }
    }

    /** 0.3 s of his own quiet from the middle of the quiet step, or a near silence when there is none. */
    fun padFrom(quiet: FloatArray?, rate: Double): FloatArray {
        val n = (0.3 * rate).toInt()
        if (quiet != null && quiet.size >= n * 2) return quiet.copyOfRange(quiet.size / 2 - n / 2, quiet.size / 2 - n / 2 + n)
        return FloatArray(n) { ((it * 7919 % 200) - 100) * 1e-6f }
    }
}

private enum class CalStep(val letter: String?, val fixture: String, val field: Field) {
    Quiet(null, "calibrare-liniste", Field.night),
    Aaa("A", "calibrare-a", Field.tip),
    Sss("S", "calibrare-s", Field.cold),
    Shh("Ș", "calibrare-sh", Field.warm),
    Result(null, "", Field.paper),
}

/** What the screen shows after the four steps. */
private data class CalResult(val room: PersonalReference.Room, val active: Boolean, val anchorsS: Int, val anchorsSh: Int)

/**
 * „Calibrare”: about 20 seconds, guided by the screen, so the app is calibrated to his voice and his sounds. Two
 * seconds of quiet, then a held "aaa", "sss" and "șșș", each with its living letter answering the voice.
 * What it measures goes into his [PersonalReference] (room floor, speaking level, F0 / group, his own S and Ș anchors):
 * the live analysis then listens with his floor, his range and his S/Ș boundary, and the judge with his anchors and
 * group. The result is drawn, not told: his two letters, where his S and Ș fall on the axis, his room and his voice.
 *
 * Prompts: only recorded clips ([AudioHub.sayIfRecorded]); a step with no clip is shown, not spoken. Nothing plays
 * while the microphone is open.
 */
@Composable
fun VoiceCalibrationScreen(profile: Profile, app: AppModel, onDone: () -> Unit) {
    var step by remember { mutableStateOf(CalStep.Quiet) }
    var listening by remember { mutableStateOf(false) }
    var run by remember { mutableIntStateOf(0) }
    val progress = remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<CalResult?>(null) }
    val letter = rememberLetterLink(step)
    val levels = app.audio.levels
    val group = app.talkers.group(profile)

    BackHandler(enabled = !listening) { onDone() }

    FeedLetter(
        letter, levels, listening = listening, modelVoice = false,
        placeBias = placeBias(group, app.voice.calibrated(profile.id)),
        aim = when (step) {
            CalStep.Sss -> LiveAim.Place(sharp = true)
            CalStep.Shh -> LiveAim.Place(sharp = false)
            else -> LiveAim.None
        },
    )

    LaunchedEffect(run) {
        result = null
        if (!app.audio.requestMicrophone()) {
            onDone()
            return@LaunchedEffect
        }
        app.audio.prepare()
        val takes = HashMap<CalStep, FloatArray>()
        var rate = 48_000.0
        for (s in listOf(CalStep.Quiet, CalStep.Aaa, CalStep.Sss, CalStep.Shh)) {
            step = s
            progress.floatValue = 0f
            // The prompt, before the microphone (the quiet rule); only a recorded clip, never a phone voice.
            when (s) {
                CalStep.Quiet -> delay(600)
                CalStep.Aaa -> app.audio.sayIfRecorded(firstRecorded(app, "Ține sunetul lung, cât poți."))
                CalStep.Sss -> app.audio.sayIfRecorded(firstRecorded(app, "Ține S cât poți", "Ține sunetul lung, cât poți."))
                CalStep.Shh -> app.audio.sayIfRecorded(firstRecorded(app, "Ține Ș cât poți", "Ține sunetul lung, cât poți."))
                CalStep.Result -> Unit
            }
            val seconds = if (s == CalStep.Quiet) CalibrationMath.QUIET_SECONDS else CalibrationMath.HOLD_SECONDS
            try {
                app.audio.startListening(group = group, calibration = null, fixtureText = s.fixture)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onDone()
                return@LaunchedEffect
            }
            listening = true
            val started = System.nanoTime()
            while (true) {
                val t = (System.nanoTime() - started) / 1e9
                progress.floatValue = (t / seconds).toFloat().coerceIn(0f, 1f)
                if (t >= seconds) break
                delay(16)
            }
            listening = false
            val clip = app.audio.stopListening()
            if (clip != null) {
                takes[s] = clip.samples
                rate = clip.sampleRate
            }
            delay(350)
        }
        step = CalStep.Result
        val measured = withContext(Dispatchers.Default) { measure(app, profile, takes, rate, group) }
        result = measured
        FoniaSfx.play(if (measured.active) SfxCue.Star else SfxCue.TakeAlmost)
    }

    val shownField = step.field
    Column(
        Modifier
            .fillMaxSize()
            .background(shownField.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        StepDots(step, shownField)
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier.weight(1f),
            label = "calibrare",
        ) { shown ->
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                when (shown) {
                    CalStep.Quiet -> {
                        FoniaIconView(FoniaIcon.ear, size = 96f, tint = shownField.foreground)
                        Spacer(Modifier.height(20.dp))
                        FoniaText("Liniște", style = ui(40, UIWeight.black, TextRole.largeTitle), color = shownField.foreground)
                    }
                    CalStep.Result -> ResultView(result, profile)
                    else -> {
                        HeldLetter(shown.letter!!, letter, shown.field, height = 220.dp)
                        FoniaText(
                            shown.letter.lowercase().repeat(3),
                            style = ui(44, UIWeight.black, TextRole.largeTitle),
                            color = shown.field.deep,
                        )
                    }
                }
            }
        }
        if (step != CalStep.Result) {
            TimeBar(progress, shownField)
            VoiceBars(isLive = listening, color = if (step == CalStep.Quiet) Palette.nightSoft else step.field.deep, level = { levels.level }, modifier = Modifier.fillMaxWidth().height(36.dp))
        } else if (result != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StyledButton(
                    onClick = { run += 1 },
                    style = FoniButtonStyle(field = Field.paper, size = FoniButtonStyle.Size.regular, kind = FoniButtonStyle.Kind.secondary),
                    modifier = Modifier.weight(1f),
                ) { FoniaText("Încă o dată") }
                StyledButton(onClick = onDone, style = FoniButtonStyle(field = Field.paper, size = FoniButtonStyle.Size.regular), modifier = Modifier.weight(1f)) {
                    FoniaText("Gata")
                }
            }
        }
    }
}

private fun firstRecorded(app: AppModel, vararg texts: String): String? = texts.firstOrNull { app.audio.hasClip(it, tech.fonia.domain.Language.Ro) }

/** Off the main thread: the four takes → his room, voice and S/Ș anchors, saved on his profile. */
private fun measure(app: AppModel, profile: Profile, takes: Map<CalStep, FloatArray>, rate: Double, liveGroup: VoiceGroup): CalResult {
    val quiet = takes[CalStep.Quiet]
    val aaa = takes[CalStep.Aaa]
    val floor = (quiet ?: aaa)?.let { CalibrationMath.floorDb(it, rate) } ?: -70f
    val speech = aaa?.let { CalibrationMath.speechDb(it, rate, floor) }?.takeIf { !it.isNaN() } ?: (floor + 30f)
    val f0 = aaa?.let { TalkerEstimator.estimate(it, rate).f0Hz }?.takeIf { !it.isNaN() }
    val group = f0?.let { TalkerVoice.groupOf(it)?.voice } ?: liveGroup
    val pad = CalibrationMath.padFrom(quiet, rate)
    fun anchors(step: CalStep, place: SibilantPlace): List<SibilantAnchor> {
        val hiss = takes[step] ?: return emptyList()
        return CalibrationMath.pieces(hiss, rate, floor, pad).mapNotNull { piece ->
            val seg = analyzeClip(piece, rate, group, null).fricatives.maxByOrNull { it.end - it.start } ?: return@mapNotNull null
            if (seg.diffuse) null else SibilantAnchor(place, seg.centroidHz, seg.peakHz, seg.bandRatioDb, seg.tiltDb)
        }
    }
    val sA = anchors(CalStep.Sss, SibilantPlace.S)
    val shA = anchors(CalStep.Shh, SibilantPlace.SH)
    val room = PersonalReference.Room(floorDb = floor, speechDb = speech, f0Hz = f0, atMillis = System.currentTimeMillis())
    val personal = app.voice.personal(profile.id)
    val active = synchronized(personal) {
        val on = personal.calibrate(room, sA, shA)
        // Where his own S and Ș now fall, measured against his own anchors (the picture of the result).
        val cal = personal.calibration()
        fun place(step: CalStep): Float = takes[step]?.let { analyzeClip(it, rate, group, cal).fricatives.maxByOrNull { s -> s.end - s.start }?.sNess } ?: Float.NaN
        personal.placed(place(CalStep.Sss), place(CalStep.Shh))
        on
    }
    app.voice.savePersonal(profile.id)
    // His voice counts at once for the live analysis' group (three observations move the tracker past its hysteresis).
    if (f0 != null) repeat(3) { app.talkers.observe(profile, f0) }
    AudioHub.log("calibrare: floor=${"%.0f".format(floor)} speech=${"%.0f".format(speech)} f0=${f0?.let { "%.0f".format(it) } ?: "-"} " +
        "anchors S=${sA.size} Ș=${shA.size} active=$active place S=${personal.room?.sPlace} Ș=${personal.room?.shPlace}")
    return CalResult(personal.room ?: room, active, sA.size, shA.size)
}

/** Four dots: where he is in the 20 seconds. */
@Composable
private fun StepDots(step: CalStep, field: Field) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
        for (s in listOf(CalStep.Quiet, CalStep.Aaa, CalStep.Sss, CalStep.Shh)) {
            val on = s.ordinal <= step.ordinal
            Box(Modifier.size(12.dp).background(if (on) field.foreground else field.foreground.copy(alpha = 0.25f), androidx.compose.foundation.shape.CircleShape))
        }
    }
}

/** The time of the step, filling calmly. */
@Composable
private fun TimeBar(progress: androidx.compose.runtime.MutableFloatState, field: Field) {
    val color = if (field == Field.night) Palette.nightSoft else field.deep
    Canvas(Modifier.fillMaxWidth().height(14.dp).semantics { contentDescription = "Timpul" }) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(color.copy(alpha = 0.2f), cornerRadius = r)
        drawRoundRect(color, size = Size(size.width * progress.floatValue, size.height), cornerRadius = r)
    }
}

/** His result: his two letters, where they fall on the S ↔ Ș axis, his room and his voice. Few words. */
@Composable
private fun ResultView(result: CalResult?, profile: Profile) {
    if (result == null) {
        FoniaText("…", style = ui(40, UIWeight.black, TextRole.largeTitle), color = Palette.ink)
        return
    }
    val sLink = rememberLetterLink(profile.id, "s", result)
    val shLink = rememberLetterLink(profile.id, "sh", result)
    LaunchedEffect(result) {
        if (result.active) {
            sLink.correct(); shLink.correct()
        } else {
            sLink.almost(); shLink.almost()
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // In the order of the axis under them: Ș on the left, S on the right.
            HeldLetter("Ș", shLink, Field.warm, height = 130.dp)
            HeldLetter("S", sLink, Field.cold, height = 130.dp)
        }
        PlaceAxis(result.room.shPlace, result.room.sPlace)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            FoniaIconView(FoniaIcon.waves, size = 28f, tint = Palette.ink)
            FoniaText(result.room.f0Hz?.let { "${it.toInt()} Hz" } ?: "–", style = ui(22, UIWeight.bold, TextRole.title3), color = Palette.ink)
            Spacer(Modifier.size(12.dp))
            FoniaIconView(FoniaIcon.loud, size = 28f, tint = Palette.ink)
            FoniaText("+${(result.room.speechDb - result.room.floorDb).toInt()} dB", style = ui(22, UIWeight.bold, TextRole.title3), color = Palette.ink)
        }
        if (!result.active) {
            FoniaText("Mai ține o dată S și Ș", style = ui(20, UIWeight.medium, TextRole.body), color = Palette.inkSoft, textAlign = TextAlign.Center)
        }
    }
}

/** The S ↔ Ș axis with his own two dots: Ș in its warm colour on the left, S in its cold colour on the right. */
@Composable
private fun PlaceAxis(sh: Float?, s: Float?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FoniaText("Ș", style = ui(22, UIWeight.black, TextRole.headline), color = Field.warm.deep)
        Canvas(Modifier.weight(1f).height(40.dp)) {
            val h = 14.dp.toPx()
            val y = size.height / 2f
            drawLine(Palette.progressTrack, Offset(0f, y), Offset(size.width, y), strokeWidth = h, cap = StrokeCap.Round)
            drawLine(Palette.inkSoft.copy(alpha = 0.5f), Offset(size.width / 2f, y - h), Offset(size.width / 2f, y + h), strokeWidth = 2.dp.toPx())
            fun dot(p: Float?, color: androidx.compose.ui.graphics.Color) {
                if (p == null) return
                val x = h + (size.width - 2 * h) * p.coerceIn(0f, 1f)
                drawCircle(color, h * 1.1f, Offset(x, y))
                drawCircle(Palette.sheet, h * 1.1f, Offset(x, y), style = Stroke(3.dp.toPx()))
            }
            dot(sh, Field.warm.deep)
            dot(s, Field.cold.deep)
        }
        FoniaText("S", style = ui(22, UIWeight.black, TextRole.headline), color = Field.cold.deep)
    }
}
