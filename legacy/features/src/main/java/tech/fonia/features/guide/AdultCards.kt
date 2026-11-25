package tech.fonia.features.guide

import tech.fonia.domain.Rung
import tech.fonia.domain.SessionFocus
import tech.fonia.features.app.FoniaPreferences
import tech.fonia.features.grownups.StageNames
import java.util.UUID

/** The kinds of activity a child profile meets, each with one card for the adult beside them. */
enum class AdultCardKind(val rawValue: String) {
    IsolatedSound("isolatedSound"),
    Syllables("syllables"),
    Words("words"),
    Sentences("sentences"),
    Listening("listening"),
    Mirror("mirror"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): AdultCardKind? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/**
 * What an adult reads before the first activity of a kind: the target, what a good try looks like, and what to do
 * after a miss. Three short parts, about twenty seconds in all.
 */
data class AdultCard(
    val kind: AdultCardKind,
    val title: String,
    val target: String,
    val goodTry: String,
    val afterMiss: String,
)

/**
 * Every line of the adult cards and the stage names, in one place so a speech therapist can read them in one go
 * (iOS `AdultCards`). Nothing here is shown to an adult who practises alone: the card functions take `isChild` for
 * that, the way [tech.fonia.features.grownups.AudienceCopy] does. Wording rules: plain words, the adult addressed as
 * "tu", no claim of therapy or diagnosis, no ages, no doses, no promise of results, and a miss is something the adult
 * answers by modelling the sound, going one step easier or stopping, never by telling a child a take was wrong.
 */
object AdultCards {
    // Headings and small lines

    const val PAGE_TITLE = "Pentru adult"
    const val TARGET_HEADING = "Ținta"
    const val GOOD_TRY_HEADING = "O încercare bună"
    const val AFTER_MISS_HEADING = "Dacă nu iese"
    const val UNDERSTOOD = "Am citit"

    /** Where the card can be read again, for whoever takes over: a grandparent, say. */
    const val REOPEN_HINT = "Găsești textul din nou în Setări, la „Ce faci la fiecare activitate”."

    /** The settings row and the title of the page that lists the cards. */
    const val SCREEN_TITLE = "Ce faci la fiecare activitate"
    const val SCREEN_DETAIL = "Pe scurt, la fiecare fel de lecție"
    const val SCREEN_LEAD = "Câte un text scurt pentru fiecare fel de activitate. Alege-l pe cel de care ai nevoie."

    /** The small button on the start sheet. */
    const val START_SHEET_BUTTON = "Ce faci, pe scurt"

    // One card for each activity

    fun isolatedSound() = AdultCard(
        kind = AdultCardKind.IsolatedSound,
        title = "Sunetul singur",
        target = "Copilul spune doar sunetul, fără cuvinte. Vrem să-l spună la fel, de mai multe ori la rând.",
        goodTry = "Sunetul se aude limpede și se ține câteva clipe, fără alt sunet adăugat. Gura arată ca în ghidul sunetului.",
        afterMiss = "Spune tu sunetul rar, iar copilul ascultă. Apoi îl spuneți împreună. Deschideți ghidul sunetului, ca să vedeți cum stă gura. După câteva încercări fără să iasă, oprește-te și continuați altă zi.",
    )

    fun syllables() = AdultCard(
        kind = AdultCardKind.Syllables,
        title = "Silabele",
        target = "Copilul spune sunetul lipit de o vocală, ca în „sa” sau „ru”. Vrem să rămână același sunet când se schimbă vocala.",
        goodTry = "Se aude același sunet în fiecare silabă, iar silaba iese dintr-o dată, fără pauză între sunet și vocală.",
        afterMiss = "Spune tu silaba rar, iar copilul ascultă. Dacă nu iese, întoarceți-vă o treaptă, la sunetul singur, apoi reluați silaba. După câteva încercări fără să iasă, oprește-te pentru azi.",
    )

    fun words() = AdultCard(
        kind = AdultCardKind.Words,
        title = "Cuvintele",
        target = "Copilul spune cuvinte cu sunetul la început, la mijloc sau la sfârșit. Vrem să-l spună în cuvânt la fel ca atunci când e singur.",
        goodTry = "Asculți doar sunetul lucrat, nu tot cuvântul. Dacă sună ca atunci când e singur, a fost o încercare bună.",
        afterMiss = "Spune tu cuvântul rar, apoi spuneți-l împreună. Dacă nu iese, treceți la o silabă cu sunetul și apoi înapoi la cuvânt. După câteva încercări, oprește-te sau alege alt cuvânt.",
    )

    fun sentences() = AdultCard(
        kind = AdultCardKind.Sentences,
        title = "Propoziții și povești",
        target = "Copilul spune propoziții și povestește cu vorbele lui. Vrem ca sunetul să vină firesc, fără să se gândească la el.",
        goodTry = "Înțelegi ce spune, iar sunetul lucrat se aude în cuvintele lui. Aici contează mai mult ce auzi tu decât ce arată telefonul.",
        afterMiss = "Lasă povestea să curgă. Repetă tu propoziția firesc, ca pe un răspuns, cu sunetul bine rostit, și mergeți mai departe. Dacă se încurcă, treceți la o propoziție mai scurtă. După câteva încercări, oprește-te când obosește.",
    )

    fun listening() = AdultCard(
        kind = AdultCardKind.Listening,
        title = "Ascultarea",
        target = "Copilul ascultă cuvinte și alege ce a auzit: dacă sunetul e în cuvânt, sau care din două cuvinte aproape la fel a fost. Vrem să audă sunetul înainte să-l spună.",
        goodTry = "Un răspuns bun vine după ce copilul a ascultat până la capăt. Nu contează cât de repede răspunde.",
        afterMiss = "Spune tu cuvântul rar și lasă-l să asculte din nou. Dacă tot e greu, treceți la următorul. După câteva încercări, oprește-te.",
    )

    fun mirror() = AdultCard(
        kind = AdultCardKind.Mirror,
        title = "Oglinda",
        target = "Telefonul arată fața copilului. Copilul pune gura ca în desen și spune sunetul, pas cu pas, ca să vadă unde stau buzele și limba.",
        goodTry = "Gura seamănă cu desenul și copilul chiar spune sunetul. Telefonul poate greși ce vede, așa că te iei după ce auzi și vezi tu.",
        afterMiss = "Arată-i tu poziția pe fața ta și spune sunetul rar. Apoi încercați împreună. Dacă nu iese, treceți la sunetul singur, fără oglindă. După câteva încercări, oprește-te.",
    )

    /** The card of a kind, for the pages that list them. Callers that may serve an adult use the `isChild` form. */
    fun card(kind: AdultCardKind): AdultCard = when (kind) {
        AdultCardKind.IsolatedSound -> isolatedSound()
        AdultCardKind.Syllables -> syllables()
        AdultCardKind.Words -> words()
        AdultCardKind.Sentences -> sentences()
        AdultCardKind.Listening -> listening()
        AdultCardKind.Mirror -> mirror()
    }

    /** Null for an adult practising alone: nobody sits beside them, and nothing here is addressed to them. */
    fun card(kind: AdultCardKind, isChild: Boolean): AdultCard? = if (isChild) card(kind) else null

    /** The line under a card's name in the list: which lessons it covers. */
    fun detail(kind: AdultCardKind): String = when (kind) {
        AdultCardKind.IsolatedSound -> "Lecția „Doar sunetul”"
        AdultCardKind.Syllables -> "Silabele și silabele pe dos"
        AdultCardKind.Words -> "Cuvinte cu sunetul la început, la mijloc și la sfârșit"
        AdultCardKind.Sentences -> "Propoziții, povești și vorbirea de zi cu zi"
        AdultCardKind.Listening -> "Lecțiile în care asculți și alegi"
        AdultCardKind.Mirror -> "Încearcă în oglindă"
    }

    // When a card is shown

    fun kind(rung: Rung): AdultCardKind = when (rung) {
        Rung.Isolated -> AdultCardKind.IsolatedSound
        Rung.Syllables -> AdultCardKind.Syllables
        Rung.WordsInitial, Rung.WordsMedial, Rung.WordsFinal, Rung.Clusters -> AdultCardKind.Words
        // The pairs lesson is built around hearing the difference first.
        Rung.Pairs -> AdultCardKind.Listening
        Rung.Sentences, Rung.Retell, Rung.Everyday -> AdultCardKind.Sentences
    }

    /** The card a lesson belongs to. Warm-ups, English and diction have none: they are not a child's sound work. */
    fun kind(focus: SessionFocus): AdultCardKind? = when (focus) {
        is SessionFocus.Sound -> kind(focus.rung)
        is SessionFocus.SyllablesBack -> AdultCardKind.Syllables
        is SessionFocus.Story -> AdultCardKind.Sentences
        is SessionFocus.Meet, SessionFocus.Voicing, SessionFocus.VoicingSay -> AdultCardKind.Listening
        is SessionFocus.Warmups, is SessionFocus.English, is SessionFocus.Diction, is SessionFocus.DictionLesson -> null
    }

    /**
     * The card to put in front of the adult before this lesson starts, or null: an adult practising alone, a lesson
     * with no card, or a kind this child profile has already met.
     */
    fun firstTimeCard(focus: SessionFocus, isChild: Boolean, seen: Set<AdultCardKind>): AdultCard? {
        val kind = kind(focus) ?: return null
        if (kind in seen) return null
        return card(kind, isChild)
    }

    /** The same for the mirror, which is a page of its own and not a lesson. */
    fun firstTimeMirrorCard(isChild: Boolean, seen: Set<AdultCardKind>): AdultCard? =
        if (AdultCardKind.Mirror in seen) null else card(AdultCardKind.Mirror, isChild)

    // Stage names

    /**
     * The names Romanian speech therapists give to the stages of work on a sound ([StageNames]). They are labels for
     * where a lesson sits in the course, in the order of the levels. They don't say that the app does therapy.
     */
    val STAGE_NAMES = listOf("Emitere", "Consolidare", "Diferențiere", "Automatizare")

    fun stageName(rung: Rung): String = StageNames.name(rung.stage)

    /** "Etapa: Consolidare.", under a sound where an adult follows progress, and in the report. */
    fun stageLine(name: String): String = "Etapa: $name."

    /**
     * Under the stage line of a sound at automatizare: why only some takes are answered there. No count, no source.
     * iOS `AdultCards.automatizareNote`.
     */
    const val AUTOMATIZARE_NOTE = "La Automatizare, telefonul spune cum a sunat doar la unele încercări. E intenționat, ca sunetul să iasă și fără ajutor. Toate încercările apar în progres."

    /** In the box of the report that says how to read it. */
    const val STAGE_REPORT_NOTE = "Numele etapei (Emitere, Consolidare, Diferențiere, Automatizare) arată doar unde se află lecțiile în aplicație. Nu e o evaluare."

    /** The one line every report for the adult says what it is with: the PDF report, „Săptămâna pe scurt”, „Cum merge”. */
    const val REPORT_DISCLAIMER = "Raportul arată ce s-a exersat și ce a auzit telefonul. Nu e o evaluare: concluziile le trage logopedul."

    /** Every line above, for tests that look at all the wording at once. */
    val everyLine: List<String>
        get() {
            val fixed = listOf(
                PAGE_TITLE, TARGET_HEADING, GOOD_TRY_HEADING, AFTER_MISS_HEADING, UNDERSTOOD, REOPEN_HINT,
                SCREEN_TITLE, SCREEN_DETAIL, SCREEN_LEAD, START_SHEET_BUTTON, STAGE_REPORT_NOTE, REPORT_DISCLAIMER,
                AUTOMATIZARE_NOTE,
            )
            val cards = AdultCardKind.entries.flatMap { kind ->
                val card = card(kind)
                listOf(card.title, card.target, card.goodTry, card.afterMiss, detail(kind))
            }
            return fixed + cards + STAGE_NAMES + Rung.entries.map(StageNames::line)
        }
}

/**
 * Which cards a child profile has already met, kept on the phone under the profile's id (iOS `AdultCardsSeen`). It is
 * by profile, not by adult: whoever takes over finds the same state, and can read any card again from the settings.
 * "Șterge tot" clears it with the rest of the preferences, and deleting the profile forgets it.
 */
object AdultCardsSeen {
    fun key(profile: UUID): String = "fonia.adultCards.seen.$profile"

    fun decode(stored: String): Set<AdultCardKind> =
        stored.split(",").mapNotNull { AdultCardKind.fromRawValue(it.trim()) }.toSet()

    fun encode(kinds: Set<AdultCardKind>): String = kinds.map { it.rawValue }.sorted().joinToString(",")

    fun seen(profile: UUID, preferences: FoniaPreferences?): Set<AdultCardKind> =
        preferences?.let { decode(it.string(key(profile))) } ?: emptySet()

    fun markSeen(kind: AdultCardKind, profile: UUID, preferences: FoniaPreferences?) {
        val store = preferences ?: return
        val kinds = seen(profile, store)
        if (kind in kinds) return
        store.setString(key(profile), encode(kinds + kind))
    }

    /** When the profile is deleted. */
    fun forget(profile: UUID, preferences: FoniaPreferences?) {
        preferences?.remove(key(profile))
    }
}
