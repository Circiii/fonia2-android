package tech.fonia.design

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * The fonts come from the APK's assets (`fonts/`, copied from the content submodule), the way iOS registers
 * the TTFs in the package bundle. [register] runs once; [FoniaTheme] calls it.
 */
object FoniaFonts {
    @Volatile
    private var interFamily: FontFamily? = null

    @Volatile
    private var andikaFamily: FontFamily? = null

    @Volatile
    private var assetManager: android.content.res.AssetManager? = null

    private val balooCache = java.util.concurrent.ConcurrentHashMap<Int, FontFamily>()

    /**
     * Baloo 2 (variable, OFL; `assets/type/`): the one face of everything a person reads (owner, 2026-11-21). Only the
     * living-letter stickers of :core:designsystem stay in Allo.
     */
    fun baloo(weight: Int): FontFamily? {
        val assets = assetManager ?: return null
        val w = weight.coerceIn(400, 800)
        return balooCache.getOrPut(w) {
            FontFamily(
                Font(
                    "type/Baloo2-wght.ttf", assets, FontWeight(w),
                    variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(
                        androidx.compose.ui.text.font.FontVariation.weight(w),
                    ),
                ),
            )
        }
    }

    fun register(context: Context) {
        if (interFamily != null) return
        synchronized(this) {
            if (interFamily != null) return
            val assets = context.applicationContext.assets
            assetManager = assets
            // Every file sits at its real weight, so asking for UIWeight.bold (800) finds Inter-ExtraBold
            // exactly and nothing is synthesised.
            andikaFamily = FontFamily(
                Font("fonts/Andika-Regular.ttf", assets, FontWeight.W400),
                Font("fonts/Andika-SemiBold.ttf", assets, FontWeight.W600),
                Font("fonts/Andika-Bold.ttf", assets, FontWeight.W700),
            )
            interFamily = FontFamily(
                Font("fonts/Inter-Medium.ttf", assets, FontWeight.W500),
                Font("fonts/Inter-SemiBold.ttf", assets, FontWeight.W600),
                Font("fonts/Inter-Bold.ttf", assets, FontWeight.W700),
                Font("fonts/Inter-ExtraBold.ttf", assets, FontWeight.W800),
                Font("fonts/Inter-Black.ttf", assets, FontWeight.W900),
            )
        }
    }

    /** Inter; the system font until [register] has run. Unused since the move to Baloo 2; kept for the asset tests. */
    val inter: FontFamily get() = interFamily ?: FontFamily.Default

    private val alloCache = java.util.concurrent.ConcurrentHashMap<Long, FontFamily>()

    /**
     * Allo (the variable font of :core:designsystem) at fixed axes: the whole app's face (owner's order, 2026-11-21).
     * ROND rounder for headings than for reading text; opsz follows the size. Cached per (weight, ROND, size).
     */
    fun allo(weight: FontWeight, rond: Float, sizeSp: Float): FontFamily {
        val key = (weight.weight.toLong() shl 32) or (rond.toInt().toLong() shl 16) or sizeSp.toInt().coerceIn(9, 32).toLong()
        return alloCache.getOrPut(key) { tech.fonia.designsystem.theme.alloFamily(weight.weight.toFloat(), rond, sizeSp) }
    }

    /** Andika. Unused since the move to Baloo 2; kept for the asset tests. */
    val andika: FontFamily get() = andikaFamily ?: FontFamily.Default
}

/** Everything sits a step heavier than its name, which is what makes wide-set Inter read as friendly. */
@Suppress("EnumEntryName")
enum class UIWeight(val rawValue: String, val fontWeight: FontWeight) {
    regular("Inter-Medium", FontWeight.W500),
    medium("Inter-SemiBold", FontWeight.W600),
    semibold("Inter-Bold", FontWeight.W700),
    bold("Inter-ExtraBold", FontWeight.W800),
    black("Inter-Black", FontWeight.W900),
}

@Suppress("EnumEntryName")
enum class PracticeWeight(val rawValue: String, val fontWeight: FontWeight) {
    regular("Andika", FontWeight.W400),
    semibold("Andika-SemiBold", FontWeight.W600),
    bold("Andika-Bold", FontWeight.W700),
}

/**
 * `Font.TextStyle`, the Dynamic Type curve a size follows on iOS. Android scales every sp by the one system
 * font scale, so the role is kept for parity and documentation only.
 */
@Suppress("EnumEntryName")
enum class TextRole { largeTitle, title, title2, title3, headline, subheadline, body, callout, footnote, caption, caption2 }

/**
 * Interface text: Baloo 2 everywhere, so a page reads as one voice (owner's order, 2026-11-21). [practice] is Baloo
 * too; Allo is kept only for the living letters.
 */
fun ui(size: Number, weight: UIWeight = UIWeight.regular, @Suppress("UNUSED_PARAMETER") relativeTo: TextRole = TextRole.body): TextStyle {
    val px = size.toFloat()
    val heavy = weight.fontWeight.weight
    // Baloo runs 400…800 and is already round and full: one step lighter than his Inter weights keeps the hierarchy.
    val w = when {
        heavy >= 900 -> 800
        heavy >= 800 -> 700
        heavy >= 700 -> 600
        heavy >= 600 -> 500
        else -> 400
    }
    val display = px >= 17f
    FoniaFonts.baloo(w)?.let { family ->
        return TextStyle(
            fontFamily = family,
            fontWeight = FontWeight(w),
            fontSize = (px * (if (display) 1.06f else 1.08f)).sp,
            // Baloo sits tall on its line: a fixed line keeps his layouts where they were and paragraphs open.
            lineHeight = (px * (if (display) 1.22f else 1.36f)).sp,
            lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
            ),
            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
        )
    }
    // Before the fonts are registered (previews): Allo.
    val fallback = FontWeight((heavy - 50).coerceAtLeast(400))
    return TextStyle(fontFamily = FoniaFonts.allo(fallback, if (heavy >= 700) 100f else 85f, px), fontWeight = fallback, fontSize = px.sp)
}

/**
 * Everything being practised: letters, words, sentences. Baloo 2 like the interface (owner, 2026-11-21: "font bun peste
 * tot, adică Baloo"), one step heavier than its name so a thin child's-eye stroke never appears; only the living-letter
 * stickers (`LiveLetter`/`StickerText`) stay in Allo, whose outlines their animation is built on. Baloo's natural line
 * is 1.6 em (ascent 1.078 + descent 0.524); a fixed line keeps words on his baselines while leaving room above for
 * Ă Â Î and below for the comma of Ș Ț. All of ș ț ă â î and „ ” are Baloo glyphs (checked in the font's cmap).
 */
fun practice(size: Number, weight: PracticeWeight = PracticeWeight.regular, @Suppress("UNUSED_PARAMETER") relativeTo: TextRole = TextRole.body): TextStyle {
    val px = size.toFloat()
    val w = when (weight) {
        PracticeWeight.regular -> 500
        PracticeWeight.semibold -> 600
        PracticeWeight.bold -> 700
    }
    FoniaFonts.baloo(w)?.let { family ->
        return TextStyle(
            fontFamily = family,
            fontWeight = FontWeight(w),
            fontSize = px.sp,
            lineHeight = (px * practiceLine(px)).sp,
            lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
            ),
            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
        )
    }
    // Before the fonts are registered (previews): Allo.
    return TextStyle(fontFamily = FoniaFonts.allo(weight.fontWeight, 70f, px), fontWeight = weight.fontWeight, fontSize = px.sp)
}

/** Big single words sit tighter; sentences get air between lines for the diacritics. */
private fun practiceLine(px: Float): Float = when {
    px >= 40f -> 1.22f
    px >= 24f -> 1.3f
    else -> 1.42f
}

/** A letter used as a poster. It's already as big as it can be, so it doesn't follow the font scale. */
@Composable
@ReadOnlyComposable
fun letterPoster(size: Number, weight: PracticeWeight = PracticeWeight.bold): TextStyle {
    val px = size.toFloat() / LocalDensity.current.fontScale
    // A lone letter: a line just above its em, so a poster's box is the letter and not Baloo's tall line.
    return practice(size, weight).copy(fontSize = px.sp, lineHeight = (px * 1.15f).sp)
}

/** iOS `dynamicTypeSize >= .xxLarge`: a font scale of 1.3 and up. */
val isLargeText: Boolean
    @Composable
    @ReadOnlyComposable
    get() = LocalDensity.current.fontScale >= 1.3f

/** `Font.monospacedDigit()`: Baloo's tabular figures. */
fun TextStyle.monospacedDigit(): TextStyle = copy(fontFeatureSettings = "tnum")

val fDisplay: TextStyle get() = ui(30, UIWeight.black, TextRole.largeTitle)
/** Page titles break into balanced lines, so a two-line title never leaves one word alone, as on iOS. */
val fTitle: TextStyle get() = ui(26, UIWeight.bold, TextRole.title).copy(lineBreak = LineBreak.Heading)
val fPrompt: TextStyle get() = ui(26, UIWeight.black, TextRole.title2)
val fHeadline: TextStyle get() = ui(18, UIWeight.bold, TextRole.title3)
val fLead: TextStyle get() = ui(18, UIWeight.medium, TextRole.title3)
val fBody: TextStyle get() = ui(16, UIWeight.regular, TextRole.body)
val fBodyStrong: TextStyle get() = ui(16, UIWeight.medium, TextRole.body)
val fCaption: TextStyle get() = ui(14, UIWeight.regular, TextRole.footnote)
val fSmall: TextStyle get() = ui(11, UIWeight.regular, TextRole.caption2)
val fButton: TextStyle get() = ui(17, UIWeight.bold, TextRole.headline)
val fValue: TextStyle get() = ui(17, UIWeight.bold, TextRole.headline).monospacedDigit()
val fNumber: TextStyle get() = ui(30, UIWeight.black, TextRole.title).monospacedDigit()

val fWord: TextStyle get() = practice(52, PracticeWeight.bold, TextRole.largeTitle)
val fPhrase: TextStyle get() = practice(30, PracticeWeight.semibold, TextRole.title)

/** A word joined at its hyphens („l-am”, „s-a”) so a line never breaks inside it. */
val String.tiedAtHyphens: String
    get() {
        if (!contains('-')) return this
        val tied = StringBuilder(length + 4)
        for (index in indices) {
            val character = this[index]
            tied.append(character)
            if (character == '-' && index > 0 && this[index - 1].isLetter() && index + 1 < length && this[index + 1].isLetter()) {
                // A word joiner: no width, and no line break on either side of it.
                tied.append('⁠')
            }
        }
        return tied.toString()
    }

/** A title or an instruction that may wrap. TalkBack and the tests read the plain string. */
@Composable
fun TiedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle? = null,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
) {
    val tied = text.tiedAtHyphens
    val plain = if (tied == text) modifier else modifier.clearAndSetSemantics { this.text = AnnotatedString(text) }
    FoniaText(tied, plain, style = style, color = color, textAlign = textAlign)
}
