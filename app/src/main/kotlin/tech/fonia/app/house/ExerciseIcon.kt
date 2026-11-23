package tech.fonia.app.house

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import tech.fonia.app.holdgames.GamePalette
import tech.fonia.app.holdgames.HoldCharacter
import tech.fonia.app.holdgames.HoldGame
import tech.fonia.app.holdgames.eye
import tech.fonia.app.holdgames.stickerCircle
import tech.fonia.app.holdgames.stickerOval
import tech.fonia.app.holdgames.stickerPath
import tech.fonia.app.holdgames.star
import tech.fonia.designsystem.theme.FoniaColors
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.model.SoundId

/** The sound's own colour on PATH and in its house (tokens only; four hues for nine sounds, neighbours differ). */
fun soundColor(s: SoundId, c: FoniaColors): Pair<Color, Color> = when (s) {
    SoundId.S, SoundId.Ce -> c.parrot to c.parrotDeep
    SoundId.Sh, SoundId.Ge, SoundId.Ts -> c.voice to c.voiceDeep
    SoundId.Z, SoundId.R, SoundId.L -> c.sun to c.sunDeep
    SoundId.J -> c.accent to c.accentDeep
}

/** A card's illustration: a small vector sticker per exercise (the `Hold` card shows the sound's own character). */
@Composable
fun ExerciseIcon(exercise: Exercise, sound: SoundId, modifier: Modifier) {
    if (exercise == Exercise.Hold) {
        val game = remember(sound) { HoldGame.of(sound) }
        HoldCharacter(game, modifier)
        return
    }
    val p = GamePalette(FoniaTheme.colors)
    val path = remember { Path() }
    Box(modifier.drawBehind { drawIcon(exercise, p, path) })
}

private fun DrawScope.drawIcon(e: Exercise, p: GamePalette, path: Path) {
    val u = size.minDimension / 100f
    val c = center
    when (e) {
        Exercise.Listen -> { // an ear with sound waves
            path.reset()
            path.moveTo(c.x - 4 * u, c.y + 30 * u)
            path.cubicTo(c.x - 34 * u, c.y + 30 * u, c.x - 36 * u, c.y - 38 * u, c.x + 2 * u, c.y - 36 * u)
            path.cubicTo(c.x + 30 * u, c.y - 34 * u, c.x + 26 * u, c.y - 2 * u, c.x + 10 * u, c.y + 6 * u)
            path.cubicTo(c.x + 2 * u, c.y + 12 * u, c.x + 8 * u, c.y + 30 * u, c.x - 4 * u, c.y + 30 * u)
            path.close()
            stickerPath(p, path, p.voice, p.voiceDeep, 3 * u)
            drawArc(p.voiceDeep, 200f, 160f, false, Offset(c.x - 16 * u, c.y - 22 * u), Size(24 * u, 24 * u), style = Stroke(4 * u, cap = StrokeCap.Round))
            for (k in 1..2) drawArc(p.accent, -40f, 80f, false, Offset(c.x + 10 * u - k * 8 * u, c.y - 18 * u - k * 8 * u), Size((26 + k * 16) * u, (26 + k * 16) * u), style = Stroke(4 * u, cap = StrokeCap.Round))
        }
        Exercise.Syllables -> { // three wagons
            for (k in 0..2) {
                val x = c.x - 40 * u + k * 28 * u
                path.reset(); path.addRoundRect(androidx.compose.ui.geometry.RoundRect(x, c.y - 14 * u, x + 24 * u, c.y + 10 * u, CornerRadius(5 * u)))
                stickerPath(p, path, if (k == 2) p.sun else p.surface, if (k == 2) p.sunDeep else p.accentDeep, 2 * u)
                drawCircle(p.ink, 4 * u, Offset(x + 6 * u, c.y + 14 * u)); drawCircle(p.ink, 4 * u, Offset(x + 18 * u, c.y + 14 * u))
            }
        }
        Exercise.Words -> { // a picture card with a sun and a hill
            path.reset(); path.addRoundRect(androidx.compose.ui.geometry.RoundRect(c.x - 34 * u, c.y - 28 * u, c.x + 34 * u, c.y + 28 * u, CornerRadius(8 * u)))
            stickerPath(p, path, p.surface, p.accentDeep, 3 * u)
            drawCircle(p.sun, 9 * u, Offset(c.x + 14 * u, c.y - 10 * u))
            drawArc(p.parrot, 180f, 180f, true, Offset(c.x - 28 * u, c.y + 2 * u), Size(40 * u, 40 * u))
            drawRect(p.parrot, Offset(c.x - 28 * u, c.y + 21 * u), Size(56 * u, 5 * u))
        }
        Exercise.Pairs -> { // two cards, a little apart
            rotate(-10f, c) {
                path.reset(); path.addRoundRect(androidx.compose.ui.geometry.RoundRect(c.x - 38 * u, c.y - 26 * u, c.x - 2 * u, c.y + 22 * u, CornerRadius(6 * u)))
                stickerPath(p, path, p.surface, p.accentDeep, 2.5f * u)
            }
            rotate(10f, c) {
                path.reset(); path.addRoundRect(androidx.compose.ui.geometry.RoundRect(c.x + 2 * u, c.y - 26 * u, c.x + 38 * u, c.y + 22 * u, CornerRadius(6 * u)))
                stickerPath(p, path, p.voice, p.voiceDeep, 2.5f * u)
            }
            drawCircle(p.accent, 6 * u, Offset(c.x - 20 * u, c.y - 4 * u)); drawCircle(p.surface, 6 * u, Offset(c.x + 20 * u, c.y))
        }
        Exercise.Sentence -> { // a speech bubble with lines
            path.reset()
            path.addRoundRect(androidx.compose.ui.geometry.RoundRect(c.x - 38 * u, c.y - 26 * u, c.x + 38 * u, c.y + 16 * u, CornerRadius(12 * u)))
            path.moveTo(c.x - 18 * u, c.y + 14 * u); path.lineTo(c.x - 26 * u, c.y + 32 * u); path.lineTo(c.x - 4 * u, c.y + 14 * u); path.close()
            stickerPath(p, path, p.surface, p.accentDeep, 3 * u)
            for (k in 0..2) drawLine(if (k == 1) p.voice else p.inkSecondary, Offset(c.x - 26 * u, c.y - 14 * u + k * 11 * u), Offset(c.x + (if (k == 2) 6f else 26f) * u, c.y - 14 * u + k * 11 * u), 5 * u, StrokeCap.Round)
        }
        Exercise.Twister -> { // the snail (slow) and the rabbit (fast)
            stickerCircle(p, Offset(c.x - 16 * u, c.y + 6 * u), 18 * u, p.sun, p.sunDeep)
            drawArc(p.sunDeep, 0f, 300f, false, Offset(c.x - 26 * u, c.y - 4 * u), Size(20 * u, 20 * u), style = Stroke(3.5f * u, cap = StrokeCap.Round))
            stickerOval(p, Offset(c.x + 8 * u, c.y - 2 * u), Size(30 * u, 22 * u), p.surface, p.inkSecondary)
            stickerOval(p, Offset(c.x + 22 * u, c.y - 34 * u), Size(8 * u, 26 * u), p.surface, p.inkSecondary)
            eye(p, Offset(c.x + 30 * u, c.y + 6 * u), 3 * u)
        }
        Exercise.Retell -> { // an open book with three frames
            path.reset(); path.addRoundRect(androidx.compose.ui.geometry.RoundRect(c.x - 40 * u, c.y - 24 * u, c.x + 40 * u, c.y + 26 * u, CornerRadius(6 * u)))
            stickerPath(p, path, p.surface, p.accentDeep, 3 * u)
            drawLine(p.separator, Offset(c.x, c.y - 22 * u), Offset(c.x, c.y + 24 * u), 2 * u)
            val colors = arrayOf(p.parrot, p.sun, p.voice)
            for (k in 0..2) drawRoundRect(colors[k], Offset(c.x - 34 * u + k * 24 * u, c.y - 12 * u), Size(18 * u, 22 * u), CornerRadius(3 * u))
        }
        Exercise.Mirror -> { // a hand mirror
            drawLine(p.accentDeep, Offset(c.x + 6 * u, c.y + 14 * u), Offset(c.x + 22 * u, c.y + 40 * u), 9 * u, StrokeCap.Round)
            stickerCircle(p, Offset(c.x - 4 * u, c.y - 8 * u), 28 * u, p.fill, p.accentDeep)
            drawArc(p.surface, 210f, 60f, false, Offset(c.x - 24 * u, c.y - 28 * u), Size(40 * u, 40 * u), style = Stroke(5 * u, cap = StrokeCap.Round))
        }
        Exercise.Mouth3D -> { // lips inside a turning frame
            drawArc(p.accent, 200f, 140f, false, Offset(c.x - 40 * u, c.y - 36 * u), Size(80 * u, 72 * u), style = Stroke(4 * u, cap = StrokeCap.Round))
            drawArc(p.accent, 20f, 140f, false, Offset(c.x - 40 * u, c.y - 36 * u), Size(80 * u, 72 * u), style = Stroke(4 * u, cap = StrokeCap.Round))
            path.reset()
            path.moveTo(c.x - 28 * u, c.y); path.cubicTo(c.x - 14 * u, c.y - 18 * u, c.x - 4 * u, c.y - 12 * u, c.x, c.y - 7 * u)
            path.cubicTo(c.x + 4 * u, c.y - 12 * u, c.x + 14 * u, c.y - 18 * u, c.x + 28 * u, c.y)
            path.cubicTo(c.x + 14 * u, c.y + 20 * u, c.x - 14 * u, c.y + 20 * u, c.x - 28 * u, c.y); path.close()
            stickerPath(p, path, p.voice, p.voiceDeep, 3 * u)
            drawLine(p.voiceDeep, Offset(c.x - 22 * u, c.y + 1 * u), Offset(c.x + 22 * u, c.y + 1 * u), 2.5f * u, StrokeCap.Round)
        }
        Exercise.Mission -> { // a star card
            path.reset(); path.addRoundRect(androidx.compose.ui.geometry.RoundRect(c.x - 30 * u, c.y - 36 * u, c.x + 30 * u, c.y + 36 * u, CornerRadius(8 * u)))
            stickerPath(p, path, p.surface, p.sunDeep, 3 * u)
            star(p, Offset(c.x, c.y - 4 * u), 18 * u, path)
        }
        Exercise.Hold -> Unit
    }
}
