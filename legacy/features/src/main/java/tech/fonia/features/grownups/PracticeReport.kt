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
import tech.fonia.features.session.CaptureLog
import tech.fonia.features.session.FrontTeeth
import tech.fonia.store.Attempt
import tech.fonia.store.JSONObject
import tech.fonia.store.JSONValue
import tech.fonia.store.PracticeData
import tech.fonia.store.Profile
import tech.fonia.store.SoundProgress
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
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
    /**
     * Takes on this week's short lists in „Săptămâna pe scurt” that no adult has answered yet. Always 0 for an adult
     * practising alone, who has no such page.
     */
    val waitingToHear: Int = 0,
    /** Tries per minute, over the sessions that ended and had tries; null under one minute of them or under one a minute. */
    val perMinute: Int? = null,
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
        /** The latest change of rung and what made it; null for progress saved before moves were kept. */
        val lastMove: SoundProgress.Move? = null,
        val attempts: Int,
        val days: Int,
        val phone: Tally,
        val adult: Tally,
        /** Tries both the phone and the adult answered, and how many of those got the same answer. */
        val compared: Int,
        val agreed: Int,
        val weeks: List<Week>,
        /**
         * Tries the phone listened to: it answered, or it said it didn't hear clearly. Tries it doesn't judge at all
         * (no detector for the sound, a take only shadowed) are left out.
         */
        val listened: Int = 0,
        /** Of those, the ones where it didn't hear clearly (`Unclear`). */
        val unheard: Int = 0,
        /** Whose own answers [selfRated] are: "copilul", or "cel care a exersat" for an adult. */
        val who: String = "copilul",
        /** The learner's own answers to „Cum ți s-a părut?”, by outcome. */
        val selfRated: Tally = Tally(),
        /** Self-rated tries the phone answered too, and how many of those got the same answer. */
        val selfCompared: Int = 0,
        val selfAgreed: Int = 0,
        /** Tries per microphone (`CaptureLog`), for the tries saved since the microphone was kept. */
        val microphones: Map<String, Int> = emptyMap(),
        /**
         * Tries said while an adult had noted the front teeth out ([FrontTeeth]), on a sound the gap changes, with the
         * first and last day of them.
         */
        val teethTries: Int = 0,
        val teethFrom: LocalDate? = null,
        val teethTo: LocalDate? = null,
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

        /** "Ultima schimbare: 12 noiembrie 2026, de la lecția 3 la lecția 4, după blocuri bune." Null when none was kept. */
        val moveLine: String?
            get() {
                val move = lastMove ?: return null
                val reason = when (move.by) {
                    SoundProgress.Move.Source.Blocks -> if (move.to > move.from) "după blocuri bune" else "după un bloc greu"
                    SoundProgress.Move.Source.Adult -> "după ce adultul a ascultat și a spus că e bine"
                    SoundProgress.Move.Source.AdultMarks -> "după ce adultul a spus „Sună bine” în două zile diferite"
                }
                val day = ReadingReport.longDate(move.at.atZone(ZoneId.systemDefault()).toLocalDate())
                return "Ultima schimbare: $day, de la lecția ${move.from + 1} la lecția ${move.to + 1}, $reason."
            }

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

        /**
         * "Telefonul n-a auzit clar 5 din 8 încercări.", and what helps when that is more than half. Empty under one
         * block of tries the phone listened to.
         */
        val hearing: List<String>
            get() {
                if (listened < HEARING_FLOOR) return emptyList()
                val line = "Telefonul n-a auzit clar $unheard din ${Plural.format(listened, Plural.Noun.Incercare)}."
                return if (unheard * 2 > listened) listOf(line, CAPTURE_TIP) else listOf(line)
            }

        /**
         * "La 9 încercări, copilul a spus singur cum i s-a părut: „Bine” 6, „Aproape” 2, „Încă nu” 1.", and how often
         * the phone said the same. Null under one block of answers. Never shown to the child.
         */
        val selfJudged: String?
            get() {
                if (selfRated.judged < SELF_FLOOR) return null
                val split = "„Bine” ${selfRated.good}, „Aproape” ${selfRated.almost}, „Încă nu” ${selfRated.notYet}"
                var line = "La ${Plural.format(selfRated.judged, Plural.Noun.Incercare)}, $who a spus singur cum i s-a părut: $split."
                if (selfCompared >= SELF_FLOOR) {
                    val same = if (selfAgreed == 0) "La niciuna" else "La $selfAgreed"
                    line += " $same dintre cele $selfCompared la care a răspuns și telefonul, răspunsurile au fost la fel."
                }
                return line
            }

        /**
         * "Încercări pe fiecare microfon: al telefonului 40, Bluetooth 12. Prin alt microfon, telefonul aude altfel."
         * Null while every try came through one microphone.
         */
        val microphoneLine: String?
            get() {
                if (microphones.size <= 1) return null
                val counts = microphones.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .joinToString(", ") { "${microphoneName(it.key)} ${it.value}" }
                return "Încercări pe fiecare microfon: $counts. Prin alt microfon, telefonul aude altfel."
            }

        /**
         * "Cât îi lipseau dinții din față (3 – 20 noiembrie): 40 de încercări. Atunci S poate suna altfel, fără ca
         * vorbirea să se fi schimbat." Null when no try was said with the teeth out.
         */
        val teethLine: String?
            get() {
                val from = teethFrom ?: return null
                val to = teethTo ?: return null
                if (teethTries <= 0) return null
                val last = dayMonth.format(to)
                val whenText = when {
                    from == to -> last
                    from.month == to.month && from.year == to.year -> "${from.dayOfMonth} – $last"
                    else -> "${dayMonth.format(from)} – $last"
                }
                return "Cât îi lipseau dinții din față ($whenText): ${Plural.format(teethTries, Plural.Noun.Incercare)}. Atunci $letter poate suna altfel, fără ca vorbirea să se fi schimbat."
            }

        /**
         * What follows the agreement line: the learner's own answers, the microphones, the days with the front teeth
         * out, then how often the phone didn't hear clearly.
         */
        val belowAnswers: List<String> get() = listOfNotNull(selfJudged, microphoneLine, teethLine) + hearing
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

    /** "3 rostiri așteaptă să fie ascultate: Setări › Săptămâna pe scurt." Null when none wait. */
    val waitingLine: String?
        get() = when {
            waitingToHear <= 0 -> null
            waitingToHear == 1 -> "O rostire așteaptă să fie ascultată: $WEEK_PATH."
            else -> {
                val lastTwo = waitingToHear % 100
                val takes = if (lastTwo in 1..19) "$waitingToHear rostiri" else "$waitingToHear de rostiri"
                "$takes așteaptă să fie ascultate: $WEEK_PATH."
            }
        }

    /** "În medie, 5 încercări pe minut de exersare." Null when there is too little to tell. */
    val pace: String? get() = perMinute?.let { "În medie, ${Plural.format(it, Plural.Noun.Incercare)} pe minut de exersare." }

    /** The lines of the „Cum se citește” box for this report. */
    val readingNotes: List<String> get() = HOW_TO_READ + if (isChild) listOf(ONLY_ADULTS_GO_BACK) else emptyList()

    /** Everything the PDF prints, in order, for the tests and for a quick look. */
    val text: String
        get() {
            val lines = mutableListOf(title)
            lines += facts.map { "${it.first}: ${it.second}" }
            lines += summary
            waitingLine?.let { lines += it }
            pace?.let { lines += it }
            lines += listOf(HOW_TO_READ_TITLE) + readingNotes + SOUNDS_TITLE
            for (sound in sounds) {
                lines += listOf(sound.title, sound.ladder, sound.stageLine)
                sound.confirmed?.let { lines += it }
                sound.moveLine?.let { lines += it }
                lines += sound.tries
                lines += sound.rows.map { it.joinToString(" | ") }
                lines += sound.agreement
                lines += sound.belowAnswers
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

        /** "20 noiembrie". */
        internal val dayMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", java.util.Locale.forLanguageTag("ro-RO"))

        /** The characters of a note the report prints at most. */
        const val NOTE_LENGTH = 700

        /** The weeks the chart shows at most, the latest ones. */
        const val CHART_WEEKS = 12

        /** Where the week's takes are heard on the phone: the settings, then the week page. */
        const val WEEK_PATH = "Setări › ${WeekCopy.TITLE}"

        const val HOW_TO_READ_TITLE = "Cum se citește"

        /** The box that says what the numbers are and aren't. */
        val HOW_TO_READ = listOf(
            "„Telefonul” arată ce a crezut telefonul la fiecare încercare. E o părere, nu o evaluare.",
            "„Adultul” arată ce a ales adultul care a ascultat. „Fără răspuns”: telefonul nu a auzit destul de clar sau adultul nu a ales nimic.",
            AdultCards.REPORT_DISCLAIMER,
            "Numele copilului nu apare. În notițe, numele e înlocuit cu [nume].",
            AdultCards.STAGE_REPORT_NOTE,
        )

        /** Only for a child: the phone never takes a child back a step. */
        const val ONLY_ADULTS_GO_BACK = "Telefonul nu coboară niciodată un copil pe o treaptă mai jos. Doar un adult poate face asta."

        /** The fewest tries the phone listened to before the report says how often it didn't hear clearly: one block. */
        const val HEARING_FLOOR = 6

        /** A microphone as `CaptureLog` keeps it, named for the report. */
        fun microphoneName(microphone: String): String = when (microphone) {
            "phone" -> "al telefonului"
            "wired" -> "cu fir"
            "bluetooth" -> "Bluetooth"
            "usb" -> "USB"
            "fixture" -> "înregistrări de test"
            else -> "altul"
        }

        /** The fewest own answers before the report shows them: the three of one block at grupuri de consoane. */
        const val SELF_FLOOR = 3

        /** The learner's own answer to „Cum ți s-a părut?”, as `SessionRun` keeps it in `detail["selfRating"]`. */
        fun selfOutcome(detail: JSONObject?): Attempt.Outcome? = when ((detail?.get("selfRating") as? JSONValue.String)?.value) {
            "yes" -> Attempt.Outcome.Good
            "nearly" -> Attempt.Outcome.Almost
            "notYet" -> Attempt.Outcome.NotYet
            else -> null
        }

        /** What usually helps when the phone misses more than half the tries. */
        const val CAPTURE_TIP = "De obicei ajută: telefonul mai aproape, televizorul oprit, microfonul neacoperit."

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
            // The tries the store holds for each session, rather than the session's own running count.
            val triesIn = data.attempts.groupingBy { it.sessionId }.eachCount()
            val timed = data.sessions.mapNotNull { session ->
                val end = session.endedAt ?: return@mapNotNull null
                val count = triesIn[session.id] ?: 0
                if (count <= 0) return@mapNotNull null
                max(0.0, Duration.between(session.startedAt, end).toMillis() / 1000.0) to count
            }
            val timedMinutes = timed.sumOf { it.first } / 60
            val rate = if (timedMinutes >= 1) Math.round(timed.sumOf { it.second } / timedMinutes).toInt() else 0
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
                var listened = 0
                var unheard = 0
                var selfRated = Tally()
                var selfCompared = 0
                var selfAgreed = 0
                val microphones = mutableMapOf<String, Int>()
                val gapChanges = sound.id in FrontTeeth.affects
                val teethDays = mutableListOf<LocalDate>()
                for (attempt in tries) {
                    CaptureLog.microphone(attempt.detail)?.let { microphones[it] = (microphones[it] ?: 0) + 1 }
                    if (gapChanges && CaptureLog.teethOut(attempt.detail)) day(attempt.localDay)?.let { teethDays += it }
                    selfOutcome(attempt.detail)?.let { own ->
                        selfRated = selfRated.adding(own)
                        if (attempt.machineOutcome in judged) {
                            selfCompared += 1
                            if (own == attempt.machineOutcome) selfAgreed += 1
                        }
                    }
                    phone = phone.adding(attempt.machineOutcome)
                    when (attempt.machineOutcome) {
                        Attempt.Outcome.Good, Attempt.Outcome.Almost, Attempt.Outcome.NotYet -> listened += 1
                        Attempt.Outcome.Unclear -> { listened += 1; unheard += 1 }
                        Attempt.Outcome.NotJudged -> Unit
                    }
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
                    lastMove = saved?.moves?.lastOrNull(),
                    attempts = tries.size,
                    days = tries.map { it.localDay }.toSet().size,
                    phone = phone,
                    adult = adult,
                    compared = compared,
                    agreed = agreed,
                    weeks = weeks.map { Week(it, perWeek[it] ?: 0) },
                    listened = listened,
                    unheard = unheard,
                    who = if (data.profile.isChild) "copilul" else "cel care a exersat",
                    selfRated = selfRated,
                    selfCompared = selfCompared,
                    selfAgreed = selfAgreed,
                    microphones = microphones,
                    teethTries = teethDays.size,
                    teethFrom = teethDays.minOrNull(),
                    teethTo = teethDays.maxOrNull(),
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

            // The same short lists the week page shows, for the week the report is made in.
            val week = WeekReport.build(
                monday = monday(today),
                sessions = data.sessions,
                attempts = data.attempts,
                recordings = data.recordings,
                progress = data.soundProgress,
                order = data.profile.orderedSounds(content).map { it.id.rawValue },
            )

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
                waitingToHear = if (data.profile.isChild) week.listWaiting else 0,
                perMinute = if (rate >= 1) rate else null,
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
