package tech.fonia.features.session

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Constraints
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
 * model's voice, which makes the letter beat along, gently). Reads only volatile fields: no recomposition.
 *
 * While listening every sound shows at once (owner, 2026-11-23: "nu se întâmplă nimic"): the letter swells with the
 * loudness envelope over the measured room floor ([HearRules.letterInflation], on within ~30 ms), wobbles with voicing,
 * sharpens for S / rounds for Ș on a hiss, ripples for R, leans away when blown. [baseInflation] is its resting size
 * (the held share in the sustain exercise). [heard] receives the envelope each frame, for the "Te aud" ring.
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
) {
    LaunchedEffect(link, listening, modelVoice) {
        val d = link.drive
        d.listening = listening
        if (!listening && !modelVoice) {
            d.quiet()
            d.inflation = baseInflation()
            heard?.invoke(0f)
            return@LaunchedEffect
        }
        var envelope = 0f
        var last = 0L
        while (isActive) {
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f)
            last = now
            if (listening) {
                envelope = HearRules.envelope(envelope, levels.level, dt)
                val hears = HearRules.hears(envelope) || levels.isFricative
                d.feed(
                    level = (envelope * 1.6f).coerceAtMost(1f),
                    speaking = hears,
                    sNess = if (levels.isFricative) levels.sNess else Float.NaN,
                    fricative = levels.fricativeStrength,
                    voicing = levels.clarity,
                    trill = levels.trillStrength,
                    blow = levels.blowStrength,
                    holdSeconds = holdSeconds(),
                )
                d.inflation = HearRules.letterInflation(baseInflation(), envelope)
                d.trembling = HearRules.letterWobble(envelope, levels.clarity)
                heard?.invoke(envelope)
            } else {
                val talk = levels.talk
                d.feed(level = talk * 0.6f, speaking = talk > 0.12f)
                d.inflation = baseInflation()
                d.trembling = 0f
            }
        }
    }
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
        modifier.layout { measurable, _ ->
            val p = measurable.measure(Constraints.fixed(slotW.toInt().coerceAtLeast(1), boxH.toInt()))
            layout(slotW.toInt().coerceAtLeast(1), lineH.toInt()) { p.place(0, (emPx * 1.0f - baselineInBox).toInt()) }
        },
        drive = link.drive,
        sticker = stickerFor(field),
        height = boxHdp,
        verdicts = VerdictStickers.Classic,
    )
}

/** The big held letter of the sustain exercise. */
@Composable
fun HeldLetter(letter: String, link: LetterLink, field: Field, modifier: Modifier = Modifier) {
    link.target = letter
    LiveLetter(
        letter,
        link.state,
        modifier,
        drive = link.drive,
        sticker = stickerFor(field),
        height = 200.dp,
        verdicts = VerdictStickers.Classic,
    )
}

/** `remember { LetterLink() }`, keyed so a new trial (or a retake) gets a fresh letter. */
@Composable
fun rememberLetterLink(vararg keys: Any?): LetterLink = remember(*keys) { LetterLink() }
