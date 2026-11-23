package tech.fonia.app.holdgames

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import tech.fonia.model.SoundId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * L — the lion Lalalu sings "la-la-la": one note on the staff per syllable (a voiced onset); a long "lll" also fills
 * the staff (half a second per note), so the sustain prompt and the syllables both work.
 */
class LionGame : HoldGame(SoundId.L) {
    override val bursts = true
    private val noteAge = FloatArray(MAX) { 9f }
    private var mouth = 0f
    private var shownNotes = 0
    private val stem = Path()
    private val starPath = Path()

    init { goal = 3f }

    /** Notes: syllables, or seconds of a held "lll" (one per 0.5 s). */
    private val notes: Int get() = max(count, (held / 0.5f).toInt())
    override val progress: Float get() = (notes / max(1f, goal)).coerceIn(0f, 1f)

    override fun burstLevel(i: HoldInput) = if (i.voicing > 0.25f) i.level else 0f
    override fun onReset() { noteAge.fill(9f); shownNotes = 0 }
    override fun onBurst() { mouth = 1f }

    override fun step(i: HoldInput, dt: Float) {
        if (i.listening && i.voicing > 0.3f && !i.fricative) held += dt
        mouth = max(0f, mouth - dt * 3f)
        if (i.listening && i.voicing > 0.3f) mouth = max(mouth, 0.35f + 0.3f * sin(time * 12f))
        val n = notes.coerceAtMost(MAX)
        while (shownNotes < n) { noteAge[shownNotes] = 0f; shownNotes++ }
        for (k in 0 until MAX) noteAge[k] += dt
    }

    override fun DrawScope.scene(p: GamePalette) {
        val w = size.width; val h = size.height; val u = size.minDimension / 100f
        ground(p, h * 0.86f, p.parrot, p.parrotDeep)
        // the staff, the letter is its clef
        val top = h * 0.12f; val gap = 3.6f * u
        for (k in 0 until 5) drawLine(p.surface.copy(alpha = 0.75f), Offset(w * 0.06f, top + k * gap), Offset(w * 0.94f, top + k * gap), 0.7f * u)
        val n = goal.toInt().coerceIn(2, MAX)
        for (k in 0 until n) {
            val x = w * (0.32f + 0.58f * (k + 0.5f) / n)
            val y = top + gap * (3.5f - (k % 3) * 1.2f)
            val a = noteAge[k]
            if (k >= shownNotes) {
                drawCircle(p.surface.copy(alpha = 0.25f), 2.4f * u, Offset(x, y))
                continue
            }
            val pop = if (a < 0.35f) 1f + 0.5f * sin(PI.toFloat() * a / 0.35f) else 1f
            val c = Offset(x, y)
            drawLine(p.ink, c + Offset(2.2f * u, 0f), c + Offset(2.2f * u, -9 * u), 0.9f * u, StrokeCap.Round)
            stickerOval(p, c - Offset(3f * u * pop, 2.2f * u * pop), Size(6f * u * pop, 4.4f * u * pop), p.sun, p.sunDeep)
        }
        if (reached) star(p, Offset(w * 0.9f, top - 2 * u), (5f + sin(time * 8f)) * u, starPath)
        // Lalalu
        val c = Offset(w * 0.5f, h * 0.66f + 0.8f * u * sin(time * 2f))
        lion(p, c, 15 * u, mouth)
        // flying notes from the mouth while singing
        if (mouth > 0.3f) for (k in 0 until 2) {
            val ph = (time * 0.9f + k * 0.5f) % 1f
            val nc = c + Offset(12 * u + ph * 18 * u, -6 * u - ph * 22 * u)
            drawLine(p.surface.copy(alpha = 1 - ph), nc + Offset(1.5f * u, 0f), nc + Offset(1.5f * u, -5 * u), 0.7f * u)
            drawCircle(p.surface.copy(alpha = 1 - ph), 1.6f * u, nc)
        }
        letter.x = 0.1f; letter.y = (top + 2 * gap) / h; letter.scale = 0.42f; letter.rotation = 6f * mouth * sin(time * 9f)
    }

    private fun DrawScope.lion(p: GamePalette, c: Offset, r: Float, open: Float) {
        for (k in 0 until 12) {
            val a = k * PI.toFloat() / 6f + time * 0.15f
            stickerCircle(p, c + Offset(cos(a) * r * 1.05f, sin(a) * r * 1.05f), r * 0.42f, p.sunDeep, p.sunDeep, gloss = false)
        }
        stickerCircle(p, c, r, p.sun, p.sunDeep)
        eyes(p, c + Offset(-r * 0.35f, -r * 0.2f), c + Offset(r * 0.35f, -r * 0.2f), r * 0.15f, 0f)
        drawOval(p.surface, c + Offset(-r * 0.5f, r * 0.08f), Size(r, r * 0.6f))
        drawOval(p.voiceDeep, c + Offset(-r * 0.15f, r * 0.05f), Size(r * 0.3f, r * 0.18f))
        val mh = r * (0.06f + 0.36f * open.coerceIn(0f, 1f))
        drawOval(p.ink, c + Offset(-r * 0.22f, r * 0.34f), Size(r * 0.44f, mh))
        if (open > 0.3f) { // "la": the tongue up
            stem.reset(); stem.moveTo(c.x - r * 0.12f, c.y + r * 0.34f + mh); stem.quadraticTo(c.x, c.y + r * 0.34f, c.x + r * 0.12f, c.y + r * 0.34f + mh)
            drawPath(stem, p.voice)
        }
    }

    override fun DrawScope.icon(p: GamePalette, t: Float) {
        lion(p, center + Offset(0f, 4f * size.minDimension / 100f), size.minDimension * 0.27f, 0.3f + 0.3f * sin(t * 3f))
    }

    private companion object { const val MAX = 6 }
}
