package tech.fonia.app.holdgames

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import tech.fonia.model.SoundId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** CE/CI — the watering can Cici gives one splash per "ci"; each splash opens the next flower. */
class CanGame : HoldGame(SoundId.Ce) {
    override val bursts = true
    private val bloom = FloatArray(MAX)
    private val dropAge = FloatArray(MAX) { 9f }
    private var tilt = 0f
    private var tiltV = 0f
    private val body = Path()
    private val starPath = Path()

    init { goal = 3f }

    override fun onReset() { bloom.fill(0f); dropAge.fill(9f) }
    override fun onBurst() { val k = (count - 1).coerceIn(0, MAX - 1); dropAge[k] = 0f; tiltV += 260f }

    override fun step(i: HoldInput, dt: Float) {
        // springy tilt kick per splash
        tiltV += (-tilt * 90f - tiltV * 9f) * dt
        tilt += tiltV * dt
        for (k in 0 until MAX) {
            dropAge[k] += dt
            val target = if (dropAge[k] in 0.35f..8f || (k < count && dropAge[k] > 8f)) 1f else 0f
            bloom[k] += (target - bloom[k]) * dt * 7f
        }
    }

    override fun DrawScope.scene(p: GamePalette) {
        val w = size.width; val h = size.height; val u = size.minDimension / 100f
        ground(p, h * 0.82f, p.parrot, p.parrotDeep)
        val n = goal.toInt().coerceIn(2, MAX)
        fun flowerX(k: Int) = w * (0.36f + 0.5f * (k + 0.5f) / n)
        val gy = h * 0.82f
        // the can, top left, tilting with each splash
        val cc = Offset(w * 0.2f, h * 0.32f)
        val spout = Offset(cc.x + 22 * u, cc.y - 6 * u)
        rotate(18f + tilt * 0.12f, cc) {
            drawLine(p.parrotDeep, cc + Offset(8 * u, -2 * u), spout, 3.6f * u, StrokeCap.Round)
            drawLine(p.parrot, cc + Offset(8 * u, -2 * u), spout, 2f * u, StrokeCap.Round)
            stickerCircle(p, spout, 2.6f * u, p.parrot, p.parrotDeep, gloss = false)
            drawArc(p.parrotDeep, 120f, 200f, false, cc + Offset(-16 * u, -12 * u), Size(14 * u, 18 * u), style = Stroke(2.4f * u, cap = StrokeCap.Round))
            body.reset(); body.addRoundRect(RoundRect(cc.x - 11 * u, cc.y - 11 * u, cc.x + 11 * u, cc.y + 11 * u, CornerRadius(5 * u)))
            stickerPath(p, body, p.parrot, p.parrotDeep, 1.3f * u)
        }
        // splashes (draw-only) and flowers
        for (k in 0 until n) {
            val a = dropAge[k]
            if (a < 0.6f) {
                val t = a / 0.6f
                val tip = Offset(cc.x + 20 * u, cc.y + 2 * u)
                val dst = Offset(flowerX(k), gy - 12 * u)
                for (d in 0 until 4) {
                    val tt = (t - d * 0.07f).coerceIn(0f, 1f)
                    val x = mix(tip.x, dst.x, tt) + (d - 1.5f) * 1.6f * u
                    val y = mix(tip.y, dst.y, tt) - sin(PI.toFloat() * tt) * 10 * u
                    drawCircle(p.surface, 1.7f * u, Offset(x, y)); drawCircle(p.accent, 1.1f * u, Offset(x, y))
                }
            }
            val fx = flowerX(k)
            drawLine(p.parrotDeep, Offset(fx, gy), Offset(fx, gy - 10 * u), 1.6f * u, StrokeCap.Round)
            val b = bloom[k].coerceIn(0f, 1.2f)
            if (b < 0.2f) {
                stickerOval(p, Offset(fx - 2.5f * u, gy - 16 * u), Size(5 * u, 7 * u), p.voice, p.voiceDeep)
            } else {
                for (q in 0 until 5) {
                    val ang = q * 2f * PI.toFloat() / 5f + time * 0.3f
                    drawCircle(p.voice, 3.2f * u * b, Offset(fx + cos(ang) * 4f * u * b, gy - 13 * u + sin(ang) * 4f * u * b))
                }
                stickerCircle(p, Offset(fx, gy - 13 * u), 2.6f * u * b, p.sun, p.sunDeep, gloss = false)
            }
        }
        if (reached) star(p, Offset(w * 0.86f, h * 0.18f), (6f + 1.2f * sin(time * 8f)) * u, starPath)
        letter.x = cc.x / w; letter.y = (cc.y + 1 * u) / h; letter.scale = 0.26f; letter.rotation = 18f + tilt * 0.12f
    }

    override fun DrawScope.icon(p: GamePalette, t: Float) {
        val u = size.minDimension / 100f; val c = center + Offset(-6 * u, 6 * u)
        rotate(-12f + 4f * sin(t * 2f), c) {
            drawLine(p.parrotDeep, c + Offset(18 * u, -6 * u), c + Offset(46 * u, -26 * u), 9 * u, StrokeCap.Round)
            drawLine(p.parrot, c + Offset(18 * u, -6 * u), c + Offset(46 * u, -26 * u), 5 * u, StrokeCap.Round)
            drawArc(p.parrotDeep, 120f, 200f, false, c + Offset(-40 * u, -26 * u), Size(30 * u, 40 * u), style = Stroke(6 * u, cap = StrokeCap.Round))
            body.reset(); body.addRoundRect(RoundRect(c.x - 26 * u, c.y - 26 * u, c.x + 26 * u, c.y + 26 * u, CornerRadius(12 * u)))
            stickerPath(p, body, p.parrot, p.parrotDeep, 3 * u)
        }
        for (d in 0 until 3) drawCircle(p.accent, 3.5f * u, c + Offset(46 * u + d * 4 * u, -12 * u + d * 9 * u))
    }

    private companion object { const val MAX = 6 }
}
