package tech.fonia.facecamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The module's dependencies bring no library known to report usage: not MediaPipe Tasks (whose stats logger sends
 * usage to Google with no documented opt-out), not Google's data transport, Firebase, Clearcut or Play services.
 * The runtime check, no socket while tracking, is NoNetworkTests on a device.
 */
class NoTelemetryTests {
    private fun present(name: String): Boolean = try {
        Class.forName(name, false, javaClass.classLoader)
        true
    } catch (e: ClassNotFoundException) {
        false
    }

    @Test
    fun noReportingLibraries() {
        val forbidden = listOf(
            "com.google.mediapipe.tasks.core.TaskRunner",
            "com.google.mediapipe.tasks.core.logging.TasksStatsLogger",
            "com.google.android.datatransport.runtime.TransportRuntime",
            "com.google.firebase.FirebaseApp",
            "com.google.android.gms.common.GoogleApiAvailability",
            "com.google.android.gms.clearcut.ClearcutLogger",
            "com.google.android.play.core.aipacks.AiPackManagerFactory",
            "com.google.android.gms.tasks.Task",
        )
        assertEquals(emptyList<String>(), forbidden.filter(::present))
        // While the runtime is there.
        assertTrue(present("org.tensorflow.lite.Interpreter"))
    }

    @Test
    fun theManifestAsksForTheCameraOnly() {
        // src/main/assets/models → src/main/AndroidManifest.xml
        val manifest = File(JvmFixtures.models.parentFile!!.parentFile, "AndroidManifest.xml").readText()
        val permissions = Regex("""<uses-permission\s+android:name="([^"]+)"\s*/>""").findAll(manifest).map { it.groupValues[1] }.toList()
        assertEquals(listOf("android.permission.CAMERA"), permissions)
        assertTrue("no INTERNET permission", "android.permission.INTERNET" !in manifest)
        // What litert-api would add for Play's model downloads is removed.
        assertTrue(Regex("""FOREGROUND_SERVICE_DATA_SYNC"\s+tools:node="remove"""").containsMatchIn(manifest))
    }
}
