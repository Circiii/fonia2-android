package tech.fonia.facecamera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import tech.fonia.face.FaceCheck
import tech.fonia.face.FaceFrame
import tech.fonia.face.FaceLevels
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug builds only: the recording tool for recalibrating the face thresholds (PARITY.md). The camera, the values the
 * mouth coach would read, a pose to hold, and a button that writes every frame to a CSV in the app's storage:
 * `adb pull /sdcard/Android/data/<package>/files/face-recordings/`. Numbers only, no image.
 *
 * `adb shell am start -n <package>/tech.fonia.facecamera.FaceRecorderActivity`
 */
class FaceRecorderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val directory = File(getExternalFilesDir(null), "face-recordings")
        val source = CameraFaceSource(this)
        setContent { RecorderScreen(source, directory) }
    }
}

private val LABELS = listOf("neutral") + FaceCheck.entries.map { it.rawValue }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecorderScreen(source: CameraFaceSource, directory: File) {
    val levels = remember { FaceLevels() }
    var frame by remember { mutableStateOf(FaceFrame.ABSENT) }
    var label by remember { mutableStateOf(LABELS.first()) }
    var recording by remember { mutableStateOf<FaceRecording?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var rows by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }
    val currentLabel = remember { arrayOf(LABELS.first()) }

    // The coach polls every 60 ms; so does this screen.
    LaunchedEffect(levels) {
        while (true) {
            frame = levels.snapshot()
            rows = recording?.rowCount ?: rows
            delay(60)
        }
    }
    DisposableEffect(source) {
        onDispose {
            source.tracker.analysisListener = null
            recording?.close()
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color.White).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(14.dp))) {
            FaceCameraView(source, levels, Modifier.fillMaxSize(), onFailure = { failure = it.message ?: it.toString() })
        }
        failure?.let { Line("Camera nu merge acum: $it") }
        Line(
            if (frame.isPresent) {
                "jawOpen %.2f  smile %.2f  funnel %.2f  pucker %.2f  puff %.2f\ncap %.0f° / %.0f°  privire %.0f° / %.0f°".format(
                    Locale.ROOT, frame.jawOpen, frame.smile, frame.funnel, frame.pucker, frame.puff,
                    frame.headYaw, frame.headPitch, frame.gazeYaw, frame.gazePitch,
                )
            } else {
                "Nicio față"
            },
        )
        Line("Poziția ținută:")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in LABELS) {
                Chip(option, selected = option == label) {
                    label = option
                    currentLabel[0] = option
                }
            }
        }
        val active = recording
        Chip(if (active == null) "Înregistrează" else "Oprește ($rows cadre)", selected = active != null) {
            if (active == null) {
                directory.mkdirs()
                val target = File(directory, SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".csv")
                val writer = FaceRecording(target)
                source.tracker.analysisListener = { analysis -> writer.write(currentLabel[0], analysis) }
                recording = writer
                file = target
            } else {
                source.tracker.analysisListener = null
                active.close()
                recording = null
            }
        }
        file?.let { Line("Fișier: ${it.absolutePath}") }
    }
}

@Composable
private fun Line(text: String) {
    BasicText(text, style = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF1C1C1E)))
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF2F6FEB) else Color(0xFFE9E9EF))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        BasicText(text, style = TextStyle(fontSize = 14.sp, color = if (selected) Color.White else Color(0xFF1C1C1E)))
    }
}
