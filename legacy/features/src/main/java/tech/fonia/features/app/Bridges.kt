package tech.fonia.features.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import tech.fonia.design.Field
import tech.fonia.design.MouthShape
import tech.fonia.design.TonguePose
import tech.fonia.domain.AgeBand
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.MouthParams
import tech.fonia.domain.Progression
import tech.fonia.domain.ProgressionState
import tech.fonia.domain.Rung
import tech.fonia.domain.SoundDef
import tech.fonia.domain.SoundFamily
import tech.fonia.domain.SoundID
import tech.fonia.domain.TonguePos
import tech.fonia.dsp.TalkerCalibration
import tech.fonia.dsp.VoiceGroup
import tech.fonia.store.JSONObject
import tech.fonia.store.JSONValue
import tech.fonia.store.Profile
import tech.fonia.store.SoundProgress
import java.text.BreakIterator
import kotlin.math.max
import kotlin.math.min

// Bridges.swift: conversions between the modules, so each module keeps its own types.

val SoundFamily.field: Field
    get() = when (this) {
        SoundFamily.ColdAir -> Field.cold
        SoundFamily.WarmAir -> Field.warm
        SoundFamily.TongueTip -> Field.tip
    }

val MouthParams.shape: MouthShape
    get() = MouthShape(
        open = open,
        width = width,
        round = round,
        smile = smile,
        teeth = teeth,
        puff = puff,
        tongue = tongue.pose,
        vibrate = vibrate,
        lipBite = lipBite,
    )

val TonguePos.pose: TonguePose
    get() = when (this) {
        TonguePos.Rest -> TonguePose.rest
        TonguePos.Up -> TonguePose.up
        TonguePos.TipUp -> TonguePose.tipUp
        TonguePos.Between -> TonguePose.between
        TonguePos.Out -> TonguePose.`out`
        TonguePos.OutUp -> TonguePose.outUp
        TonguePos.OutLeft -> TonguePose.outLeft
        TonguePos.OutRight -> TonguePose.outRight
        TonguePos.Hidden -> TonguePose.hidden
    }

val Profile.domainAge: AgeBand
    get() = when (ageBand) {
        Profile.AgeBand.FourToFive -> AgeBand.FourToFive
        Profile.AgeBand.SixToSeven -> AgeBand.SixToSeven
        Profile.AgeBand.EightToTen -> AgeBand.EightToTen
        Profile.AgeBand.Adult -> AgeBand.TeenAdult
    }

val Profile.dspGroup: VoiceGroup
    get() = when (voiceGroup) {
        Profile.VoiceGroup.Child -> VoiceGroup.CHILD
        Profile.VoiceGroup.Female -> VoiceGroup.FEMALE
        Profile.VoiceGroup.Male -> VoiceGroup.MALE
    }

val Profile.isChild: Boolean get() = kind == Profile.Kind.Child

/**
 * The stored boundary, or at least the age prior: children's spectra sit higher than adults'.
 *
 * iOS re-encodes the stored `JSONObject` with `JSONEncoder` and decodes it as `TalkerCalibration`; here the object
 * goes back to JSON text and through `TalkerCalibration.fromJson`, the port of the same synthesized `Codable`.
 */
val Profile.calibration: TalkerCalibration?
    get() {
        val stored = sibilantCalibration
        if (stored != null) {
            runCatching { TalkerCalibration.fromJson(stored.toJsonText()) }.getOrNull()?.let { return it }
        }
        return when (ageBand) {
            Profile.AgeBand.FourToFive -> TalkerCalibration(ageYears = 5)
            Profile.AgeBand.SixToSeven -> TalkerCalibration(ageYears = 6)
            Profile.AgeBand.EightToTen -> TalkerCalibration(ageYears = 9)
            Profile.AgeBand.Adult -> null
        }
    }

/** Swift's `mutating func setCalibration`: a value type, so here a changed copy. A calibration that can't be read back is not stored. */
fun Profile.settingCalibration(calibration: TalkerCalibration): Profile {
    val stored = runCatching { jsonObjectFromText(calibration.toJson()) }.getOrNull() ?: return this
    return copy(sibilantCalibration = stored)
}

/** What this person is working on, in the order an adult chose, then the rest. */
fun Profile.orderedSounds(content: FoniaContent): List<SoundDef> {
    val chosen = targetSounds.mapNotNull { SoundID.fromRawValue(it) }.map { content.sound(it) }
    val others = content.sounds.filter { it.id.rawValue !in targetSounds }
    return chosen + others
}

fun Profile.progressionState(sound: SoundID, progress: SoundProgress?): ProgressionState {
    val history = progress?.blockHistory?.filter { it.rung == 0 } ?: emptyList()
    return ProgressionState(
        rung = Rung.at(progress?.rung ?: 0),
        adultConfirmed = sound.rawValue in confirmedSounds,
        lockEnabled = requireConfirmation,
        openedByParent = openPath,
        establishCorrect = history.fold(0) { sum, block -> sum + block.good },
        weakEstablishBlocks = history.count { it.good < Progression.dropBelow },
    )
}

/** `Rung.at(_:)`: the rung at an index, held inside the ladder. */
fun Rung.Companion.at(index: Int): Rung = Rung.entries[max(0, min(Rung.entries.size - 1, index))]

val Rung.index: Int get() = Rung.entries.indexOf(this).let { if (it < 0) 0 else it }

fun Rung.title(letter: String): String = when (this) {
    Rung.Isolated -> "Doar sunetul $letter"
    Rung.Syllables -> "Silabe cu $letter"
    Rung.WordsInitial -> "Cuvinte care încep cu $letter"
    Rung.WordsMedial -> "Cuvinte cu $letter la mijloc"
    Rung.WordsFinal -> "Cuvinte care se termină cu $letter"
    Rung.Clusters -> "Cuvinte mai grele cu $letter"
    Rung.Pairs -> "Cuvinte care seamănă"
    Rung.Sentences -> "Propoziții cu $letter"
    Rung.Retell -> "Povești cu $letter"
    Rung.Everyday -> "$letter acasă și afară"
}

/** For a sentence that gets glued after other words. */
val String.lowercasedFirstLetterForSentence: String
    get() {
        val first = firstCharacter() ?: return this
        return first.lowercase() + substring(first.length)
    }

/** A rough time, for the line under the title. About six attempts a minute. */
fun AgeBand.minutes(attempts: Int): Int = max(3, min(10, attempts / 6))

val SoundDef.shape: MouthShape get() = viseme.mouth.shape

val SoundDef.lowercaseLetter: String
    get() = letter.split("/").firstOrNull { it.isNotEmpty() }?.lowercase() ?: letter.lowercase()

val SoundDef.shortLetter: String
    get() = letter.split("/").firstOrNull { it.isNotEmpty() } ?: letter

// Rows.swift and SoundGuide.swift: the String helpers the screens share.

val String.firstSentence: String
    get() {
        val end = indexOfFirst { it in ".!?" }
        return if (end < 0) this else substring(0, end + 1)
    }

/** Swift's `lowercasedFirstLetter`. A sound letter opening the phrase stays capital: "S în mijlocul cuvântului". */
val String.lowercasedFirstLetter: String
    get() {
        val first = firstCharacter() ?: return this
        val rest = substring(first.length)
        if (characterCount() > 1) {
            val second = rest.firstCharacter()
            if (second != null && (second.isWhitespaceCharacter() || second.isUppercaseCharacter())) return this
        }
        return first.lowercase() + rest
    }

val String.capitalizedFirstLetter: String
    get() {
        val first = firstCharacter() ?: return this
        return first.uppercase() + substring(first.length)
    }

/** Swift's `String.first`: the first extended grapheme cluster. */
internal fun String.firstCharacter(): String? {
    if (isEmpty()) return null
    val breaks = BreakIterator.getCharacterInstance()
    breaks.setText(this)
    val end = breaks.next()
    return substring(0, if (end == BreakIterator.DONE) length else end)
}

internal fun String.characterCount(): Int {
    if (isEmpty()) return 0
    val breaks = BreakIterator.getCharacterInstance()
    breaks.setText(this)
    var count = 0
    while (breaks.next() != BreakIterator.DONE) count++
    return count
}

private fun String.isWhitespaceCharacter(): Boolean = isNotEmpty() && codePointAt(0).let { Character.isWhitespace(it) || Character.isSpaceChar(it) }

private fun String.isUppercaseCharacter(): Boolean = isNotEmpty() && Character.isUpperCase(codePointAt(0))

// JSONObject <-> JSON text, so a calibration can travel through JSON as it does on iOS.

private val bridgeJson = Json

internal fun JSONObject.toJsonText(): String = bridgeJson.encodeToString(JsonElement.serializer(), JsonObject(mapValues { it.value.toElement() }))

internal fun jsonObjectFromText(text: String): JSONObject {
    val element = bridgeJson.parseToJsonElement(text)
    require(element is JsonObject) { "Expected a JSON object" }
    return element.mapValues { it.value.toJSONValue() }
}

private fun JSONValue.toElement(): JsonElement = when (this) {
    JSONValue.Null -> JsonNull
    is JSONValue.Bool -> JsonPrimitive(value)
    // Whole numbers go out without ".0", as JSONEncoder writes them, so `ageYears` reads back as an Int.
    is JSONValue.Number -> if (value == Math.rint(value) && kotlin.math.abs(value) < 9.007199254740992E15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
    is JSONValue.String -> JsonPrimitive(value)
    is JSONValue.Array -> JsonArray(value.map { it.toElement() })
    is JSONValue.Object -> JsonObject(value.mapValues { it.value.toElement() })
}

private fun JsonElement.toJSONValue(): JSONValue = when (this) {
    JsonNull -> JSONValue.Null
    is JsonPrimitive -> when {
        isString -> JSONValue.String(content)
        booleanOrNull != null -> JSONValue.Bool(booleanOrNull!!)
        else -> JSONValue.Number(content.toDouble())
    }
    is JsonArray -> JSONValue.Array(map { it.toJSONValue() })
    is JsonObject -> JSONValue.Object(mapValues { it.value.toJSONValue() })
}
