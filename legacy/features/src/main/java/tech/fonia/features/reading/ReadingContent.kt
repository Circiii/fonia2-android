package tech.fonia.features.reading

import android.content.res.AssetManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The reading probe (C1 in fonia-android-plan/03 §1.3) has the structure of PEAFC: one minute each on a list of
// one-syllable words, one of two-syllable words, one of three-syllable words and a short text. The words and texts
// are Fonia's own (PEAFC's are a book under copyright), in `assets/drafts/reading.json`, which stays a draft until a
// logoped approves it.

/** Which of the four one-minute probes. [syllables] is the length of every word in a list; null for the text. */
enum class ProbeKind(val rawValue: String, val syllables: Int?) {
    Mono("mono", 1),
    Bi("bi", 2),
    Tri("tri", 3),
    Text("text", null),
    ;

    /** What the adult picks: "Cuvinte de o silabă". */
    val title: String
        get() = when (this) {
            Mono -> "Cuvinte de o silabă"
            Bi -> "Cuvinte de două silabe"
            Tri -> "Cuvinte de trei silabe"
            Text -> "Un text scurt"
        }

    /** The same, inside a sentence: "la cuvintele de o silabă". */
    val inSentence: String
        get() = when (this) {
            Mono -> "cuvintele de o silabă"
            Bi -> "cuvintele de două silabe"
            Tri -> "cuvintele de trei silabe"
            Text -> "text"
        }

    companion object {
        fun fromRawValue(rawValue: String): ProbeKind? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/** One word on the screen, as the child reads it (punctuation included), and its syllables. */
data class ProbeWord(val text: String, val syllables: List<String>) {
    val syllableCount: Int get() = syllables.size
}

/** One list or text of one set. */
data class ReadingProbe(
    val id: String,
    val set: String,
    val kind: ProbeKind,
    val title: String,
    val words: List<ProbeWord>,
) {
    val totalSyllables: Int get() = words.sumOf { it.syllableCount }
}

/** `reading.json`: two equivalent sets of the four probes, the instructions, and whether it is still a draft. */
data class ReadingContent(
    val version: String,
    val status: String,
    val approvedBy: String?,
    val notice: String,
    val grade: String,
    val wordSource: String,
    val sets: List<String>,
    val probes: List<ReadingProbe>,
    /** What the adult says to the child before a list, and before the text. */
    val listInstruction: String,
    val textInstruction: String,
    /** How the adult marks: what counts as read differently, the 3-second rule, self-corrections. */
    val adultRules: List<String>,
) {
    /** True until a logoped's name is in `approvedBy`. */
    val isDraft: Boolean get() = approvedBy.isNullOrBlank()

    fun probe(kind: ProbeKind, set: String): ReadingProbe? = probes.firstOrNull { it.kind == kind && it.set == set }

    fun probe(id: String): ReadingProbe? = probes.firstOrNull { it.id == id }

    fun instruction(kind: ProbeKind): String = if (kind == ProbeKind.Text) textInstruction else listInstruction

    companion object {
        const val ASSET = "drafts/reading.json"

        /** Reads the bundled file; null when it is missing or broken, and the reading page then says so. */
        fun load(assets: AssetManager): ReadingContent? = runCatching {
            parse(assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() })
        }.getOrNull()

        fun parse(json: String): ReadingContent {
            val root = Json.parseToJsonElement(json).jsonObject
            val instructions = root.obj("instructions")
            val sets = root.array("sets").map { it.jsonObject }
            val probes = sets.flatMap { set ->
                val setID = set.string("id")
                set.array("probes").map { parseProbe(it.jsonObject, setID) }
            }
            return ReadingContent(
                version = root.string("version"),
                status = root.string("status"),
                approvedBy = root["approvedBy"]?.let { (it as? JsonPrimitive)?.contentOrNull },
                notice = root.string("notice"),
                grade = root.string("grade"),
                wordSource = root.obj("sources").string("words"),
                sets = sets.map { it.string("id") },
                probes = probes,
                listInstruction = instructions.string("lists"),
                textInstruction = instructions.string("text"),
                adultRules = instructions.array("adult").map { it.jsonPrimitive.content },
            )
        }

        private fun parseProbe(node: JsonObject, set: String): ReadingProbe {
            val kind = ProbeKind.fromRawValue(node.string("kind")) ?: error("Unknown probe kind in ${node.string("id")}")
            val words = if (kind == ProbeKind.Text) {
                textWords(node.string("text"), node.string("syllables"))
            } else {
                // A list word is written with its syllables: "ca-să".
                node.array("words").map { item ->
                    val split = item.jsonPrimitive.content
                    ProbeWord(split.replace("-", ""), split.split("-"))
                }
            }
            return ReadingProbe(node.string("id"), set, kind, node.string("title"), words)
        }

        /**
         * The text comes twice: as the child reads it, and with its syllables split by hyphens. The two are cut at
         * the same spaces, so word i of one is word i of the other; the texts have no real hyphens.
         */
        internal fun textWords(text: String, syllables: String): List<ProbeWord> {
            val shown = text.split(' ').filter { it.isNotEmpty() }
            val split = syllables.split(' ').filter { it.isNotEmpty() }
            require(shown.size == split.size) { "The text and its syllables have ${shown.size} and ${split.size} words" }
            return shown.zip(split) { word, hyphenated ->
                require(hyphenated.replace("-", "") == word) { "\"$hyphenated\" is not \"$word\" split into syllables" }
                ProbeWord(word, hyphenated.split("-").map { lettersOf(it) }.filter { it.isNotEmpty() })
            }
        }

        /** The letters of a token, without the punctuation around it: „Mâine → Mâine. */
        internal fun lettersOf(token: String): String = token.filter { it.isLetter() }
    }
}

private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: error("Missing \"$key\"")

private fun JsonObject.obj(key: String): JsonObject = (this[key] as? JsonObject) ?: error("Missing \"$key\"")

private fun JsonObject.array(key: String): JsonArray = (this[key] as? JsonArray) ?: error("Missing \"$key\"")
