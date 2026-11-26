package tech.fonia.domain

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * „Cuvinte noi” (Plan Fonia utilă, fișa 7): every fourth lesson on a sound, from words on, opens with 25 words, later
 * 10 sentences, that are never practised. Nothing is said about a take. The phone's answer is kept quietly beside
 * the adult's own, so the two can be compared, and only the adult's marks count. The lists are drafts until a
 * speech therapist reads them (`ProbeLists.isApproved`), and drafts show only where drafts are shown.
 */
enum class ProbeLevel(override val rawValue: String) : StringRawValue {
    Words("words"),
    Sentences("sentences"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): ProbeLevel? = entries.byRawValue(rawValue)
    }
}

/** What the adult marks on an item: „Sună bine”, „Mai lucrăm” or „Sari peste”. Null leaves it unmarked. */
enum class ProbeMark(override val rawValue: String) : StringRawValue {
    Good("good"),
    KeepWorking("keepWorking"),
    Skip("skip"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): ProbeMark? = entries.byRawValue(rawValue)
    }
}

/** One item of a probe, as it was said. */
data class ProbeItem(
    val text: String,
    /**
     * The phone's answer, an `Attempt.Outcome` raw value: "good", "almost", "notYet", "unclear" or "notJudged".
     * Null when the item was never said.
     */
    val phone: String? = null,
    val adult: ProbeMark? = null,
) {
    /** The phone gave an answer about the sound. */
    internal val phoneJudged: Boolean get() = phone in listOf("good", "almost", "notYet")
}

/**
 * One probe: when, on which sound, what was said and marked. `finished` is false when the learner stopped before the
 * last item, so the share of probes done is the share of occasions taken up.
 */
data class NewWordsRecord(
    val id: UUID = UUID.randomUUID(),
    val date: Instant,
    val sound: String,
    val level: ProbeLevel,
    val items: List<ProbeItem>,
    val finished: Boolean,
) {
    data class Tally(
        val items: Int = 0,
        val good: Int = 0,
        val keepWorking: Int = 0,
        val skipped: Int = 0,
        val unmarked: Int = 0,
        val phoneGood: Int = 0,
        val phoneJudged: Int = 0,
        /** Items both the adult marked and the phone judged, and how many of them the two saw the same way. */
        val compared: Int = 0,
        val agreed: Int = 0,
    )

    val tally: Tally
        get() {
            var good = 0
            var keepWorking = 0
            var skipped = 0
            var unmarked = 0
            var phoneGood = 0
            var phoneJudged = 0
            var compared = 0
            var agreed = 0
            for (item in items) {
                when (item.adult) {
                    ProbeMark.Good -> good += 1
                    ProbeMark.KeepWorking -> keepWorking += 1
                    ProbeMark.Skip -> skipped += 1
                    null -> unmarked += 1
                }
                if (!item.phoneJudged) continue
                phoneJudged += 1
                if (item.phone == "good") phoneGood += 1
                val adult = item.adult
                if (adult == null || adult == ProbeMark.Skip) continue
                compared += 1
                if ((adult == ProbeMark.Good) == (item.phone == "good")) agreed += 1
            }
            return Tally(items.size, good, keepWorking, skipped, unmarked, phoneGood, phoneJudged, compared, agreed)
        }

    fun withMarks(marks: List<ProbeMark?>): NewWordsRecord =
        copy(items = items.mapIndexed { index, item -> if (index < marks.size) item.copy(adult = marks[index]) else item })
}

/** A probe as the export carries it: the counts, not the words. */
data class ProbeSummary(
    val date: Instant,
    val sound: String,
    val level: ProbeLevel,
    val finished: Boolean,
    val items: Int,
    val good: Int,
    val keepWorking: Int,
    val skipped: Int,
    val unmarked: Int,
    /** The adult's „Sună bine” over the items they marked good or not yet; null when they marked none. */
    val goodShare: Double?,
    val phoneGood: Int,
    val phoneJudged: Int,
    val compared: Int,
    val agreed: Int,
) {
    companion object {
        operator fun invoke(record: NewWordsRecord): ProbeSummary {
            val tally = record.tally
            val marked = tally.good + tally.keepWorking
            return ProbeSummary(
                date = record.date,
                sound = record.sound,
                level = record.level,
                finished = record.finished,
                items = tally.items,
                good = tally.good,
                keepWorking = tally.keepWorking,
                skipped = tally.skipped,
                unmarked = tally.unmarked,
                goodShare = if (marked > 0) tally.good.toDouble() / marked.toDouble() else null,
                phoneGood = tally.phoneGood,
                phoneJudged = tally.phoneJudged,
                compared = tally.compared,
                agreed = tally.agreed,
            )
        }
    }
}

/** The probe lists, one set per sound: words not used in any exercise, then sentences. */
data class ProbeLists(
    /** True once a speech therapist has read the lists (`review.status` "approved"). */
    val isApproved: Boolean,
    val sounds: Map<String, Lists>,
) {
    data class Lists(
        val words: List<String>,
        val sentences: List<String>,
    )

    fun items(sound: String, level: ProbeLevel): List<String> {
        val lists = sounds[sound] ?: sounds[nfc(sound)] ?: return emptyList()
        return if (level == ProbeLevel.Words) lists.words else lists.sentences
    }

    companion object {
        const val resource = "probes"

        fun parse(json: String): ProbeLists {
            val root = Json.parseToJsonElement(json).jsonObject
            val review = root.getValue("review").jsonObject
            val sounds = root.getValue("sounds").jsonObject.mapValues { (_, value) ->
                val lists = value.jsonObject
                Lists(
                    words = lists.getValue("words").jsonArray.map { it.jsonPrimitive.content },
                    sentences = lists.getValue("sentences").jsonArray.map { it.jsonPrimitive.content },
                )
            }
            return ProbeLists(isApproved = review.getValue("status").jsonPrimitive.content == "approved", sounds = sounds)
        }
    }
}

/** When a probe comes, what it asks, and what its marks suggest. Only the adult's marks count here. */
object ProbeRules {
    /** A probe opens every fourth lesson on a sound. */
    const val every = 4

    /** The first rung with a probe: words. */
    val firstRung: Rung = Rung.WordsInitial

    /** Under this many items on the list, there is no probe. */
    const val fewestItems = 8

    /**
     * Whether this lesson opens with „Cuvinte noi”: a lesson from words on, with three lessons on the sound since the
     * last probe was offered (or since the first lesson). Not at 4–5, where the lesson alone is already long enough.
     */
    fun isDue(rung: Rung, lessonsSince: Int, age: AgeBand): Boolean =
        age != AgeBand.FourToFive && rung >= firstRung && lessonsSince >= every - 1

    /** Sentences once the adult said yes to them for this sound, words before. */
    fun level(sound: String, sentencesFor: Set<String>): ProbeLevel =
        if (sound in sentencesFor) ProbeLevel.Sentences else ProbeLevel.Words

    /** From eight years on, the learner reads each item: no model first. Younger children hear it once and say it. */
    fun reads(age: AgeBand): Boolean = age == AgeBand.EightToTen || age == AgeBand.TeenAdult

    /**
     * The last two finished word probes of a sound both have „Sună bine” on at least 20 of 25 items (80%).
     * `records` are one sound's probes, oldest first.
     */
    fun suggestsSentences(records: List<NewWordsRecord>): Boolean {
        val words = records.filter { it.finished && it.level == ProbeLevel.Words }.takeLast(2)
        if (words.size != 2) return false
        return words.all { record ->
            val tally = record.tally
            tally.items > 0 && tally.good * 5 >= tally.items * 4
        }
    }

    /** The last finished sentence probe has „Sună bine” on more than 80% of its items. */
    fun tellsTherapist(records: List<NewWordsRecord>): Boolean {
        val last = records.lastOrNull { it.finished && it.level == ProbeLevel.Sentences } ?: return false
        val tally = last.tally
        return tally.items > 0 && tally.good * 5 > tally.items * 4
    }
}
