package tech.fonia.features.grownups

import tech.fonia.domain.Plural
import tech.fonia.features.app.title
import tech.fonia.features.session.WeakJudgeGate
import tech.fonia.store.Attempt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The words of the week page (iOS `WeekCopy`). Plain sentences for a parent, with counts kept quiet; the child never sees them. */
object WeekCopy {
    const val TITLE = "Săptămâna pe scurt"
    const val ROW_DETAIL = "Ce s-a lucrat și câteva înregistrări de ascultat, aici pe telefon"

    const val EARLIER = "Săptămâna dinainte"
    const val LATER = "Săptămâna următoare"

    const val SOUNDS_GOOD = "Sună bine"
    const val KEEP_WORKING = "Mai lucrăm"

    const val NO_PRACTICE = "Nicio zi cu exerciții."

    /** Under the takes when the switch is off. Said once, with where the switch is, and nothing more. */
    const val KEEPING_OFF = "Înregistrările copiilor nu se păstrează acum. Dacă vrei să le asculți aici, pornește „Păstrează înregistrările copiilor” din Setări."

    const val WHAT_MARKS_DO = "Ce alegi la o înregistrare se vede în „Cum merge” și în raportul pentru logoped. „Mai lucrăm” îl ține pe copil la lecțiile de acum până când sunetul iese bine în alte două zile."

    /** Only under takes of the sounds in [WeakJudgeGate.sounds], where the phone may not hear a good take as good. */
    const val WEAK_SOUND_MARKS = "La CE, GE, J și Ț telefonul poate să nu audă bine, așa că urechea ta contează mai mult. „Sună bine” în două zile diferite îl trece pe copil la lecțiile următoare."

    /** [WEAK_SOUND_MARKS] shows to a child's adult when the week has takes to hear of a [WeakJudgeGate] sound. */
    fun showsWeakSoundMarks(sounds: List<WeekReport.SoundWeek>, isChild: Boolean): Boolean =
        isChild && sounds.any { week -> week.picked.isNotEmpty() && WeakJudgeGate.sounds.any { it.rawValue == week.sound } }

    /** The three newest takes of each sound stay, and the first tries at new words ([tech.fonia.features.session.RecordingPairs]). */
    const val STAYS_HERE = "Înregistrările nu pleacă de pe telefon. Logopedul le poate asculta aici. La fiecare sunet rămân ultimele trei și primele încercări la cuvinte noi."

    const val STOP = "Oprește"

    private val romanian = Locale.forLanguageTag("ro-RO")
    private val dayMonth = DateTimeFormatter.ofPattern("d MMMM", romanian)
    private val weekdayTime = DateTimeFormatter.ofPattern("EEEE, HH:mm", romanian)

    /** "Săptămâna asta", "Săptămâna trecută", or the week's dates. */
    fun heading(monday: LocalDate, thisMonday: LocalDate): String = when (monday) {
        thisMonday -> "Săptămâna asta"
        thisMonday.minusDays(7) -> "Săptămâna trecută"
        else -> "Săptămâna de la ${dayMonth.format(monday)}"
    }

    /** "17 – 23 noiembrie", "27 octombrie – 2 noiembrie", with the year when it isn't this one. */
    fun range(monday: LocalDate, thisYear: Int): String {
        val sunday = monday.plusDays(6)
        val year = if (sunday.year == thisYear) "" else " ${sunday.year}"
        if (monday.month == sunday.month) return "${monday.dayOfMonth} – ${dayMonth.format(sunday)}$year"
        val startYear = if (monday.year == sunday.year) "" else " ${monday.year}"
        return "${dayMonth.format(monday)}$startYear – ${dayMonth.format(sunday)}$year"
    }

    /** "4 zile cu exerciții, 5 ședințe, 35 de minute." The sessions are left out while none was finished. */
    fun summary(report: WeekReport): String {
        if (report.days == 0) return NO_PRACTICE
        val sessions = if (report.sessions > 0) " ${Plural.format(report.sessions, Plural.Noun.Sedinta)}," else ""
        return "${Plural.format(report.days, Plural.Noun.Zi)} cu exerciții,$sessions ${Plural.format(report.minutes, Plural.Noun.Minut)}."
    }

    /** "3 zile, 12 minute, 40 de încercări." */
    fun facts(sound: WeekReport.SoundWeek): String =
        "${Plural.format(sound.days, Plural.Noun.Zi)}, ${Plural.format(sound.minutes, Plural.Noun.Minut)}, ${Plural.format(sound.attempts, Plural.Noun.Incercare)}."

    /** What moved in the week, or null when the lessons stayed on the same step. */
    fun moved(sound: WeekReport.SoundWeek, letter: String): String? {
        if (!sound.moved) return null
        val before = sound.rungBefore.title(letter)
        val after = sound.rungAfter.title(letter)
        if (sound.rungAfter < sound.rungBefore) return "Lecțiile au revenit la „$after”."
        val step = "A trecut de la „$before” la „$after”."
        return if (sound.changedStage) "$step A ajuns la etapa ${StageNames.name(sound.rungAfter.stage)}." else step
    }

    /** The line over the takes: how many were chosen, and how many still wait for the adult's ear. */
    fun takesLead(sound: WeekReport.SoundWeek): String {
        val chosen = when {
            sound.picked.size < sound.kept.size -> "Am ales ${sound.picked.size} din cele ${recordings(sound.kept.size)} păstrate."
            sound.kept.size == 1 -> "O singură înregistrare păstrată."
            else -> "Toate cele ${recordings(sound.kept.size)} păstrate."
        }
        return when (val waiting = sound.waiting) {
            0 -> chosen
            1 -> "$chosen La una nu ai spus încă ce ai auzit."
            else -> "$chosen La $waiting nu ai spus încă ce ai auzit."
        }
    }

    /** What the phone thought of the take, as a plain fact. */
    fun phone(outcome: Attempt.Outcome): String = when (outcome) {
        Attempt.Outcome.Good -> "Telefonul a zis „bine”."
        Attempt.Outcome.Almost -> "Telefonul a zis „aproape”."
        Attempt.Outcome.NotYet -> "Telefonul a zis „încă nu”."
        Attempt.Outcome.Unclear -> "Telefonul nu a auzit-o clar."
        Attempt.Outcome.NotJudged -> "Telefonul nu a judecat-o."
    }

    /** Why a take of the pair is on the list. The others carry the phone's word, which says it already. */
    fun why(why: WeekReport.Take.Why): String? = when (why) {
        WeekReport.Take.Why.FirstOnWord -> "Prima din săptămână. Ascult-o lângă cea mai nouă."
        WeekReport.Take.Why.LatestOnWord -> "Cea mai nouă. Ascult-o lângă prima."
        WeekReport.Take.Why.PhoneUnsure, WeekReport.Take.Why.NotHeard, WeekReport.Take.Why.Other -> null
    }

    fun title(ear: AdultEar): String = when (ear) {
        AdultEar.SoundsGood -> SOUNDS_GOOD
        AdultEar.KeepWorking -> KEEP_WORKING
    }

    fun listen(word: String): String = "Ascultă „$word”"

    /** "marți, 18:05". */
    fun time(ts: Instant, zone: ZoneId = ZoneId.systemDefault()): String = weekdayTime.format(ts.atZone(zone))

    /** "înregistrări", with "de" from twenty on. */
    fun recordings(count: Int): String {
        if (count == 1) return "o înregistrare"
        val lastTwo = count % 100
        return if (count == 0 || lastTwo in 1..19) "$count înregistrări" else "$count de înregistrări"
    }

    /** Every fixed line, for the tests that read all the wording at once. */
    val everyLine: List<String>
        get() = listOf(TITLE, ROW_DETAIL, EARLIER, LATER, SOUNDS_GOOD, KEEP_WORKING, NO_PRACTICE, KEEPING_OFF, WHAT_MARKS_DO, WEAK_SOUND_MARKS,
            STAYS_HERE, STOP) +
            Attempt.Outcome.entries.map(::phone) +
            listOf(WeekReport.Take.Why.FirstOnWord, WeekReport.Take.Why.LatestOnWord).mapNotNull(::why)
}
