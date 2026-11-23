package tech.fonia.collector

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioDeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tech.fonia.audio.CaptureEngine
import tech.fonia.audio.CaptureSource
import tech.fonia.audio.CapturedClip
import tech.fonia.audio.ClipLocation
import tech.fonia.audio.FixtureSource
import tech.fonia.audio.HopProcessor
import tech.fonia.audio.InputPreset
import tech.fonia.audio.LiveLevels
import tech.fonia.audio.MicrophoneSource
import tech.fonia.audio.SpeechGate
import tech.fonia.collector.core.CaptureSummary
import tech.fonia.collector.core.HopStats
import tech.fonia.collector.core.Levels
import tech.fonia.dsp.encodeWAV
import java.io.File
import java.util.Collections
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Where takes come from when the app was started with the `fixture` extra: no microphone involved. */
class FixtureConfig(val take: ClipLocation, val room: ClipLocation, val description: String)

/** How a take ended. */
enum class EndedBy(val raw: String) { Auto("auto"), Manual("manual"), Maximum("maximum"), NoSpeech("noSpeech") }

class TakeResult(
    val clip: CapturedClip,
    val endedBy: EndedBy,
    val speechStartSec: Double?,
    val speechEndSec: Double?,
) {
    val speechDetected: Boolean get() = speechStartSec != null
}

class RoomResult(val clip: CapturedClip, val floorDb: Float)

/**
 * Runs the takes. The source is `MicrophoneSource` of `:platform:audio` (UNPROCESSED where the phone has it,
 * VOICE_RECOGNITION otherwise, effects switched off) or a `FixtureSource`. Everything the source delivers is kept:
 * the take starts [PRE_ROLL_MS] before the speaker is told to talk and ends after ≥ 0.9 s of quiet (or [POST_ROLL_MS]
 * after a manual stop). The energy gate only decides when to stop.
 */
class Recorder(context: Context, private val fixture: FixtureConfig?) {
    companion object {
        const val PRE_ROLL_MS = 300L
        const val POST_ROLL_MS = 300L
        const val ROOM_SECONDS = 3.0
        const val SILENCE_SECONDS = 0.9
    }

    val levels = LiveLevels()
    private val microphone: MicrophoneSource? = if (fixture == null) MicrophoneSource(context) else null
    private val takeEngine = CaptureEngine(source(fixture?.take))
    private val roomEngine = if (fixture == null) takeEngine else CaptureEngine(source(fixture.room))
    private val gate = SpeechGate()

    @Volatile var speechThresholdDb: Float = Levels.speechThreshold(null)

    private fun source(location: ClipLocation?): CaptureSource =
        if (location != null) FixtureSource(location, FixtureSource.Pacing.RealTime) else microphone!!

    val isFixture: Boolean get() = fixture != null

    /** What the input opened with; known after the first take. */
    fun captureSummary(sampleRate: Double): CaptureSummary {
        if (fixture != null) {
            return CaptureSummary(
                audioSource = "fixture", requestedSource = "fixture", sampleRate = sampleRate.toInt(),
                narrowBand = sampleRate < 32_000, bluetooth = false, fixture = true,
            )
        }
        val info = microphone?.captureInfo
        fun name(preset: InputPreset?): String = when (preset) {
            InputPreset.Unprocessed -> "UNPROCESSED"
            InputPreset.VoiceRecognition -> "VOICE_RECOGNITION"
            null -> "other"
        }
        return if (info == null) {
            CaptureSummary("unknown", "unknown", sampleRate.toInt(), narrowBand = sampleRate < 32_000, bluetooth = false)
        } else {
            CaptureSummary(
                audioSource = name(info.inputPreset),
                requestedSource = name(info.requestedPreset),
                sampleRate = info.sampleRate.toInt(),
                narrowBand = info.isNarrowBand,
                bluetooth = info.isBluetoothMic,
                deviceType = info.deviceType,
                lowLatency = info.isLowLatency,
                framesPerBurst = info.framesPerBurst,
                effectsSwitchedOff = info.platformEffectsSwitchedOff,
            )
        }
    }

    suspend fun measureRoom(seconds: Double = ROOM_SECONDS): RoomResult {
        val hops = Collections.synchronizedList(ArrayList<Float>())
        val engine = roomEngine
        engine.start(levels) { _ ->
            HopProcessor { samples, offset, count, live ->
                val db = Levels.dbfs(samples, offset, count)
                hops.add(db)
                live.publish(level = Levels.meter(db))
            }
        }
        val clip = try {
            delay((seconds * 1000).toLong())
            engine.stop()
        } catch (e: Throwable) {
            withContext(NonCancellable) { engine.stop() }
            throw e
        }
        levels.publish()
        val floor = Levels.floor(synchronized(hops) { hops.toList() })
        speechThresholdDb = Levels.speechThreshold(floor)
        return RoomResult(clip, floor)
    }

    /**
     * Records one take. [onListening] runs once the pre-roll is in the take. Ends on silence after speech,
     * on [stop], at [maximum] seconds, or after [startTimeout] seconds without speech.
     */
    suspend fun recordTake(maximum: Double, startTimeout: Double, onListening: () -> Unit): TakeResult {
        gate.reset()
        val stats = HopStats(speechThresholdDb)
        var rate = 48_000.0
        val engine = takeEngine
        engine.start(levels) { sampleRate ->
            rate = sampleRate
            HopProcessor { samples, offset, count, live ->
                val db = Levels.dbfs(samples, offset, count)
                gate.update(stats.add(db, count))
                live.publish(level = Levels.meter(db))
            }
        }
        val started = System.nanoTime()
        var endedBy: EndedBy
        val clip: CapturedClip
        try {
            delay(PRE_ROLL_MS)
            onListening()
            gate.waitForTakeEnd(silence = SILENCE_SECONDS, startTimeout = startTimeout, maximum = maximum)
            val elapsed = (System.nanoTime() - started) / 1e9
            endedBy = when {
                gate.isStopRequested -> EndedBy.Manual
                !gate.hasSpoken -> EndedBy.NoSpeech
                elapsed >= maximum -> EndedBy.Maximum
                else -> EndedBy.Auto
            }
            if (endedBy == EndedBy.Manual) delay(POST_ROLL_MS)
            clip = engine.stop()
        } catch (e: Throwable) {
            withContext(NonCancellable) { engine.stop() }
            levels.publish()
            throw e
        }
        levels.publish()
        val r = if (clip.sampleRate > 0) clip.sampleRate else rate
        val start = stats.firstSpeech.takeIf { it >= 0 }?.let { it / r }
        val end = stats.lastSpeechEnd.takeIf { it >= 0 }?.let { it / r }
        if (start == null && endedBy != EndedBy.Manual) endedBy = EndedBy.NoSpeech
        return TakeResult(clip, endedBy, start, end)
    }

    fun stop() = gate.requestStop()
}

/** Writes a take as 16-bit PCM mono WAV at the rate the source gave, untouched. */
suspend fun writeWav(clip: CapturedClip, file: File) = withContext(Dispatchers.IO) {
    file.parentFile?.mkdirs()
    val tmp = File(file.path + ".tmp")
    tmp.writeBytes(encodeWAV(clip.samples, clip.sampleRate.toInt()))
    if (!tmp.renameTo(file)) {
        file.delete()
        tmp.renameTo(file)
    }
}

object Fixtures {
    const val EXTRA = "fixture"

    /**
     * `--es fixture <path or asset>` uses that WAV/MP3 for every take; `--ez fixture true` (or `foniaFixtures`)
     * uses a synthetic word: 0.5 s quiet, a hiss and a voiced vowel, then 1.6 s quiet.
     */
    fun resolve(context: Context, path: String?, flag: Boolean): FixtureConfig? {
        if (path.isNullOrBlank() && !flag) return null
        // Not the cache dir: the system may clear it under storage pressure while the app runs.
        val dir = File(context.noBackupFilesDir, "fixture").apply { mkdirs() }
        val room = File(dir, "room.wav")
        if (!room.isFile) room.writeBytes(encodeWAV(synthetic(48_000, voiced = false), 48_000))
        val chosen: ClipLocation? = when {
            path.isNullOrBlank() || path == "true" -> null
            File(path).isFile -> ClipLocation.File(File(path))
            assetExists(context.assets, path) -> ClipLocation.Asset(context.assets, path)
            else -> null
        }
        if (chosen != null) return FixtureConfig(chosen, ClipLocation.File(room), chosen.name)
        val take = File(dir, "take.wav")
        if (!take.isFile) take.writeBytes(encodeWAV(synthetic(48_000, voiced = true), 48_000))
        return FixtureConfig(ClipLocation.File(take), ClipLocation.File(room), "sunet sintetic")
    }

    private fun assetExists(assets: AssetManager, path: String): Boolean =
        runCatching { assets.open(path).close() }.isSuccess

    /** A stand-in word at 48 kHz: frication (differenced noise) then a 150 Hz vowel with harmonics, in a quiet room. */
    fun synthetic(rate: Int, voiced: Boolean): FloatArray {
        val random = Random(7)
        val total = (rate * (if (voiced) 2.75 else 3.4)).toInt()
        val out = FloatArray(total) { (random.nextFloat() - 0.5f) * 0.0006f }
        if (!voiced) return out
        val hissStart = (0.5 * rate).toInt()
        val hissEnd = (0.78 * rate).toInt()
        var previous = 0f
        for (i in hissStart until hissEnd) {
            val white = random.nextFloat() - 0.5f
            val ramp = minOf(1f, (i - hissStart) / (0.02f * rate), (hissEnd - i) / (0.02f * rate))
            out[i] += (white - previous) * 0.12f * ramp
            previous = white
        }
        val vowelEnd = (1.15 * rate).toInt()
        for (i in hissEnd until vowelEnd) {
            val t = (i - hissEnd).toDouble() / rate
            val ramp = minOf(1.0, t / 0.02, (vowelEnd - i).toDouble() / (0.04 * rate))
            var v = 0.0
            for (h in 1..8) v += sin(2 * PI * 150.0 * h * t) / h
            out[i] += (v * 0.12 * ramp).toFloat()
        }
        return out
    }
}

/** `AudioDeviceInfo` type names for the summary card. */
fun deviceTypeName(type: Int?): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "microfonul telefonului"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "căști cu fir"
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "microfon USB"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"
    null -> "necunoscut"
    else -> "alt microfon ($type)"
}
