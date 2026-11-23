package tech.fonia.features.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CompletableDeferred

/**
 * The prompt `AudioSessionController.requestMicrophone(prompt)` asks for: Android shows the microphone question
 * through the activity (iOS shows it from the session itself). `FoniaRoot` attaches an `ActivityResultLauncher` for
 * `RECORD_AUDIO`; until it has, the answer is what the system already knows.
 */
class MicrophonePermission(private val context: Context) {
    private var launch: (() -> Unit)? = null
    private var pending: CompletableDeferred<Boolean>? = null

    val isGranted: Boolean
        get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun attach(launch: (() -> Unit)?) {
        this.launch = launch
    }

    /** The answer from the system's prompt. */
    fun deliver(granted: Boolean) {
        pending?.complete(granted)
        pending = null
    }

    /** Shows the prompt (once, however many callers ask at the same time) and returns the answer. */
    suspend fun request(): Boolean {
        if (isGranted) return true
        val launch = launch ?: return false
        val answer = pending ?: CompletableDeferred<Boolean>().also {
            pending = it
            launch()
        }
        return answer.await()
    }
}
