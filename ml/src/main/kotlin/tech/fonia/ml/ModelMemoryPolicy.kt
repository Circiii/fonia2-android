package tech.fonia.ml

import android.content.ComponentCallbacks2
import android.content.res.Configuration

/**
 * Releases models when Android asks for memory (plan 02 §6: PSS ≤ 350 MB in a lesson). Register it once:
 * `application.registerComponentCallbacks(ModelMemoryPolicy(listOf(zipaHost)))`.
 *
 * ZIPA stays warm while the app is in front (it must answer within 600 ms of every take); it is released when the
 * app's UI is hidden or the app is in the background list, and on a critical in-front warning. The next take after a
 * release pays the load (≈ 1–3 s on a phone, the fast head answers meanwhile).
 */
class ModelMemoryPolicy(private val hosts: List<ModelHost<*, *>>) : ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) {
        if (shouldTrim(level)) hosts.forEach { it.trim() }
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        hosts.forEach { it.trim() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    companion object {
        /** True for UI hidden (20) and above, and for the critical running level (15). */
        @Suppress("DEPRECATION")
        fun shouldTrim(level: Int): Boolean =
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
    }
}
