package tech.fonia.designsystem.theme

import android.provider.Settings
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import tech.fonia.designsystem.R
import tech.fonia.designsystem.tokens.FoniaPalette
import tech.fonia.designsystem.tokens.FoniaSticker
import tech.fonia.designsystem.tokens.FoniaTypeScale
import tech.fonia.designsystem.tokens.StickerSet
import tech.fonia.designsystem.tokens.TypeToken

/** The colour roles as Compose colours (light theme; the only one in v1). */
@Immutable
class FoniaColors internal constructor() {
    val bg = FoniaPalette.bg.color
    val surface = FoniaPalette.surface.color
    val fill = FoniaPalette.fill.color
    val separator = FoniaPalette.separator.color
    val inkSecondary = FoniaPalette.inkSecondary.color
    val ink = FoniaPalette.ink.color
    val accent = FoniaPalette.accent.color
    val accentDeep = FoniaPalette.accentDeep.color
    val voice = FoniaPalette.voice.color
    val voiceDeep = FoniaPalette.voiceDeep.color
    val parrot = FoniaPalette.parrot.color
    val parrotDeep = FoniaPalette.parrotDeep.color
    val sun = FoniaPalette.sun.color
    val sunDeep = FoniaPalette.sunDeep.color
    val stickerShadow = FoniaPalette.stickerShadow.color
    val gloss = FoniaPalette.stickerGloss.color
}

/** Who the screen is for (§2.4): the kid shell is rounder and one step larger. */
enum class FoniaShell { Kid, Grown }

/** The two surfaces of §1: Paper (bg + ink) and Stage (accent + white), only where LiveLetter is the hero. */
enum class FoniaSurface { Paper, Stage }

@Immutable
class FoniaTypography internal constructor(shell: FoniaShell) {
    private val kid = shell == FoniaShell.Kid
    private fun style(t: TypeToken, wghtOverride: Float? = null, rondOverride: Float? = null): TextStyle {
        val step = if (kid && t.sizeSp < 20f && t.name in BUMPED) 3f else 0f
        val size = t.sizeSp + step
        val rond = rondOverride ?: if (kid) t.rond else FoniaTypeScale.ROND_GROWN
        val w = wghtOverride ?: t.wght
        return TextStyle(
            fontFamily = alloFamily(w, rond, size),
            fontWeight = FontWeight(w.toInt().coerceIn(1, 1000)),
            fontSize = size.sp,
            lineHeight = (t.lineHeightSp + step).sp,
            fontFeatureSettings = if (t.name == "counter") "tnum" else null,
        )
    }

    val display = style(FoniaTypeScale.display)
    val title = style(FoniaTypeScale.title)
    val title2 = style(FoniaTypeScale.title2)
    val headline = style(FoniaTypeScale.headline)
    val body = style(FoniaTypeScale.body)
    val label = style(FoniaTypeScale.label)
    val caption = style(FoniaTypeScale.caption)
    val practiceLine = style(FoniaTypeScale.practiceLine).copy(fontFeatureSettings = "ss01, ss02, calt 0")
    val counter = style(FoniaTypeScale.counter)

    private companion object {
        val BUMPED = setOf("body", "label", "headline")
    }
}

/** Allo at fixed axes (mode A of §3.4). opsz follows the size, clamped to 9–32. */
fun alloFamily(wght: Float, rond: Float, sizeSp: Float): FontFamily = FontFamily(
    Font(
        R.font.allo_variable,
        weight = FontWeight(wght.toInt().coerceIn(1, 1000)),
        variationSettings = FontVariation.Settings(
            FontVariation.weight(wght.toInt().coerceIn(100, 900)),
            FontVariation.Setting("ROND", rond.coerceIn(0f, 100f)),
            FontVariation.Setting("opsz", sizeSp.coerceIn(9f, 32f)),
        ),
    ),
)

val LocalFoniaColors = staticCompositionLocalOf { FoniaColors() }
val LocalFoniaTypography = staticCompositionLocalOf<FoniaTypography> { error("FoniaTheme is missing") }
val LocalFoniaShell = staticCompositionLocalOf { FoniaShell.Kid }
val LocalFoniaSurface = staticCompositionLocalOf { FoniaSurface.Paper }

/** True when animations should be reduced: system animator scale 0 or the Fonia setting. */
val LocalReduceMotion = staticCompositionLocalOf { false }

object FoniaTheme {
    val colors: FoniaColors @Composable get() = LocalFoniaColors.current
    val type: FoniaTypography @Composable get() = LocalFoniaTypography.current
    val shell: FoniaShell @Composable get() = LocalFoniaShell.current
    val surface: FoniaSurface @Composable get() = LocalFoniaSurface.current
    val reduceMotion: Boolean @Composable get() = LocalReduceMotion.current

    /** The plain-letter set for the current surface (ink on Paper, white on Stage). */
    val flat: StickerSet @Composable get() = if (LocalFoniaSurface.current == FoniaSurface.Stage) FoniaSticker.FlatOnStage else FoniaSticker.FlatOnPaper
    /** Text colour for the current surface. */
    val content: Color @Composable get() = if (LocalFoniaSurface.current == FoniaSurface.Stage) LocalFoniaColors.current.surface else LocalFoniaColors.current.ink
}

/**
 * The Fonia theme. [reduceMotion] null = follow the system (`ANIMATOR_DURATION_SCALE == 0`); the app passes the Fonia
 * setting when the parent turned it on.
 */
@Composable
fun FoniaTheme(
    shell: FoniaShell = FoniaShell.Kid,
    reduceMotion: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemReduced = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val typography = remember(shell) { FoniaTypography(shell) }
    val colors = remember { FoniaColors() }
    CompositionLocalProvider(
        LocalFoniaColors provides colors,
        LocalFoniaTypography provides typography,
        LocalFoniaShell provides shell,
        LocalReduceMotion provides (reduceMotion ?: systemReduced),
        LocalTextSelectionColors provides TextSelectionColors(colors.accent, colors.fill),
        content = content,
    )
}

/** Marks a subtree as drawn on the Stage (accent background, white text). */
@Composable
fun FoniaStage(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFoniaSurface provides FoniaSurface.Stage, content = content)
}
