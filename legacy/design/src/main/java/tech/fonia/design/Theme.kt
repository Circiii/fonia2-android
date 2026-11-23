package tech.fonia.design

import android.provider.Settings
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

// SwiftUI passes a few things down the tree through the environment: the font, the foreground style, the
// tracking, the alignment of wrapped lines, the tint and Reduce Motion. These locals carry the same values.

/** `.foregroundStyle(_:)`: text and template icons take this colour. */
val LocalForeground = compositionLocalOf { Palette.ink }

/** `.font(_:)` set on a container. Null means the default body text. */
val LocalFont = compositionLocalOf<TextStyle?> { null }

/** `.tracking(_:)`. The app root sets 0.3 pt everywhere; `PracticeSheet` sets 0. */
val LocalTracking = compositionLocalOf { 0.sp }

/** `.multilineTextAlignment(_:)`. */
val LocalTextAlign = compositionLocalOf { TextAlign.Start }

/** `.lineLimit(_:)` set on a container. Null means no limit. */
val LocalLineLimit = compositionLocalOf<Int?> { null }

/** `.tint(_:)`: the app root sets `Palette.accentText`. */
val LocalTint = compositionLocalOf { Palette.accentText }

/**
 * `accessibilityReduceMotion`. Android has no such switch; the closest is "Remove animations", which sets the
 * animator duration scale to 0, so [FoniaTheme] reads that.
 */
val LocalReduceMotion = compositionLocalOf { false }

/**
 * What `RootView` puts on the whole app: the fonts, the asset provider, `.tint(Palette.accentText)`,
 * `.tracking(0.3)` and the ink colour for text. The app only has a light look, as on iOS.
 */
@Composable
fun FoniaTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val assets = remember(context) {
        FoniaFonts.register(context)
        FoniaAssets.get(context)
    }
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    CompositionLocalProvider(
        LocalFoniaAssets provides assets,
        LocalForeground provides Palette.ink,
        LocalTracking provides 0.3.sp,
        LocalTint provides Palette.accentText,
        LocalReduceMotion provides reduceMotion,
        content = content,
    )
}

/** SwiftUI's default `.body` text, for a `Text` with no font of its own. */
private val defaultBody: TextStyle get() = ui(17)

/**
 * `Text`, reading the font, colour, tracking and alignment from the locals above.
 *
 * [minimumScaleFactor] shrinks the text until it fits, down to that share of its size, like SwiftUI's
 * `.minimumScaleFactor(_:)`; [maxLines] is `.lineLimit(_:)` (else [LocalLineLimit]) and truncates with an
 * ellipsis.
 */
@Composable
fun FoniaText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle? = null,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int? = null,
    minimumScaleFactor: Float = 1f,
    tracking: TextUnit = TextUnit.Unspecified,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) = FoniaText(AnnotatedString(text), modifier, style, color, textAlign, maxLines, minimumScaleFactor, tracking, onTextLayout)

@Composable
fun FoniaText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle? = null,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int? = null,
    minimumScaleFactor: Float = 1f,
    tracking: TextUnit = TextUnit.Unspecified,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val lines = maxLines ?: LocalLineLimit.current ?: Int.MAX_VALUE
    val base = style ?: LocalFont.current ?: defaultBody
    val spacing = when {
        tracking.isSpecified -> tracking
        base.letterSpacing.isSpecified -> base.letterSpacing
        else -> LocalTracking.current
    }
    val resolved = base.copy(
        color = color.takeOrElse { base.color.takeOrElse { LocalForeground.current } },
        letterSpacing = spacing,
        textAlign = textAlign ?: LocalTextAlign.current,
    )
    val autoSize = if (minimumScaleFactor < 1f && resolved.fontSize.isSpecified) {
        TextAutoSize.StepBased(
            minFontSize = resolved.fontSize * minimumScaleFactor,
            maxFontSize = resolved.fontSize,
            stepSize = 0.25.sp,
        )
    } else {
        null
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = resolved,
        onTextLayout = onTextLayout,
        overflow = if (lines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        maxLines = lines,
        autoSize = autoSize,
    )
}
