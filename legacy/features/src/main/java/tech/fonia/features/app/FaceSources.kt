package tech.fonia.features.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tech.fonia.design.FoniaIcon
import tech.fonia.design.TemplateImage
import tech.fonia.face.FaceFrame
import tech.fonia.face.FaceLevels
import tech.fonia.face.FaceSource
import tech.fonia.face.FakeFaceSource
import tech.fonia.facecamera.CameraFaceSource
import tech.fonia.facecamera.FaceCameraView
import kotlin.time.Duration.Companion.seconds

/**
 * The one place the camera plugs in: `makePreferredFaceSource()` and `FaceCameraView` from FoniaFace.
 *
 * On iOS ARKit runs where it can, Vision otherwise, and only the simulator gets the scripted mouth. On Android the
 * camera source is :facecamera (the Face Landmarker models on LiteRT); like the iOS simulator, only an emulator without
 * a front camera gets the scripted mouth of [FakeFaceSource].
 */
object FaceCamera {
    fun source(context: Context): FaceSource = tech.fonia.facecamera.makePreferredFaceSource(context)

    /**
     * The live, mirrored camera of [source]. It owns the camera's lifetime: the source starts when the view enters the
     * screen and stops when it leaves, so the camera is off whenever the mirror isn't shown. A source that can't start
     * reports to [onFailure].
     */
    @Composable
    fun View(source: FaceSource, levels: FaceLevels, modifier: Modifier = Modifier, onFailure: (Throwable) -> Unit = {}) =
        if (source is CameraFaceSource) FaceCameraView(source, levels, modifier, onFailure)
        else ScriptedFaceView(source, levels, modifier, onFailure)
}

/** The source the mouth coach gets: a held face when `foniaFace` names one (debug builds), else [FaceCamera.source]. */
fun makePreferredFaceSource(context: Context, arguments: LaunchArguments): FaceSource =
    arguments.face?.let { heldFace(it) } ?: FaceCamera.source(context)

/** `smile=0.8,jawOpen=0.1`: a face that holds these values, the others at rest. Null when nothing could be read. */
internal fun heldFace(text: String): FaceSource? {
    var frame = FaceFrame()
    var read = false
    for (pair in text.split(',')) {
        val (name, value) = pair.split('=').map { it.trim() }.takeIf { it.size == 2 } ?: continue
        val number = value.toFloatOrNull() ?: continue
        frame = when (name) {
            "jawOpen" -> frame.copy(jawOpen = number)
            "smile" -> frame.copy(smile = number)
            "funnel" -> frame.copy(funnel = number)
            "pucker" -> frame.copy(pucker = number)
            "puff" -> frame.copy(puff = number)
            "tongueOut" -> frame.copy(tongueOut = number)
            else -> continue
        }
        read = true
    }
    if (!read) return null
    return FakeFaceSource(listOf(FakeFaceSource.Step(frame, hold = 3600.seconds)))
}

/**
 * `FaceCameraView` for a source with no camera (the scripted one): it starts and stops the source like the camera view
 * does, and shows the placeholder iOS shows (`face.smiling` on the secondary background).
 */
@Composable
private fun ScriptedFaceView(source: FaceSource, levels: FaceLevels, modifier: Modifier, onFailure: (Throwable) -> Unit) {
    val scope = rememberCoroutineScope()
    val failed by rememberUpdatedState(onFailure)
    DisposableEffect(source, levels) {
        val starting = scope.launch {
            try {
                source.start(levels)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed(error)
            }
        }
        onDispose {
            // A view that left before the source started must not turn it on: stop() also tells the source.
            starting.cancel()
            source.stop()
        }
    }
    Box(modifier.background(SystemColors.secondarySystemBackground), contentAlignment = Alignment.Center) {
        TemplateImage(FoniaIcon.mouth.rawValue, Modifier.fillMaxSize().padding(10.dp), tint = SystemColors.secondaryLabel)
    }
}
