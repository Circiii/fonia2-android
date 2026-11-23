package tech.fonia.facecamera

import android.content.Context
import android.os.Build
import tech.fonia.face.FaceSource
import tech.fonia.face.FaceSourceKind
import tech.fonia.face.FakeFaceSource
import tech.fonia.face.TrackerFaceSource

/**
 * The camera face source: ARKitFaceSource's control flow ([TrackerFaceSource], from `:face`) over a
 * [CameraFaceTracker]. Its [kind] is [FaceSourceKind.ARKIT]: `:face` has no Android case, and this source follows the
 * ARKit path (blendshapes, head pose, gaze), not the Vision one; only `canDetectTongue` differs.
 *
 * [FaceCameraView] shows its preview and lends it the permission prompt.
 */
class CameraFaceSource internal constructor(
    val tracker: CameraFaceTracker,
) : FaceSource by TrackerFaceSource(tracker, FaceSourceKind.ARKIT) {
    constructor(context: Context) : this(CameraFaceTracker(context))
}

/**
 * `makePreferredFaceSource()` (FoniaFace/FaceSource.swift): the camera where there is one; the scripted face only on
 * an emulator without a front camera, as only the iOS simulator gets it, so a real phone never shows a made-up mouth.
 * A real phone without a front camera gets the camera source, unsupported, and the coach says the camera does not work.
 */
fun makePreferredFaceSource(context: Context): FaceSource {
    val camera = CameraFaceSource(context)
    return if (preferredFaceSourceKind(isEmulator(), camera.isSupported) == FaceSourceKind.FAKE) FakeFaceSource() else camera
}

internal fun preferredFaceSourceKind(isEmulator: Boolean, cameraSupported: Boolean): FaceSourceKind =
    if (isEmulator && !cameraSupported) FaceSourceKind.FAKE else FaceSourceKind.ARKIT

/** The Android Emulator (ranchu, formerly goldfish) or Cuttlefish; never a phone. */
internal fun isEmulator(): Boolean =
    Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" || Build.HARDWARE == "cutf_cvm" || Build.HARDWARE.startsWith("vsoc")
