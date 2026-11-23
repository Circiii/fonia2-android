package tech.fonia.design

import android.app.NotificationManager
import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import tech.fonia.designsystem.motion.rememberFoniaHaptics
import java.io.IOException

/**
 * Plays what [FoniaSfx] lets through: one `SoundPool` on its own thread, every file of `assets/sfx/` loaded at start
 * (a sound asked for before it has loaded is skipped, never late), `USAGE_GAME` so it follows the media volume, four
 * streams. Only this class and [SfxGate] touch effect playback.
 */
class SfxPlayer private constructor(private val assets: AssetManager) : Sfx {
    private val handler = Handler(HandlerThread("FoniaSfx").apply { start() }.looper)
    private val loaded = HashMap<SfxSound, Int>()
    private val pending = HashMap<Int, SfxSound>()
    private var lastStream = 0
    private lateinit var pool: SoundPool

    init {
        handler.post {
            pool = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()
            pool.setOnLoadCompleteListener { _, id, status ->
                val sound = pending.remove(id) ?: return@setOnLoadCompleteListener
                if (status == 0) loaded[sound] = id else Log.e(TAG, "Cannot load ${sound.fileName}: $status")
            }
            for (sound in SfxSound.entries) load(sound)
        }
    }

    private fun load(sound: SfxSound) {
        try {
            assets.openFd("sfx/${sound.fileName}").use { pending[pool.load(it, 1)] = sound }
        } catch (e: IOException) {
            Log.e(TAG, "Cannot open sfx/${sound.fileName}: ${e.message}")
        }
    }

    override fun play(sound: SfxSound, rate: Float, replacesLast: Boolean) {
        handler.post {
            val id = loaded[sound] ?: return@post
            if (replacesLast && lastStream != 0) pool.stop(lastStream)
            val stream = pool.play(id, 1f, 1f, 1, 0, rate.coerceIn(0.5f, 2f))
            if (stream == 0) {
                // The audio server restarted: load it again for next time.
                loaded.remove(sound)
                pool.unload(id)
                load(sound)
            }
            lastStream = stream
        }
    }

    fun schedule(delayMs: Long, run: () -> Unit) {
        handler.postDelayed(run, delayMs)
    }

    companion object {
        private const val TAG = "FoniaSfx"

        @Volatile
        private var installed: SfxPlayer? = null

        /** Once per process: the player, the phone's state and the log go into [FoniaSfx]. */
        fun install(context: Context) {
            if (installed != null) return
            synchronized(this) {
                if (installed != null) return
                val app = context.applicationContext
                val player = SfxPlayer(app.assets)
                installed = player
                FoniaSfx.gate.environment = PhoneState(app)
                FoniaSfx.scheduler = player::schedule
                FoniaSfx.log = { Log.i(TAG, it) }
                FoniaSfx.sink = player
            }
        }
    }
}

/** Silent or vibrate ringer, Do Not Disturb that lets nothing through, TalkBack: read at each request (cheap). */
private class PhoneState(context: Context) : SfxGate.Environment {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val accessibility = context.getSystemService(AccessibilityManager::class.java)

    override fun state(): SfxGate.Environment.State {
        val ringerQuiet = audio != null && audio.ringerMode != AudioManager.RINGER_MODE_NORMAL
        val filter = notifications?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
        val dnd = filter == NotificationManager.INTERRUPTION_FILTER_NONE || filter == NotificationManager.INTERRUPTION_FILTER_ALARMS
        return SfxGate.Environment.State(
            silent = ringerQuiet || dnd,
            screenReader = accessibility?.isTouchExplorationEnabled == true,
        )
    }
}

/**
 * Put once at the root of the app: installs the player and pairs key sounds with Fonia's haptic vocabulary
 * (`FoniaHaptics`, which keeps its own on / off switch). The gate decides for both, so nothing vibrates while the
 * microphone listens either.
 */
@Composable
fun SfxHost() {
    val context = LocalContext.current
    val haptics = rememberFoniaHaptics()
    DisposableEffect(context, haptics) {
        SfxPlayer.install(context)
        val main = Handler(context.mainLooper)
        val pair: (SfxHaptic) -> Unit = { kind ->
            main.post {
                when (kind) {
                    SfxHaptic.Tap -> haptics.tap()
                    SfxHaptic.Success -> haptics.success()
                    SfxHaptic.Pop -> haptics.pop()
                }
            }
        }
        FoniaSfx.haptics = pair
        onDispose { if (FoniaSfx.haptics === pair) FoniaSfx.haptics = null }
    }
}
