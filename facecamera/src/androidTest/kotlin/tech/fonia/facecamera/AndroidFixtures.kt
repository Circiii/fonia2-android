package tech.fonia.facecamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry

val targetContext: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

/** The fixtures from the test APK's assets (`faces/`). */
object AndroidFixtures : Fixtures {
    override fun text(name: String): String = targetContext.assets.open("faces/$name").use { it.readBytes().decodeToString() }

    override fun argb(name: String): Fixtures.Image {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = targetContext.assets.open("faces/$name").use { BitmapFactory.decodeStream(it, null, options) }!!
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Fixtures.Image(bitmap.width, bitmap.height, argb)
    }

    fun reference(name: String) = FaceReference(text("$name.json"))

    /** An upright frame of fixture [name]. */
    internal fun frame(name: String): RgbaFrame {
        val image = argb("$name.png")
        return RgbaFrame().apply { set(image.rgba(), image.width, image.height, image.width * 4, 0, false) }
    }
}

/** One pipeline for the tests in this process, used from one thread at a time. */
internal object TestPipeline {
    val pipeline: FaceLandmarkerPipeline by lazy { FaceLandmarkerPipeline(ModelBundle.load(targetContext)) }
}

/** Results go to logcat under this tag and into PARITY.md. */
fun report(message: String) {
    Log.i("FoniaFaceTest", message)
    println(message)
}
