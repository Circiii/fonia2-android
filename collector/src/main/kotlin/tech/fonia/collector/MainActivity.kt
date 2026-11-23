package tech.fonia.collector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import tech.fonia.audio.FixtureMode
import tech.fonia.collector.ui.CollectorApp

/**
 * Fonia Colector. `adb shell am start -n tech.fonia.collector/.MainActivity --ez fixture true` (or
 * `--es fixture <path or asset>`) feeds a fixture instead of the microphone, so the flow runs on an emulator.
 */
class MainActivity : ComponentActivity() {
    private val vm: CollectorModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val extras = intent.extras
        val path = runCatching { extras?.getString(Fixtures.EXTRA) }.getOrNull()
        val flag = extras?.getBoolean(Fixtures.EXTRA, false) == true || FixtureMode.isRequested(extras)
        vm.configure(Fixtures.resolve(this, path, flag))
        setContent { CollectorApp(vm) }
    }
}
