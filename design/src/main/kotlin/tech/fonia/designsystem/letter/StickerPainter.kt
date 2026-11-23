package tech.fonia.designsystem.letter

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import tech.fonia.designsystem.motion.Motion
import tech.fonia.designsystem.tokens.FoniaLetterTokens
import tech.fonia.designsystem.tokens.FoniaPalette
import tech.fonia.designsystem.tokens.FoniaSticker
import tech.fonia.designsystem.tokens.StickerSet
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/** ARGB colours of one sticker, blended and faded, for a frame. */
internal class StickerColors {
    var face = 0; var edge = 0; var rim = 0; var flatness = 0f

    fun set(s: StickerSet, alpha: Float = 1f): StickerColors {
        face = fade(s.face.toArgb(), alpha); edge = fade(s.edge.toArgb(), alpha); rim = fade(s.rim.toArgb(), alpha)
        flatness = if (s.flat) 1f else 0f
        return this
    }

    fun blend(a: StickerSet, b: StickerSet, t: Float, alpha: Float): StickerColors {
        face = fade(lerpArgb(a.face.toArgb(), b.face.toArgb(), t), alpha)
        edge = fade(lerpArgb(a.edge.toArgb(), b.edge.toArgb(), t), alpha)
        rim = fade(lerpArgb(a.rim.toArgb(), b.rim.toArgb(), t), alpha)
        flatness = Motion.lerp(if (a.flat) 1f else 0f, if (b.flat) 1f else 0f, t)
        return this
    }
}

internal fun lerpArgb(a: Int, b: Int, t: Float): Int {
    if (t <= 0f) return a
    if (t >= 1f) return b
    fun ch(s: Int) = ((((a ushr s) and 0xFF) + (((b ushr s) and 0xFF) - ((a ushr s) and 0xFF)) * t) + 0.5f).toInt().coerceIn(0, 255)
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

internal fun fade(argb: Int, alpha: Float): Int {
    if (alpha >= 1f) return argb
    val a = (((argb ushr 24) and 0xFF) * alpha.coerceIn(0f, 1f) + 0.5f).toInt()
    return (a shl 24) or (argb and 0xFFFFFF)
}

/**
 * Draws a [LetterEngine] frame on an Android canvas: props behind, ghost, shadow, rim, edge, face, Almost's liquid,
 * gloss, props in front, particles, badge. Owns every Path and Paint it uses (reused each frame), so a frame
 * allocates nothing. Called only from the draw phase.
 */
class StickerPainter {
    private val body = Path().apply { fillType = PathFillType.NonZero }
    private val comma = Path().apply { fillType = PathFillType.NonZero }
    private val ghost = Path().apply { fillType = PathFillType.NonZero }
    private val badge = Path().apply { fillType = PathFillType.NonZero }
    private val prop = Path().apply { fillType = PathFillType.NonZero }
    private val prop2 = Path().apply { fillType = PathFillType.NonZero }
    private val clip = Path()
    private val star = Path().apply { fillType = PathFillType.NonZero }
    private val heart = Path().apply { fillType = PathFillType.NonZero }
    private var unitShapesFor: LetterEngine? = null
    private val sink = ComposePathSink(body)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val colors = StickerColors()
    private val propColors = StickerColors()

    private val shadowArgb = FoniaPalette.stickerShadow.color.toArgb()
    private val glossArgb = FoniaPalette.stickerGloss.color.toArgb()
    private val sunArgb = FoniaPalette.sun.color.toArgb()
    private val particleFace = intArrayOf(
        FoniaPalette.sun.color.toArgb(), FoniaPalette.voice.color.toArgb(), FoniaPalette.parrot.color.toArgb(),
        FoniaPalette.surface.color.toArgb(), FoniaSticker.Sky.edge.toArgb(),
    )
    private val particleRim = intArrayOf(
        FoniaPalette.sunDeep.color.toArgb(), FoniaPalette.voiceDeep.color.toArgb(), FoniaPalette.parrotDeep.color.toArgb(),
        FoniaPalette.accentDeep.color.toArgb(), FoniaPalette.accentDeep.color.toArgb(),
    )

    /** The plain-letter colour (ink on Paper, white on Stage) for ghosts and flat badges. */
    var flatSet: StickerSet = FoniaSticker.FlatOnPaper
    /** What the phone heard (the swap-glide ghost). */
    var heard: String? = null
    /** Gloss crescent and inner shade on the face. */
    var gloss: Boolean = true
    /** False when the particles are drawn in a [tech.fonia.designsystem.motion.FoniaOverlayHost] instead. */
    var inlineParticles: Boolean = true

    private val lipArgb = fade(FoniaPalette.voice.color.toArgb(), 0.55f)
    private val skinArgb = FoniaPalette.voiceFace.color.toArgb()
    private val cavityArgb = FoniaPalette.voiceDeep.color.toArgb()
    private val surfaceArgb = FoniaPalette.surface.color.toArgb()
    private val inkArgb = FoniaPalette.ink.color.toArgb()
    private val tongueArgb = FoniaPalette.voice.color.toArgb()
    private val tongueRimArgb = FoniaPalette.voiceDeep.color.toArgb()

    /** Only the particles of [e]'s frame (for an overlay that draws above the letter's box). */
    fun drawParticlesOnly(canvas: Canvas, e: LetterEngine) {
        if (!e.particles.alive) return
        ensureUnitShapes(e)
        drawParticles(canvas, e)
    }

    fun draw(canvas: Canvas, e: LetterEngine) {
        ensureUnitShapes(e)
        val p = e.pose
        val E = e.emNowPx
        val alpha = p.alpha.coerceIn(0f, 1f)
        colors.blend(e.prevSet, e.curSet, e.setMix, alpha)
        val solid = 1f - colors.flatness
        val rimW = e.rimEm * E * solid
        val edgeW = e.edgeEm * E * solid

        // geometry of this frame
        body.rewind(); sink.path = body; e.emitBody(sink)
        comma.rewind(); sink.path = comma
        val hasComma = e.emitComma(sink)
        val commaFade = if (hasComma) p.commaAlpha.coerceIn(0f, 1f) else 1f

        // behind the letter
        if (p.ringT in 0f..0.55f) drawRing(canvas, e, p.ringT / 0.55f)
        if (p.sunDotT >= 0f) drawSunDot(canvas, e, p.sunDotT)
        if (p.ghostMode == 1) {
            ghost.rewind(); sink.path = ghost
            if (e.emitGhost(sink, heard)) fillPath(canvas, ghost, fade(flatSet.face.toArgb(), p.ghostAlpha))
        }

        // the sticker: shadow, rim, edge, face (body and separate comma layer by layer, so they never cut each other)
        if (solid > 0.01f) {
            val lift = p.shadowLift * e.emPx
            val sdx = FoniaLetterTokens.SHADOW_DX_EM * E
            val sdy = FoniaLetterTokens.SHADOW_DY_EM * E + lift
            val sa = alpha * solid * (1f - 0.55f * Motion.clamp01(p.shadowLift / 0.4f))
            val sw = rimW + max(0f, p.shadowSpread) * E
            canvas.save(); canvas.translate(sdx, sdy)
            strokeFill(canvas, body, fade(shadowArgb, sa), sw)
            if (hasComma) strokeFill(canvas, comma, fade(shadowArgb, sa * commaFade), rimW)
            canvas.restore()
            strokeFill(canvas, body, colors.rim, rimW)
            if (hasComma) strokeFill(canvas, comma, fade(colors.rim, commaFade), rimW)
            strokeFill(canvas, body, colors.edge, edgeW)
            if (hasComma) strokeFill(canvas, comma, fade(colors.edge, commaFade), edgeW)
        }
        fillPath(canvas, body, colors.face)
        if (hasComma) fillPath(canvas, comma, fade(colors.face, commaFade))
        if (p.halfFill > 0f) drawLiquid(canvas, e)
        if (gloss && solid > 0.5f && E > 40f) drawGloss(canvas, body, E * 0.022f, alpha)

        // in front
        if (p.teethT >= 0f) drawTeeth(canvas, e, p.teethT)
        if (p.waveT >= 0f) drawWave(canvas, e, p.waveT)
        if (p.puffT >= 0f) drawPuffs(canvas, e, p.puffT)
        if (p.streakT >= 0f) drawStreaks(canvas, e, p.streakT)
        if (p.ghostMode == 2) {
            ghost.rewind(); sink.path = ghost
            if (e.emitGhost(sink, heard)) drawSticker(canvas, ghost, propColors.set(FoniaSticker.Sun, p.ghostAlpha), E * 0.55f, true)
        }
        if (inlineParticles && e.particles.alive) drawParticles(canvas, e)
        badge.rewind(); sink.path = badge
        if (e.emitBadge(sink)) {
            val set = if (p.badgeKind == 1) FoniaSticker.Parrot else flatSet
            drawSticker(canvas, badge, propColors.set(set), E * 0.36f, shadow = p.badgeKind == 1)
        }
    }

    // ---------------------------------------------------------------- primitives

    private fun strokeFill(c: Canvas, path: Path, argb: Int, width: Float) {
        if ((argb ushr 24) == 0) return
        paint.style = if (width > 0.05f) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
        paint.strokeWidth = width
        paint.color = argb
        c.drawPath(path.asAndroidPath(), paint)
    }

    private fun fillPath(c: Canvas, path: Path, argb: Int) {
        if ((argb ushr 24) == 0) return
        paint.style = Paint.Style.FILL
        paint.color = argb
        c.drawPath(path.asAndroidPath(), paint)
    }

    private fun stroke(c: Canvas, path: Path, argb: Int, width: Float) {
        if ((argb ushr 24) == 0 || width <= 0f) return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.color = argb
        c.drawPath(path.asAndroidPath(), paint)
    }

    /** A small sticker (props, badge): rim and edge proportional to [em], optional shadow. */
    private fun drawSticker(c: Canvas, path: Path, k: StickerColors, em: Float, shadow: Boolean) {
        val rimW = 0.20f * em * (1f - k.flatness)
        val edgeW = 0.10f * em * (1f - k.flatness)
        if (shadow && k.flatness < 0.5f) {
            c.save(); c.translate(0.03f * em, 0.09f * em)
            strokeFill(c, path, fade(shadowArgb, ((k.face ushr 24) and 0xFF) / 255f), rimW)
            c.restore()
        }
        if (k.flatness < 0.5f) {
            strokeFill(c, path, k.rim, rimW)
            strokeFill(c, path, k.edge, edgeW)
        }
        fillPath(c, path, k.face)
    }

    /** Gloss: a thin light crescent along the top-left of the face, and a faint shade along the bottom-right. */
    private fun drawGloss(c: Canvas, path: Path, d: Float, alpha: Float) {
        val ap = path.asAndroidPath()
        c.save()
        c.clipPath(ap)
        c.translate(d, d * 1.3f)
        c.clipOutPath(ap)
        c.drawColor(fade(glossArgb, alpha))
        c.restore()
        c.save()
        c.clipPath(ap)
        c.translate(-d * 0.8f, -d * 1.1f)
        c.clipOutPath(ap)
        c.drawColor(fade(colors.edge, 0.22f * alpha))
        c.restore()
    }

    // ---------------------------------------------------------------- Almost: half-full face

    private fun drawLiquid(c: Canvas, e: LetterEngine) {
        val p = e.pose
        val E = e.emNowPx
        val base = e.baselinePx()
        val y0 = base - p.fillLine * e.capEm * E
        val l = e.bodyLeftPx() - E
        val r = e.bodyRightPx() + E
        val cx = e.bodyCenterXPx()
        val k = tan(p.slosh * (Math.PI.toFloat() / 180f))
        clip.rewind()
        val steps = 28
        for (i in 0..steps) {
            val x = l + (r - l) * i / steps
            val y = y0 + k * (x - cx) + 0.014f * E * sin(Motion.TAU * (x - cx) / (0.34f * E) + p.sloshWave)
            if (i == 0) clip.moveTo(x, y) else clip.lineTo(x, y)
        }
        clip.lineTo(r, base + 2f * E); clip.lineTo(l, base + 2f * E); clip.close()
        c.save()
        c.clipPath(clip.asAndroidPath())
        fillPath(c, body, colors.edge)
        c.restore()
    }

    // ---------------------------------------------------------------- props

    private fun drawRing(c: Canvas, e: LetterEngine, u: Float) {
        val E = e.emNowPx
        // a sticker ring: rim + sun, thinning as it grows; alpha only at the very end (yellow at half alpha on blue reads grey)
        val r = E * Motion.lerp(0.32f, 1.15f, Motion.out(u))
        val w = E * 0.075f * (1f - u) + 1f
        val a = 1f - Motion.progress(u, 0.65f, 1f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 1.8f
        paint.color = fade(FoniaSticker.Sun.rim.toArgb(), a)
        c.drawCircle(e.bodyCenterXPx(), e.capCenterYPx(), r, paint)
        paint.strokeWidth = w
        paint.color = fade(sunArgb, a)
        c.drawCircle(e.bodyCenterXPx(), e.capCenterYPx(), r, paint)
    }

    private fun drawSunDot(c: Canvas, e: LetterEngine, t: Float) {
        val E = e.emNowPx
        val cx = e.bodyCenterXPx()
        val from = e.capCenterYPx()
        val to = e.bodyTopPx() - 0.13f * E
        val y = Motion.lerp(from, to, Motion.step(t - 0.1f, 0.55f, 260f))
        prop.rewind()
        prop.addOval(androidx.compose.ui.geometry.Rect(cx - 0.075f * E, y - 0.075f * E, cx + 0.075f * E, y + 0.075f * E))
        drawSticker(c, prop, propColors.set(FoniaSticker.Sun), E * 0.4f, false)
    }

    /**
     * Tongue behind the teeth (§5.2, redrawn after review): a round "mouth from the side" badge next to the letter, kept
     * inside the letter's box. Lips, upper and lower teeth, and the tongue, whose tip rises from the floor of the mouth
     * and touches just behind the upper teeth (a small sun spark marks the spot, twice), then holds.
     */
    private fun drawTeeth(c: Canvas, e: LetterEngine, t: Float) {
        val E = e.emNowPx
        val pop = Motion.step(t, 0.55f, 500f)
        if (pop <= 0.01f) return
        val boxW = e.anchorX * 2f
        val boxH = e.emPx / HERO_EM
        val r = 0.34f * e.emPx
        val margin = 0.07f * e.emPx
        val cx = (e.bodyRightPx() + 0.10f * E + r).coerceIn(r + margin, max(r + margin, boxW - r - margin))
        val cy = (e.capCenterYPx() + 0.18f * E).coerceIn(r + margin, max(r + margin, boxH - r - margin))
        c.save()
        c.scale(pop, pop, cx, cy)

        // the badge: a white sticker disc with an ink rim and its shadow
        prop.rewind()
        prop.addOval(androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r))
        drawSticker(c, prop, propColors.set(FoniaSticker.Teeth), r * 0.55f, true)

        c.save()
        c.clipPath(prop.asAndroidPath())
        fun rr(l: Float, tp: Float, rt: Float, b: Float, k: Float, argb: Int) {
            paint.style = Paint.Style.FILL; paint.color = argb
            c.drawRoundRect(cx + l * r, cy + tp * r, cx + rt * r, cy + b * r, k * r, k * r, paint)
        }
        val lip = lipArgb
        rr(-0.68f, -0.30f, 1.1f, 0.56f, 0.34f, cavityArgb)         // inside of the mouth
        rr(-1.1f, -1.1f, 1.1f, -0.30f, 0.10f, skinArgb)            // the face above
        rr(-1.1f, 0.56f, 1.1f, 1.1f, 0.10f, skinArgb)              // and below
        paint.style = Paint.Style.FILL; paint.color = lip
        c.drawOval(cx - 0.95f * r, cy - 0.52f * r, cx - 0.38f * r, cy - 0.16f * r, paint) // upper lip
        c.drawOval(cx - 0.92f * r, cy + 0.40f * r, cx - 0.38f * r, cy + 0.76f * r, paint) // lower lip
        fun tooth(l: Float, tp: Float, rt: Float, b: Float) {
            rr(l, tp, rt, b, 0.10f, surfaceArgb)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.06f * r; paint.color = inkArgb
            c.drawRoundRect(cx + l * r, cy + tp * r, cx + rt * r, cy + b * r, 0.10f * r, 0.10f * r, paint)
        }
        tooth(-0.56f, -0.34f, -0.24f, 0.06f)                       // upper front tooth
        tooth(-0.54f, 0.24f, -0.26f, 0.50f)                        // lower front tooth

        // the tongue: lies flat, then arches and its tip lifts to just behind the upper tooth
        val lift = Motion.step(t - 0.35f, 0.62f, 220f)
        val tapK = Motion.bell(Motion.progress(t, 0.95f, 1.15f)) + Motion.bell(Motion.progress(t, 1.25f, 1.45f))
        val tipX = cx + Motion.lerp(0.0f, -0.08f, lift) * r
        val tipY = cy + Motion.lerp(0.34f, -0.14f, lift) * r - 0.04f * r * tapK
        prop2.rewind()
        prop2.moveTo(cx + 1.1f * r, cy + 0.44f * r)
        prop2.cubicTo(
            cx + 0.50f * r, cy + Motion.lerp(0.48f, 0.52f, lift) * r,
            cx + Motion.lerp(0.10f, 0.02f, lift) * r, cy + Motion.lerp(0.40f, 0.30f, lift) * r,
            tipX, tipY,
        )
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.46f * r; paint.color = tongueRimArgb
        c.drawPath(prop2.asAndroidPath(), paint)
        paint.strokeWidth = 0.36f * r; paint.color = tongueArgb
        c.drawPath(prop2.asAndroidPath(), paint)
        // a light line along the top of the tongue
        paint.strokeWidth = 0.06f * r; paint.color = fade(skinArgb, 0.6f)
        c.save(); c.translate(0f, -0.08f * r); c.drawPath(prop2.asAndroidPath(), paint); c.restore()
        c.restore()

        // where the tip touches: a sun spark, twice, then a small dot stays
        if (lift > 0.8f) {
            val k = max(tapK, 0.45f) * Motion.clamp01((lift - 0.8f) * 5f)
            val sx = cx - 0.23f * r
            val sy = cy - 0.20f * r
            paint.style = Paint.Style.FILL
            paint.color = FoniaSticker.Sun.rim.toArgb(); c.drawCircle(sx, sy, (0.07f + 0.06f * k) * r + 0.035f * r, paint)
            paint.color = sunArgb; c.drawCircle(sx, sy, (0.07f + 0.06f * k) * r, paint)
        }
        c.restore()
    }

    /** One line under the letter that becomes a wave and rises into it; a small wave stays. */
    private fun drawWave(c: Canvas, e: LetterEngine, t: Float) {
        val E = e.emNowPx
        val cx = e.bodyCenterXPx()
        val base = e.restBaselinePx()
        val draw = Motion.easeOutCubic(Motion.progress(t, 0f, 0.24f))
        val half = 0.48f * E * draw
        if (half < 1f) return
        val rise = Motion.easeInOut(Motion.progress(t, 0.5f, 0.82f)) * (1f - Motion.easeInOut(Motion.progress(t, 0.9f, 1.25f)))
        val y0 = base + Motion.lerp(0.34f, 0.16f, rise) * E
        val grow = Motion.easeInOut(Motion.progress(t, 0.2f, 0.5f))
        val settle = Motion.easeInOut(Motion.progress(t, 0.95f, 1.35f))
        val amp = E * (0.06f * grow * (1f - settle) + 0.02f * settle)
        val lambda = 0.25f * E
        val phase = Motion.TAU * 2.4f * t
        prop.rewind()
        val n = 40
        for (i in 0..n) {
            val x = cx - half + 2f * half * i / n
            val taper = Motion.bell(i / n.toFloat()).let { 0.35f + 0.65f * it }
            val y = y0 + amp * taper * sin(Motion.TAU * (x - cx) / lambda - phase)
            if (i == 0) prop.moveTo(x, y) else prop.lineTo(x, y)
        }
        stroke(c, prop, FoniaSticker.Voice.rim.toArgb(), 0.12f * E)
        stroke(c, prop, FoniaSticker.Voice.edge.toArgb(), 0.075f * E)
        stroke(c, prop, FoniaSticker.Voice.face.toArgb(), 0.026f * E)
    }

    /** Two puffs leave the sides, meet in the middle and fly out toward the child (bigger, lower). */
    private fun drawPuffs(c: Canvas, e: LetterEngine, t: Float) {
        val E = e.emNowPx
        val cx = e.bodyCenterXPx()
        val cy = e.capCenterYPx()
        val base = e.restBaselinePx()
        val side = (e.bodyRightPx() - e.bodyLeftPx()) * 0.5f + 0.2f * E
        val appear = Motion.step(t, 0.55f, 500f)
        val meet = Motion.out(Motion.progress(t, 0.25f, 0.68f))
        val fly = Motion.out(Motion.progress(t, 0.66f, 1.3f))
        val r = 0.13f * E * appear * (1f + 0.45f * fly)
        if (r < 1f) return
        val y = Motion.lerp(Motion.lerp(cy, base + 0.12f * E, meet), base + 0.36f * E, fly)
        val dx = side * (1f - meet)
        val bob = 0.02f * E * sin(Motion.TAU * t * 1.4f)
        prop.rewind()
        puff(prop, cx - dx, y + bob, r)
        if (dx > r * 0.6f) puff(prop, cx + dx, y - bob, r)
        // little air dashes behind each puff while they travel
        val trail = Motion.bell(Motion.progress(t, 0.25f, 0.7f))
        if (trail > 0.05f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.035f * E
            paint.color = fade(FoniaSticker.Sun.rim.toArgb(), 0.8f * trail)
            for (s in intArrayOf(-1, 1)) {
                val px = cx + s * dx + s * r * 1.25f
                c.drawLine(px, y - 0.05f * E, px + s * 0.12f * E, y - 0.08f * E, paint)
                c.drawLine(px, y + 0.05f * E, px + s * 0.12f * E, y + 0.08f * E, paint)
            }
        }
        drawSticker(c, prop, propColors.set(FoniaSticker.Sun), E * 0.45f, true)
    }

    private fun puff(path: Path, x: Float, y: Float, r: Float) {
        path.addOval(androidx.compose.ui.geometry.Rect(x - r * 0.95f, y - r * 0.35f, x + r * 0.05f, y + r * 0.65f))
        path.addOval(androidx.compose.ui.geometry.Rect(x - r * 0.10f, y - r * 0.30f, x + r * 0.95f, y + r * 0.65f))
        path.addOval(androidx.compose.ui.geometry.Rect(x - r * 0.62f, y - r * 0.85f, x + r * 0.55f, y + r * 0.32f))
    }

    /** Air streaks to the right of the stretching letter. */
    private fun drawStreaks(c: Canvas, e: LetterEngine, t: Float) {
        val a = Motion.bell(Motion.progress(t, 0.05f, 0.8f))
        if (a < 0.02f) return
        val E = e.emNowPx
        val x0 = e.bodyRightPx() + 0.12f * E + 0.4f * E * Motion.progress(t, 0.05f, 0.8f)
        val cy = e.capCenterYPx()
        for (i in -1..1) {
            val y = cy + i * 0.2f * E
            val len = (0.22f - 0.05f * kotlin.math.abs(i)) * E
            prop.rewind(); prop.moveTo(x0 + 0.04f * E * kotlin.math.abs(i), y); prop.lineTo(x0 + len, y)
            stroke(c, prop, fade(FoniaSticker.Sun.rim.toArgb(), a), 0.085f * E)
            stroke(c, prop, fade(FoniaSticker.Sun.edge.toArgb(), a), 0.045f * E)
        }
    }

    // ---------------------------------------------------------------- particles

    private fun ensureUnitShapes(e: LetterEngine) {
        if (unitShapesFor === e) return
        unitShapesFor = e
        val o = e.newOutline()
        val k = UNIT / e.font.upm
        val s = ComposePathSink(star)
        star.rewind()
        // centre the glyph on the origin: scale font units to UNIT px per em, flip y
        s.matrix.reset().postScale(k, -k)
        e.emitFixedGlyph(0x2605, s, o)
        s.path = heart
        heart.rewind()
        s.matrix.reset().postScale(k, -k)
        e.emitFixedGlyph(0x2665, s, o)
    }

    private fun drawParticles(c: Canvas, e: LetterEngine) {
        val ps = e.particles
        for (i in 0 until ps.count) {
            val life = ps.life[i]
            val u = ps.age[i] / life
            val a = 1f - Motion.progress(u, 0.62f, 1f)
            val grow = Motion.easeOutCubic(Motion.progress(u, 0f, 0.12f))
            val size = ps.size[i] * grow
            if (size < 0.5f) continue
            val face = fade(particleFace[ps.color[i]], a)
            val rim = fade(particleRim[ps.color[i]], a)
            when (ps.kind[i]) {
                Particles.KIND_DOT -> {
                    paint.style = Paint.Style.FILL
                    paint.color = rim
                    c.drawCircle(ps.x[i], ps.y[i], size * 0.5f + size * 0.18f, paint)
                    paint.color = face
                    c.drawCircle(ps.x[i], ps.y[i], size * 0.5f, paint)
                }
                Particles.KIND_CONFETTI -> {
                    c.save(); c.translate(ps.x[i], ps.y[i]); c.rotate(ps.rot[i])
                    paint.style = Paint.Style.FILL
                    paint.color = rim
                    c.drawRoundRect(-size * 0.55f, -size * 0.22f, size * 0.55f, size * 0.22f, size * 0.2f, size * 0.2f, paint)
                    paint.color = face
                    c.drawRoundRect(-size * 0.42f, -size * 0.12f, size * 0.42f, size * 0.12f, size * 0.1f, size * 0.1f, paint)
                    c.restore()
                }
                else -> {
                    val shape = if (ps.kind[i] == Particles.KIND_HEART) heart else star
                    val sc = size / UNIT * 1.4f
                    c.save(); c.translate(ps.x[i], ps.y[i]); c.rotate(ps.rot[i] * 0.25f); c.scale(sc, sc)
                    strokeFill(c, shape, rim, 0.16f * UNIT)
                    fillPath(c, shape, face)
                    c.restore()
                }
            }
        }
    }

    private companion object {
        const val UNIT = 100f
    }
}
