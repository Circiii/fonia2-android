package tech.fonia.audio

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** On a device the fixtures are assets of the test APK, copied once to the cache so they have file paths. */
object FixturesLocator {
    val directory: File by lazy {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.cacheDir, "fixtures").apply { mkdirs() }
        val assets = instrumentation.context.assets
        for (name in assets.list("fixtures").orEmpty()) {
            assets.open("fixtures/$name").use { input -> File(folder, name).outputStream().use { input.copyTo(it) } }
        }
        folder
    }
}
