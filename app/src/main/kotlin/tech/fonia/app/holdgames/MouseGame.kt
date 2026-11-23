package tech.fonia.app.holdgames

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import tech.fonia.model.SoundId
import kotlin.math.PI
import kotlin.math.sin

/** Ț — the mouse Țuțu takes one hop per short "ț" burst, cushion by cushion, to the cheese. */
class MouseGame : HoldGame(SoundId.Ts) {
    override val bursts = true
    private var from = 0f
    private var to = 0f
    private var hop = 1f
    private val tail = Path()
    private val cheese = Path()
    private val starPath = Path()

    init { goal = 3f }

    override fun onReset() { from = 0f; to = 0f; hop = 1f }
    override fun onBurst() { from = posNow(); to = count.toFloat().coerceAtMost(goal); hop = 0f }
    override fun step(i: HoldInput, dt: Float) { if (hop < 1f) hop = (hop + dt / 0.34f).coerceAtMost(1f) }
    private fun posNow() = mix(from, to, hop)

    override fun DrawScope.scene(p: GamePalette) {
        val w = size.width; val h = size.height; val u = size.minDimension / 100f
        ground(p, h * 0.8f, p.fill, p.separator)
        for (k in 1..6) drawLine(p.separator, Offset(w * k / 7f, h * 0.8f), Offset(w * k / 7f - 6 * u, h), 0.8f * u)
        val n = goal.toInt().coerceIn(2, 6)
        fun stoneX(k: Int) = w * (0.12f + 0.62f * k / n)
        val gy = h * 0.74f
        // cushions: one per hop; the reached ones turn sun
        for (k in 1..n) {
            val done = count >= k
            stickerOval(p, Offset(stoneX(k) - 7 * u, gy - 2.5f * u), Size(14 * u, 6 * u), if (done) p.sun else p.surface, if (done) p.sunDeep else p.accentDeep)
        }
        // the cheese
        val cx = w * 0.88f
        cheese.reset(); cheese.moveTo(cx - 9 * u, gy + 2 * u); cheese.lineTo(cx + 9 * u, gy + 2 * u); cheese.lineTo(cx + 9 * u, gy - 10 * u); cheese.close()
        stickerPath(p, cheese, p.sun, p.sunDeep, 1.2f * u)
        drawCircle(p.sunDeep, 1.4f * u, Offset(cx + 5 * u, gy - 2 * u))
        drawCircle(p.sunDeep, 1f * u, Offset(cx + 1 * u, gy))
        if (reached) star(p, Offset(cx, gy - 22 * u), (6f + 1.2f * sin(time * 8f)) * u, starPath)
        // Țuțu, hopping
        val pos = posNow()
        fun xAt(k: Int) = if (k <= 0) w * 0.1f else stoneX(k.coerceAtMost(n))
        val i0 = pos.toInt()
        val x = mix(xAt(i0), xAt(i0 + 1), pos - i0)
        val lift = sin(PI.toFloat() * hop) * 14 * u * if (hop < 1f) 1f else 0f
        val squash = if (hop >= 1f) 1f + 0.05f * sin(time * 3f) else 1f
        val c = Offset(x, gy - 6 * u - lift)
        tail.reset(); tail.moveTo(c.x - 7 * u, c.y + 2 * u); tail.quadraticTo(c.x - 16 * u, c.y + 2 * u, c.x - 14 * u, c.y - 6 * u)
        drawPath(tail, p.inkSecondary, style = Stroke(1.2f * u, cap = StrokeCap.Round))
        stickerOval(p, c - Offset(8 * u, 5.5f * u * squash), Size(16 * u, 11 * u * squash), p.surface, p.inkSecondary)
        stickerCircle(p, c + Offset(2 * u, -7 * u), 3.6f * u, p.surface, p.inkSecondary, gloss = false)
        drawCircle(p.voice.copy(alpha = 0.6f), 2f * u, c + Offset(2 * u, -7 * u))
        eye(p, c + Offset(4.5f * u, -1.5f * u), 1.7f * u, 0.8f)
        drawCircle(p.voice, 1.2f * u, c + Offset(8.3f * u, 0.5f * u))
        letter.x = c.x / w; letter.y = (c.y - 21 * u) / h; letter.scale = 0.4f; letter.rotation = if (hop < 1f) 12f * sin(PI.toFloat() * hop) else 0f
    }

    override fun DrawScope.icon(p: GamePalette, t: Float) {
        val u = size.minDimension / 100f; val c = center + Offset(0f, 8 * u)
        stickerCircle(p, c + Offset(-22 * u, -26 * u), 15 * u, p.surface, p.inkSecondary, gloss = false)
        stickerCircle(p, c + Offset(22 * u, -26 * u), 15 * u, p.surface, p.inkSecondary, gloss = false)
        drawCircle(p.voice.copy(alpha = 0.55f), 9 * u, c + Offset(-22 * u, -26 * u))
        drawCircle(p.voice.copy(alpha = 0.55f), 9 * u, c + Offset(22 * u, -26 * u))
        stickerCircle(p, c, 28 * u, p.surface, p.inkSecondary)
        eyes(p, c + Offset(-10 * u, -4 * u), c + Offset(10 * u, -4 * u), 5 * u, 0f)
        drawCircle(p.voice, 4 * u, c + Offset(0f, 9 * u + u * sin(t * 4)))
    }
}
