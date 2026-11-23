package tech.fonia.app.holdgames

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import tech.fonia.model.SoundId
import kotlin.math.sin

/** Ș — the little train Șușu puffs steam and rolls further towards the station the longer "șșș" is held. */
class TrainGame : HoldGame(SoundId.Sh) {
    override val bursts = false
    private val px = FloatArray(PUFFS)
    private val py = FloatArray(PUFFS)
    private val age = FloatArray(PUFFS) { 99f }
    private var next = 0
    private var spawn = 0f
    private var wheel = 0f
    private var lastX = -1f
    private val flag = Path()
    private val starPath = Path()
    private val bodyPath = Path()
    private val cabPath = Path()

    override fun good(i: HoldInput) = i.onTargetNow || (i.fricative && i.sNess < 0.6f)

    private var steaming = false
    override fun step(i: HoldInput, dt: Float) {
        steaming = i.listening && good(i)
        for (k in 0 until PUFFS) age[k] += dt
        spawn += dt
        if (steaming && spawn > 0.09f) { spawn = 0f; age[next] = 0f; px[next] = Float.NaN; next = (next + 1) % PUFFS }
    }

    override fun DrawScope.scene(p: GamePalette) {
        val w = size.width; val h = size.height; val u = size.minDimension / 100f
        // hills behind
        drawCircle(p.parrot, w * 0.42f, Offset(w * 0.18f, h * 1.12f))
        drawCircle(p.parrotDeep.copy(alpha = 0.55f), w * 0.36f, Offset(w * 0.82f, h * 1.1f))
        ground(p, h * 0.8f, p.parrot, p.parrotDeep)
        // the station at the end, with a sun flag
        val sx = w * 0.84f; val gy = h * 0.76f
        drawRoundRect(p.surface, Offset(sx - 9 * u, gy - 18 * u), Size(18 * u, 18 * u), CornerRadius(2 * u))
        drawRoundRect(p.accentDeep, Offset(sx - 11 * u, gy - 22 * u), Size(22 * u, 5 * u), CornerRadius(2 * u))
        drawLine(p.ink, Offset(sx - 8 * u, gy - 22 * u), Offset(sx - 8 * u, gy - 36 * u), 1.2f * u)
        flag.reset(); flag.moveTo(sx - 8 * u, gy - 36 * u); flag.lineTo(sx + 2 * u, gy - 32.5f * u + 1.5f * u * sin(time * 5f)); flag.lineTo(sx - 8 * u, gy - 29 * u); flag.close()
        drawPath(flag, p.sun)
        if (reached) star(p, Offset(sx, gy - 46 * u), (7f + 1.5f * sin(time * 8f)) * u, starPath)
        // rails and sleepers
        for (k in 0..14) drawRect(p.accentDeep, Offset(w * k / 14f - 2 * u, gy + 1 * u), Size(4 * u, 3 * u))
        drawLine(p.ink.copy(alpha = 0.7f), Offset(0f, gy + 1.2f * u), Offset(w, gy + 1.2f * u), 1.1f * u)
        // the train
        val x = w * (0.2f + 0.44f * shown.coerceIn(0f, 1.05f))
        if (lastX >= 0f) wheel += (x - lastX) / (3.4f * u)
        lastX = x
        val bob = if (steaming) 0.6f * u * sin(time * 30f) else 0f
        val by = gy - 4 * u + bob
        // steam puffs (draw-only particles, preallocated)
        val chimney = Offset(x + 12 * u, by - 22 * u)
        for (k in 0 until PUFFS) {
            val a = age[k]
            if (a > 1.4f) continue
            if (px[k].isNaN()) { px[k] = chimney.x; py[k] = chimney.y }
            val t = a / 1.4f
            val c = Offset(px[k] - a * 9 * u, py[k] - a * 16 * u)
            drawCircle(p.surface.copy(alpha = 0.9f * (1f - t)), (2.5f + 6f * t) * u, c)
        }
        // body, cab, chimney
        bodyPath.reset()
        bodyPath.addRoundRect(androidx.compose.ui.geometry.RoundRect(x - 14 * u, by - 15 * u, x + 18 * u, by, CornerRadius(4 * u)))
        stickerPath(p, bodyPath, p.voice, p.voiceDeep, 1.2f * u)
        val cab = cabPath; cab.reset(); cab.addRoundRect(androidx.compose.ui.geometry.RoundRect(x - 18 * u, by - 27 * u, x - 2 * u, by - 4 * u, CornerRadius(3 * u)))
        stickerPath(p, cab, p.voice, p.voiceDeep, 1.2f * u)
        drawRoundRect(p.surface, Offset(x - 15.5f * u, by - 24 * u), Size(11 * u, 10 * u), CornerRadius(2 * u))
        drawRoundRect(p.voiceDeep, Offset(x + 9 * u, by - 22 * u), Size(6 * u, 8 * u), CornerRadius(1 * u))
        drawRoundRect(p.sun, Offset(x + 8 * u, by - 24 * u), Size(8 * u, 3 * u), CornerRadius(1 * u))
        drawCircle(p.sun, 2.4f * u, Offset(x + 18.5f * u, by - 8 * u)) // the lamp
        for (wx in floatArrayOf(x - 10 * u, x + 1 * u, x + 12 * u)) {
            val c = Offset(wx, by + 1 * u)
            drawCircle(p.ink, 4.6f * u, c)
            drawCircle(p.sun, 2.6f * u, c)
            rotate(wheel * 57.3f, c) { drawLine(p.sunDeep, c - Offset(2.6f * u, 0f), c + Offset(2.6f * u, 0f), 0.8f * u) }
        }
        letter.x = (x - 10 * u) / w; letter.y = (by - 19 * u) / h; letter.scale = 0.36f; letter.rotation = 0f
    }

    override fun DrawScope.icon(p: GamePalette, t: Float) {
        val u = size.minDimension / 100f; val c = center
        val body = Path(); body.addRoundRect(androidx.compose.ui.geometry.RoundRect(c.x - 30 * u, c.y - 6 * u, c.x + 34 * u, c.y + 22 * u, CornerRadius(8 * u)))
        stickerPath(p, body, p.voice, p.voiceDeep, 2.5f * u)
        val cab = Path(); cab.addRoundRect(androidx.compose.ui.geometry.RoundRect(c.x - 38 * u, c.y - 28 * u, c.x - 6 * u, c.y + 14 * u, CornerRadius(6 * u)))
        stickerPath(p, cab, p.voice, p.voiceDeep, 2.5f * u)
        drawRoundRect(p.surface, Offset(c.x - 32 * u, c.y - 22 * u), Size(20 * u, 16 * u), CornerRadius(3 * u))
        drawRoundRect(p.sun, Offset(c.x + 14 * u, c.y - 22 * u), Size(14 * u, 16 * u), CornerRadius(2 * u))
        for (k in 0..2) drawCircle(p.surface.copy(alpha = 0.85f), (5f + k * 3f) * u, Offset(c.x + 22 * u - k * 10 * u, c.y - 30 * u - k * 9 * u + u * sin(t * 2 + k)))
        for (wx in floatArrayOf(-22f, 0f, 22f)) { drawCircle(p.ink, 8 * u, Offset(c.x + wx * u, c.y + 26 * u)); drawCircle(p.sun, 4.5f * u, Offset(c.x + wx * u, c.y + 26 * u)) }
    }

    private companion object { const val PUFFS = 14 }
}
