package tech.fonia.designsystem.letter

import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import tech.fonia.designsystem.tokens.FoniaPalette
import tech.fonia.designsystem.tokens.StickerSet
import tech.fonia.glyphs.AlloOutlines
import tech.fonia.glyphs.GlyphOutline
import tech.fonia.glyphs.VariationInterpolator
import java.text.Normalizer

/**
 * Static sticker text (titles, "Bravo!", glyph icons ★ ✓ →): the whole string drawn as ONE sticker, layer by layer
 * (all shadows, all rims, all edges, all faces) so neighbouring letters never cut each other. Not animated; it
 * re-emits only when its parameters change.
 */
@Composable
fun StickerText(
    text: String,
    sticker: StickerSet,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 40.sp,
    weight: Float = 900f,
    roundness: Float = 100f,
    tilt: Float = 0f,
    contentDescription: String = text,
) {
    val font by rememberAlloOutlines()
    val r = remember(font) { font?.let { StickerTextRenderer(it) } }
    r?.set(text, weight, roundness)
    val key = r?.key
    Box(
        modifier
            .semantics { this.contentDescription = contentDescription }
            .layout { m, c ->
                val em = fontSize.toPx()
                val unit = if (key != null && r != null) r.widthUnits / r.font.upm else 0f
                val pad = em * 0.3f
                val w = (unit * em + 2 * pad).toInt().coerceAtLeast(c.minWidth)
                val h = (em * 1.25f + pad).toInt().coerceAtLeast(c.minHeight)
                val p = m.measure(Constraints.fixed(w, h))
                layout(w, h) { p.place(0, 0) }
            }
            .drawBehind {
                val rr = r ?: return@drawBehind
                val em = fontSize.toPx()
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    nc.save()
                    nc.rotate(tilt, size.width / 2, size.height / 2)
                    rr.draw(nc, sticker, em, size.width / 2, size.height * 0.5f + em * 0.32f)
                    nc.restore()
                }
            },
    )
}

internal class StickerTextRenderer(val font: AlloOutlines) {
    private val vi = VariationInterpolator(font)
    private var outlines = emptyArray<GlyphOutline>()
    private var pens = FloatArray(0)
    private val path = Path().apply { fillType = PathFillType.NonZero }
    private val sink = ComposePathSink(path)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val shadow = FoniaPalette.stickerShadow.color.toArgb()
    var key: String? = null
        private set
    var widthUnits = 0f
        private set

    fun set(text: String, wght: Float, rond: Float) {
        val k = "$text|$wght|$rond"
        if (k == key) return
        key = k
        val s = Normalizer.normalize(text, Normalizer.Form.NFC)
        vi.setAxes(wght, rond, true)
        val ids = s.codePoints().toArray().map { font.glyphFor(it) }.filter { it >= 0 }
        outlines = Array(ids.size) { GlyphOutline(font) }
        pens = FloatArray(ids.size)
        var x = 0f
        var prev = -1
        for ((i, g) in ids.withIndex()) {
            if (prev >= 0) x += font.kerning(prev, g)
            vi.interpolate(g, outlines[i])
            pens[i] = x
            x += outlines[i].advance + 0.13f * font.upm
            prev = g
        }
        widthUnits = x
        path.rewind()
        laidOut = false
    }

    private var laidOut = false
    private var lastEm = 0f
    private var lastX = 0f
    private var lastY = 0f

    fun draw(c: android.graphics.Canvas, s: StickerSet, em: Float, cx: Float, baseline: Float) {
        if (!laidOut || em != lastEm || cx != lastX || baseline != lastY) {
            path.rewind()
            val k = em / font.upm
            val x0 = cx - widthUnits * k / 2
            for (i in outlines.indices) {
                sink.matrix.reset().postScale(k, -k).postTranslate(x0 + pens[i] * k, baseline)
                outlines[i].emit(sink)
            }
            laidOut = true; lastEm = em; lastX = cx; lastY = baseline
        }
        val ap = path.asAndroidPath()
        val rim = 0.20f * em
        val edge = 0.10f * em
        if (!s.flat) {
            c.save(); c.translate(0.03f * em, 0.09f * em)
            paint.style = Paint.Style.FILL_AND_STROKE; paint.strokeWidth = rim; paint.color = shadow
            c.drawPath(ap, paint)
            c.restore()
            paint.color = s.rim.toArgb(); c.drawPath(ap, paint)
            paint.strokeWidth = edge; paint.color = s.edge.toArgb(); c.drawPath(ap, paint)
        }
        paint.style = Paint.Style.FILL; paint.color = s.face.toArgb()
        c.drawPath(ap, paint)
    }
}
