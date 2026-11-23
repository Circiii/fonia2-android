package tech.fonia.app.holdgames

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import tech.fonia.model.SoundId
import kotlin.math.PI
import kotlin.math.sin

/** S — Sâsâilă the snake stretches along the meadow path while "sss" lasts; the star waits at the end. */
class SnakeGame : HoldGame(SoundId.S) {
    override val bursts = false
    private val body = Path()
    private val trail = Path()
    private val starPath = Path()
    private var wiggle = 0f
    private var tongue = 0f

    override fun good(i: HoldInput) = i.onTargetNow || (i.fricative && i.sNess > 0.45f)

    override fun step(i: HoldInput, dt: Float) {
        val target = if (i.listening && good(i)) 1f else 0f
        wiggle += (target * (0.4f + i.level) - wiggle) * (dt * 6f)
        tongue = if (i.listening && good(i)) tongue + dt else 0f
    }

    private fun pathX(w: Float, t: Float) = w * (0.08f + 0.72f * t)
    private fun pathY(h: Float, t: Float) = h * (0.6f + 0.1f * sin(t * 2f * PI.toFloat() * 1.25f + 0.4f))

    override fun DrawScope.scene(p: GamePalette) {
        val w = size.width; val h = size.height; val u = size.minDimension / 100f
        ground(p, h * 0.76f, p.parrot, p.parrotDeep)
        for (k in 0 until 6) { // grass tufts
            val x = w * (0.1f + k * 0.16f); val y = h * 0.76f
            drawLine(p.parrotDeep, Offset(x, y), Offset(x - 2 * u, y - 4 * u), 1.2f * u, StrokeCap.Round)
            drawLine(p.parrotDeep, Offset(x, y), Offset(x + 2 * u, y - 5 * u), 1.2f * u, StrokeCap.Round)
        }
        // the path: dashed, from the burrow to the star
        trail.reset()
        for (k in 0..40) { val t = k / 40f; if (k == 0) trail.moveTo(pathX(w, t), pathY(h, t)) else trail.lineTo(pathX(w, t), pathY(h, t)) }
        drawPath(trail, p.surface.copy(alpha = 0.45f), style = Stroke(2.2f * u, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * u, 4 * u))))
        val end = Offset(pathX(w, 1f) + 9 * u, pathY(h, 1f) - 22 * u)
        val pulse = if (reached) 1f + 0.18f * sin(time * 9f).coerceAtLeast(0f) else 1f
        star(p, end, 8 * u * pulse, starPath)
        // the snake: tail at the burrow, head where the hiss has carried it
        val tail = 0.02f
        val head = 0.05f + 0.86f * shown.coerceIn(0f, 1.05f)
        body.reset()
        val n = 36
        var hx = 0f; var hy = 0f
        for (k in 0..n) {
            val t = tail + (head - tail) * k / n
            val amp = (1.2f + 3.5f * wiggle) * u * (k / n.toFloat())
            val off = amp * sin(time * 8f - t * 34f)
            val x = pathX(w, t); val y = pathY(h, t) + off
            if (k == 0) body.moveTo(x, y) else body.lineTo(x, y)
            hx = x; hy = y
        }
        stickerStroke(p, body, p.parrot, p.parrotDeep, 7.5f * u)
        // diamonds along the back
        for (k in 1 until n step 4) {
            val t = tail + (head - tail) * k / n
            val off = (1.2f + 3.5f * wiggle) * u * (k / n.toFloat()) * sin(time * 8f - t * 34f)
            drawCircle(p.parrotDeep, 1.3f * u, Offset(pathX(w, t), pathY(h, t) + off))
        }
        val hc = Offset(hx + 2 * u, hy - 1 * u)
        stickerCircle(p, hc, 6.5f * u, p.parrot, p.parrotDeep)
        eye(p, hc + Offset(1.5f * u, -2.5f * u), 2.2f * u, 0.6f)
        if (tongue > 0f && sin(time * 18f) > -0.2f) { // the forked tongue flicks while the hiss is good
            val tip = hc + Offset(9f * u, 1.5f * u)
            drawLine(p.voice, hc + Offset(5.5f * u, 1.5f * u), tip, 1.1f * u, StrokeCap.Round)
            drawLine(p.voice, tip, tip + Offset(2f * u, -1.5f * u), 1f * u, StrokeCap.Round)
            drawLine(p.voice, tip, tip + Offset(2f * u, 1.5f * u), 1f * u, StrokeCap.Round)
        }
        letter.x = (hc.x - 8 * u) / w; letter.y = (hc.y - 19 * u) / h; letter.scale = 0.55f; letter.rotation = 4f * sin(time * 3f)
    }

    override fun DrawScope.icon(p: GamePalette, t: Float) {
        val u = size.minDimension / 100f; val c = center
        body.reset()
        for (k in 0..40) {
            val q = k / 40f
            val x = c.x - 38 * u + q * 66 * u
            val y = c.y + 14 * u + 14 * u * sin(q * 2f * PI.toFloat() * 1.1f + t * 2f)
            if (k == 0) body.moveTo(x, y) else body.lineTo(x, y)
        }
        stickerStroke(p, body, p.parrot, p.parrotDeep, 13 * u)
        val hc = Offset(c.x + 30 * u, c.y - 12 * u + 2 * u * sin(t * 3f))
        stickerCircle(p, hc, 14 * u, p.parrot, p.parrotDeep)
        eye(p, hc + Offset(3 * u, -4 * u), 4.5f * u, 0.5f)
        drawLine(p.voice, hc + Offset(12 * u, 4 * u), hc + Offset(22 * u, 4 * u), 2.5f * u, StrokeCap.Round)
    }
}
