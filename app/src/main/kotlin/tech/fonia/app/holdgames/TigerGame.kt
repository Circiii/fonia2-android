package tech.fonia.app.holdgames

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import tech.fonia.model.SoundId
import kotlin.math.max
import kotlin.math.sin

/**
 * R — the tiger Rică purrs, then roars with the strength of the trill: rings leave his mouth with the living letter.
 * A single tap of the tongue also counts (chapter 06 §1: at R one tap is accepted), so taps fill the paw prints too.
 */
class TigerGame : HoldGame(SoundId.R) {
    override val bursts = true
    private var roar = 0f
    private var rolled = 0f
    private var lastTrill = false
    private var taps = 0
    private val leaf = Path()
    private val starPath = Path()

    init { goal = 3f }

    override val progress: Float get() = max(count / max(1f, goal), rolled / 1.2f).coerceIn(0f, 1f)

    override fun onReset() { rolled = 0f; taps = 0 }
    override fun burstLevel(i: HoldInput) = if (i.voicing > 0.2f || i.trill > 0.2f) i.level else 0f

    override fun step(i: HoldInput, dt: Float) {
        val strength = if (i.listening) max(i.trill, i.level * 0.6f) else 0f
        roar += (strength - roar) * dt * 8f
        if (i.listening && (i.trillDetected || i.trill > 0.45f)) rolled += dt
        if (i.listening && i.trillDetected && !lastTrill) taps++
        lastTrill = i.trillDetected
    }

    override fun DrawScope.scene(p: GamePalette) {
        val w = size.width; val h = size.height; val u = size.minDimension / 100f
        ground(p, h * 0.86f, p.parrot, p.parrotDeep)
        // jungle leaves in the corners
        for ((k, sign) in floatArrayOf(1f, -1f).withIndex()) {
            val o = if (k == 0) Offset(0f, 0f) else Offset(w, 0f)
            leaf.reset(); leaf.moveTo(o.x, o.y); leaf.quadraticTo(o.x + sign * 30 * u, o.y + 6 * u, o.x + sign * 34 * u, o.y + 26 * u); leaf.quadraticTo(o.x + sign * 10 * u, o.y + 22 * u, o.x, o.y); leaf.close()
            drawPath(leaf, if (k == 0) p.parrot else p.parrotDeep)
        }
        val c = Offset(w * 0.62f, h * 0.52f)
        // rings of the roar
        if (roar > 0.04f) for (k in 0 until 3) {
            val ph = (time * 1.4f + k / 3f) % 1f
            val r = (8f + ph * 34f) * u
            drawArc(p.surface.copy(alpha = (1f - ph) * roar.coerceAtMost(1f)), 140f, 80f, false, c + Offset(-14 * u, 6 * u) - Offset(r, r), Size(2 * r, 2 * r), style = Stroke(1.6f * u, cap = StrokeCap.Round))
        }
        face(p, c, 20 * u, roar, u)
        // paw prints: the progress, one per tap (or filled by a rolled R)
        val n = goal.toInt().coerceIn(2, 6)
        for (k in 0 until n) {
            val on = progress * n > k + 0.5f
            val pc = Offset(w * (0.2f + k * 0.12f), h * 0.93f)
            drawCircle(if (on) p.sun else p.surface.copy(alpha = 0.5f), 2.6f * u, pc)
            for (t in 0 until 3) drawCircle(if (on) p.sun else p.surface.copy(alpha = 0.5f), 1f * u, pc + Offset((t - 1) * 2.2f * u, -3.4f * u))
        }
        if (reached) star(p, c + Offset(0f, -32 * u), (6f + sin(time * 8f)) * u, starPath)
        letter.x = (c.x - 30 * u - roar * 8 * u) / w; letter.y = (c.y + 4 * u) / h
        letter.scale = 0.42f + 0.25f * roar.coerceAtMost(1f); letter.rotation = 10f * roar * sin(time * 38f)
    }

    private fun DrawScope.face(p: GamePalette, c: Offset, r: Float, open: Float, u: Float) {
        stickerCircle(p, c + Offset(-r * 0.7f, -r * 0.8f), r * 0.32f, p.sun, p.sunDeep, gloss = false)
        stickerCircle(p, c + Offset(r * 0.7f, -r * 0.8f), r * 0.32f, p.sun, p.sunDeep, gloss = false)
        stickerCircle(p, c, r, p.sun, p.sunDeep)
        for (k in -1..1) drawLine(p.ink, c + Offset(k * r * 0.25f, -r * 0.95f), c + Offset(k * r * 0.2f, -r * 0.62f), r * 0.07f, StrokeCap.Round)
        drawLine(p.ink, c + Offset(-r, 0f), c + Offset(-r * 0.7f, r * 0.05f), r * 0.07f, StrokeCap.Round)
        drawLine(p.ink, c + Offset(r, 0f), c + Offset(r * 0.7f, r * 0.05f), r * 0.07f, StrokeCap.Round)
        eyes(p, c + Offset(-r * 0.38f, -r * 0.25f), c + Offset(r * 0.32f, -r * 0.25f), r * 0.16f, -0.6f)
        drawOval(p.surface, c + Offset(-r * 0.62f, r * 0.05f), Size(r * 1.2f, r * 0.62f))
        val mh = r * (0.08f + 0.42f * open.coerceIn(0f, 1f))
        drawOval(p.ink, c + Offset(-r * 0.32f, r * 0.32f), Size(r * 0.56f, mh))
        if (open > 0.25f) drawOval(p.voice, c + Offset(-r * 0.18f, r * 0.32f + mh * 0.5f), Size(r * 0.3f, mh * 0.45f))
        drawOval(p.voice, c + Offset(-r * 0.14f, r * 0.08f), Size(r * 0.28f, r * 0.18f))
    }

    override fun DrawScope.icon(p: GamePalette, t: Float) {
        val u = size.minDimension / 100f
        face(p, center + Offset(0f, 6 * u), 36 * u, 0.3f + 0.2f * sin(t * 2f), u)
    }
}
