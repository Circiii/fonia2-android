package tech.fonia.features.speaking

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tech.fonia.dsp.ClipSummary
import tech.fonia.dsp.VoiceGroup
import tech.fonia.dsp.analyzeClip
import tech.fonia.dsp.decodeWAV
import tech.fonia.features.reading.repositoryRoot
import tech.fonia.features.speaking.bac.BacContent
import tech.fonia.features.speaking.emphasis.EmphasisContent
import tech.fonia.speech.TranscriptWord
import java.io.File

/**
 * A recorded take for the JVM tests: one of the speech fixtures of the iOS end-to-end suite (ElevenLabs voices, 16 kHz
 * WAV, the same clips `:whisper` checks its parity on), with the words and times Whisper gave for it on the emulator
 * in that parity run (`whisper/eval_speech/results.jsonl`). The text is the script the clip was made from.
 */
class RecordedTake(val name: String, val samples: FloatArray, val rate: Double, val words: List<TranscriptWord>, val script: String) {
    val summary: ClipSummary by lazy { analyzeClip(samples, rate, VoiceGroup.FEMALE, null) }
}

object Recorded {
    private val results: List<JsonObject> by lazy {
        File(repositoryRoot, "whisper/eval_speech/results.jsonl").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
    }

    /** Scripts of the clips, from `fonia/scripts/make-test-audio.ts`, which made them. */
    private val scripts = mapOf(
        "ro-speech" to "Bună ziua tuturor. Astăzi vreau să vă vorbesc despre somn. Deci, practic, adolescenții au nevoie de nouă ore de somn, dar cei mai mulți dorm mai puțin de șapte.",
        "en-speech" to "Hi everyone. Um, today I want to talk about, like, why sleep matters. So, basically, teenagers need about nine hours of sleep, and most of us get less than seven.",
        "en-sentence-sheep" to "The sheep is on the ship.",
        "en-sentence-think" to "I think three things.",
        "ro-accent-walk" to "I walk to work.",
    )

    fun take(name: String): RecordedTake {
        val wav = decodeWAV(File(repositoryRoot, "whisper/src/androidTest/assets/speech/$name.wav").readBytes())
        // The free-speech rows carry the transcript's words; the sentence rows carry the target words with their times.
        val row = results.first { row ->
            row["fixture"]?.jsonPrimitive?.content == name &&
                (row["kind"]?.jsonPrimitive?.content == "speech" || row["result"]?.jsonPrimitive?.content == "allHeard")
        }
        val words = row["words"]!!.jsonArray.map { node ->
            val word = node.jsonObject
            TranscriptWord(
                word = word["word"]!!.jsonPrimitive.content,
                start = word["start"]!!.jsonPrimitive.double,
                end = word["end"]!!.jsonPrimitive.double,
                prob = word["prob"]!!.jsonPrimitive.double,
            )
        }
        return RecordedTake(name, wav.pcm, wav.sampleRate, words, scripts.getValue(name))
    }
}

internal fun assetText(path: String): String = File(repositoryRoot, "legacy/features/src/main/assets/$path").readText(Charsets.UTF_8)

internal val emphasisContent: EmphasisContent by lazy { EmphasisContent.parse(assetText(EmphasisContent.ASSET)) }

internal val bacContent: BacContent by lazy { BacContent.parse(assetText(BacContent.ASSET)) }

/** Words the product rules keep out of anything said about a person: states they would have to infer (AI Act art. 5(1)(f)). */
internal val inferredStates = listOf(
    "emoți", "emoţi", "fericit", "trist", "nervos", "furios", "entuziast", "anxi", "panic", "stres", "încrede", "sigur pe tine",
    "carism", "pari ", "părea", "ai simțit", "convingăt", "talent",
)

/** Whatever of [inferredStates] [text] contains. */
internal fun statesIn(text: String): List<String> = inferredStates.filter { it in text.lowercase() }
