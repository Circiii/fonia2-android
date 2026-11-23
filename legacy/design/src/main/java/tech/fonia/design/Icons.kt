package tech.fonia.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Every icon in the app, from one set, so they share a stroke and a corner. */
@Suppress("EnumEntryName")
enum class FoniaIcon(val rawValue: String) {
    settings("gear-six-duotone"),
    close("x-bold"),
    back("caret-left-bold"),
    forward("caret-right-bold"),
    down("caret-down-bold"),
    plus("plus-bold"),
    play("play-fill"),
    retry("arrow-counter-clockwise-bold"),
    speaker("speaker-high-duotone"),
    microphone("microphone-duotone"),
    check("check-circle-duotone"),
    ear("ear-duotone"),
    listenBack("user-sound-duotone"),
    glide("wave-sine-bold"),
    mirror("scan-smiley-duotone"),
    mouth("smiley-duotone"),
    people("users-duotone"),
    book("book-open-duotone"),
    waves("waveform-bold"),
    download("download-simple-bold"),
    loud("megaphone-duotone"),
    lock("lock-simple-duotone"),
    arrow("arrow-right-bold"),
    tick("check-bold"),
    help("question-bold"),
    fire("fire-duotone"),
    star("star-duotone"),
    target("target-duotone"),
    hourglass("hourglass-medium-duotone");

    companion object {
        /** The tab bar's larger drawings: `tab-home`, `tab-home-on`, `tab-progress`, `tab-progress-on`. */
        val tabs = listOf("tab-home", "tab-home-on", "tab-progress", "tab-progress-on")
    }
}

/**
 * A template image from `images/icons`: the SVG drawn at the pixel size it's shown at, then filled with one
 * colour wherever it isn't transparent, which is what iOS does with a template. The duotone layer keeps its
 * 0.2 opacity. Sized by [modifier]; the colour is [tint], else the foreground colour.
 */
@Composable
fun TemplateImage(name: String, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    val assets = foniaAssets()
    val color = tint.takeOrElse { LocalForeground.current }
    val filter = remember(color) { ColorFilter.tint(color) }
    Spacer(
        modifier.drawBehind {
            val side = minOf(size.width, size.height).roundToInt()
            if (side <= 0) return@drawBehind
            val image = assets.icon(name, side) ?: return@drawBehind
            drawImage(
                image = image,
                dstOffset = IntOffset(((size.width - side) / 2).roundToInt(), ((size.height - side) / 2).roundToInt()),
                dstSize = IntSize(side, side),
                colorFilter = filter,
            )
        },
    )
}

/**
 * An icon that grows with the text next to it (`@ScaledMetric`: here, the system font scale). Whatever holds
 * it carries the spoken name, so it has no semantics of its own.
 */
@Composable
fun FoniaIconView(
    icon: FoniaIcon,
    modifier: Modifier = Modifier,
    size: Float = 22f,
    @Suppress("UNUSED_PARAMETER") relativeTo: TextRole = TextRole.body,
    tint: Color = Color.Unspecified,
) {
    val side = size * LocalDensity.current.fontScale
    TemplateImage(icon.rawValue, modifier.size(side.dp), tint)
}

/** `FoniaIcon.tab(_:)`, sized by [modifier]. */
@Composable
fun FoniaTabIcon(name: String, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) =
    TemplateImage(name, modifier, tint)

/**
 * Illustrations kept outside the word pictures. On iOS `FoniaArt.bundle` lets other modules name an image in
 * this package's catalogs; here [FoniaImage] does that for any name in `images/catalog.json`.
 */
object FoniaArt

/**
 * `Image(name, bundle: .module).resizable()`: any picture from the catalog (art, word pictures, scenes,
 * mascot poses), decoded once and kept in the asset cache. Draws nothing when the name is unknown, as a
 * missing catalog image does on iOS. Hidden from accessibility.
 */
@Composable
fun FoniaImage(
    name: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    alpha: Float = 1f,
) {
    val assets = foniaAssets()
    val cached = remember(name, assets) { assets.cached(name) }
    if (cached != null) {
        val painter = remember(cached) { BitmapPainter(cached, filterQuality = FilterQuality.High) }
        Image(painter = painter, contentDescription = null, modifier = modifier, alignment = alignment, contentScale = contentScale, alpha = alpha)
        return
    }
    // Not decoded yet (QA 2026-11-21: big scenes decoded on the main thread held a page's first frame for seconds).
    // The picture keeps its place from the file's header, decodes off the main thread and fades in.
    val bounds = remember(name, assets) { assets.bounds(name) }
    if (bounds == null) {
        Box(modifier)
        return
    }
    val reduce = LocalReduceMotion.current
    val painter = remember(name, assets) { FadeInBitmapPainter(bounds) }
    LaunchedEffect(painter) {
        val decoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { assets.bitmap(name) }.getOrNull() }
        painter.bitmap = decoded
        if (decoded != null) {
            if (reduce) painter.fade.snapTo(1f) else painter.fade.animateTo(1f, androidx.compose.animation.core.tween(240))
        }
    }
    Image(painter = painter, contentDescription = null, modifier = modifier, alignment = alignment, contentScale = contentScale, alpha = alpha)
}

/** A painter of a known size that draws nothing until its bitmap arrives, then fades it in. */
private class FadeInBitmapPainter(private val pixels: androidx.compose.ui.unit.IntSize) : androidx.compose.ui.graphics.painter.Painter() {
    var bitmap by androidx.compose.runtime.mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    val fade = androidx.compose.animation.core.Animatable(0f)
    private var alphaFromImage = 1f

    override val intrinsicSize: androidx.compose.ui.geometry.Size get() = androidx.compose.ui.geometry.Size(pixels.width.toFloat(), pixels.height.toFloat())

    override fun applyAlpha(alpha: Float): Boolean {
        alphaFromImage = alpha
        return true
    }

    override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
        val image = bitmap ?: return
        drawImage(
            image,
            dstSize = androidx.compose.ui.unit.IntSize(size.width.roundToInt(), size.height.roundToInt()),
            alpha = fade.value * alphaFromImage,
            filterQuality = FilterQuality.High,
        )
    }
}
