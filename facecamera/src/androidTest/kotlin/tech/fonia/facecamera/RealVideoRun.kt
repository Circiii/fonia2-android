package tech.fonia.facecamera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.fonia.face.CameraEvidence
import tech.fonia.face.FaceMeshFrame
import tech.fonia.face.MouthState
import tech.fonia.face.MouthStateEngine
import tech.fonia.face.PoseSpec
import tech.fonia.face.TargetMatcher
import tech.fonia.model.SoundId
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Debug harness, not a regression test: runs real video clips through the real pipeline (detector → mesh →
 * blendshapes → [MeshFrameFiller] → [MouthStateEngine]) frame by frame and writes one JSON line per frame.
 *
 * The clips are pushed by hand to `/data/local/tmp/face_videos/` (`.mp4` files) (copied into the test's cache for decoding,
 * deleted afterwards); results go to the test app's external files dir, `face_runs/<clip>.jsonl`, to be pulled with
 * adb. Without clips the test is skipped. No Activity, no UI: safe on a shared emulator.
 */
@RunWith(AndroidJUnit4::class)
class RealVideoRun {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
    }

    private fun copyFromTmp(path: String, to: File) {
        val pfd = instrumentation.uiAutomation.executeShellCommand("cat $path")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> FileOutputStream(to).use { input.copyTo(it) } }
    }

    @Test
    fun runClips() {
        val dir = InstrumentationRegistry.getArguments().getString("clipsDir") ?: "/data/local/tmp/face_videos"
        val only = InstrumentationRegistry.getArguments().getString("clip")
        val clips = shell("ls $dir").lines().map { it.trim() }.filter { it.endsWith(".mp4") && (only == null || it.startsWith(only)) }
        assumeTrue("no clips in $dir", clips.isNotEmpty())
        val outDir = File(targetContext.getExternalFilesDir(null), "face_runs").apply { mkdirs() }
        val pipeline = TestPipeline.pipeline
        for (clip in clips) runClip(pipeline, "$dir/$clip", clip.removeSuffix(".mp4"), outDir)
    }

    private fun runClip(pipeline: FaceLandmarkerPipeline, path: String, name: String, outDir: File) {
        val local = File(targetContext.cacheDir, "clip.mp4")
        copyFromTmp(path, local)
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(local.absolutePath)
        val frames = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)!!.toInt()
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        val fps = frames * 1000.0 / durationMs
        val engine = MouthStateEngine()
        engine.irisMm = 11.7f // mostly adults in the clips
        val filler = MeshFrameFiller()
        val mesh = FaceMeshFrame()
        val state = MouthState()
        val matchers = listOf(SoundId.S, SoundId.Sh, SoundId.R).associateWith { TargetMatcher(PoseSpec.bundled(it)) }
        val evidence = CameraEvidence()
        val target = FloatArray(40)
        val targetInner = FloatArray(40)
        val rgba = RgbaFrame()
        var pixels = ByteArray(0)
        val cropDir = File(outDir, "${name}_crops").apply { mkdirs() }
        // The mirror as a screen would draw it: MouthTracker + the overlay's own drawing code, off screen.
        val overlaySound = OVERLAY_SOUNDS.entries.firstOrNull { name.startsWith(it.key) }?.value
        val mouth = MouthTracker(CameraFaceTracker(targetContext)).apply { sound = overlaySound }
        val holder = OverlayHolder()
        val overlayDir = File(outDir, "${name}_overlay").apply { mkdirs() }
        pipeline.reset()
        File(outDir, "$name.jsonl").bufferedWriter().use { out ->
            var i = 0
            val batch = 10
            while (i < frames) {
                val count = minOf(batch, frames - i)
                val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
                val bitmaps = retriever.getFramesAtIndex(i, count, params)
                for ((k, bitmap) in bitmaps.withIndex()) {
                    val index = i + k
                    val bytes = bitmap.byteCount
                    if (pixels.size < bytes) pixels = ByteArray(bytes)
                    bitmap.copyPixelsToBuffer(ByteBuffer.wrap(pixels))
                    rgba.set(pixels, bitmap.width, bitmap.height, bitmap.rowBytes, 0, false)
                    val tNanos = (index * 1e9 / fps).toLong()
                    val found = pipeline.process(rgba, tNanos / 1000, stream = true)
                    val modelNanos = pipeline.timings[4]
                    val e0 = System.nanoTime()
                    filler.fill(pipeline, rgba, tNanos, found, mesh)
                    val e1 = System.nanoTime()
                    engine.process(mesh, state)
                    val engineNanos = System.nanoTime() - e1
                    mouth.process(mesh)
                    holder.advance(mouth.levels, tNanos + 1, reducedMotion = false)
                    if (index % 30 == 0 && index > 0) renderOverlay(bitmap, holder, tNanos + 1, File(overlayDir, String.format(Locale.ROOT, "%05d.png", index)))
                    val fillNanos = e1 - e0
                    val sb = StringBuilder(4096)
                    sb.append("{\"i\":").append(index).append(",\"t\":").append(tNanos / 1_000_000)
                        .append(",\"w\":").append(bitmap.width).append(",\"h\":").append(bitmap.height)
                        .append(",\"found\":").append(found).append(",\"modelMs\":").append(f(modelNanos / 1e6f))
                        .append(",\"engineUs\":").append(f(engineNanos / 1e3f)).append(",\"fillUs\":").append(f(fillNanos / 1e3f))
                    if (found) {
                        sb.append(",\"detected\":").append(pipeline.detected)
                            .append(",\"presence\":").append(f(mesh.presence)).append(",\"tongueModel\":").append(f(mesh.tongueModelScore))
                            .append(",\"yaw\":").append(f(mesh.headYaw)).append(",\"pitch\":").append(f(mesh.headPitch))
                            .append(",\"roll\":").append(f(mesh.headRoll)).append(",\"dist\":").append(f(mesh.distanceM))
                            .append(",\"lumaMouth\":").append(f(mesh.lumaMouth)).append(",\"lumaFace\":").append(f(mesh.lumaFace))
                            .append(",\"clipped\":").append(f(mesh.clippedShare))
                        sb.append(",\"outer\":"); xy(sb, mesh.lipsOuter)
                        sb.append(",\"inner\":"); xy(sb, mesh.lipsInner)
                        sb.append(",\"anchors\":"); xy(sb, mesh.anchors)
                        sb.append(",\"irises\":"); xy(sb, mesh.irises)
                        sb.append(",\"rawOuter\":"); rawRing(sb, pipeline.rawLandmarks, tech.fonia.face.FaceMeshIndices.LIPS_OUTER)
                        sb.append(",\"rawInner\":"); rawRing(sb, pipeline.rawLandmarks, tech.fonia.face.FaceMeshIndices.LIPS_INNER)
                        val bs = mesh.blendshapes
                        sb.append(",\"bs\":{")
                        for ((n, idx) in listOf(
                            "jawOpen" to 25, "close" to 27, "funnel" to 32, "pucker" to 38, "smileL" to 44, "smileR" to 45,
                            "stretchL" to 46, "stretchR" to 47, "rollLower" to 40, "rollUpper" to 41,
                        )) sb.append('"').append(n).append("\":").append(f(bs[idx])).append(',')
                        sb.setLength(sb.length - 1)
                        sb.append('}')
                        val m = engine.measures
                        sb.append(",\"m\":{\"apertureRel\":").append(f(m.apertureRel)).append(",\"widthRel\":").append(f(m.widthRel))
                            .append(",\"jawRel\":").append(f(m.jawRel)).append(",\"eyePx\":").append(f(m.eyePx))
                            .append(",\"irisPx\":").append(f(m.irisPx)).append(",\"mouthPx\":").append(f(m.widthPx))
                            .append(",\"areaRel\":").append(f(m.innerAreaRel)).append(",\"band\":").append(f(m.lowerBandRel / engine.baseline.lowerBandRel)).append(",\"drop\":").append(f(m.mouthDropRel)).append('}')
                        if (engine.targetRings(mesh, PoseSpec.bundled(SoundId.Sh).lips, target, targetInner)) {
                            sb.append(",\"targetSh\":"); xy2(sb, target)
                        }
                    }
                    sb.append(",\"s\":{\"q\":\"").append(state.quality).append("\",\"issue\":\"").append(state.issue.key)
                        .append("\",\"rest\":\"").append(state.rest).append("\",\"aperture\":").append(f(state.aperture))
                        .append(",\"width\":").append(f(state.width)).append(",\"jaw\":").append(f(state.jaw))
                        .append(",\"open\":").append(f(state.open)).append(",\"round\":").append(f(state.round))
                        .append(",\"spread\":").append(f(state.spread)).append(",\"closed\":").append(state.lipsClosed)
                        .append(",\"smile\":").append(state.smile).append(",\"tongue\":\"").append(state.tongue)
                        .append("\",\"apMm\":").append(f(state.apertureMm)).append(",\"wMm\":").append(f(state.widthMm))
                        .append(",\"confGeom\":").append(f(state.confGeom)).append(",\"tDelta\":").append(f(state.tongueColourDelta))
                        .append(",\"rawTongue\":\"").append(engine.tongue.rawState).append("\",\"sep\":").append(f(engine.tongue.separation))
                        .append(",\"blob\":").append(f(engine.tongue.blobAreaW0)).append(",\"deltas\":[")
                    for (z in 0 until 5) sb.append(f(engine.tongue.delta[z])).append(if (z < 4) "," else "")
                    sb.append("]}")
                    sb.append(",\"ev\":{")
                    for ((sound, matcher) in matchers) {
                        matcher.update(state, evidence)
                        sb.append('"').append(sound.name).append("\":{\"status\":\"").append(evidence.status)
                            .append("\",\"m\":").append(f(evidence.match)).append(",\"advice\":\"").append(evidence.advice?.key ?: "")
                            .append("\"},")
                    }
                    sb.setLength(sb.length - 1)
                    sb.append("}}")
                    out.write(sb.toString())
                    out.newLine()
                    if (index % 15 == 0 && mesh.hasCrop) saveCrop(mesh, File(cropDir, String.format(Locale.ROOT, "%05d.png", index)))
                    bitmap.recycle()
                }
                i += count
            }
        }
        retriever.release()
        local.delete()
        report("$name: $frames frames at %.1f fps".format(Locale.ROOT, fps))
    }

    private fun renderOverlay(frame: Bitmap, holder: OverlayHolder, now: Long, file: File) {
        val w = 360
        val h = 640
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val scale = maxOf(w.toFloat() / frame.width, h.toFloat() / frame.height)
        val dx = (w - frame.width * scale) / 2f
        val dy = (h - frame.height * scale) / 2f
        val m = Matrix().apply {
            setScale(-scale, scale) // mirrored, FILL_CENTER: what PreviewView shows
            postTranslate(dx + frame.width * scale, dy)
        }
        canvas.drawBitmap(frame, m, null)
        CanvasDrawScope().draw(Density(2f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(canvas), Size(w.toFloat(), h.toFloat())) {
            holder.draw(this, COLORS, showChildLips = true, reducedMotion = false, now = now)
        }
        FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        out.recycle()
    }

    private fun saveCrop(mesh: FaceMeshFrame, file: File) {
        val n = FaceMeshFrame.CROP
        val argb = IntArray(n * n)
        for (p in 0 until n * n) {
            val r = (mesh.mouthCrop[p * 3] * 255).toInt().coerceIn(0, 255)
            val g = (mesh.mouthCrop[p * 3 + 1] * 255).toInt().coerceIn(0, 255)
            val b = (mesh.mouthCrop[p * 3 + 2] * 255).toInt().coerceIn(0, 255)
            argb[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bitmap = Bitmap.createBitmap(argb, n, n, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        val OVERLAY_SOUNDS = mapOf("clip01" to SoundId.S, "clip02" to SoundId.Sh, "clip03" to SoundId.Sh, "clip04" to SoundId.R,
            "clip05" to SoundId.L, "clip06" to SoundId.R, "clip07" to SoundId.Sh, "clip08" to SoundId.S)
        val COLORS = MirrorColors(
            target = Color(0xFF3D8BFF), matched = Color(0xFF3CC46B), childLips = Color.White, neutral = Color(0xFF9AA0A6),
            hintBackground = Color(0x8C000000), hintText = Color.White,
        )
    }

    private fun f(x: Float): String = if (x.isNaN()) "null" else String.format(Locale.ROOT, "%.5f", x)

    private fun xy(sb: StringBuilder, a: FloatArray) {
        sb.append('[')
        for (k in 0 until a.size / 3) sb.append(f(a[k * 3])).append(',').append(f(a[k * 3 + 1])).append(',')
        sb.setLength(sb.length - 1)
        sb.append(']')
    }

    private fun xy2(sb: StringBuilder, a: FloatArray) {
        sb.append('[')
        for (v in a) sb.append(f(v)).append(',')
        sb.setLength(sb.length - 1)
        sb.append(']')
    }

    private fun rawRing(sb: StringBuilder, all: FloatArray, ring: IntArray) {
        sb.append('[')
        for (i in ring) sb.append(f(all[i * 3])).append(',').append(f(all[i * 3 + 1])).append(',')
        sb.setLength(sb.length - 1)
        sb.append(']')
    }
}
