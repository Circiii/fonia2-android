package tech.fonia.collector.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId

// The prompt list of the collector, `spec/collector/prompts.json` (bundled as an asset). Pure Kotlin: parsed,
// expanded and validated on the PC by the unit tests.

@Serializable
data class PromptFile(
    val version: String,
    val note: String = "",
    val packs: List<PackSpec>,
    val sounds: List<SoundSpec>,
) {
    fun sound(raw: String): SoundSpec? = sounds.firstOrNull { it.sound == raw }

    fun pack(id: String): PackSpec? = packs.firstOrNull { it.id == id }
}

@Serializable
data class PackSpec(val id: String, val title: String, val sounds: List<String>)

@Serializable
data class SoundSpec(
    /** `SoundId.raw`: "s", "ș", "z", "j", "ț", "ce", "ge", "r", "l". */
    val sound: String,
    /** ASCII key used in folder names and prompt ids: "s", "sh", "z", "j", "ts", "ce", "ge", "r", "l". */
    val key: String,
    val letter: String,
    val isolated: IsolatedSpec,
    val syllables: List<String>,
    @SerialName("initial") val initialWords: List<String>,
    @SerialName("medial") val medialWords: List<String>,
    @SerialName("final") val finalWords: List<String>,
    val sentences: List<String>,
    val child: List<ChildWord>,
    val errors: List<ErrorSpec>,
)

@Serializable
data class IsolatedSpec(val text: String, val hint: String, val count: Int = 3)

@Serializable
data class ChildWord(val text: String, val position: String, val pic: String)

@Serializable
data class ErrorSpec(
    /** An `ErrorKind` name, e.g. "PlaceSwap". */
    val kind: String,
    /** What the sound is replaced with, when the kind names one ("ș", "t", "i"…). */
    val heardAs: String? = null,
    val wave: Int,
    val therapistOnly: Boolean = false,
    /** Completes "Spune „sare” …", e.g. "cu Ș în loc de S". */
    val hint: String,
    /** "base>imitation", or just "base" when the imitation cannot be spelled. Two syllables then three words. */
    val items: List<String>,
)

enum class Level(val raw: String, val code: String, val title: String) {
    Isolated("isolated", "iso", "Izolat"),
    Syllable("syllable", "syl", "Silabă"),
    Word("word", "w", "Cuvânt"),
    Sentence("sentence", "sen", "Propoziție"),
}

enum class Position(val raw: String, val code: String, val title: String) {
    Initial("initial", "ini", "la început"),
    Medial("medial", "med", "la mijloc"),
    Final("final", "fin", "la sfârșit"),
    ;

    companion object {
        fun of(raw: String): Position? = entries.firstOrNull { it.raw == raw }
    }
}

enum class Role(val raw: String, val prefix: Char, val title: String) {
    Adult("adult", 'A', "adult"),
    Therapist("therapist", 'T', "logoped"),
    Child("child", 'C', "copil"),
    ;

    companion object {
        fun of(raw: String): Role? = entries.firstOrNull { it.raw == raw }
    }
}

/** Child sessions only name pictures; adults and speech therapists say each item correctly and imitate errors. */
enum class Mode(val raw: String) { Adult("adult"), Child("child") }

/** One thing to say. Error items reuse the prompt id of their base item; the label tells them apart. */
data class Prompt(
    val promptId: String,
    val sound: SoundId,
    val soundKey: String,
    val letter: String,
    val level: Level,
    val position: Position?,
    /** The correct text (for an error item: the word the error is made on). */
    val text: String,
    /** "correct", or the `ErrorKind` name, with "-<heardAs>" when one sound has the kind twice. */
    val label: String,
    val kind: ErrorKind? = null,
    val heardAs: String? = null,
    val wave: Int? = null,
    /** What to actually say, when it can be spelled ("șare" for "sare" said with Ș). */
    val imitation: String? = null,
    /** The instruction for an error item, without the "Spune „…”" prefix; the isolated hint otherwise. */
    val hint: String? = null,
    val pic: String? = null,
) {
    val key: String get() = "${promptId}__$label"
    val isError: Boolean get() = kind != null

    /** `correct`, or the kind and what replaces the sound (`PlaceSwap:ș`), as `clips.jsonl` stores it. */
    val intendedLabel: String
        get() = when {
            kind == null -> CORRECT
            heardAs != null -> "${kind.name}:$heardAs"
            else -> kind.name
        }

    companion object {
        const val CORRECT = "correct"
    }
}

object PromptCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): PromptFile = json.decodeFromString(PromptFile.serializer(), text)

    /** The prompts of [packId] for [role], in recording order: per sound, correct items first, then the errors. */
    fun plan(file: PromptFile, packId: String, role: Role): List<Prompt> {
        val pack = requireNotNull(file.pack(packId)) { "No pack $packId" }
        return pack.sounds.flatMap { raw ->
            val spec = requireNotNull(file.sound(raw)) { "Pack $packId names unknown sound $raw" }
            if (role == Role.Child) childPrompts(spec) else adultPrompts(spec, therapist = role == Role.Therapist)
        }
    }

    fun childPrompts(spec: SoundSpec): List<Prompt> {
        val sound = soundOf(spec)
        return spec.child.map { word ->
            val position = requireNotNull(Position.of(word.position)) { "Bad position ${word.position}" }
            Prompt(
                promptId = Naming.promptId(spec.key, Level.Word, position, word.text),
                sound = sound, soundKey = spec.key, letter = spec.letter, level = Level.Word, position = position,
                text = word.text, label = Prompt.CORRECT, pic = word.pic,
            )
        }
    }

    fun correctPrompts(spec: SoundSpec): List<Prompt> {
        val sound = soundOf(spec)
        fun prompt(level: Level, position: Position?, text: String, id: String, hint: String? = null) = Prompt(
            promptId = id, sound = sound, soundKey = spec.key, letter = spec.letter, level = level, position = position,
            text = text, label = Prompt.CORRECT, hint = hint,
        )
        val out = mutableListOf<Prompt>()
        for (i in 1..spec.isolated.count) {
            out += prompt(Level.Isolated, null, spec.isolated.text, "${spec.key}_iso_$i", spec.isolated.hint)
        }
        spec.syllables.forEach { out += prompt(Level.Syllable, null, it, Naming.promptId(spec.key, Level.Syllable, null, it)) }
        for ((position, words) in listOf(
            Position.Initial to spec.initialWords, Position.Medial to spec.medialWords, Position.Final to spec.finalWords,
        )) {
            words.forEach { out += prompt(Level.Word, position, it, Naming.promptId(spec.key, Level.Word, position, it)) }
        }
        spec.sentences.forEachIndexed { i, s -> out += prompt(Level.Sentence, null, s, "${spec.key}_sen_${i + 1}") }
        return out
    }

    fun errorPrompts(spec: SoundSpec, therapist: Boolean): List<Prompt> {
        val bases = correctPrompts(spec).filter { it.level == Level.Syllable || it.level == Level.Word }
        val kindCounts = spec.errors.groupingBy { it.kind }.eachCount()
        return spec.errors.filter { therapist || !it.therapistOnly }.flatMap { error ->
            val kind = requireNotNull(ErrorKind.entries.firstOrNull { it.name == error.kind }) { "Unknown kind ${error.kind}" }
            val label = if ((kindCounts[error.kind] ?: 0) > 1 && error.heardAs != null) {
                "${error.kind}-${Naming.slug(error.heardAs)}"
            } else {
                error.kind
            }
            error.items.map { item ->
                val (baseText, imitation) = splitItem(item)
                val base = requireNotNull(bases.firstOrNull { it.text == baseText }) {
                    "${spec.key} ${error.kind}: '$baseText' is not one of the sound's syllables or words"
                }
                base.copy(
                    label = label, kind = kind, heardAs = error.heardAs, wave = error.wave,
                    imitation = imitation, hint = error.hint,
                )
            }
        }
    }

    fun adultPrompts(spec: SoundSpec, therapist: Boolean): List<Prompt> =
        correctPrompts(spec) + errorPrompts(spec, therapist)

    fun splitItem(item: String): Pair<String, String?> {
        val parts = item.split('>')
        return parts[0].trim() to parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun soundOf(spec: SoundSpec): SoundId = requireNotNull(SoundId.of(spec.sound)) { "Unknown sound ${spec.sound}" }

    /** Seconds per prompt in the automatic flow: read, ~1–2 s of speech, 0.9 s of silence, a glance at the result. */
    const val SECONDS_PER_PROMPT = 4.5

    /** A child names a picture after the adult's model, with taps by hand: slower. */
    const val SECONDS_PER_CHILD_PROMPT = 10.0

    fun estimatedMinutes(count: Int, child: Boolean = false): Int =
        kotlin.math.ceil(count * (if (child) SECONDS_PER_CHILD_PROMPT else SECONDS_PER_PROMPT) / 60.0).toInt()

    /**
     * Everything that would make the list unusable or wrong: counts per §9, unknown names, a word that does not
     * have the target sound in the position it is listed under, an imitation that still has the sound, comma-below.
     */
    fun validate(file: PromptFile): List<String> {
        val problems = mutableListOf<String>()
        val keys = mutableSetOf<String>()
        for (spec in file.sounds) {
            val sound = SoundId.of(spec.sound)
            if (sound == null) {
                problems += "unknown sound ${spec.sound}"
                continue
            }
            if (!keys.add(spec.key)) problems += "duplicate key ${spec.key}"
            if (!Regex("[a-z]+").matches(spec.key)) problems += "${spec.key}: key must be ASCII"
            val tag = spec.key
            if (spec.isolated.count != 3) problems += "$tag: 3 isolated takes expected"
            if (spec.syllables.size != 6) problems += "$tag: 6 syllables expected"
            if (spec.sentences.size != 2) problems += "$tag: 2 sentences expected"
            if (spec.child.size != 6) problems += "$tag: 6 child words expected"
            for ((position, words) in listOf(
                Position.Initial to spec.initialWords, Position.Medial to spec.medialWords, Position.Final to spec.finalWords,
            )) {
                if (words.size != 3) problems += "$tag: 3 ${position.raw} words expected"
                for (w in words) {
                    if (position !in SoundSpotter.positions(w, sound)) problems += "$tag: '$w' has no ${spec.letter} ${position.raw}"
                }
            }
            for (s in spec.syllables) if (SoundSpotter.count(s, sound) == 0) problems += "$tag: syllable '$s' lacks ${spec.letter}"
            for (s in spec.sentences) if (SoundSpotter.count(s, sound) == 0) problems += "$tag: sentence '$s' lacks ${spec.letter}"
            for (c in spec.child) {
                val position = Position.of(c.position)
                if (position == null) {
                    problems += "$tag: child '${c.text}' bad position"
                } else if (position !in SoundSpotter.positions(c.text, sound)) {
                    problems += "$tag: child '${c.text}' has no ${spec.letter} ${position.raw}"
                }
                if (c.pic.isBlank()) problems += "$tag: child '${c.text}' has no picture"
            }
            val bases = correctPrompts(spec).filter { it.level == Level.Syllable || it.level == Level.Word }
            for (e in spec.errors) {
                if (ErrorKind.entries.none { it.name == e.kind }) problems += "$tag: unknown kind ${e.kind}"
                if (e.wave !in 1..3) problems += "$tag ${e.kind}: wave ${e.wave}"
                if (e.items.size != 5) problems += "$tag ${e.kind}: 5 items expected"
                val levels = e.items.mapNotNull { item -> bases.firstOrNull { it.text == splitItem(item).first }?.level }
                if (levels.size != e.items.size) problems += "$tag ${e.kind}: an item is not a syllable or word of the sound"
                if (levels.count { it == Level.Syllable } != 2 || levels.count { it == Level.Word } != 3) {
                    problems += "$tag ${e.kind}: 2 syllables and 3 words expected"
                }
                for (item in e.items) {
                    val (base, imitation) = splitItem(item)
                    if (imitation != null && SoundSpotter.count(imitation, sound) >= SoundSpotter.count(base, sound)) {
                        problems += "$tag ${e.kind}: imitation '$imitation' still has every ${spec.letter} of '$base'"
                    }
                }
            }
            // Every error label must be unique within the sound.
            val labels = errorPrompts(spec, therapist = true).map { it.key }
            if (labels.size != labels.toSet().size) problems += "$tag: duplicate error keys"
        }
        for (pack in file.packs) {
            for (s in pack.sounds) if (file.sound(s) == null) problems += "pack ${pack.id}: unknown sound $s"
            for (role in Role.entries) {
                val plan = runCatching { plan(file, pack.id, role) }.getOrElse {
                    problems += "pack ${pack.id}: ${it.message}"
                    emptyList()
                }
                if (plan.size != plan.map { it.key }.toSet().size) problems += "pack ${pack.id} ${role.raw}: duplicate prompt keys"
            }
        }
        return problems
    }
}

/**
 * Where a Romanian target sound occurs in a written word. Romanian spelling is regular enough for the target
 * sounds: s, ș, z, j, ț, r, l are one letter each; CE/GE are c/g before e or i (never "che", "ghi"), and a final
 * "ci"/"gi" is the sound alone at the end of the word ("arici", "covrigi").
 */
object SoundSpotter {
    private val letters = Regex("[\\p{L}]+")

    fun positions(word: String, sound: SoundId): Set<Position> {
        val w = word.lowercase()
        val out = mutableSetOf<Position>()
        for (i in w.indices) {
            when (sound) {
                SoundId.Ce, SoundId.Ge -> {
                    val c = if (sound == SoundId.Ce) 'c' else 'g'
                    if (w[i] != c || i + 1 >= w.length || w[i + 1] !in "ei") continue
                    out += when {
                        i == 0 -> Position.Initial
                        w[i + 1] == 'i' && i + 2 == w.length -> Position.Final
                        else -> Position.Medial
                    }
                }
                else -> {
                    val letter = when (sound) {
                        SoundId.S -> 's'
                        SoundId.Sh -> 'ș'
                        SoundId.Z -> 'z'
                        SoundId.J -> 'j'
                        SoundId.Ts -> 'ț'
                        SoundId.R -> 'r'
                        SoundId.L -> 'l'
                        SoundId.Ce, SoundId.Ge -> error("handled above")
                    }
                    if (w[i] != letter) continue
                    out += when (i) {
                        0 -> Position.Initial
                        w.length - 1 -> Position.Final
                        else -> Position.Medial
                    }
                }
            }
        }
        return out
    }

    /** Occurrences in a word or a whole sentence. */
    fun count(text: String, sound: SoundId): Int = letters.findAll(text).sumOf { m ->
        val w = m.value.lowercase()
        when (sound) {
            SoundId.Ce, SoundId.Ge -> {
                val c = if (sound == SoundId.Ce) 'c' else 'g'
                (0 until w.length - 1).count { w[it] == c && w[it + 1] in "ei" }
            }
            else -> {
                val letter = mapOf(
                    SoundId.S to 's', SoundId.Sh to 'ș', SoundId.Z to 'z', SoundId.J to 'j',
                    SoundId.Ts to 'ț', SoundId.R to 'r', SoundId.L to 'l',
                ).getValue(sound)
                w.count { it == letter }
            }
        }
    }
}
