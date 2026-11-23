package tech.fonia.audio

import android.Manifest
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry

val targetContext: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

/** The library's test APK declares RECORD_AUDIO through the merged manifest; tests that open the microphone grant it. */
fun grantMicrophone() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
}

/** Milliseconds [body] took. */
inline fun elapsedMs(body: () -> Unit): Long {
    val began = System.nanoTime()
    body()
    return (System.nanoTime() - began) / 1_000_000
}
