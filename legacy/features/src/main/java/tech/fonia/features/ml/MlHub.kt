package tech.fonia.features.ml

import android.content.Context
import android.os.Build
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.fonia.audio.CapturedClip
import tech.fonia.domain.Exercise
import tech.fonia.domain.FeedbackLevel
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.Language
import tech.fonia.domain.SoundID
import tech.fonia.dsp.DSPThresholds
import tech.fonia.features.app.AudioHub
import tech.fonia.features.pilot.PilotHooks
import tech.fonia.features.pilot.PilotLog
import tech.fonia.features.pilot.ShadowContext
import tech.fonia.features.pilot.SoundPositions
import tech.fonia.features.session.TakeVerdict
import tech.fonia.phones.RomanianG2P
import tech.fonia.phones.TargetSound
import tech.fonia.whisper.HttpsModelFetcher
import tech.fonia.whisper.NoAllowedNetworkException
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/**
 * The downloaded models as the settings show them: one state per model, changed on the main thread, like `SpeechHub`
 * for Whisper. The download runs in the app's scope, so leaving the settings doesn't stop it.
 */
class ModelsHub(
    val manager: ModelManager,
    private val scope: CoroutineScope,
    /** False on a phone the bundled ONNX Runtime doesn't run on: the settings then offer nothing to download. */
    val isSupported: Boolean,
    /** Closes whatever has the model open, before its files go. */
    private val beforeDelete: suspend (ModelEntry) -> Unit = {},
) {
    sealed interface State {
        data object Absent : State

        data class Downloading(val fraction: Double) : State

        data object Ready : State

        /** The last download stopped. [noNetwork]: there was no Wi-Fi or Ethernet to use. */
        data class Failed(val noNetwork: Boolean) : State
    }

    private val states = mutableStateMapOf<String, State>().apply {
        for (entry in manager.catalog) put(entry.id, if (runCatching { manager.isReady(entry) }.getOrDefault(false)) State.Ready else State.Absent)
    }

    fun state(entry: ModelEntry): State = states[entry.id] ?: State.Absent

    fun download(entry: ModelEntry) {
        val current = state(entry)
        if (!isSupported || current is State.Downloading || current == State.Ready) return
        states[entry.id] = State.Downloading(0.0)
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    manager.download(entry) { fraction ->
                        scope.launch { if (states[entry.id] is State.Downloading) states[entry.id] = State.Downloading(fraction) }
                    }
                }
                State.Ready
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                State.Failed(noNetwork = error is NoAllowedNetworkException)
            }
            states[entry.id] = result
        }
    }

    /** "Șterge tot": the model goes now, and a failure is thrown so the adult hears that it stayed. */
    suspend fun clear(entry: ModelEntry) {
        states[entry.id] = State.Absent
        beforeDelete(entry)
        withContext(Dispatchers.IO) { manager.delete(entry) }
    }

    fun remove(entry: ModelEntry) {
        if (state(entry) is State.Downloading) return
        states[entry.id] = State.Absent
        scope.launch {
            runCatching { beforeDelete(entry) }
            runCatching { withContext(Dispatchers.IO) { manager.delete(entry) } }
        }
    }
}

/** The spoken item TakeJudge judged, when ZIPA can score it: Romanian, with one of the nine target sounds. */
internal data class ShadowItem(val text: String, val sound: SoundID, val level: String)

internal val Exercise.shadowItem: ShadowItem?
    get() = when (this) {
        is Exercise.Repeat -> prompt.sound?.takeIf { prompt.language == Language.Ro }?.let { ShadowItem(prompt.text, it, prompt.level.rawValue) }
        is Exercise.Name -> ShadowItem(item.text, sound, "word")
        is Exercise.Twister -> sound?.takeIf { language == Language.Ro }?.let { ShadowItem(text, it, "sentence") }
        else -> null
    }

/** How the log names the DSP settings of a verdict: `v1`, or `v1-noSibilants` for a narrow-band microphone. */
internal fun DSPThresholds.label(): String = "v$version" + if (this == AudioHub.withoutSibilants) "-noSibilants" else ""

/**
 * The new ML next to the port (docs/PLAN.md §6, §7.1 P13): the model manager behind the settings, and shadow mode.
 * Shadow mode runs only once ZIPA is on the phone, and only on a phone the bundled ONNX Runtime runs on.
 */
class MlHub(
    val models: ModelsHub,
    val shadow: ShadowMode,
    private val zipa: ModelEntry = ModelCatalog.ZIPA,
) : AutoCloseable {
    /** ZIPA is on the phone and can run: each production take is scored in shadow mode. */
    val isShadowing: Boolean get() = models.isSupported && models.state(zipa) == ModelsHub.State.Ready

    /**
     * Called right after TakeJudge, with its verdict already decided and on its way to the screen: hands the same take
     * to the shadow scorer and returns. It never waits and never throws; the verdict is read only to log it next to
     * ZIPA's. False when the take wasn't queued (no model, not a Romanian target item, a full queue).
     */
    fun afterJudge(
        context: ShadowContext,
        profileId: UUID,
        sessionId: UUID?,
        take: String,
        exercise: Exercise,
        clip: CapturedClip,
        verdict: TakeVerdict,
        feedback: FeedbackLevel?,
        content: FoniaContent,
        thresholds: DSPThresholds,
        fixtures: Boolean,
    ): Boolean = try {
        if (!isShadowing) {
            false
        } else {
            val item = exercise.shadowItem
            val target = item?.let { TargetSound.fromRawValue(it.sound.rawValue) }
            if (item == null || target == null || RomanianG2P.pronounce(item.text).occurrences(target).isEmpty()) {
                false
            } else {
                shadow.submit(
                    ShadowTake(
                        profileId = profileId,
                        sessionId = sessionId,
                        take = take,
                        context = context,
                        text = item.text,
                        sound = item.sound.rawValue,
                        target = target,
                        position = SoundPositions.of(item.text, PilotHooks.graphemes(content, item.sound), item.level),
                        level = item.level,
                        feedback = feedback?.rawValue,
                        shown = verdict.outcome,
                        clip = clip,
                        narrowBand = thresholds == AudioHub.withoutSibilants,
                        input = if (fixtures) "fixture" else "microphone",
                        dsp = thresholds.label(),
                    ),
                )
            }
        }
    } catch (_: Throwable) {
        false
    }

    override fun close() {
        shadow.close()
    }

    companion object {
        /** The models' folder: `noBackupFilesDir/FoniaModels`, never backed up, beside Whisper's `FoniaSpeech`. */
        fun modelsFolder(context: Context): File = File(context.noBackupFilesDir, "FoniaModels")

        /** The manager the app uses: HTTPS on unmetered networks, the bytes resumed from the model's `.partial` folder. */
        fun manager(context: Context): ModelManager =
            ModelManager(modelsFolder(context)) { partial -> HttpsModelFetcher(context.applicationContext, partial) }

        fun live(context: Context, scope: CoroutineScope, pilot: PilotLog?): MlHub {
            val manager = manager(context)
            runCatching { manager.sweep() }
            val source = ZipaScoringSource(modelFile = { manager.mainFile(ModelCatalog.ZIPA) })
            val shadow = ShadowMode(source, sink = { record -> pilot?.shadow(record) })
            val models = ModelsHub(manager, scope, isSupported = runsOnnxRuntime(context), beforeDelete = { shadow.unload() })
            return MlHub(models, shadow)
        }

        /**
         * True when the installed APK carries ONNX Runtime for this phone's processor. The app ships it for 64-bit
         * processors only (app/build.gradle.kts), as it ships Whisper; the APK's own entries say which, without loading
         * the 34 MB library to find out.
         */
        fun runsOnnxRuntime(context: Context): Boolean {
            val abi = Build.SUPPORTED_ABIS?.firstOrNull() ?: return false
            val info = context.applicationInfo
            val apks = listOfNotNull(info.sourceDir) + info.splitSourceDirs.orEmpty()
            return apks.any { path -> runCatching { ZipFile(path).use { it.getEntry("lib/$abi/libonnxruntime.so") != null } }.getOrDefault(false) }
        }
    }
}
