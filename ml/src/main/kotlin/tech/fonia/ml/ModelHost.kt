package tech.fonia.ml

import kotlinx.coroutines.flow.StateFlow

/** Which model, which bytes, which runtime: saved with every attempt (`attempt.modelVersions`, plan 02 §6). */
data class ModelVersion(val id: String, val version: String, val sha256: String, val runtime: String) {
    /** `zipa@dfe87c976626`, the piece of `ModelRegistry.versions()`. */
    val tag: String get() = "$id@$version"
}

/** Cold: nothing in memory. Loading: the session is being created. Warm: ready. Failed: the last load threw. */
enum class HostState { Cold, Loading, Warm, Failed }

/**
 * One on-device model behind its own thread (plan 02 §6). Plan 02 puts this interface in `:core:model`; it lives here
 * until that module's owner moves it (one writer per module), with the same shape.
 */
interface ModelHost<I, O> : AutoCloseable {
    val version: ModelVersion
    val state: StateFlow<HostState>

    /** Loads the model and runs one inference on a dummy input, so the first real take pays no start-up. */
    suspend fun warmUp()

    /** Runs on the host's thread; cancelling the caller stops waiting (the inference itself finishes in the background). */
    suspend fun run(input: I): O

    /** Frees the model's memory; the next [run] loads it again. */
    fun trim()
}
