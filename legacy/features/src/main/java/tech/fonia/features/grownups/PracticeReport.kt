package tech.fonia.features.grownups

import tech.fonia.domain.Catalog
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.Plural
import tech.fonia.domain.Rung
import tech.fonia.domain.SoundID
import tech.fonia.features.app.at
import tech.fonia.features.app.index
import tech.fonia.features.app.isChild
import tech.fonia.features.app.lowercasedFirstLetterForSentence
import tech.fonia.features.app.orderedSounds
import tech.fonia.features.app.shortLetter
import tech.fonia.features.app.title
import tech.fonia.features.guide.AdultCards
import tech.fonia.features.journal.MissionFollowUp
import tech.fonia.features.reading.ReadingReport
import tech.fonia.store.Attempt
import tech.fonia.store.PracticeData
import tech.fonia.store.Profile
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil
import kotlin.math.max

// The report a parent hands to the speech therapist: what was practised, how often, what the phone and the adult
// thought of each try, and what the adult noticed. It is read by an adult professional, so it carries counts, but
// never a verdict, a level or a score. It is built from the export's pseudonymous copy, so no name gets in.

/** Everything the PDF prints, already in words and numbers, so it can be checked without drawing it (iOS `PracticeReport`). */
data class PracticeReport(
    val ageBand: Profile.AgeBand,
    val isChild: Boolean,
    val chosenSounds: List<String>,
    val createdOn: LocalDate,
    /** The first and last day with practice; null when there is none. */
    val first: LocalDate?,
    val last: LocalDate?,
    val days: Int,
    val sessions: Int,
    val minutes: Int,
    val attempts: Int,
    val sounds: List<SoundPart>,
    /** Newest first. */
    val notes: List<Note>,
) {
    /**
     * How many tries got each answer. [none] is "no answer": the phone didn't hear clearly or didn't judge, or the
     * adult didn't pick anything.
     */
    data class Tally(val good: Int = 0, val almost: Int = 0, val notYet: Int = 0, val none: Int = 0) {
        val total: Int get() = good + almost + notYet + none
        val judged: Int get() = good + almost + notYet

        fun adding(outcome: Attempt.Outcome?): Tally = when (outcome) {
            Attempt.Outcome.Good -> copy(good = good + 1)
            Attempt.Outcome.Almost -> copy(almost = almost + 1)
            Attempt.Outcome.NotYet -> copy(notYet = notYet + 1)
            Attempt.Outcome.Unclear, Attempt.Outcome.NotJudged, null -> copy(none = none + 1)
        }
    }

    /** Tries in one week, which starts on [monday]. */
    data class Week(val monday: LocalDate, val attempts: Int)

    /** One sound that was worked on. */
    data class SoundPart(
        val letter: String,
        val title: String,
        /** 1 for the plain sound, up to [rungs]. */
        val rung: Int,
        val rungs: Int,
        /** The course's name for the lesson on that rung, and its line. */
        val lesson: String,
        val lessonDetail: String,
        /** The stage name of that rung ([StageNames]): where the lesson sits in the course, not a grade. */
        val stage: String,
        val confirmedByAdult: Boolean,
        val attempts: Int,
        val days: Int,
        val phone: Tally,
        val adult: Tally,
        /** Tries both the phone and the adult answered, and how many of those got the same answer. */
        val compared: Int,
        val agreed: Int,
        val weeks: List<Week>,
    ) {
        /** "Treapta 4 din 10: „În mijlocul cuvântului” (cuvinte cu S la mijloc)." */
        val ladder: String
            get() {
                // "S singur" keeps its capital: the first word is the sound itself.
                val firstWord = lessonDetail.takeWhile { it != ' ' }
                val shown = if (firstWord.length > 1) lessonDetail.lowercasedFirstLetterForSentence else lessonDetail
                val detail = if (lessonDetail.isEmpty()) "" else " ($shown)"
                return "Treapta $rung din $rungs: „$lesson”$detail."
            }

        /** "Etapa: Consolidare." */
        val stageLine: String get() = AdultCards.stageLine(stage)

        val confirmed: String? get() = if (confirmedByAdult) "Adultul a ascultat $letter singur și a spus că e bine." else null

        val tries: String get() = "${Plural.format(attempts, Plural.Noun.Incercare)} în ${Plural.format(days, Plural.Noun.Zi)}."

        val rows: List<List<String>>
            get() = listOf(
                TABLE_HEADER,
                listOf("Telefonul", "${phone.good}", "${phone.almost}", "${phone.notYet}", "${phone.none}"),
                listOf("Adultul", "${adult.good}", "${adult.almost}", "${adult.notYet}", "${adult.none}"),
            )

        /** How often the two said the same, as counts. */
        val agreement: String
            get() {
                if (compared == 0) {
                    return if (adult.judged == 0) {
                        "Adultul nu a ales încă nimic la încercările cu $letter."
                    } else {
                        "Telefonul nu a răspuns la încercările pe care le-a ales adultul."
                    }
                }
                if (compared == 1) {
                    return if (agreed == 1) {
                        "La singura încercare la care au răspuns amândoi, telefonul și adultul au spus același lucru."
                    } else {
                        "La singura încercare la care au răspuns amândoi, telefonul și adultul au spus lucruri diferite."
                    }
                }
                val times = when (agreed) {
                    0 -> "la niciuna"
                    1 -> "o dată"
                    else -> "de ${Plural.format(agreed, Plural.Noun.Oara)}"
                }
                return "Din ${Plural.format(compared, Plural.Noun.Incercare)} la care au răspuns amândoi, telefonul și adultul au spus același lucru $times."
            }
    }

    data class Note(val day: String, val text: String)

    /** The report needs at least one day of practice; before that there is nothing to show. */
    val isEmpty: Boolean get() = days == 0

    val fileName: String get() = fileName(createdOn)

    val title: String get() = "Fonia: raport de exersare"

    val period: String
        get() {
            val first = first ?: return "nicio zi încă"
            val last = last ?: return "nicio zi încă"
            if (first == last) return ReadingReport.longDate(first)
            return "${ReadingReport.longDate(first)} – ${ReadingReport.longDate(last)}"
        }

    val age: String get() = if (ageBand == Profile.AgeBand.Adult) "adult" else "${ageBand.rawValue} ani"

    /** The lines under the title, as a name and a value. */
    val facts: List<Pair<String, String>>
        get() = buildList {
            add("Profil" to "fără nume")
            add("Vârsta" to age)
            add("Perioada" to period)
            add("Făcut pe" to ReadingReport.longDate(createdOn))
            if (chosenSounds.isNotEmpty()) add("Sunete alese" to chosenSounds.joinToString(", "))
        }

    val summary: String
        get() = "${Plural.format(days, Plural.Noun.Zi)} cu exerciții, ${Plural.format(sessions, Plural.Noun.Sedinta)}, " +
            "${Plural.format(minutes, Plural.Noun.Minut)} și ${Plural.format(attempts, Plural.Noun.Incercare)}."

    /** Everything the PDF prints, in order, for the tests and for a quick look. */
    val text: String
        get() {
            val lines = mutableListOf(title)
            lines += facts.map { "${it.first}: ${it.second}" }
            lines += listOf(summary, HOW_TO_READ_TITLE) + HOW_TO_READ + SOUNDS_TITLE
            for (sound in sounds) {
                lines += listOf(sound.title, sound.ladder, sound.stageLine)
                sound.confirmed?.let { lines += it }
                lines += sound.tries
                lines += sound.rows.map { it.joinToString(" | ") }
                lines += sound.agreement
                lines += CHART_TITLE
                lines += sound.weeks.map { "${ReadingReport.shortDate(it.monday)}: ${it.attempts}" }
            }
            lines += NOTES_TITLE
            lines += if (notes.isEmpty()) listOf(NO_NOTES) else notes.flatMap { listOf(it.day, it.text) }
            lines += FOOTER
            return lines.joinToString("\n")
        }

    companion object {
        fun fileName(day: LocalDate): String = "fonia-raport-$day.pdf"

        /** The characters of a note the report prints at most. */
        const val NOTE_LENGTH = 700

        /** The weeks the chart shows at most, the latest ones. */
        const val CHART_WEEKS = 12

        const val HOW_TO_READ_TITLE = "Cum se citește"

        /** The box that says what the numbers are and aren't. */
        val HOW_TO_READ = listOf(
            "„Telefonul” arată ce a crezut telefonul la fiecare încercare. E o părere, nu o evaluare.",
            "„Adultul” arată ce a ales adultul care a ascultat. „Fără răspuns”: telefonul nu a auzit destul de clar sau adultul nu a ales nimic.",
            AdultCards.REPORT_DISCLAIMER,
            "Numele copilului nu apare. În notițe, numele e înlocuit cu [nume].",
            AdultCards.STAGE_REPORT_NOTE,
        )

        const val SOUNDS_TITLE = "Sunetele exersate"

        const val NO_SOUNDS = "Încă nu sunt încercări la un sunet anume."

        const val NOTES_TITLE = "Ce a observat adultul"

        const val NO_NOTES = "Nu sunt notițe."

        /** When the sounds don't fit in the pages. */
        const val MORE_SOUNDS = "Celelalte sunete sunt în fișierul pentru logoped."

        /** When the notes don't fit on the last page. */
        const val MORE_NOTES = "Restul notițelor sunt în fișierul pentru logoped."

        val TABLE_HEADER = listOf("", "Bine", "Aproape", "Încă nu", "Fără răspuns")

        const val CHART_TITLE = "Încercări pe săptămână (data e lunea în care începe)"

        /** What every page says at its foot. */
        const val FOOTER = "Fonia, raport de exersare, fără nume. Ce spune telefonul e o părere, nu o evaluare."

        /** "Pagina 2". */
        fun page(number: Int): String = "Pagina $number"

        /** "4.11" under a week's bar. */
        fun barDate(day: LocalDate): String = DateTimeFormatter.ofPattern("d.MM").format(day)

        private val judged = setOf(Attempt.Outcome.Good, Attempt.Outcome.Almost, Attempt.Outcome.NotYet)

        fun build(data: PracticeData, content: FoniaContent, today: LocalDate): PracticeReport {
            val practised = data.sessions.filter { it.endedAt != null || it.attempts > 0 }
            val practisedDays = practised.mapNotNull { day(it.localDay) }.toSortedSet().toList()
            val seconds = data.sessions.sumOf { session ->
                val end = session.endedAt ?: return@sumOf 0.0
                max(0.0, Duration.between(session.startedAt, end).toMillis() / 1000.0)
            }
            val soundAttempts = data.attempts.filter { it.sound != null }
            val weeks = weekRange(soundAttempts.mapNotNull { day(it.localDay) } + practisedDays)

            val catalog = Catalog(content)
            val sounds = data.profile.orderedSounds(content).mapNotNull { sound ->
                val id = sound.id.rawValue
                val tries = soundAttempts.filter { it.sound == id }
                val saved = data.soundProgress.firstOrNull { it.sound == id }
                if (tries.isEmpty() && (saved?.rung ?: 0) <= 0) return@mapNotNull null
                val rung = Rung.at(saved?.rung ?: 0)
                val unit = catalog.unit(sound)
                val lesson = unit.lessons.firstOrNull { it.rung == rung }
                var phone = Tally()
                var adult = Tally()
                var compared = 0
                var agreed = 0
                for (attempt in tries) {
                    phone = phone.adding(attempt.machineOutcome)
                    adult = adult.adding(attempt.adultOutcome)
                    val said = attempt.adultOutcome
                    if (said != null && said in judged && attempt.machineOutcome in judged) {
                        compared += 1
                        if (said == attempt.machineOutcome) agreed += 1
                    }
                }
                val perWeek = tries.mapNotNull { day(it.localDay) }.groupingBy { monday(it) }.eachCount()
                val letter = sound.shortLetter
                SoundPart(
                    letter = letter,
                    title = unit.title,
                    rung = rung.index + 1,
                    rungs = Rung.entries.size,
                    lesson = lesson?.title ?: rung.title(letter),
                    lessonDetail = lesson?.detail ?: "",
                    stage = AdultCards.stageName(rung),
                    confirmedByAdult = id in data.profile.confirmedSounds,
                    attempts = tries.size,
                    days = tries.map { it.localDay }.toSet().size,
                    phone = phone,
                    adult = adult,
                    compared = compared,
                    agreed = agreed,
                    weeks = weeks.map { Week(it, perWeek[it] ?: 0) },
                )
            }

            val written = data.notes.mapNotNull { note ->
                val text = note.text.trim()
                if (text.isEmpty()) return@mapNotNull null
                // A very long note would take a page of its own; the whole of it is in the JSON file.
                val shown = if (text.length > NOTE_LENGTH) text.take(NOTE_LENGTH) + "…" else text
                note.localDay to Note(longDay(note.localDay), shown)
            }
            // What the adult said about a mission is one plain line under the day the mission was for.
            val missions = data.missionAnswers.mapNotNull { mission ->
                val answer = mission.answer ?: return@mapNotNull null
                val letter = SoundID.fromRawValue(mission.sound)?.let { content.sound(it).shortLetter } ?: mission.sound.uppercase()
                mission.localDay to Note(longDay(mission.localDay), MissionFollowUp.line(letter, answer))
            }
            val notes = (missions + written).sortedByDescending { it.first }.map { it.second }

            return PracticeReport(
                ageBand = data.profile.ageBand,
                isChild = data.profile.isChild,
                chosenSounds = data.profile.targetSounds.mapNotNull { SoundID.fromRawValue(it) }.map { content.sound(it).shortLetter },
                createdOn = today,
                first = practisedDays.firstOrNull(),
                last = practisedDays.lastOrNull(),
                days = practisedDays.size,
                sessions = practised.size,
                minutes = ceil(seconds / 60).toInt(),
                attempts = soundAttempts.size,
                sounds = sounds,
                notes = notes,
            )
        }

        /** The Monday of the week [day] falls in. */
        fun monday(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        /** Every week from the first to the last with practice, at most [CHART_WEEKS] of them, ending with the last. */
        private fun weekRange(days: List<LocalDate>): List<LocalDate> {
            val first = days.minOrNull() ?: return emptyList()
            val last = days.maxOrNull() ?: return emptyList()
            val start = monday(first)
            val weeks = ArrayDeque<LocalDate>()
            var week = monday(last)
            while (!week.isBefore(start) && weeks.size < CHART_WEEKS) {
                weeks.addFirst(week)
                week = week.minusWeeks(1)
            }
            return weeks.toList()
        }

        private fun longDay(text: String): String = day(text)?.let(ReadingReport::longDate) ?: text

        private fun day(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()
    }
}
