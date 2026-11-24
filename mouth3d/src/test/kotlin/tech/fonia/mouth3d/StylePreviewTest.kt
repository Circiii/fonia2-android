package tech.fonia.mouth3d

import org.junit.Assume.assumeTrue
import org.junit.Test
import tech.fonia.mouth3d.style.MeshBuilder
import tech.fonia.mouth3d.style.HeadModel
import tech.fonia.mouth3d.style.SagittalShape
import tech.fonia.mouth3d.style.StylePalette
import tech.fonia.mouth3d.style.StyleScene
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.io.DataOutputStream
import javax.imageio.ImageIO

/**
 * Offline renders of the 3D mouth through the software copy of the shader ([StylePreview]). Runs only with
 * `-Pmouth3d.previewDir=<dir>`: writes `sheet.png` (per sound: front, 45°, profile, cut-away) and one PNG per view;
 * `-Pmouth3d.only=s,sh` limits the sounds; `-Pmouth3d.clip=rotate|front` writes the frames of a clip into
 * `<dir>/clip_<kind>/`.
 */
class StylePreviewTest {
    private val dir = System.getProperty("mouth3d.previewDir").orEmpty()

    @Test
    fun renderSheet() {
        assumeTrue(dir.isNotEmpty())
        val lib = TestData.library
        val only = System.getProperty("mouth3d.only").orEmpty()
        val keys = PoseLibrary.FILES.filter { only.isEmpty() || it in only.split(',') }
        val cw = 400; val ch = 250; val lab = 26
        val views = listOf("față" to View(0f, 0f), "45°" to View(45f, 0f), "profil" to View(90f, 0f), "secțiune" to View(90f, 1f))
        val sheet = BufferedImage(cw * views.size, (ch + lab) * keys.size, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0xFFFFFF); g.fillRect(0, 0, sheet.width, sheet.height)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 17)
        File(dir).mkdirs()
        keys.forEachIndexed { r, key ->
            val spec = lib.specs.getValue(key)
            views.forEachIndexed { c, (name, v) ->
                val img = still(spec, lib.rest, v, cw, ch, time = 1.3f, highlight = null)
                ImageIO.write(img, "png", File(dir, "${key}_${c}.png"))
                g.drawImage(img, c * cw, r * (ch + lab) + lab, null)
                g.color = Color(0x14254A)
                g.drawString(label(spec) + " · " + name, c * cw + 8, r * (ch + lab) + 19)
            }
        }
        ImageIO.write(sheet, "png", File(dir, "sheet.png"))
        for (k in listOf("ce", "ge").filter { it in keys }) {
            val spec = lib.specs.getValue(k)
            ImageIO.write(still(spec, lib.rest, View(45f, 0f), 900, 560, 1.3f, null), "png", File(dir, "${k}_45_big.png"))
        }
        // closures, highlights and the in-between angles, for a closer look
        for (k in listOf("ts", "ce", "l")) if (k in keys) ImageIO.write(still(lib.specs.getValue(k), lib.rest, View(90f, 1f), cw, ch, 0.45f, null), "png", File(dir, "${k}_closure.png"))
        if ("s" in keys) {
            for (a in Articulator.entries) {
                ImageIO.write(still(lib.specs.getValue("s"), lib.rest, View(0f, 0f), cw, ch, 1.3f, a), "png", File(dir, "s_front_${a.name}.png"))
                ImageIO.write(still(lib.specs.getValue("s"), lib.rest, View(90f, 1f), cw, ch, 1.3f, a), "png", File(dir, "s_cut_${a.name}.png"))
            }
            for (y in listOf(20f, 65f, 100f, -45f)) ImageIO.write(still(lib.specs.getValue("s"), lib.rest, View(y, 0f), cw, ch, 1.3f, null), "png", File(dir, "s_yaw_${y.toInt()}.png"))
            for (c in listOf(0.3f, 0.6f)) ImageIO.write(still(lib.specs.getValue("s"), lib.rest, View(90f, c), cw, ch, 1.3f, null), "png", File(dir, "s_cut_${(c * 100).toInt()}.png"))
            ImageIO.write(still(lib.specs.getValue("s"), lib.rest, View(30f, 0f, pitch = 22f), cw, ch, 1.3f, null), "png", File(dir, "s_pitch_up.png"))
            ImageIO.write(still(lib.specs.getValue("s"), lib.rest, View(90f, 1f), 900, 560, 1.3f, null), "png", File(dir, "s_cut_big.png"))
            ImageIO.write(still(lib.specs.getValue("s"), lib.rest, View(60f, 1f, pitch = -10f), 900, 560, 1.3f, null), "png", File(dir, "s_cut_60deg.png"))
            ImageIO.write(still(lib.specs.getValue("s"), lib.rest, View(70f, 0f, pitch = -22f), cw, ch, 1.3f, null), "png", File(dir, "s_pitch_down.png"))
        }
    }

    @Test
    fun renderClip() {
        val kind = System.getProperty("mouth3d.clip").orEmpty()
        assumeTrue(dir.isNotEmpty() && kind.isNotEmpty())
        val lib = TestData.library
        val out = File(dir, "clip_$kind").also { it.deleteRecursively(); it.mkdirs() }
        val fps = 30
        var n = 0
        fun shot(img: BufferedImage) = ImageIO.write(img, "png", File(out, "f%04d.png".format(n++)))
        if (kind == "rotate") {
            // drag round from the front to the profile, the cut opens, the air flows, then back
            var time = 0f
            fun ease(t: Float) = t * t * (3 - 2 * t)
            for (key in listOf("s", "sh")) {
                val spec = lib.specs.getValue(key)
                val scene = MouthScene()
                val inp = SceneInputs(spec, lib.rest, progress = 1f, inside = false)
                var f = scene.step(0f, inp)
                repeat(60) { f = scene.step(1f / 60, inp) }
                fun frame(yaw: Float, cut: Float) {
                    f = scene.step(1f / fps, inp)
                    time += 1f / fps
                    shot(render(SceneFrame(f.pose, yaw, 0f, cut, 1f, true, f.timeSec, f.closure, f.sinceRelease, f.breath, false, cut), spec, 720, 450, null))
                }
                repeat(15) { frame(0f, 0f) }
                repeat(60) { frame(90f * ease(it / 59f), 0f) }
                repeat(30) { frame(90f, ease(it / 29f)) }
                repeat(75) { frame(90f, 1f) }
                repeat(24) { frame(90f, 1f - ease(it / 23f)) }
                repeat(45) { frame(90f * (1f - ease(it / 44f)), 0f) }
            }
        } else {
            val scene = MouthScene()
            for (key in listOf("s", "sh", "r", "l", "ts")) {
                val spec = lib.specs.getValue(key)
                val base = SceneInputs(spec, lib.rest, progress = 0f, inside = false)
                repeat(15) { shot(render(scene.step(1f / fps, base), spec, 720, 450, null)) }
                repeat(48) { shot(render(scene.step(1f / fps, base.copy(progress = 1f)), spec, 720, 450, null)) }
            }
        }
    }

    @Test
    fun timeBuild() {
        assumeTrue(dir.isNotEmpty())
        val lib = TestData.library
        val mb = MeshBuilder()
        val sb = StringBuilder()
        for (cut in listOf(0f, 1f)) {
            val scene = MouthScene()
            val inp = SceneInputs(lib.specs.getValue("ts"), lib.rest, progress = 1f, inside = false)
            var f = scene.step(0f, inp)
            fun once() { f = scene.step(1f / 60, inp); val g = SceneFrame(f.pose, 90f * cut, 0f, cut, 1f, true, f.timeSec, f.closure, f.sinceRelease, f.breath, false, cut); StyleScene.build(mb, g, inp.pose, GUIDE, null, StyleScene.camera(g, 900, 560)) }
            repeat(200) { once() }
            val t0 = System.nanoTime()
            repeat(300) { once() }
            sb.append("cut=$cut build %.3f ms, %d vertices, %d triangles, %d batches%n".format((System.nanoTime() - t0) / 300e6, mb.vertexCount, mb.indexCount / 3, mb.batches.size))
        }
        File(dir, "timing.txt").writeText(sb.toString())
    }

    /** A stable indexed copy of exactly the app mesh, without camera, cut, particles or highlights. */
    @Test
    fun exportMesh() {
        assumeTrue(dir.isNotEmpty() && System.getProperty("mouth3d.mesh") == "true")
        val library = TestData.library
        val folder = File(dir, "mesh").also { it.mkdirs() }
        fun write(name: String, pose: Pose) {
            val mb = MeshBuilder()
            val range = HeadModel.Ranges()
            HeadModel.build(mb, pose, SagittalShape.of(pose), GUIDE, HeadModel.Glow(0f, 0f, 0f), 0f, range)
            DataOutputStream(File(folder, "$name.mouth").outputStream().buffered()).use { out ->
                out.writeInt(mb.vertexCount)
                out.writeInt(range.head1)
                out.writeInt(range.teeth1)
                out.writeInt(range.tongue1)
                for (i in 0 until mb.vertexCount * MeshBuilder.STRIDE) out.writeFloat(mb.data[i])
                for (i in 0 until range.tongue1) out.writeInt(mb.indices[i])
            }
        }
        library.specs.forEach { (name, spec) ->
            write(name, spec.pose)
            spec.animation?.keys?.firstOrNull()?.let { write("${name}_closure", it.pose) }
        }
    }

    data class View(val yaw: Float, val cut: Float, val pitch: Float? = null)

    companion object {
        val GUIDE: StylePalette = StylePalette.of(
            0xE6B59C, 0xC58078, 0xF7EFDF, 0xBE7C75, 0xD38480, 0x58312F,
            0xE8BAA9, 0xFFE5CB, 0x6EC3FF, 0xFF9A3D, 0xFFB020, 0xFFE066,
        )
        const val FACE = 0xEEE8FA

        fun label(spec: PoseSpec) = spec.sound?.letter ?: "Repaus"

        /** The pose settled for [time] seconds (animation, flutter, air), seen from [v]. */
        fun still(spec: PoseSpec, rest: PoseSpec, v: View, w: Int, h: Int, time: Float, highlight: Articulator?): BufferedImage {
            val scene = MouthScene()
            val inp = SceneInputs(spec, rest, progress = 1f, inside = false, playing = true, reduceMotion = false, highlight = highlight)
            var f = scene.step(0f, inp)
            repeat((time * 60).toInt()) { f = scene.step(1f / 60, inp) }
            val pitch = v.pitch ?: (if (v.yaw == 0f) spec.front.pitchDeg else 0f)
            val g = SceneFrame(f.pose, v.yaw, pitch, v.cut, 1f, true, f.timeSec, f.closure, f.sinceRelease, f.breath, false, v.cut)
            return render(g, spec, w, h, highlight)
        }

        fun render(f: SceneFrame, spec: PoseSpec, w: Int, h: Int, highlight: Articulator?): BufferedImage {
            val cam = StyleScene.camera(f, w, h)
            val mb = MeshBuilder()
            StyleScene.build(mb, f, spec, GUIDE, highlight, cam)
            return StylePreview.render(mb, cam, w, h, GUIDE.highlight, GUIDE.rim, GUIDE.lips, FACE)
        }
    }
}
