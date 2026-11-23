package tech.fonia.app.holdgames

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import tech.fonia.model.SoundId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Z — the bee Zuzu flies to the flower while "zzz" is voiced; when the voice stops she hovers where she is. */
class BeeGame : HoldGame(SoundId.Z) {
    override val bursts = false
    private var buzzing = false
    private var flap = 0f
    private val starPath = Path()

    override fun good(i: HoldInput) = i.onTargetNow || (i.voicing > 0.3f && (i.fricative || i.sibilance > 0.15f))

    override fun step(i: HoldInput, dt: Float) {
        buzzing = i.listening && good(i)
        flap += dt * if (buzzing) 46f else 9f
    }

    private fun fx(w: Float, t: Float) = w * (0.12f + 0.56f * t)
    private fun fy(h: Float, t: Float) = h * (0.3f + 0.16f * t - 0.08f * sin(t * PI.toFloat() * 3f))

    override fun DrawScope.scene(p: GamePalette) {
        val w = size.width; val h = size.height; val u = size.minDimension / 100f
        ground(p, h * 0.78f, p.parrot, p.parrotDeep)
        // small flowers on the meadow, and the big one Zuzu flies to
        for (k in 0 until 5) {
            val x = w * (0.08f + k * 0.12f); val y = h * 0.78f
            drawLine(p.parrotDeep, Offset(x, y), Offset(x, y - 5 * u), 1f * u)
            drawCircle(p.surface, 2f * u, Offset(x, y - 6 * u))
            drawCircle(p.sun, 0.9f * u, Offset(x, y - 6 * u))
        }
        val fc = Offset(w * 0.82f, h * 0.58f)
        drawLine(p.parrotDeep, fc, Offset(fc.x, h * 0.78f), 2.2f * u, StrokeCap.Round)
        drawOval(p.parrot, Offset(fc.x, h * 0.68f), Size(9 * u, 4 * u))
        val bloom = 1f + (if (reached) 0.15f * sin(time * 7f).coerceAtLeast(0f) else 0f)
        for (k in 0 until 6) {
            val a = k * PI.toFloat() / 3f + time * 0.2f
            stickerCircle(p, fc + Offset(cos(a) * 7.5f * u * bloom, sin(a) * 7.5f * u * bloom), 5f * u * bloom, p.voice, p.voiceDeep, gloss = false)
        }
        stickerCircle(p, fc, 5f * u, p.sun, p.sunDeep)
        if (reached) star(p, fc + Offset(0f, -22 * u), 6 * u, starPath)
        // the dotted trail behind her
        val t = shown.coerceIn(0f, 1f)
        var k = 0f
        while (k < t) { drawCircle(p.sun.copy(alpha = 0.7f), 0.9f * u, Offset(fx(w, k), fy(h, k))); k += 0.035f }
        // Zuzu
        val bob = (if (buzzing) 0.8f else 2.2f) * u * sin(time * if (buzzing) 14f else 3f)
        val c = Offset(fx(w, t), fy(h, t) + bob)
        val wing = if (buzzing) 25f * sin(flap) else 8f * sin(flap)
        rotate(-25f + wing, c + Offset(-1 * u, -5 * u)) { drawOval(p.surface.copy(alpha = 0.9f), c + Offset(-8 * u, -14 * u), Size(8 * u, 11 * u)) }
        rotate(25f - wing, c + Offset(1 * u, -5 * u)) { drawOval(p.surface.copy(alpha = 0.9f), c + Offset(0f, -14 * u), Size(8 * u, 11 * u)) }
        stickerOval(p, c - Offset(9 * u, 6.5f * u), Size(18 * u, 13 * u), p.sun, p.sunDeep)
        drawLine(p.ink, c + Offset(-2.5f * u, -5.5f * u), c + Offset(-2.5f * u, 5.5f * u), 2.2f * u, StrokeCap.Round)
        drawLine(p.ink, c + Offset(2.5f * u, -5.5f * u), c + Offset(2.5f * u, 5.5f * u), 2.2f * u, StrokeCap.Round)
        eye(p, c + Offset(6 * u, -1.5f * u), 2.2f * u, 0.7f)
        drawLine(p.ink, c + Offset(-9 * u, 0f), c + Offset(-11.5f * u, 0f), 1f * u, StrokeCap.Round)
        letter.x = (c.x - 4 * u) / w; letter.y = (c.y - 20 * u) / h; letter.scale = 0.34f; letter.rotation = 6f * sin(time * 2f)
    }

    override fun DrawScope.icon(p: GamePalette, t: Float) {
        val u = size.minDimension / 100f; val c = center + Offset(0f, 6 * u + 2 * u * sin(t * 3f))
        drawOval(p.surface.copy(alpha = 0.95f), c + Offset(-20 * u, -38 * u), Size(18 * u, 26 * u))
        drawOval(p.surface.copy(alpha = 0.95f), c + Offset(2 * u, -38 * u), Size(18 * u, 26 * u))
        stickerOval(p, c - Offset(30 * u, 20 * u), Size(60 * u, 40 * u), p.sun, p.sunDeep)
        drawLine(p.ink, c + Offset(-8 * u, -17 * u), c + Offset(-8 * u, 17 * u), 6 * u, StrokeCap.Round)
        drawLine(p.ink, c + Offset(6 * u, -17 * u), c + Offset(6 * u, 17 * u), 6 * u, StrokeCap.Round)
        eye(p, c + Offset(20 * u, -4 * u), 6 * u, 0.6f)
    }
}
