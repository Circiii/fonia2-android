package tech.fonia.facecamera

import java.io.File
import javax.imageio.ImageIO

/** The fixtures from the source tree (`fonia.facecamera.faces`, set by build.gradle.kts). */
object JvmFixtures : Fixtures {
    private val faces = File(System.getProperty("fonia.facecamera.faces") ?: "src/androidTest/assets/faces")
    val models = File(System.getProperty("fonia.facecamera.models") ?: "src/main/assets/models")

    override fun text(name: String): String = File(faces, name).readText()

    override fun argb(name: String): Fixtures.Image {
        val image = ImageIO.read(File(faces, name)) ?: error("cannot read $name")
        val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        return Fixtures.Image(image.width, image.height, argb)
    }

    fun reference(name: String) = FaceReference(text("$name.json"))

    /** The bundle as the app ships it, parsed (checksum included). */
    internal val bundle: ModelBundle by lazy { ModelBundle.parse(File(models, "face_landmarker.task").readBytes()) }
}
