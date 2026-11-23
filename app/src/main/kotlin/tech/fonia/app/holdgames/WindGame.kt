package tech.fonia.app.holdgames

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import tech.fonia.model.SoundId
import kotlin.math.sin

/** J — the wind Vâjâilă blows "jjj" and spins the pinwheel; the ring around it fills with the held sound. */
class WindGame : HoldGame(SoundId.J) {
    override val bursts = false
    private var angle = 0f
    private var strength = 0f
    private var dash = 0f
    private val blade = Path()
    private val streak = Path()

    override fun good(i: HoldInput) = i.onTargetNow || (i.voicing > 0.3f && (i.fricative || i.sibilance > 0.15f))

    override fun step(i: HoldInput, dt: Float) {
        val target = if (i.listening && good(i)) 0.4f + i.level else 0f
        strength += (target - strength) * dt * 3f
        angle += (40f + 900f * strength) * dt
        dash += dt * (20f + 160f * strength)
    }

    override fun DrawScope.scene(p: GamePalette) {
        val w = size.width; val h = size.height; val u = size.minDimension / 100f
        ground(p, h * 0.84f, p.parrot, p.parrotDeep)
        // Vâjâilă: a cloud with puffed cheeks
        val cc = Offset(w * 0.22f, h * 0.48f)
        val puff = 1f + 0.06f * strength * sin(time * 20f)
        cloud(p, cc, 12 * u * puff, u)
        drawArc(p.ink, 200f, 140f, false, cc + Offset(-6 * u, -5 * u), Size(5 * u, 4 * u), style = Stroke(1.1f * u, cap = StrokeCap.Round))
        drawArc(p.ink, 200f, 140f, false, cc + Offset(2 * u, -5 * u), Size(5 * u, 4 * u), style = Stroke(1.1f * u, cap = StrokeCap.Round))
        drawCircle(p.voice.copy(alpha = 0.45f), (2.6f + 1.4f * strength) * u, cc + Offset(-7 * u, 2 * u))
        drawCircle(p.voice.copy(alpha = 0.45f), (2.6f + 1.4f * strength) * u, cc + Offset(8 * u, 2 * u))
        drawOval(p.ink, cc + Offset(10 * u, -1.2f * u), Size((2.2f + 1.5f * strength) * u, (2.6f + 1.5f * strength) * u))
        // the wind
        val pc = Offset(w * 0.74f, h * 0.44f)
        if (strength > 0.05f) for (k in 0 until 4) {
            val y = cc.y - 6 * u + k * 4.5f * u
            streak.reset(); streak.moveTo(cc.x + 14 * u, y)
            streak.cubicTo(w * 0.4f, y - 6 * u, w * 0.52f, pc.y + (k - 1.5f) * 6 * u, pc.x - 16 * u, pc.y + (k - 1.5f) * 5 * u)
            drawPath(streak, p.surface.copy(alpha = (0.35f + 0.5f * strength).coerceAtMost(0.85f)),
                style = Stroke(1.4f * u, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7 * u, 5 * u), -dash * u)))
        }
        // the pinwheel on its stick, and the ring of the held sound
        drawLine(p.accentDeep, pc, Offset(pc.x, h * 0.84f), 2f * u, StrokeCap.Round)
        val ring = shown.coerceIn(0f, 1f)
        drawCircle(p.surface.copy(alpha = 0.18f), 21 * u, pc, style = Stroke(2.4f * u))
        if (ring > 0.01f) drawArc(if (reached) p.sun else p.surface, -90f, 360f * ring, false, pc - Offset(21 * u, 21 * u), Size(42 * u, 42 * u), style = Stroke(2.4f * u, cap = StrokeCap.Round))
        val faces = arrayOf(p.voice, p.sun, p.parrot, p.surface)
        val rims = arrayOf(p.voiceDeep, p.sunDeep, p.parrotDeep, p.accentDeep)
        for (k in 0 until 4) rotate(angle + k * 90f, pc) {
            blade.reset(); blade.moveTo(pc.x, pc.y); blade.lineTo(pc.x, pc.y - 15 * u); blade.quadraticTo(pc.x + 10 * u, pc.y - 12 * u, pc.x + 6 * u, pc.y - 2 * u); blade.close()
            stickerPath(p, blade, faces[k], rims[k], 0.9f * u)
        }
        stickerCircle(p, pc, 2.4f * u, p.sun, p.sunDeep, gloss = false)
        letter.x = cc.x / w; letter.y = (cc.y - 22 * u) / h; letter.scale = 0.45f; letter.rotation = 5f * strength * sin(time * 16f)
    }

    private fun DrawScope.cloud(p: GamePalette, c: Offset, r: Float, u: Float) {
        val e = 1.2f * u
        drawCircle(p.shadow, r * 1.05f, c + Offset(0.8f * u, 2f * u))
        for (pass in 0..2) {
            val color = when (pass) { 0 -> p.accentDeep; 1 -> p.surface; else -> p.surface }
            val grow = when (pass) { 0 -> e * 1.8f; 1 -> e * 0.9f; else -> 0f }
            drawCircle(color, r + grow, c)
            drawCircle(color, r * 0.75f + grow, c + Offset(-r * 0.95f, r * 0.2f))
            drawCircle(color, r * 0.8f + grow, c + Offset(r * 0.95f, r * 0.15f))
            drawCircle(color, r * 0.7f + grow, c + Offset(-r * 0.3f, -r * 0.65f))
        }
    }

    override fun DrawScope.icon(p: GamePalette, t: Float) {
        val u = size.minDimension / 100f; val c = center
        drawLine(p.accentDeep, c, c + Offset(0f, 46 * u), 4 * u, StrokeCap.Round)
        val faces = arrayOf(p.voice, p.sun, p.parrot, p.surface)
        val rims = arrayOf(p.voiceDeep, p.sunDeep, p.parrotDeep, p.accentDeep)
        for (k in 0 until 4) rotate(t * 60f + k * 90f, c) {
            blade.reset(); blade.moveTo(c.x, c.y); blade.lineTo(c.x, c.y - 40 * u); blade.quadraticTo(c.x + 26 * u, c.y - 32 * u, c.x + 16 * u, c.y - 5 * u); blade.close()
            stickerPath(p, blade, faces[k], rims[k], 2.2f * u)
        }
        stickerCircle(p, c, 6 * u, p.sun, p.sunDeep, gloss = false)
    }
}
