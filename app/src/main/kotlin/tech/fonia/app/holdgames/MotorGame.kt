package tech.fonia.app.holdgames

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import tech.fonia.model.SoundId
import kotlin.math.sin

/** GE/GI — the little motor Gigel starts with "ge-ge-ge": a puff and a jolt per burst; at the goal it drives off. */
class MotorGame : HoldGame(SoundId.Ge) {
    override val bursts = true
    private val puffAge = FloatArray(8) { 9f }
    private var next = 0
    private var jolt = 0f
    private var joltV = 0f
    private var drive = 0f
    private var wheel = 0f
    private var road = 0f
    private val car = Path()
    private val starPath = Path()

    init { goal = 3f }

    override fun burstLevel(i: HoldInput) = if (i.voicing > 0.2f || i.fricative) i.level else i.level * 0.6f
    override fun onReset() { drive = 0f; puffAge.fill(9f) }
    override fun onBurst() { puffAge[next] = 0f; next = (next + 1) % puffAge.size; joltV += 220f }

    override fun step(i: HoldInput, dt: Float) {
        joltV += (-jolt * 260f - joltV * 14f) * dt
        jolt += joltV * dt
        for (k in puffAge.indices) puffAge[k] += dt
        if (reached) drive = (drive + dt * 0.5f).coerceAtMost(1f)
        val speed = 0.4f * count / goal + 3f * drive
        wheel += speed * dt * 6f
        road += speed * dt
    }

    override fun DrawScope.scene(p: GamePalette) {
        val w = size.width; val h = size.height; val u = size.minDimension / 100f
        // the road, its dashes slide when the motor runs
        drawRect(p.accentDeep, Offset(0f, h * 0.72f), Size(w, h * 0.28f))
        drawLine(p.surface.copy(alpha = 0.7f), Offset(0f, h * 0.86f), Offset(w, h * 0.86f), 1.4f * u,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8 * u, 6 * u), road * 30 * u))
        drawRect(p.parrot, Offset(0f, h * 0.68f), Size(w, h * 0.04f))
        // the starter dial: fills with each "ge"
        val dc = Offset(w * 0.8f, h * 0.24f)
        drawArc(p.surface, 180f, 180f, true, dc - Offset(12 * u, 12 * u), Size(24 * u, 24 * u))
        drawArc(p.sun, 180f, 180f * shown.coerceIn(0f, 1f), true, dc - Offset(12 * u, 12 * u), Size(24 * u, 24 * u))
        drawArc(p.accentDeep, 180f, 180f, false, dc - Offset(12 * u, 12 * u), Size(24 * u, 24 * u), style = Stroke(1.4f * u))
        rotate(-90f + 180f * shown.coerceIn(0f, 1f), dc) { drawLine(p.ink, dc, dc + Offset(0f, -10 * u), 1.4f * u, StrokeCap.Round) }
        drawCircle(p.ink, 1.8f * u, dc)
        if (reached) star(p, dc + Offset(0f, -20 * u), (5f + sin(time * 8f)) * u, starPath)
        // Gigel
        val baseX = w * (0.26f + 0.12f * shown.coerceIn(0f, 1f)) + drive * drive * w * 0.36f
        val gy = h * 0.8f
        val c = Offset(baseX, gy - 9 * u - jolt * 0.02f * u)
        for (k in puffAge.indices) {
            val a = puffAge[k]
            if (a > 1.1f) continue
            val t = a / 1.1f
            drawCircle(p.surface.copy(alpha = 0.85f * (1 - t)), (2f + 5f * t) * u, Offset(c.x - 18 * u - a * 14 * u, c.y + 3 * u - a * 8 * u))
        }
        car.reset(); car.addRoundRect(RoundRect(c.x - 16 * u, c.y - 7 * u, c.x + 16 * u, c.y + 6 * u, CornerRadius(5 * u)))
        car.addRoundRect(RoundRect(c.x - 9 * u, c.y - 17 * u, c.x + 7 * u, c.y - 4 * u, CornerRadius(5 * u)))
        rotate(-jolt * 0.03f, c) {
            stickerPath(p, car, p.voice, p.voiceDeep, 1.2f * u)
            drawRoundRect(p.surface, Offset(c.x - 6.5f * u, c.y - 14.5f * u), Size(11 * u, 9 * u), CornerRadius(3 * u))
            drawCircle(p.sun, 2f * u, Offset(c.x + 15 * u, c.y - 2 * u))
            drawRoundRect(p.inkSecondary, Offset(c.x - 19 * u, c.y + 1 * u), Size(4 * u, 2.4f * u), CornerRadius(1 * u))
        }
        for (wx in floatArrayOf(-9f, 10f)) {
            val wc = Offset(c.x + wx * u, gy - 2 * u)
            drawCircle(p.ink, 4.6f * u, wc); drawCircle(p.sun, 2.4f * u, wc)
            rotate(wheel * 57f, wc) { drawLine(p.sunDeep, wc - Offset(2.4f * u, 0f), wc + Offset(2.4f * u, 0f), 0.8f * u) }
        }
        if (drive > 0f) for (k in 0 until 3) drawLine(p.surface.copy(alpha = 0.7f), Offset(c.x - 24 * u - k * 3 * u, c.y - 8 * u + k * 5 * u), Offset(c.x - 34 * u - k * 3 * u, c.y - 8 * u + k * 5 * u), 1.2f * u, StrokeCap.Round)
        letter.x = (c.x - 1 * u) / w; letter.y = (c.y - 10 * u) / h; letter.scale = 0.22f; letter.rotation = -jolt * 0.03f
    }

    override fun DrawScope.icon(p: GamePalette, t: Float) {
        val u = size.minDimension / 100f; val c = center + Offset(0f, 2 * u * sin(t * 6f))
        car.reset(); car.addRoundRect(RoundRect(c.x - 40 * u, c.y - 14 * u, c.x + 40 * u, c.y + 16 * u, CornerRadius(12 * u)))
        car.addRoundRect(RoundRect(c.x - 22 * u, c.y - 38 * u, c.x + 18 * u, c.y - 8 * u, CornerRadius(12 * u)))
        stickerPath(p, car, p.voice, p.voiceDeep, 3 * u)
        drawRoundRect(p.surface, Offset(c.x - 15 * u, c.y - 32 * u), Size(26 * u, 20 * u), CornerRadius(6 * u))
        for (wx in floatArrayOf(-22f, 24f)) { drawCircle(p.ink, 11 * u, Offset(c.x + wx * u, c.y + 20 * u)); drawCircle(p.sun, 6 * u, Offset(c.x + wx * u, c.y + 20 * u)) }
    }
}
