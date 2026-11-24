package tech.fonia.features.pilot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import kotlin.math.roundToLong

/** Everything the summary for the logoped is made from: the pilot log of one profile, read on the log's thread. */
data class PilotExportInput(
    val generatedOn: LocalDate,
    val install: PilotInstall?,
    val profile: PilotProfile,
    /** `Profile.targetSounds`: sound codes chosen by an adult. */
    val targetSounds: List<String>,
    val detailDays: Int,
    /** `DSPThresholds.version` of the verdicts the child got. */
    val dspThresholdsVersion: Int,
    val counters: List<AttemptCounter>,
    val sessionDays: List<SessionDay>,
    val shadowCounters: List<ShadowCounter>,
    val shadowDetails: List<ShadowDetail>,
    val shadowVersions: List<Pair<String, String>>,
    val phoneVsAdult: List<PhoneVsAdult>,
    val access: List<AccessEntry>,
    /** The adaptive pilot's state of the profile (`AdaptiveCodec.summary`), when it was ever in the pilot. */
    val adaptive: JsonObject? = null,
    /** The adaptive pilot's decisions, counted by day. */
    val adaptiveCounters: List<AdaptiveCounter> = emptyList(),
)

/**
 * The summary for the logoped (03 §4.3), added to the grown-ups' export: JSON, aggregated, under a random pseudonym.
 * It holds counts, codes and dates only. No audio, no names, no notes, no words the child said or typed: positions,
 * sounds, rungs and verdicts are codes. Keys keep their order, and [FORMAT_VERSION] changes with the shape.
 *
 * Agreement figures (phone against adult, phone against ZIPA by abstention reason and latency) cover the detail period
 * ([PilotExportInput.detailDays]); counts of practice and of shadow verdicts cover everything since setup.
 */
object PilotExport {
    const val FORMAT = "fonia-pilot-summary"
    /**
     * 2: the `phone` section (model and Android version) is gone, as on iOS. The file travels with the logoped file,
     * and the phone model narrows down whose child it is.
     */
    const val FORMAT_VERSION = 2

    private val json = Json { prettyPrint = true }

    fun encode(input: PilotExportInput): ByteArray = json.encodeToString(JsonElement.serializer(), document(input)).toByteArray(Charsets.UTF_8)

    /** `fonia-exersare-<pseudonym>.json`: the file name names no one. */
    fun fileName(pseudonym: String): String = "fonia-exersare-$pseudonym.json"

    fun document(input: PilotExportInput): JsonObject {
        val today = input.generatedOn
        val counters = input.counters
        val sessions = input.sessionDays
        val setup = PilotMath.day(input.profile.setupDay)
        val activeDays = PilotMath.activeDays(counters, sessions)
        val firstDataDay = (activeDays + listOfNotNull(setup)).minOrNull()
        return buildJsonObject {
            put("format", FORMAT)
            put("formatVersion", FORMAT_VERSION)
            put("generatedOn", today.toString())
            put("pseudonym", input.profile.pseudonym)
            putJsonObject("profile") {
                put("kind", input.profile.kind)
                put("ageBand", input.profile.ageBand)
                putJsonArray("targetSounds") { input.targetSounds.forEach { add(JsonPrimitive(it)) } }
            }
            putJsonObject("period") {
                put("from", firstDataDay?.toString())
                put("to", today.toString())
            }
            putJsonObject("app") {
                put("version", input.install?.appVersion)
                put("dspThresholds", input.dspThresholdsVersion)
                put("detailDays", input.detailDays)
            }
            putJsonObject("activation") {
                put("loggingSince", input.install?.day)
                put("setUpOn", input.profile.setupDay)
                put("firstSessionOn", sessions.firstOrNull { it.sessions > 0 }?.day)
                put("firstSessionFinishedOn", sessions.firstOrNull { it.finished > 0 }?.day)
            }
            put("retention", retention(setup, activeDays, today))
            put("days", days(counters, sessions))
            put("weeks", weeks(counters, sessions))
            put("sounds", sounds(counters, today))
            put("phoneVsAdult", phoneVsAdult(input.phoneVsAdult))
            put("shadow", shadow(input))
            putJsonArray("accessLog") {
                for (entry in input.access) add(buildJsonObject { put("on", entry.day); put("action", entry.action) })
            }
            // Only for a profile that was in the adaptive pilot: every other summary keeps the keys above, as before.
            adaptive(input)?.let { put("adaptive", it) }
        }
    }

    /**
     * The adaptive pilot (docs/PLAN.md §7.4): its state per sound with the rules' numbers, then every decision it made,
     * counted by day, sound, kind, choice and reason.
     */
    private fun adaptive(input: PilotExportInput): JsonObject? {
        if (input.adaptive == null && input.adaptiveCounters.isEmpty()) return null
        return buildJsonObject {
            input.adaptive?.forEach { (key, value) -> put(key, value) }
            putJsonArray("decisions") {
                for (counter in input.adaptiveCounters) {
                    add(
                        buildJsonObject {
                            put("on", counter.day)
                            // The profile's switch is about every sound: no sound.
                            put("sound", counter.sound.ifEmpty { null })
                            put("kind", counter.kind)
                            put("choice", counter.choice)
                            put("reason", counter.reason)
                            put("count", counter.decisions)
                        },
                    )
                }
            }
        }
    }

    private fun retention(setup: LocalDate?, activeDays: Set<LocalDate>, today: LocalDate): JsonObject {
        val markers = setup?.let { PilotMath.retention(it, activeDays, today) }
        return buildJsonObject {
            put("definition", RetentionMarkers.DEFINITION)
            put("lastActiveOn", markers?.lastActive?.toString())
            for (n in RetentionMarkers.DAYS) put("activeAtDay$n", markers?.activeAt?.get(n))
        }
    }

    private fun days(counters: List<AttemptCounter>, sessions: List<SessionDay>): JsonArray {
        val byDay = counters.groupBy { it.day }
        val sessionByDay = sessions.associateBy { it.day }
        val keys = (byDay.keys + sessionByDay.keys).sorted()
        return buildJsonArray {
            for (day in keys) {
                val rows = byDay[day].orEmpty()
                val session = sessionByDay[day]
                add(
                    buildJsonObject {
                        put("date", day)
                        put("sessions", session?.sessions ?: 0)
                        put("sessionsFinished", session?.finished ?: 0)
                        put("minutes", minutes(session?.seconds ?: 0))
                        put("productionAttempts", rows.filter { it.mode == AttemptMode.Production.code }.sumOf { it.attempts })
                        put("doseAttempts", rows.sumOf { it.dose })
                        put("listeningAttempts", rows.filter { it.mode == AttemptMode.Listening.code }.sumOf { it.attempts })
                        put("mirrorAttempts", rows.filter { it.mode == AttemptMode.Mirror.code }.sumOf { it.attempts })
                    },
                )
            }
        }
    }

    private fun weeks(counters: List<AttemptCounter>, sessions: List<SessionDay>): JsonArray {
        val active = PilotMath.activeDays(counters, sessions)
        val doseByDay = counters.groupBy { it.day }.mapValues { (_, rows) -> rows.sumOf { it.dose } }
        val secondsByDay = sessions.associate { it.day to it.seconds }
        val mondays = active.map(PilotMath::monday).toSortedSet()
        return buildJsonArray {
            for (monday in mondays) {
                val days = (0L until 7L).map { monday.plusDays(it) }
                add(
                    buildJsonObject {
                        put("weekOf", monday.toString())
                        put("activeDays", days.count { it in active })
                        put("minutes", minutes(days.sumOf { secondsByDay[it.toString()] ?: 0L }))
                        put("doseAttempts", days.sumOf { doseByDay[it.toString()] ?: 0 })
                    },
                )
            }
        }
    }

    private fun sounds(counters: List<AttemptCounter>, today: LocalDate): JsonArray {
        val productions = counters.filter { it.mode == AttemptMode.Production.code && it.sound.isNotEmpty() }
        val doses = PilotMath.doses(counters, today).sounds.associateBy { it.sound }
        return buildJsonArray {
            for ((sound, rows) in productions.groupBy { it.sound }.toSortedMap()) {
                val dose = doses[sound]
                add(
                    buildJsonObject {
                        put("sound", sound)
                        put("doseAttempts", rows.sumOf { it.dose })
                        put("activeDays", rows.filter { it.dose > 0 }.map { it.day }.toSet().size)
                        putJsonObject("intensiveWeek") {
                            val week = dose?.intensiveWeek
                            put("startedOn", week?.start?.toString())
                            put("daysWithPractice", week?.daysWithPractice)
                            put("complete", week?.isComplete)
                            put("open", week?.isOpen)
                        }
                        putJsonArray("weekly") {
                            if (dose != null) {
                                for (monday in dose.byDay.keys.map(PilotMath::monday).toSortedSet()) {
                                    val week = dose.week(monday)
                                    add(buildJsonObject { put("weekOf", monday.toString()); put("doseAttempts", week.attempts); put("activeDays", week.days) })
                                }
                            }
                        }
                        putJsonArray("items") {
                            val groups = rows.groupBy { it.position to it.rung }.toList().sortedWith(compareBy({ it.first.first }, { it.first.second }))
                            for ((key, items) in groups) add(item(key.first, key.second, items))
                        }
                    },
                )
            }
        }
    }

    /** One sound × position × rung: attempts, verdicts, % good (good of the takes with a verdict), days, feedback. */
    private fun item(position: String, rung: String, rows: List<AttemptCounter>): JsonObject {
        val good = rows.sumOf { it.good }
        val almost = rows.sumOf { it.almost }
        val notYet = rows.sumOf { it.notYet }
        val judged = good + almost + notYet
        return buildJsonObject {
            put("position", position)
            put("rung", rung)
            put("attempts", rows.sumOf { it.attempts })
            put("doseAttempts", rows.sumOf { it.dose })
            put("good", good)
            put("almost", almost)
            put("notYet", notYet)
            put("unjudged", rows.sumOf { it.unjudged })
            put("percentGood", if (judged == 0) null else (good * 1000.0 / judged).roundToLong() / 10.0)
            put("activeDays", rows.filter { it.attempts > 0 }.map { it.day }.toSet().size)
            putJsonObject("byFeedback") {
                for ((level, levelRows) in rows.groupBy { it.feedback }.toSortedMap()) put(level.ifEmpty { "none" }, levelRows.sumOf { it.attempts })
            }
        }
    }

    private fun phoneVsAdult(rows: List<PhoneVsAdult>): JsonArray = buildJsonArray {
        for (row in rows) {
            add(
                buildJsonObject {
                    put("sound", row.sound)
                    put("compared", row.compared)
                    put("agreed", row.agreed)
                    put("phoneAbstained", row.phoneAbstained)
                },
            )
        }
    }

    private fun shadow(input: PilotExportInput): JsonObject {
        val counters = input.shadowCounters
        val scored = counters.filter { it.status == ShadowStatus.Scored.code }
        return buildJsonObject {
            putJsonArray("models") { input.shadowVersions.map { it.first }.distinct().filter { it.isNotEmpty() }.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("policies") { input.shadowVersions.map { it.second }.distinct().filter { it.isNotEmpty() }.forEach { add(JsonPrimitive(it)) } }
            put("takes", counters.sumOf { it.takes })
            for (status in ShadowStatus.entries) put(status.code, counters.filter { it.status == status.code }.sumOf { it.takes })
            putJsonArray("bySound") {
                for ((sound, rows) in scored.groupBy { it.sound }.toSortedMap()) {
                    val details = input.shadowDetails.filter { it.sound == sound }
                    add(
                        buildJsonObject {
                            put("sound", sound)
                            put("scored", rows.sumOf { it.takes })
                            var compared = 0
                            var agreed = 0
                            var zipaAbstained = 0
                            var phoneAbstained = 0
                            for (row in rows) {
                                val phone = PilotMath.shownAsVerdict(row.shown)
                                val zipa = PilotMath.zipaAsVerdict(row.verdict)
                                when {
                                    phone == null -> phoneAbstained += row.takes
                                    zipa == null -> zipaAbstained += row.takes
                                    else -> {
                                        compared += row.takes
                                        if (phone == zipa) agreed += row.takes
                                    }
                                }
                            }
                            put("bothDecided", compared)
                            put("agreed", agreed)
                            put("phoneAbstained", phoneAbstained)
                            put("zipaAbstained", zipaAbstained)
                            putJsonObject("matrix") {
                                for ((shown, shownRows) in rows.groupBy { it.shown }.toSortedMap()) {
                                    putJsonObject(shown) {
                                        for ((verdict, verdictRows) in shownRows.groupBy { it.verdict }.toSortedMap()) put(verdict.ifEmpty { "none" }, verdictRows.sumOf { it.takes })
                                    }
                                }
                            }
                            putJsonObject("zipaAbstentions") {
                                for ((reason, reasonRows) in details.filter { it.abstention != null }.groupBy { it.abstention!! }.toSortedMap()) put(reason, reasonRows.size)
                            }
                            put("medianScoreMs", median(details.mapNotNull { it.scoreMs }))
                        },
                    )
                }
            }
        }
    }

    private fun minutes(seconds: Long): Long = (seconds + 30) / 60

    /** The lower median, a value that occurred. */
    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[(sorted.size - 1) / 2]
    }
}
