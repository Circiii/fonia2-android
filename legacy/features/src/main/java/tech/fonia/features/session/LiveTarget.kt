package tech.fonia.features.session

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import tech.fonia.audio.LiveLevels
import tech.fonia.design.Field
import tech.fonia.design.Palette
import tech.fonia.design.PracticeWeight
import tech.fonia.design.TargetWord
import tech.fonia.design.TextRole
import tech.fonia.design.practice
import tech.fonia.designsystem.letter.LetterCue
import tech.fonia.designsystem.letter.LetterDrive
import tech.fonia.designsystem.letter.LiveLetter
import tech.fonia.designsystem.letter.LiveLetterState
import tech.fonia.designsystem.tokens.FoniaClassicSticker
import tech.fonia.designsystem.tokens.StickerSet
import tech.fonia.designsystem.tokens.VerdictStickers
import tech.fonia.model.ErrorKind

/**
 * One living letter of a practice screen: its state (the cues) and its drive (the voice, every frame). The exercise
 * owns it, the prompt draws it, the verdict cues it. Never observed: changing it recomposes nothing.
 */
@Stable
class LetterLink {
    val state = LiveLetterState()
    val drive = LetterDrive()

    /** The grapheme drawn alive (for instruction cues that need it); set by whoever draws the letter. */
    var target: String = ""

    /** The calm live values ([FeedLetter]); one per letter. */
    val smoother = LiveSmoother()

    /** 0..1 light behind the letter while the target sound is heard (read only in the draw phase). */
    val glow = mutableFloatStateOf(0f)

    /** The target sound is heard now: the letter wears the "good" sticker. */
    var hit by mutableStateOf(false)

    fun correct() = state.cue(LetterCue.Correct)
    fun almost() = state.cue(LetterCue.Almost)
    fun retake() = state.cue(LetterCue.Retake)
    fun rest() = state.cue(LetterCue.Rest)
    fun pop() = state.cue(LetterCue.Pop)

    /** A named error: only the instruction animation (the comma drops, the air goes to the centre ...). */
    fun instruct(kind: ErrorKind, heard: String?) = state.cue(LetterCue.Instruct(kind, heard))
}

/** His family colours as a sticker: cold blue for S Z Ț, warm for Ș J CE GE, his yellow for R L. */
fun stickerFor(field: Field): StickerSet = when (field) {
    Field.cold -> FoniaClassicSticker.Cold
    Field.warm -> FoniaClassicSticker.Warm
    Field.tip -> FoniaClassicSticker.Tip
    else -> FoniaClassicSticker.Good
}

/**
 * Feeds [link]'s drive from his live levels once per frame while [listening] (the child's voice) or [modelVoice] (the
 * model's voice, which makes the letter beat along, gently). Reads only volatile fields: no recomposition, except
 * [LetterLink.hit] flipping (at most a few times a second, with hysteresis).
 *
 * While listening every sound shows at once, calmly: the raw 10 ms values go through a [LiveSmoother] (attack ~70 ms,
 * release ~320 ms, dead zone, from `spec/pacing.json`); the letter swells with the voice up to
 * [Pacing.voiceInflationTop] and the rest of the way, plus its light and the "good" sticker, only while the target sound itself is heard
 * ([LiveAim]). Nothing noisy reaches its outline: no voicing boil, a third of the 11 Hz energy buzz.
 * [baseInflation] is its resting size (the held share in the sustain exercise). [heard] receives the envelope each
 * frame, for the "Te aud" ring.
 */
@Composable
fun FeedLetter(
    link: LetterLink,
    levels: LiveLevels,
    listening: Boolean,
    modelVoice: Boolean,
    holdSeconds: () -> Float = { 0f },
    baseInflation: () -> Float = { REST_INFLATION },
    heard: ((Float) -> Unit)? = null,
    /** Log-odds added to the live S-ness before the letter and the meters see it (see [placeBias]). */
    placeBias: Float = 0f,
    /** What lights the letter: the target sound heard live. */
    aim: LiveAim = LiveAim.None,
    /** The phoneme model's live answer for the target, when a take spots one. */
    spot: LiveSpot? = null,
) {
    LaunchedEffect(link, listening, modelVoice, placeBias, aim) {
        val d = link.drive
        val smooth = link.smoother
        val pacing = Pacing.current
        d.listening = listening
        if (!listening && !modelVoice) {
            d.quiet()
            smooth.reset()
            link.glow.floatValue = 0f
            link.hit = false
            d.inflation = baseInflation()
            heard?.invoke(0f)
            return@LaunchedEffect
        }
        var last = 0L
        while (isActive) {
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f)
            last = now
            if (listening) {
                smooth.step(
                    dtSeconds = dt,
                    rawLevel = levels.level,
                    rawVoicing = levels.clarity,
                    isFricative = levels.isFricative,
                    rawFricative = levels.fricativeStrength,
                    sNess = if (levels.isFricative) recentred(levels.sNess, placeBias) else Float.NaN,
                    rawTrill = levels.trillStrength,
                    rawBlow = levels.blowStrength,
                    aim = aim,
                    fricativeVoiced = levels.isFricativeVoiced,
                    spotted = spot?.value() ?: Float.NaN,
                    hissFlatness = levels.hissFlatness,
                )
                val envelope = smooth.level
                // With a target the letter is still until that sound is heard; the "Te aud" ring answers the voice.
                val share = if (aim == LiveAim.None) 1f else smooth.glow
                d.feed(
                    level = (envelope * 1.6f).coerceAtMost(1f) * pacing.letterBuzzShare * share,
                    speaking = if (aim == LiveAim.None) HearRules.hears(envelope) || smooth.fricative > 0.12f else smooth.hit,
                    sNess = smooth.place,
                    fricative = smooth.fricative * share,
                    voicing = smooth.voicing * share,
                    trill = smooth.trill * share,
                    blow = smooth.blow * share,
                    holdSeconds = holdSeconds(),
                )
                d.inflation = smooth.inflation(baseInflation(), voiceMoves = aim == LiveAim.None)
                d.trembling = 0f
                link.glow.floatValue = smooth.glow
                if (link.hit != smooth.hit) link.hit = smooth.hit
                heard?.invoke(envelope)
            } else {
                val talk = levels.talk
                d.feed(level = talk * 0.6f * pacing.letterBuzzShare, speaking = talk > 0.12f)
                d.inflation = baseInflation()
                d.trembling = 0f
            }
        }
    }
}

/**
 * Where the live S-ness is recentred for the place meter and the letter. The live analyzer's man boundary sits high:
 * on the man's test clips (output/aude/clips held_s5/held_sh5, tmp/test_audio roger_s/roger_sh) a held S reads a median
 * 0.34-0.59 and a held Ș 0.01-0.02 with the MALE group, so the bead stayed in the middle for a good S. Shifting the
 * log-odds by 2.2 puts that S at about 0.84-0.94 and that Ș at 0.07-0.13. Children and women are left as they are.
 * No judge verdict reads it (the held bar does). Once his own S/Ș are calibrated ("Calibrare", or enough good takes), the live
 * analysis measures against them and no shift is applied.
 */
fun placeBias(group: tech.fonia.dsp.VoiceGroup, calibrated: Boolean = false): Float =
    if (group == tech.fonia.dsp.VoiceGroup.MALE && !calibrated) 2.2f else 0f

internal fun recentred(sNess: Float, bias: Float): Float {
    if (bias == 0f || sNess.isNaN()) return sNess
    val p = sNess.coerceIn(1e-4f, 1f - 1e-4f)
    val logit = kotlin.math.ln(p / (1f - p)) + bias
    return (1f / (1f + kotlin.math.exp(-1.1f * logit)))
}

/** The letter's size at rest (the drive's own default). */
const val REST_INFLATION = 0.55f

/** Where the cap centre of a hero letter sits in its box, and the em of the box (see design/README "Hero layout"). */
private const val HERO_EM = 0.54f
private const val HERO_CAP_CENTER = 0.47f

/** Allo's cap height (em): the hero's baseline is the cap centre plus half of it. */
private const val ALLO_CAP = 0.70f

/**
 * His target word ([TargetWord]: practice type, ink, the target grapheme in the family colour) with the target letter
 * drawn alive as a sticker in the family colour, sitting on the same baseline. Falls back to his word when the target
 * is not in it.
 */
@Composable
fun LiveTargetWord(
    word: String,
    graphemes: List<String>,
    field: Field,
    link: LetterLink,
    modifier: Modifier = Modifier,
    size: Float = 52f,
    weight: PracticeWeight = PracticeWeight.regular,
    ink: Color = Palette.ink,
) {
    val lowered = word.lowercase()
    val at = graphemes.sortedByDescending { it.length }.filter { it.isNotEmpty() }
        .map { lowered.indexOf(it) }.filter { it >= 0 }.minOrNull()
    if (at == null || word.contains(' ')) {
        TargetWord(word, graphemes, target = field.deep, modifier = modifier, size = size, ink = ink)
        return
    }
    val letter = word.substring(at, at + 1)
    link.target = letter
    val prefix = word.substring(0, at)
    val suffix = word.substring(at + 1)
    BoxWithConstraints(modifier.clearAndSetSemantics { contentDescription = word }, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        // A word shrinks before it breaks (his minimumScaleFactor 0.5): Baloo runs about 0.6 em per letter.
        val estimate = with(density) { (size * fontScale * 0.62f * word.length).dp.toPx() }
        val room = constraints.maxWidth.toFloat()
        val fit = if (constraints.hasBoundedWidth && estimate > room) (room / estimate).coerceAtLeast(0.5f) else 1f
        val shown = size * fit
        val style = practice(shown, weight, TextRole.largeTitle).copy(color = ink)
        val emPx = with(density) { (shown * fontScale).dp.toPx() }
        Row(verticalAlignment = Alignment.Bottom) {
            if (prefix.isNotEmpty()) BasicText(prefix, Modifier.alignByBaseline(), style = style, maxLines = 1, softWrap = false)
            LetterSlot(letter, link, field, emPx, Modifier.alignBy { (emPx * 1.0f).toInt() })
            if (suffix.isNotEmpty()) BasicText(suffix, Modifier.alignByBaseline(), style = style, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * A hero [LiveLetter] cut down to the room of one letter in a line of text: it reports the width of the glyph and the
 * height of a line (baseline at 1 em), and draws over its neighbours when it inflates or pops.
 */
@Composable
private fun LetterSlot(letter: String, link: LetterLink, field: Field, emPx: Float, modifier: Modifier) {
    val density = LocalDensity.current
    val boxH = emPx / HERO_EM
    val wide = letter.uppercase().let { it == "M" || it == "W" } || letter.first().isUpperCase()
    val slotW = emPx * (if (wide) 0.72f else 0.58f)
    val lineH = emPx * 1.3f
    val baselineInBox = boxH * (HERO_CAP_CENTER + HERO_EM * ALLO_CAP / 2f)
    val boxHdp = with(density) { boxH.toDp() }
    LiveLetter(
        letter,
        link.state,
        modifier
            .layout { measurable, _ ->
                val p = measurable.measure(Constraints.fixed(slotW.toInt().coerceAtLeast(1), boxH.toInt()))
                layout(slotW.toInt().coerceAtLeast(1), lineH.toInt()) { p.place(0, (emPx * 1.0f - baselineInBox).toInt()) }
            }
            .targetGlow(link),
        drive = link.drive,
        sticker = if (link.hit) FoniaClassicSticker.Good else stickerFor(field),
        height = boxHdp,
        verdicts = VerdictStickers.Classic,
    )
}

/**
 * The light behind a living letter while the target sound is heard: a soft disc in the "good" colour that comes up in
 * ~90 ms and fades in ~380 ms ([LetterLink.glow], read only while drawing). Seen at arm's length; never red.
 */
fun Modifier.targetGlow(link: LetterLink): Modifier = drawBehind {
    val g = link.glow.floatValue
    if (g < 0.02f) return@drawBehind
    val r = size.minDimension * 0.62f
    val c = Offset(size.width / 2f, size.height * HERO_CAP_CENTER)
    drawCircle(
        Brush.radialGradient(
            0f to Palette.progress.copy(alpha = 0.55f * g),
            0.6f to Palette.progress.copy(alpha = 0.28f * g),
            1f to Palette.progress.copy(alpha = 0f),
            center = c,
            radius = r,
        ),
        radius = r,
        center = c,
    )
}

/** The big held letter of the sustain exercise (and the hero letter over a sentence). */
@Composable
fun HeldLetter(letter: String, link: LetterLink, field: Field, modifier: Modifier = Modifier, height: Dp = 200.dp) {
    link.target = letter
    LiveLetter(
        letter,
        link.state,
        modifier.targetGlow(link),
        drive = link.drive,
        sticker = if (link.hit) FoniaClassicSticker.Good else stickerFor(field),
        height = height,
        verdicts = VerdictStickers.Classic,
    )
}

/**
 * A second living letter on the same voice ([LetterLink.drive], light and sticker) but with its own cues: shown where
 * the practised letter is hidden while he speaks (naming a picture), so something always answers his voice.
 */
@Composable
fun EchoLetter(letter: String, link: LetterLink, field: Field, modifier: Modifier = Modifier, height: Dp = 150.dp) {
    val own = remember(link) { LiveLetterState() }
    LiveLetter(
        letter,
        own,
        modifier.targetGlow(link),
        drive = link.drive,
        sticker = if (link.hit) FoniaClassicSticker.Good else stickerFor(field),
        height = height,
        verdicts = VerdictStickers.Classic,
    )
}

/** `remember { LetterLink() }`, keyed so a new trial (or a retake) gets a fresh letter. */
@Composable
fun rememberLetterLink(vararg keys: Any?): LetterLink = remember(*keys) { LetterLink() }
