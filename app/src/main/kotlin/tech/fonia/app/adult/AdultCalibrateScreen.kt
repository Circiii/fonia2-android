package tech.fonia.app.adult

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import tech.fonia.app.AppGraph
import tech.fonia.app.data.AttemptView
import tech.fonia.app.data.CalibrationBook
import tech.fonia.app.text.T
import tech.fonia.designsystem.components.QuietButton
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaSpace
import tech.fonia.live.PromptRef
import tech.fonia.model.SoundId
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** One kept take with its attempt row and its file. */
private data class KeptTake(val attempt: AttemptView, val file: File)

/**
 * ADULT-CALIBRATE (chapter 01 §9, plan 03 §6): the adult listens to the child's last takes (at most 3 per sound are
 * kept) through the audio director — which never plays while the microphone is open — and marks each one. A 👍 adds
 * the take's measurements as an anchor; after [CalibrationBook.CALIBRATED_AT] confirmed takes the sound is
 * „calibrat pe copil”. Thumbs from the lessons count too.
 */
@Composable
fun AdultCalibrateScreen(graph: AppGraph, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var takes by remember { mutableStateOf<List<KeptTake>?>(null) }
    var book by remember { mutableStateOf(CalibrationBook.Empty) }
    var playing by remember { mutableStateOf<UUID?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        takes = runCatching {
            val attempts = graph.attempts.allAttempts().filter { it.recordingId != null }.associateBy { it.recordingId }
            graph.attempts.recordings().mapNotNull { (rec, file) -> attempts[rec.id]?.let { KeptTake(it, file) } }
        }.getOrElse { Log.e("FoniaCalibrate", "load", it); emptyList() }
        book = graph.calibration.refresh()
    }

    AdultPage(T.calTitle, onBack, graph.settings.reduceMotion) {
        AdultCard {
            AdultText(T.calWhy)
            AdultCaption(T.calHow)
        }
        val list = takes ?: return@AdultPage
        if (list.isEmpty()) AdultText(T.calNone, secondary = true)
        val bySound = list.groupBy { it.attempt.sound }.toSortedMap(compareBy { it?.ordinal ?: 99 })
        for ((sound, kept) in bySound) {
            if (sound == null) continue
            SoundHeader(sound, book)
            for (k in kept) {
                TakeRow(
                    k, playing == k.attempt.id,
                    onPlay = {
                        scope.launch {
                            playing = k.attempt.id
                            runCatching { graph.audio.play(PromptRef.File(k.file.path)) }
                            playing = null
                        }
                    },
                    onThumb = { good ->
                        scope.launch {
                            runCatching { graph.attempts.adult(k.attempt.id, good) }
                            reload++
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SoundHeader(sound: SoundId, book: CalibrationBook) {
    val c = FoniaTheme.colors
    val cal = book.forSound(sound)
    Row(Modifier.fillMaxWidth().padding(top = FoniaSpace.m, bottom = FoniaSpace.xxs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FoniaSpace.s)) {
        BasicText(sound.letter, style = FoniaTheme.type.title.copy(color = c.accentDeep))
        BasicText(
            if (cal.tier >= 1) T.adultCalibrated else T.adultCalibrating(cal.confirmedGood.coerceAtMost(CalibrationBook.CALIBRATED_AT), CalibrationBook.CALIBRATED_AT),
            style = FoniaTheme.type.label.copy(color = if (cal.tier >= 1) c.parrotDeep else c.inkSecondary),
        )
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("d.MM, HH:mm").withZone(ZoneId.systemDefault())

@Composable
private fun TakeRow(k: KeptTake, playing: Boolean, onPlay: () -> Unit, onThumb: (Boolean) -> Unit) {
    val a = k.attempt
    AdultCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FoniaSpace.xxs)) {
                AdultHeading(a.text.ifEmpty { a.sound?.letter ?: "" })
                AdultCaption(timeFmt.format(a.ts) + " · " + T.calPhone(a.verdict) + (a.adultGood?.let { " · " + T.calAnswered(it) } ?: ""))
            }
            QuietButton(if (playing) "▶ …" else T.calPlay, onClick = onPlay, glyph = if (playing) null else "▶")
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(FoniaSpace.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(FoniaSpace.s)) {
            QuietButton(T.calGood, onClick = { onThumb(true) }, emphasised = a.adultGood == true, modifier = Modifier.weight(1f))
            QuietButton(T.calNotYet, onClick = { onThumb(false) }, emphasised = a.adultGood == false, modifier = Modifier.weight(1f))
        }
    }
}
