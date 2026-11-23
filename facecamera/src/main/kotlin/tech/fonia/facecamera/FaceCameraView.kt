package tech.fonia.facecamera

import android.Manifest
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import tech.fonia.face.FaceLevels
import tech.fonia.face.FaceSource
import kotlin.math.min

/**
 * `FaceCameraView` (FoniaFace/FaceCameraView.swift): the live, mirrored camera for whichever source is given. Sources
 * with no camera, such as the scripted one on an emulator, show a static placeholder.
 *
 * It owns the camera's lifetime: it starts the source when it enters the composition and stops it when it leaves,
 * so the camera is off whenever the mirror is not on screen. A view that leaves before its start ran never turns the
 * camera on (the start is the composition's effect, cancelled with it). The app going to the background closes the
 * camera on its own ([CameraFaceTracker]).
 *
 * @param onFailure called if the camera can't start, for instance with no permission.
 */
@Composable
fun FaceCameraView(
    source: FaceSource,
    levels: FaceLevels,
    modifier: Modifier = Modifier,
    onFailure: ((Throwable) -> Unit)? = null,
) {
    val camera = source as? CameraFaceSource
    val currentOnFailure by rememberUpdatedState(onFailure)

    if (camera != null) {
        val tracker = camera.tracker
        val lifecycleOwner = LocalLifecycleOwner.current
        // The permission prompt needs an Activity; the view lends its own while it is on screen.
        val registryOwner = LocalActivityResultRegistryOwner.current
        val pending = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
        val launcher = if (registryOwner != null) {
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                pending[0]?.complete(granted)
                pending[0] = null
            }
        } else {
            null
        }
        DisposableEffect(tracker, lifecycleOwner, launcher) {
            tracker.hostLifecycle = lifecycleOwner.lifecycle
            tracker.permissionPrompt = launcher?.let {
                suspend {
                    val answer = CompletableDeferred<Boolean>()
                    pending[0] = answer
                    it.launch(Manifest.permission.CAMERA)
                    answer.await()
                }
            }
            onDispose {
                tracker.permissionPrompt = null
                tracker.hostLifecycle = null
            }
        }
    }

    Box(modifier) {
        if (camera != null && camera.isSupported) {
            AndroidView(
                factory = { context ->
                    PreviewView(context).apply {
                        // A TextureView, so the coach's rounded corners clip it; filled like resizeAspectFill.
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                modifier = Modifier.fillMaxSize().testTag(CAMERA_PREVIEW_TAG),
                update = { view -> camera.tracker.previewSurfaceProvider = view.surfaceProvider },
                onRelease = { camera.tracker.previewSurfaceProvider = null },
            )
        } else {
            FacePlaceholder(Modifier.fillMaxSize().testTag(PLACEHOLDER_TAG))
        }
    }

    DisposableEffect(source, levels) {
        onDispose { source.stop() }
    }
    LaunchedEffect(source, levels) {
        try {
            source.start(levels)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            currentOnFailure?.invoke(e)
        }
    }
}

const val CAMERA_PREVIEW_TAG = "face.camera"
const val PLACEHOLDER_TAG = "face.placeholder"

/** `UIImage(systemName: "face.smiling")`, aspect fit, `.secondaryLabel` on `.secondarySystemBackground`. */
@Composable
private fun FacePlaceholder(modifier: Modifier) {
    Canvas(modifier.background(SECONDARY_SYSTEM_BACKGROUND)) {
        val side = min(size.width, size.height)
        val stroke = side * 0.07f
        val radius = side / 2 - stroke
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(SECONDARY_LABEL, radius, center, style = Stroke(stroke))
        val eye = Size(side * 0.075f, side * 0.15f)
        for (dx in floatArrayOf(-0.17f, 0.17f)) {
            drawOval(SECONDARY_LABEL, Offset(center.x + dx * side - eye.width / 2, center.y - side * 0.2f), eye)
        }
        val smile = side * 0.52f
        drawArc(
            SECONDARY_LABEL, startAngle = 25f, sweepAngle = 130f, useCenter = false,
            topLeft = Offset(center.x - smile / 2, center.y - smile * 0.62f), size = Size(smile, smile),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/** iOS light-mode system colours: `.secondaryLabel` is #3C3C43 at 60 %, `.secondarySystemBackground` #F2F2F7. */
private val SECONDARY_LABEL = Color(0x993C3C43)
private val SECONDARY_SYSTEM_BACKGROUND = Color(0xFFF2F2F7)
