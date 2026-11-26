package tech.fonia.features.session

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import tech.fonia.features.app.shortLetter
import tech.fonia.features.app.shape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tech.fonia.design.Field
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaLabel
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.PillButtonStyle
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.ui
import tech.fonia.domain.FeedbackLevel
import tech.fonia.domain.SoundID
import tech.fonia.judge.HintKey
import tech.fonia.judge.TakeVisual
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** The words and the guide step of a hint: at most three words, an instruction (what to do), never what was wrong. */
object HintWords {
    fun of(key: HintKey): String = when (key) {
        HintKey.LipsRound -> "Buze rotunde"
        HintKey.LipsSpread -> "Zâmbește larg"
        HintKey.TongueTipDown -> "Limba jos"
        HintKey.TongueTipUp -> "Limba sus"
        HintKey.TongueBack -> "Limba înăuntru"
        HintKey.StopThenRelease -> "Atinge, apoi suflă"
        HintKey.TapTongue -> "Limba vibrează"
        HintKey.AddVoice -> "Pornește vocea"
        HintKey.NoVoice -> "Fără voce"
        HintKey.MoreAir -> "Mai mult aer"
        HintKey.HoldLonger -> "Ține mai mult"
    }

    /** Words of the sound's "Cum spun?" steps that belong to a hint, in the order they are looked for. */
    private fun keywords(key: HintKey): List<String> = when (key) {
        HintKey.LipsRound -> listOf("pupic", "buze")
        HintKey.LipsSpread -> listOf("zâmbește")
        HintKey.TongueTipDown, HintKey.TongueBack -> listOf("culcă limba", "limba jos")
        HintKey.TongueTipUp -> listOf("ridică limba", "urcă limba", "lipește", "sus")
        HintKey.StopThenRelease -> listOf("atinge", "lipește limba", "dă drumul", "dă-i drumul")
        HintKey.TapTongue -> listOf("atinge scurt", "urcă limba")
        HintKey.AddVoice -> listOf("palma pe gât", "cu voce", "bâzâie")
        HintKey.NoVoice, HintKey.MoreAir -> listOf("suflă", "dă drumul")
        HintKey.HoldLonger -> listOf("fă ca mine")
    }

    /** The step of [how] (the sound's "Cum spun?" lines) that shows this hint; 0 when none matches. */
    fun step(key: HintKey, how: List<String>): Int {
        val lower = how.map { it.lowercase() }
        for (word in keywords(key)) {
            val i = lower.indexOfFirst { it.contains(word) }
            if (i >= 0) return i
        }
        return 0
    }
}

/** Pure rules of the strip, kept apart from the drawing for the tests. */
object TakeFeedbackRules {
    /** Half-width of the uncertain middle of the axis; the target zone starts past it (as the judge's visual zones). */
    const val LIVE_ZONE_HALF = 0.1f

    /**
     * A take of a silent slot says nothing about how it sounded: no panel, no letter cue, no word marks, no haptic.
     * Still shown: the learner's own question and "Nu te-am auzit", which is about the microphone and offers the take
     * again. iOS `TakeFeedbackRules.hidesVerdict`.
     */
    fun hidesVerdict(feedback: FeedbackLevel, asks: Boolean, notHeard: Boolean): Boolean =
        feedback == FeedbackLevel.Silent && !asks && !notHeard

    /**
     * How long "Te-am auzit" stays at automatizare before the next item comes by itself: long enough to read, shorter
     * than a good take's verdict, so a hidden one never feels like a wait. iOS `TakeFeedbackRules.heardBeat`.
     */
    const val HEARD_BEAT_MS = 1200L

    /**
     * [visual] with a place axis made from the live hiss when the judge gave none and the sound has an axis; the
     * reference is "live". Unchanged when the judge placed it, when there was no hiss, or for R and L.
     */
    fun withLivePlace(visual: TakeVisual?, sound: SoundID?, liveSNess: Float): TakeVisual? {
        if (sound == null || liveSNess.isNaN()) return visual
        if (visual?.placeAxis != null) return visual
        val target = MeterPlan.of(sound).targetSharp ?: return visual
        val axis = if (target >= 0.5f) {
            tech.fonia.judge.PlaceAxis(liveSNess, 0.5f + LIVE_ZONE_HALF, 1f, "live")
        } else {
            tech.fonia.judge.PlaceAxis(liveSNess, 0f, 0.5f - LIVE_ZONE_HALF, "live")
        }
        return (visual ?: TakeVisual()).copy(placeAxis = axis)
    }
}

/** The two letters at the ends of the place axis, and which end is the target. */
internal fun axisLabels(id: SoundID): Pair<String, String> = MeterPlan.of(id).let { it.left to it.right }

/**
 * What the take looked like, under the outcome word, without a sentence: the S↔Ș axis frozen where HIS sound sat with
 * the target zone (glowing when he hit it), the voice chip when voicing matters, the R flutter beads, the
 * closure → release chip of Ț/CE/GE, the held-time bar when the length mattered.
 */
@Composable
fun TakeStrip(visual: TakeVisual, sound: SoundID, field: Field, correct: Boolean, modifier: Modifier = Modifier) {
    val glowT = rememberInfiniteTransition(label = "zone")
    val glow by glowT.animateFloat(0.55f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "glow")
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = describe(visual) }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        visual.placeAxis?.let { axis ->
            val (left, right) = axisLabels(sound)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FoniaText(left, style = ui(17, UIWeight.black, TextRole.headline), color = Palette.ink)
                Canvas(Modifier.weight(1f).height(30.dp)) {
                    val h = 14.dp.toPx()
                    val top = (size.height - h) / 2f
                    val r = CornerRadius(h / 2f)
                    drawRoundRect(Palette.progressTrack, Offset(0f, top), Size(size.width, h), r)
                    val pad = h * 0.9f
                    fun x(p: Float) = pad + (size.width - 2 * pad) * p.coerceIn(0f, 1f)
                    val zx0 = if (axis.zoneLow <= 0.001f) 0f else x(axis.zoneLow)
                    val zx1 = if (axis.zoneHigh >= 0.999f) size.width else x(axis.zoneHigh)
                    // A take the judge accepted lights the zone even when the hiss sat just outside it: the dot stays
                    // where it really was, in the good colour.
                    val on = axis.inZone || correct
                    drawRoundRect(if (on) Palette.progress.copy(alpha = 0.55f + 0.45f * glow) else Palette.accentSoft, Offset(zx0, top), Size(zx1 - zx0, h), r)
                    val cx = x(axis.value)
                    val cy = size.height / 2f
                    // The same knob as the live meter: a white rim round a solid dot, all inside the canvas.
                    drawCircle(Palette.sheet, h * 0.95f, Offset(cx, cy))
                    drawCircle(if (on) Palette.progress else field.deep, h * 0.72f, Offset(cx, cy))
                }
                FoniaText(right, style = ui(17, UIWeight.black, TextRole.headline), color = Palette.ink)
            }
        }
        val chips = chipsOf(visual, correct)
        if (chips.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                for (chip in chips) Chip(chip, field)
            }
        }
    }
}

/** The small cues besides the axis, at most three. */
internal sealed interface Cue {
    val good: Boolean

    data class Voice(val on: Boolean, override val good: Boolean) : Cue
    data class Trill(val strength: Float, val needed: Float, override val good: Boolean) : Cue
    data class Closure(override val good: Boolean) : Cue
    data class Held(val share: Float, override val good: Boolean) : Cue
}

internal fun chipsOf(v: TakeVisual, correct: Boolean = false): List<Cue> = buildList {
    v.voicing?.let { c ->
        // Voicing matters where the sound needs a voice, or where a voice crept into a whisper sound (shown only on
        // a take that was not right: the vowels around an S hum, and a right S must not look wrong).
        val present = c.present
        if (present != null && (c.expected || (present && !correct))) add(Cue.Voice(present, present == c.expected))
    }
    v.trill?.let { add(Cue.Trill(it.value, it.needed, it.ok == true)) }
    v.closure?.let { c -> if (c.closure != null) add(Cue.Closure(c.closure == true && c.release != false)) }
    v.duration?.let { d -> if (!correct && (d.ok == false || v.hint?.key == HintKey.HoldLonger)) add(Cue.Held((d.value / d.needed).coerceIn(0f, 1f), d.ok == true)) }
}.take(3)

private fun describe(v: TakeVisual): String = buildList {
    v.placeAxis?.let { add(if (it.inZone) "Sunetul a fost în zonă" else "Sunetul a fost lângă zonă") }
    v.voicing?.present?.let { add(if (it) "Vocea pornită" else "Vocea oprită") }
}.joinToString(". ")

@Composable
private fun Chip(cue: Cue, field: Field) {
    val tint = if (cue.good) Palette.progress else field.deep
    Row(
        Modifier
            .background(if (cue.good) Palette.accentSoft else Palette.progressTrack, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (cue) {
            is Cue.Voice -> {
                Canvas(Modifier.size(width = 34.dp, height = 20.dp)) { drawVoice(cue.on, tint) }
                FoniaText(if (cue.on) "Voce" else "Fără voce", style = ui(15, UIWeight.bold, TextRole.body), color = Palette.ink)
            }
            is Cue.Trill -> Canvas(Modifier.size(width = 86.dp, height = 20.dp)) {
                val n = 5
                val filled = (cue.strength * n).roundToInt()
                for (i in 0 until n) {
                    val cx = size.width * (i + 0.5f) / n
                    drawCircle(if (i < filled) tint else Palette.sheet, size.height * 0.36f, Offset(cx, size.height / 2f))
                    drawCircle(tint, size.height * 0.36f, Offset(cx, size.height / 2f), style = Stroke(1.5.dp.toPx()))
                }
            }
            is Cue.Closure -> Canvas(Modifier.size(width = 70.dp, height = 20.dp)) { drawClosure(tint) }
            is Cue.Held -> Canvas(Modifier.size(width = 80.dp, height = 16.dp)) {
                val r = CornerRadius(size.height / 2f)
                drawRoundRect(Palette.sheet, cornerRadius = r)
                drawRoundRect(tint, size = Size(size.width * cue.share.coerceAtLeast(0.08f), size.height), cornerRadius = r)
            }
        }
    }
}

private fun DrawScope.drawVoice(on: Boolean, color: Color) {
    val y = size.height / 2f
    val stroke = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)
    if (!on) {
        drawLine(color, Offset(2f, y), Offset(size.width - 2f, y), strokeWidth = stroke.width, cap = StrokeCap.Round)
        return
    }
    val p = Path()
    val steps = 40
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val px = 2f + (size.width - 4f) * t
        val py = y + sin(t * 4f * PI).toFloat() * size.height * 0.38f
        if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
    }
    drawPath(p, color, style = stroke)
}

private fun DrawScope.drawClosure(color: Color) {
    val h = size.height
    // the closure: a small block (the tongue against the ridge) ...
    drawRoundRect(color, Offset(0f, h * 0.15f), Size(h * 0.7f, h * 0.7f), CornerRadius(h * 0.15f))
    // ... an arrow ...
    val ax0 = h * 1.0f
    val ax1 = size.width * 0.55f
    drawLine(color, Offset(ax0, h / 2f), Offset(ax1, h / 2f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color, Offset(ax1, h / 2f), Offset(ax1 - h * 0.25f, h * 0.25f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color, Offset(ax1, h / 2f), Offset(ax1 - h * 0.25f, h * 0.75f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    // ... then the release: three short air lines.
    for (i in 0 until 3) {
        val y = h * (0.25f + 0.25f * i)
        drawLine(color, Offset(size.width * 0.64f, y), Offset(size.width - 2f, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

/**
 * The one hint after a take that was not right yet: a big drawn icon, at most three words, and „Vezi cum”, which opens
 * the sound's „Cum spun?” at the step that shows it.
 */
@Composable
fun HintCard(key: HintKey, field: Field, onHow: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Palette.accentSoft.copy(alpha = 0.6f), RoundedCornerShape(22.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = HintWords.of(key) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(58.dp).background(Palette.sheet, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            HintIcon(key, field.deep, Modifier.size(46.dp))
        }
        // The words above „Vezi cum”: a pill button takes the whole width it is given, so it gets its own line.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FoniaText(HintWords.of(key), style = ui(20, UIWeight.black, TextRole.headline), color = Palette.ink, maxLines = 2)
            StyledButton(onClick = onHow, style = PillButtonStyle()) { FoniaLabel("Vezi cum", FoniaIcon.mouth, 18f) }
        }
    }
}

/** Simple drawn icons, in the family colour: lips, the tongue in a side view, the voice, the air, the length. */
@Composable
fun HintIcon(key: HintKey, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val s = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
        fun teeth() {
            // upper and lower front teeth, side view, on the right
            drawRoundRect(color.copy(alpha = 0.35f), Offset(w * 0.68f, h * 0.22f), Size(w * 0.12f, h * 0.22f), CornerRadius(4f))
            drawRoundRect(color.copy(alpha = 0.35f), Offset(w * 0.68f, h * 0.56f), Size(w * 0.12f, h * 0.22f), CornerRadius(4f))
        }
        fun arrow(from: Offset, to: Offset) {
            drawLine(color, from, to, s.width, StrokeCap.Round)
            val dx = to.x - from.x
            val dy = to.y - from.y
            val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            val ux = dx / len
            val uy = dy / len
            val k = w * 0.1f
            drawLine(color, to, Offset(to.x - k * (ux + uy * 0.7f), to.y - k * (uy - ux * 0.7f)), s.width, StrokeCap.Round)
            drawLine(color, to, Offset(to.x - k * (ux - uy * 0.7f), to.y - k * (uy + ux * 0.7f)), s.width, StrokeCap.Round)
        }
        fun tongue(tipY: Float) {
            val p = Path().apply {
                moveTo(w * 0.08f, h * 0.78f)
                quadraticBezierTo(w * 0.35f, h * 0.45f, w * 0.64f, tipY)
            }
            drawPath(p, color, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
        }
        when (key) {
            HintKey.LipsRound -> {
                drawOval(color, Offset(w * 0.22f, h * 0.18f), Size(w * 0.56f, h * 0.64f), style = Stroke(7.dp.toPx()))
                drawOval(color.copy(alpha = 0.25f), Offset(w * 0.36f, h * 0.34f), Size(w * 0.28f, h * 0.32f))
            }
            HintKey.LipsSpread -> {
                val p = Path().apply {
                    moveTo(w * 0.06f, h * 0.45f)
                    quadraticBezierTo(w * 0.5f, h * 0.95f, w * 0.94f, h * 0.45f)
                    quadraticBezierTo(w * 0.5f, h * 0.62f, w * 0.06f, h * 0.45f)
                }
                drawPath(p, color)
                drawLine(color, Offset(w * 0.06f, h * 0.45f), Offset(w * 0.94f, h * 0.45f), s.width, StrokeCap.Round)
            }
            HintKey.TongueTipDown, HintKey.TongueBack -> {
                teeth()
                tongue(h * 0.72f)
                arrow(Offset(w * 0.5f, h * 0.2f), Offset(w * 0.5f, h * 0.5f))
            }
            HintKey.TongueTipUp -> {
                teeth()
                tongue(h * 0.3f)
                arrow(Offset(w * 0.3f, h * 0.55f), Offset(w * 0.3f, h * 0.2f))
            }
            HintKey.StopThenRelease -> {
                teeth()
                tongue(h * 0.32f)
                for (i in 0 until 3) {
                    val y = h * (0.34f + 0.12f * i)
                    drawLine(color, Offset(w * 0.84f, y), Offset(w * 0.98f, y), s.width, StrokeCap.Round)
                }
            }
            HintKey.TapTongue -> {
                teeth()
                tongue(h * 0.34f)
                for (i in 0 until 3) {
                    val x = w * (0.42f + 0.08f * i)
                    drawLine(color, Offset(x, h * 0.08f), Offset(x + w * 0.04f, h * 0.2f), s.width, StrokeCap.Round)
                }
            }
            HintKey.AddVoice, HintKey.NoVoice -> {
                val on = key == HintKey.AddVoice
                // a throat (two lines) and the voice wave, or a flat line
                drawLine(color.copy(alpha = 0.4f), Offset(w * 0.12f, h * 0.1f), Offset(w * 0.12f, h * 0.9f), s.width, StrokeCap.Round)
                drawLine(color.copy(alpha = 0.4f), Offset(w * 0.88f, h * 0.1f), Offset(w * 0.88f, h * 0.9f), s.width, StrokeCap.Round)
                if (on) {
                    val p = Path()
                    for (i in 0..40) {
                        val t = i / 40f
                        val px = w * (0.2f + 0.6f * t)
                        val py = h * 0.5f + sin(t * 6f * PI).toFloat() * h * 0.22f
                        if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
                    }
                    drawPath(p, color, style = s)
                } else {
                    drawLine(color, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.8f, h * 0.5f), s.width, StrokeCap.Round)
                }
            }
            HintKey.MoreAir -> {
                for (i in 0 until 3) {
                    val y = h * (0.28f + 0.22f * i)
                    arrow(Offset(w * 0.1f, y), Offset(w * (0.7f + 0.1f * (i % 2)), y))
                }
            }
            HintKey.HoldLonger -> {
                drawRoundRect(color.copy(alpha = 0.25f), Offset(w * 0.05f, h * 0.38f), Size(w * 0.9f, h * 0.24f), CornerRadius(h * 0.12f))
                drawRoundRect(color, Offset(w * 0.05f, h * 0.38f), Size(w * 0.55f, h * 0.24f), CornerRadius(h * 0.12f))
                arrow(Offset(w * 0.55f, h * 0.2f), Offset(w * 0.92f, h * 0.2f))
            }
        }
    }
}

/** „Calibrat pe vocea ta”: a small badge once his own S/Ș reference is in use. */
@Composable
fun CalibratedBadge(modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(Palette.accentSoft, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.size(16.dp)) {
            // a tuning mark: a wave inside a ring
            drawCircle(Palette.progress, size.minDimension / 2f, style = Stroke(2.dp.toPx()))
            val p = Path()
            for (i in 0..20) {
                val t = i / 20f
                val px = size.width * (0.22f + 0.56f * t)
                val py = size.height / 2f + sin(t * 4f * PI).toFloat() * size.height * 0.16f
                if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
            }
            drawPath(p, Palette.progress, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
        }
        FoniaText("Calibrat pe vocea ta", style = ui(13, UIWeight.bold, TextRole.footnote), color = Palette.ink, maxLines = 1)
    }
}

/**
 * „Vezi cum” over the session: the sound's „Cum spun?” (the mouth and its numbered steps) with the step the hint is
 * about in bold, and its line said when there is a recording of it. A tap outside or „Am înțeles” closes it.
 */
@Composable
fun HowSheet(sound: tech.fonia.domain.SoundDef, step: Int, field: Field, app: tech.fonia.features.app.AppModel, onClose: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(sound.id, step) {
        sound.how.getOrNull(step)?.let { app.audio.sayIfRecorded(it) }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.ink.copy(alpha = 0.35f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Palette.sheet, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FoniaText("Cum spun ${sound.shortLetter}?", style = ui(24, UIWeight.black, TextRole.title), color = Palette.ink)
            MouthCard(sound.shape, viseme = sound.viseme, height = 170.dp)
            StepList(sound.how, current = step.coerceIn(0, (sound.how.size - 1).coerceAtLeast(0)), field = field)
            MainAction("Am înțeles", field, onClose)
        }
    }
}

