package tech.fonia.features.reading

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// The report of C2 (fonia-android-plan/03 §1.3, research report A §2.3 and §7.6): Anexa 1 of the methodology
// approved by OMEN 3124/2017 is the form a teacher fills in after six months of watching a pupil, and this report
// follows its parts so the teacher and the logoped find each answer where they expect it. Fonia measures only the
// reading probe (and the sounds practised), so the other parts are left for the adult to fill in. The words are the
// adult ones of 03 §2.1: no verdict, no level, the band only as a band, and the one sentence about diagnosis that the
// report must carry.

/** One sound practised in the app, for the sixth part. */
data class SpeechLine(val sound: String, val step: String, val good: Int, val almost: Int, val notYet: Int)

/** One part of the report: a numbered title, what Fonia knows, and whether the adult has lines to write on. */
data class ReportPart(val number: Int, val title: String, val lines: List<String>, val forTheAdult: String?)

/** One row of the page that maps the report onto the items of Anexa 1. */
data class AnnexRow(val item: String, val fromFonia: String)

/**
 * Everything the PDF shows, already in words and numbers, so it can be checked on the JVM. [probes] are the ones in
 * the six months that end on [createdOn], oldest first.
 */
class ReadingReport(
    val pseudonym: String,
    val age: String,
    val grade: SchoolGrade?,
    val from: LocalDate,
    val createdOn: LocalDate,
    val probes: List<ProbeRecord>,
    val speech: List<SpeechLine>,
    val contentVersion: String,
    val contentIsDraft: Boolean,
) {
    val title: String get() = "Raport de citire"

    val subtitle: String get() = "Pe părțile Anexei 1 din metodologia aprobată prin OMEN 3124/2017"

    val period: String get() = "${longDate(from)} – ${longDate(createdOn)}"

    /** What every page says at its foot, and the report says first. */
    val disclaimer: String get() = DISCLAIMER

    val purpose: String
        get() = "Cifrele vin dintr-o probă de un minut făcută cu telefonul, acasă sau la școală. Ajută la " +
            "completarea fișei și la discuția cu logopedul. Nu se folosesc pentru note, calificative sau orientarea școlară."

    /** Part 1. */
    val identification: List<Pair<String, String>>
        get() = listOf(
            "Pseudonim" to pseudonym.ifBlank { "nescris" },
            "Vârsta" to age.ifBlank { "nescrisă" },
            "Clasa" to (grade?.title ?: "nescrisă"),
            "Perioada" to period,
            "Probe de citire" to "${probes.size}",
        )

    val readingIntro: String
        get() = "Copilul citește un minut de pe telefon, iar un adult atinge cuvintele citite altfel sau sărite. " +
            "Ceasul pornește la primul cuvânt. Cuvintele corecte pe minut sunt cele citite bine în cele 60 de secunde, " +
            "raportate la un minut dacă termină mai repede. Silabele pe secundă numără doar cuvintele citite bine."

    /** The table of part 2: a header, then one row per probe, oldest first. */
    val readingTable: List<List<String>>
        get() = listOf(listOf("Data", "Proba (setul)", "Corecte pe minut", "Corectitudine", "Silabe pe secundă", "Citite", "Citite altfel", "Așezare")) +
            probes.map { record ->
                val score = record.score
                listOf(
                    shortDate(record.day),
                    "${record.kind.shortTitle} (${record.set})",
                    "${score.wcpmShown}",
                    "${score.accuracyPercent}%",
                    oneDecimal(score.syllablesPerSecond),
                    "${score.wordsRead}",
                    "${score.errors}",
                    record.preset.title,
                )
            }

    /** The kinds that have at least one probe in the period, in the order of the probe. */
    val kinds: List<ProbeKind> get() = ProbeKind.entries.filter { kind -> probes.any { it.kind == kind } }

    fun curve(kind: ProbeKind): List<ProbeRecord> = probes.filter { it.kind == kind }

    /** For the last probe of each kind: where it stands against the published band, with the band itself. */
    val bandLines: List<String>
        get() = kinds.map { kind ->
            val last = curve(kind).last()
            val shown = last.score.wcpmShown
            val position = PeafcNorms.position(kind, last.score.wcpm)
            "${kind.title} (${shortDate(last.day)}): ${wordsPerMinute(shown)}, ${PeafcNorms.phrase(position)} " +
                "(${PeafcNorms.range(kind)})."
        }

    val bandSource: String
        get() = "Sursa benzii: ${PeafcNorms.SOURCE} Listele și textele Fonia sunt altele decât cele din studiu, deci " +
            "banda e doar un reper. Comparația de bază e cu el însuși, de la o lună la alta."

    /** Criterion II: what changed over the period, for each kind, in numbers only. */
    val progressLines: List<String>
        get() = if (kinds.isEmpty()) {
            listOf("Nicio probă de citire în perioadă.")
        } else {
            kinds.map { kind ->
                val curve = curve(kind)
                val first = curve.first()
                val last = curve.last()
                if (curve.size == 1) {
                    "${kind.title}: o singură probă (${shortDate(first.day)}), ${wordsPerMinute(first.score.wcpmShown)}."
                } else {
                    "${kind.title}: de la ${first.score.wcpmShown} (${shortDate(first.day)}) la ${last.score.wcpmShown} " +
                        "(${shortDate(last.day)}) cuvinte corecte pe minut, în ${curve.size} probe."
                }
            }
        }

    /** The six parts of Anexa 1, in its order. */
    val parts: List<ReportPart>
        get() = listOf(
            ReportPart(1, "Identificare", identification.map { (name, value) -> "$name: $value" }, null),
            ReportPart(
                2,
                "Citit",
                listOf(readingIntro) + bandLines,
                "De completat la citit: felul fiecărui cuvânt citit altfel (omis, înlocuit, inversat, inventat) și cum " +
                    "citește (pe litere, pe silabe, cu intonație).",
            ),
            ReportPart(3, "Conștiința fonologică", listOf(NOT_MEASURED), "De completat: silabe, rimă, sunetul de la început și de la sfârșit, unirea și despărțirea sunetelor."),
            ReportPart(4, "Denumirea rapidă și pseudocuvintele", listOf(NOT_MEASURED), "De completat: denumirea rapidă a culorilor sau a obiectelor, citirea unor cuvinte inventate."),
            ReportPart(5, "Scris", listOf(NOT_MEASURED), "De completat: dictare, copiere, cât de repede scrie, cum arată literele."),
            ReportPart(6, "Vorbire", speechLines, "De completat: ce aude logopedul în vorbire."),
        )

    private val speechLines: List<String>
        get() = if (speech.isEmpty()) {
            listOf("Nu a exersat sunete în Fonia.")
        } else {
            listOf("Sunetele exersate în Fonia, cu răspunsurile telefonului la încercări, de până acum (orientative):") +
                speech.map { "${it.sound}: ${it.step}. Bine ${it.good}, aproape ${it.almost}, încă nu ${it.notYet}." }
        }

    /** The page "Corespondență cu Anexa 1": each item of the form, and what in this report speaks to it. */
    val annex: List<AnnexRow>
        get() {
            val fluency = if (kinds.isEmpty()) {
                "Nicio probă în perioadă."
            } else {
                "Cuvinte corecte pe minut și silabe pe secundă la fiecare probă (partea 2). " + kinds.joinToString(" ") { kind ->
                    val last = curve(kind).last().score
                    "${kind.title}: ${last.wcpmShown} pe minut, ${oneDecimal(last.syllablesPerSecond)} silabe pe secundă."
                }
            }
            val accuracy = if (kinds.isEmpty()) {
                "Nicio probă în perioadă."
            } else {
                "Corectitudinea la fiecare probă (partea 2): de la ${probes.minOf { it.score.accuracyPercent }}% la " +
                    "${probes.maxOf { it.score.accuracyPercent }}%. Felul fiecărui cuvânt citit altfel nu se notează în Fonia."
            }
            val speaking = if (speech.isEmpty()) {
                NOT_IN_FONIA
            } else {
                "Sunetele exersate (partea 6): " + speech.joinToString(", ") { it.sound } + "."
            }
            return listOf(
                AnnexRow("I.1 Exprimarea orală", speaking),
                AnnexRow("I.2 Înțelegerea orală", NOT_IN_FONIA),
                AnnexRow("I.3 a) Citit: fluența", fluency),
                AnnexRow("I.3 b) Citit: corectitudinea", accuracy),
                AnnexRow("I.3 c) Citit: înțelegerea", NOT_IN_FONIA),
                AnnexRow("I.4 a) Scris: rapiditatea", NOT_IN_FONIA),
                AnnexRow("I.4 b) Scris: corectitudinea", NOT_IN_FONIA),
                AnnexRow("Criteriul II: progresul după 6 luni de instruire adecvată", progressLines.joinToString(" ")),
            )
        }

    val annexTitle: String get() = "Corespondență cu Anexa 1 (OMEN 3124/2017)"

    val annexIntro: String
        get() = "Fișa se completează de învățător, după 6 luni de observație. Coloana din dreapta rămâne pentru el."

    /** The line under the disclaimer on every page. */
    val madeBy: String
        get() {
            val draft = if (contentIsDraft) ", ciornă care așteaptă verificarea unui logoped" else ""
            return "Făcut pe telefon de Fonia pe ${longDate(createdOn)}. Listele de citit: $contentVersion$draft."
        }

    companion object {
        /** The sentence 03 §1.3 C2 requires on the report. */
        const val DISCLAIMER = "Orientativ; diagnosticul se pune doar prin evaluarea complexă (art. 10–11)."

        const val NOT_MEASURED = "Fonia nu măsoară încă partea aceasta."

        const val NOT_IN_FONIA = "Nu se măsoară în Fonia."

        /** How far back the report looks: criterion II asks about six months. */
        const val MONTHS = 6L

        private val romanian = Locale.forLanguageTag("ro-RO")

        fun shortDate(date: LocalDate): String = DateTimeFormatter.ofPattern("d.MM.yyyy", romanian).format(date)

        fun longDate(date: LocalDate): String = DateTimeFormatter.ofPattern("d MMMM yyyy", romanian).format(date)

        /**
         * The report for one child on [today]: the probes of the last six months, the details the adult typed, the
         * age band of the profile when no age was typed.
         */
        fun build(
            data: ReadingProfileData,
            ageBand: String?,
            today: LocalDate,
            speech: List<SpeechLine>,
            contentVersion: String,
            contentIsDraft: Boolean,
        ): ReadingReport {
            val start = today.minusMonths(MONTHS)
            val inPeriod = data.probes
                .filter { !it.day.isBefore(start) && !it.day.isAfter(today) }
                .sortedBy { it.takenAt }
            val from = inPeriod.firstOrNull()?.day ?: today
            return ReadingReport(
                pseudonym = data.report.pseudonym.trim(),
                age = data.report.age.trim().ifBlank { ageBand?.let { "$it ani" } ?: "" },
                grade = data.report.grade,
                from = from,
                createdOn = today,
                probes = inPeriod,
                speech = speech,
                contentVersion = contentVersion,
                contentIsDraft = contentIsDraft,
            )
        }
    }
}

/** The kind as a table cell: "o silabă", "text". */
val ProbeKind.shortTitle: String
    get() = when (this) {
        ProbeKind.Mono -> "o silabă"
        ProbeKind.Bi -> "două silabe"
        ProbeKind.Tri -> "trei silabe"
        ProbeKind.Text -> "text"
    }
