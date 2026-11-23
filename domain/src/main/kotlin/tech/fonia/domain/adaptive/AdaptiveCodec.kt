package tech.fonia.domain.adaptive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import tech.fonia.domain.Rung
import tech.fonia.domain.SoundID
import kotlin.math.roundToLong

/**
 * The adaptive pilot's file for one profile, and its summary for the logoped. Codes and numbers only: no names, no
 * words the child said, no audio. A value that can't be read falls back to its default, so a damaged file loses that
 * value and nothing else.
 */
object AdaptiveCodec {
    const val FORMAT = 1

    fun encode(profile: AdaptiveProfile): String = buildJsonObject {
        put("format", FORMAT)
        put("enabled", profile.enabled)
        putJsonObject("sounds") {
            for (id in SoundID.entries) {
                val sound = profile.sounds[id] ?: continue
                put(id.rawValue, encode(sound))
            }
        }
    }.toString()

    fun decode(text: String): AdaptiveProfile {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return AdaptiveProfile()
        val sounds = (root["sounds"] as? JsonObject)?.mapNotNull { (raw, node) ->
            val id = SoundID.fromRawValue(raw) ?: return@mapNotNull null
            val decoded = (node as? JsonObject)?.let { runCatching { decodeSound(it) }.getOrNull() } ?: return@mapNotNull null
            id to decoded
        }?.toMap() ?: emptyMap()
        return AdaptiveProfile(enabled = root.bool("enabled") ?: false, sounds = sounds)
    }

    private fun encode(sound: SoundAdaptive): JsonObject = buildJsonObject {
        put("setting", sound.setting.rawValue)
        sound.mode?.let { put("mode", it.rawValue) }
        putJsonArray("sessions") {
            for (session in sound.sessions) add(buildJsonObject { put("day", session.day); put("good", session.good); put("judged", session.judged) })
        }
        sound.challenge?.let { put("challenge", encode(it)) }
        putJsonArray("mastery") {
            for (key in sound.mastery) {
                add(
                    buildJsonObject {
                        put("position", key.position.rawValue)
                        put("rung", key.rung.rawValue)
                        put("p", key.pKnown)
                        put("takes", key.takes)
                        putJsonArray("masteredOn") { key.masteredOn.forEach { add(JsonPrimitive(it)) } }
                    },
                )
            }
        }
    }

    private fun encode(state: ChallengeState): JsonObject = buildJsonObject {
        put("ceiling", state.ceiling.rawValue)
        put("rung", state.step.rung.rawValue)
        put("fast", state.step.fast)
        put("kp", state.kp)
        put("sinceMove", tallies(state.sinceMove))
        put("recent", tallies(state.recent))
    }

    private fun tallies(list: List<BlockTally>): JsonArray = buildJsonArray {
        for (tally in list) add(buildJsonObject { put("good", tally.good); put("total", tally.total) })
    }

    private fun decodeSound(node: JsonObject): SoundAdaptive = SoundAdaptive(
        setting = node.text("setting")?.let { ModeSetting.fromRawValue(it) } ?: ModeSetting.Auto,
        mode = node.text("mode")?.let { PracticeMode.fromRawValue(it) },
        sessions = node.objects("sessions").mapNotNull { session ->
            val day = session.text("day") ?: return@mapNotNull null
            SessionTally(day, session.int("good") ?: 0, session.int("judged") ?: 0)
        },
        challenge = (node["challenge"] as? JsonObject)?.let { decodeChallenge(it) },
        mastery = node.objects("mastery").mapNotNull { key ->
            val position = key.text("position")?.let { ItemPosition.fromRawValue(it) } ?: return@mapNotNull null
            val rung = key.text("rung")?.let { Rung.fromRawValue(it) } ?: return@mapNotNull null
            KeyMastery(
                position = position,
                rung = rung,
                pKnown = key.double("p")?.takeIf { it in 0.0..1.0 } ?: Bkt.P_INIT,
                takes = key.int("takes") ?: 0,
                masteredOn = (key["masteredOn"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            )
        },
    )

    private fun decodeChallenge(node: JsonObject): ChallengeState? {
        val ceiling = node.text("ceiling")?.let { Rung.fromRawValue(it) } ?: return null
        val rung = node.text("rung")?.let { Rung.fromRawValue(it) } ?: ceiling
        val tallies = { key: String -> node.objects(key).map { BlockTally(it.int("good") ?: 0, it.int("total") ?: 0) } }
        return ChallengeState(
            ceiling = ceiling,
            step = Step(rung, node.bool("fast") ?: false),
            sinceMove = tallies("sinceMove"),
            recent = tallies("recent"),
            kp = node.bool("kp") ?: false,
        )
    }

    /**
     * The adaptive part of the summary for the logoped (03 §4.3): per sound, the setting and the mode, the step the
     * controller stands on, the sessions' takes, and P(mastered) per position × rung, with the rules' numbers so the
     * values can be read against the version that made them.
     */
    fun summary(profile: AdaptiveProfile): JsonObject = buildJsonObject {
        put("enabled", profile.enabled)
        put("rules", rules())
        putJsonArray("sounds") {
            for (id in SoundID.entries) {
                val sound = profile.sounds[id] ?: continue
                add(
                    buildJsonObject {
                        put("sound", id.rawValue)
                        put("setting", sound.setting.rawValue)
                        put("mode", sound.mode?.rawValue)
                        sound.challenge?.let { challenge ->
                            putJsonObject("step") {
                                put("ceiling", challenge.ceiling.rawValue)
                                put("rung", challenge.step.rung.rawValue)
                                put("fast", challenge.step.fast)
                                put("kp", challenge.kp)
                            }
                        }
                        putJsonArray("sessions") {
                            for (session in sound.sessions) add(buildJsonObject { put("on", session.day); put("good", session.good); put("judged", session.judged) })
                        }
                        putJsonArray("mastery") {
                            for (key in sound.mastery) {
                                add(
                                    buildJsonObject {
                                        put("position", key.position.rawValue)
                                        put("rung", key.rung.rawValue)
                                        put("p", (key.pKnown * 100).roundToLong() / 100.0)
                                        put("takes", key.takes)
                                        put("daysAtLeast90", key.masteredOn.size)
                                        put("lastDayAtLeast90", key.masteredOn.lastOrNull())
                                        put("mastered", key.isMastered)
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }
    }

    /** The constants behind every decision, as the export states them. */
    fun rules(): JsonObject = buildJsonObject {
        putJsonObject("bkt") {
            put("pInit", Bkt.P_INIT)
            put("pLearn", Bkt.P_LEARN)
            put("guess", Bkt.DETECTOR_GUESS)
            put("slip", Bkt.DETECTOR_SLIP)
            put("adultWeight", Bkt.ADULT_WEIGHT)
            put("masteredAt", Bkt.MASTERED_AT)
            put("masteredDays", Bkt.MASTERED_DAYS)
            put("minTakesInSession", Bkt.MIN_TAKES_IN_SESSION)
        }
        putJsonObject("challengePoint") {
            put("targetLow", ChallengePoint.TARGET_LOW)
            put("targetHigh", ChallengePoint.TARGET_HIGH)
            put("harderAbove", ChallengePoint.HARDER_ABOVE)
            put("harderAfterBlocks", ChallengePoint.HARDER_AFTER_BLOCKS)
            put("easierBelow", ChallengePoint.EASIER_BELOW)
            put("minJudgedTakes", ChallengePoint.MIN_JUDGED_TAKES)
        }
        putJsonObject("modes") {
            put("acquisitionBelow", PracticeModes.ACQUISITION_BELOW)
            put("generalizationAt", PracticeModes.GENERALIZATION_AT)
            put("generalizationSessions", PracticeModes.GENERALIZATION_SESSIONS)
            put("acquisitionTargetsPerBlock", PracticeModes.ACQUISITION_TARGETS_PER_BLOCK)
        }
        putJsonObject("results") {
            put("krStart", FeedbackFading.KR_START)
            put("krEnd", FeedbackFading.KR_END)
            put("fadeFrom", FeedbackFading.FADE_FROM)
            put("fadeTo", FeedbackFading.FADE_TO)
        }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
}
