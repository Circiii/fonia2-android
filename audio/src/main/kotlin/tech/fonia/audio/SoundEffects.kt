package tech.fonia.audio

import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Short game sounds from `voice/sfx`. Silent while muted and while a take is being captured. */
class SoundEffects(directory: ClipLocation) {
    enum class Effect(internal val fileName: String, internal val gain: Float) {
        Correct("correct.mp3", 0.55f),
        Almost("almost.mp3", 0.45f),
        Pop("pop.mp3", 0.5f),
        Star("star.mp3", 0.5f),
        Unlock("unlock.mp3", 0.5f),
        LevelUp("levelup.mp3", 0.6f),
    }

    private val muted = AtomicBoolean(false)
    private val players = EffectPlayers(directory)

    var isMuted: Boolean
        get() = muted.get()
        set(value) = muted.set(value)

    fun play(effect: Effect) {
        if (!isAudible(isMuted = isMuted, isCapturing = CaptureActivity.isActive)) return
        players.play(effect)
    }

    internal companion object {
        fun isAudible(isMuted: Boolean, isCapturing: Boolean): Boolean = !isMuted && !isCapturing
    }
}

/**
 * iOS keeps one `AVAudioPlayer` per effect, made at first use and restarted from 0. Here one `SoundPool` on its
 * own thread does the same: an effect is loaded at first use and plays as soon as it is ready, a replay stops
 * the previous one, and a sound that fails to play is dropped so the next call loads it again.
 */
private class EffectPlayers(private val directory: ClipLocation) {
    private companion object {
        const val TAG = "SoundEffects"
    }

    private val handler: Handler by lazy { Handler(HandlerThread("FoniaEffects").apply { start() }.looper) }
    private var pool: SoundPool? = null
    private val sounds = HashMap<SoundEffects.Effect, Int>()
    private val loading = HashMap<Int, SoundEffects.Effect>()
    private val streams = HashMap<SoundEffects.Effect, Int>()

    fun play(effect: SoundEffects.Effect) {
        handler.post { playNow(effect) }
    }

    private fun playNow(effect: SoundEffects.Effect) {
        val pool = pool ?: makePool().also { pool = it }
        val sound = sounds[effect]
        if (sound == null) {
            if (effect !in loading.values) load(pool, effect)
            return
        }
        streams.remove(effect)?.let { pool.stop(it) }
        val stream = pool.play(sound, effect.gain, effect.gain, 1, 0, 1f)
        if (stream == 0) {
            // A pool does not survive the audio server dying, so the next call loads the sound again.
            pool.unload(sound)
            sounds.remove(effect)
        } else {
            streams[effect] = stream
        }
    }

    private fun load(pool: SoundPool, effect: SoundEffects.Effect) {
        val id = try {
            when (val location = directory.resolve(effect.fileName)) {
                is ClipLocation.File -> pool.load(location.file.path, 1)
                is ClipLocation.Asset -> location.assets.openFd(location.path).use { pool.load(it, 1) }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Cannot load ${effect.fileName}: ${e.message}")
            return
        }
        if (id == 0) {
            Log.e(TAG, "Cannot load ${effect.fileName}")
            return
        }
        loading[id] = effect
    }

    /** Made on the effects thread, so load results arrive on it. */
    private fun makePool(): SoundPool = SoundPool.Builder()
        .setMaxStreams(SoundEffects.Effect.entries.size)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
        .apply {
            setOnLoadCompleteListener { pool, id, status ->
                val effect = loading.remove(id) ?: return@setOnLoadCompleteListener
                if (status != 0) {
                    Log.e(TAG, "Cannot load ${effect.fileName}: status $status")
                    pool.unload(id)
                    return@setOnLoadCompleteListener
                }
                sounds[effect] = id
                playNow(effect)
            }
        }
}
