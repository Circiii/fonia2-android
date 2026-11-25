package tech.fonia.speaking.coach

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.sin

/** A card of the setup screen: what to talk about. Original Romanian texts in `spec/speech/prompts.json`. */
@Serializable
data class CoachPrompt(val id: String, val kind: String, val title: String, val hint: String)

@Serializable
data class CoachPrompts(val version: String = "prompts-1", val prompts: List<CoachPrompt> = emptyList()) {
    companion object {
        const val RESOURCE = "/spec/speech/prompts.json"
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): CoachPrompts = json.decodeFromString(serializer(), text)

        val v1: CoachPrompts by lazy {
            CoachPrompts::class.java.getResourceAsStream(RESOURCE)?.use { parse(it.readBytes().decodeToString()) }
                ?: CoachPrompts(prompts = listOf(CoachPrompt("free", "free", "Liber", "Ce vrei tu.")))
        }
    }
}

/**
 * Fixtures: a scripted face for emulators and screenshots, so every camera cue can be triggered without a person.
 * The script is `name:seconds,name:seconds,…` and loops; names: `calm` (at the lens, a little natural movement),
 * `away` (eyes and head down at the screen), `side` (turned to one side), `sway` (rocking side to side), `lively`
 * (brows and smile muscles moving, nods), `gone` (no face). Pure: [sample] writes the face at a time into a sample.
 */
class ScriptedFaceTrack(script: String) {
    private val steps: List<Pair<String, Double>> = script.split(',').mapNotNull { part ->
        val bits = part.trim().split(':')
        val name = bits.getOrNull(0)?.takeIf { it in NAMES } ?: return@mapNotNull null
        name to (bits.getOrNull(1)?.toDoubleOrNull() ?: 6.0)
    }.ifEmpty { listOf("calm" to 3600.0) }
    private val total = steps.sumOf { it.second }

    /** The step's name at [t] seconds. */
    fun stepAt(t: Double): String {
        var x = ((t % total) + total) % total
        for ((name, seconds) in steps) {
            if (x < seconds) return name
            x -= seconds
        }
        return steps.last().first
    }

    /** Writes the face at [t] (seconds since the track started) into [into]; [tSeconds] is the coach's clock. */
    fun sample(t: Double, tSeconds: Double, into: FaceSample) {
        val name = stepAt(t)
        into.tSeconds = tSeconds
        into.present = name != "gone"
        // A living face is never still: tiny breathing and saccades.
        val breathe = sin(2 * PI * 0.25 * t)
        val jitter = sin(2 * PI * 1.7 * t) * 0.3 + sin(2 * PI * 3.1 * t) * 0.2
        into.posX = 3.0 + 0.02 * breathe
        into.posY = 3.0 + 0.015 * breathe
        into.yawDeg = 0.6 * jitter
        into.pitchDeg = 0.5 * breathe
        into.rollDeg = 0.4 * breathe
        into.eyeH = 0.01 * jitter
        into.eyeV = 0.0
        into.brow = 0.08 + 0.01 * breathe
        into.smile = 0.1
        when (name) {
            "away" -> { into.pitchDeg = -9.0 + 0.5 * breathe; into.eyeV = -0.45 }
            "side" -> { into.yawDeg = 22.0 + jitter; into.eyeH = 0.15 }
            "sway" -> {
                val s = sin(2 * PI * 0.55 * t)
                into.posX = 3.0 + 0.55 * s
                into.rollDeg = 7.0 * s
                into.yawDeg = 4.0 * s
            }
            "lively" -> {
                into.brow = 0.08 + 0.12 * (0.5 + 0.5 * sin(2 * PI * 0.7 * t))
                into.smile = 0.1 + 0.25 * (0.5 + 0.5 * sin(2 * PI * 0.33 * t + 1.0))
                into.pitchDeg = 3.0 * sin(2 * PI * 0.9 * t)
            }
        }
    }

    companion object {
        val NAMES = setOf("calm", "away", "side", "sway", "lively", "gone")
    }
}
