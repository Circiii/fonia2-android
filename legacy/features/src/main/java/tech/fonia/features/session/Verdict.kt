package tech.fonia.features.session

import tech.fonia.design.Rating
import tech.fonia.domain.SoundDef
import tech.fonia.domain.SoundID
import tech.fonia.domain.Viseme
import tech.fonia.dsp.FricativeSegment
import tech.fonia.dsp.ProsodyComparison
import tech.fonia.dsp.ProsodyCurves
import tech.fonia.dsp.ProsodyOutcome
import tech.fonia.dsp.SibilantPlace
import tech.fonia.dsp.SoundOutcome
import tech.fonia.dsp.StressOutcome
import tech.fonia.dsp.UnclearReason
import tech.fonia.dsp.parseStress
import tech.fonia.features.app.SoundEffect
import tech.fonia.features.app.lowercasedFirstLetterForSentence
import tech.fonia.features.app.shortLetter
import tech.fonia.speech.PairOutcome
import tech.fonia.speech.SentenceOutcome
import tech.fonia.speech.WordOutcome
import tech.fonia.store.Attempt

// Verdict.swift: what the phone says about one take, already in the words the learner will read. Every text is the
// iOS one. The feedback is knowledge of the result only: "bine", "aproape", "încă nu", or that nothing was heard.

/** What the phone says about one take. A Swift struct with `var`s: here a data class, changed with `copy`. */
data class TakeVerdict(
    /** Null means "I can't tell" or "that's for a person to judge". */
    val rating: Rating?,
    val outcome: Attempt.Outcome,
    val title: String,
    val detail: String? = null,
    val tip: String? = null,
    /** A mouth pose to show next to the tip. */
    val pose: Viseme? = null,
    /** The phone can't judge this one, so it asks. */
    val asksAdult: Boolean = false,
    val words: List<WordOutcome> = emptyList(),
    val curves: ProsodyCurves? = null,
    val keepsTake: Boolean = true,
    /** The target sound as heard. If an adult says it was good, it teaches the phone this voice. */
    val anchor: FricativeSegment? = null,
    val anchorPlace: SibilantPlace? = null,
)

/**
 * The sound checks haven't met enough real children's mistakes to correct a child on their own (port-android.md §2.7).
 * So a child hears praise when it's good and a plain "once more" otherwise. The rating and outcome stay, so the take
 * is stored and counted as before; only what the child is shown changes.
 */
val TakeVerdict.softenedForChild: TakeVerdict
    get() {
        if (rating != Rating.nearly && rating != Rating.notYet) return this
        return copy(title = "Hai să mai încercăm", detail = null, tip = null, pose = null)
    }

/** Swift's `randomElement() ?? options[0]`. */
private fun pick(options: List<String>): String = options.randomOrNull() ?: options[0]

object Praise {
    val short = listOf("Bravo!", "Foarte bine!", "Da!", "Așa!")

    fun clear(letter: String): String = "$letter-ul a sunat clar."
}

val Rating.attemptOutcome: Attempt.Outcome
    get() = when (this) {
        Rating.yes -> Attempt.Outcome.Good
        Rating.nearly -> Attempt.Outcome.Almost
        Rating.notYet -> Attempt.Outcome.NotYet
    }

val Rating.effect: SoundEffect
    get() = if (this == Rating.yes) SoundEffect.Correct else SoundEffect.Almost

/** `selfRating` as it is stored: the Swift raw value. */
val Rating.rawValue: String get() = name

object VerdictWriter {
    /** The sound this one is usually confused with. Written fixes are keyed by the same names. */
    private fun rival(sound: SoundID): String = when (sound) {
        SoundID.S -> "Ș"
        SoundID.Sh -> "S"
        SoundID.Z -> "J"
        SoundID.J -> "Z"
        SoundID.Ts -> "CE"
        SoundID.Ce -> "Ț"
        SoundID.Ge -> "Z sau J"
        SoundID.R, SoundID.L -> "S"
    }

    /** "O dată din 3", "De 2 ori din 3", "De 20 de ori din 30". */
    private fun timesOf(count: Int, total: Int): String {
        if (count == 1) return "O dată din $total"
        return "De $count ${if (count % 100 >= 20) "de ori" else "ori"} din $total"
    }

    val notHeard = TakeVerdict(rating = null, outcome = Attempt.Outcome.Unclear, title = "Nu te-am auzit", tip = "Vorbește mai aproape de telefon.", keepsTake = false)

    /** The microphone gave nothing back, so there is no take to judge. Said plainly, with the one thing to do. */
    val listenFailed = TakeVerdict(rating = null, outcome = Attempt.Outcome.Unclear, title = "Nu am putut asculta", tip = "Mai încearcă o dată.", keepsTake = false)

    /** The take was recorded but judging it took too long. Nothing is wrong with the take; ask for it again. */
    val judgeTimedOut = TakeVerdict(rating = null, outcome = Attempt.Outcome.Unclear, title = "Nu știu sigur", tip = "Mai spune o dată, rar.", keepsTake = false)

    fun capture(outcome: SoundOutcome): TakeVerdict? = when (outcome) {
        SoundOutcome.NotHeard -> notHeard
        SoundOutcome.TooShort -> TakeVerdict(rating = null, outcome = Attempt.Outcome.Unclear, title = "A fost prea scurt", tip = "Mai spune o dată, rar.", keepsTake = false)
        SoundOutcome.Clipped -> TakeVerdict(rating = null, outcome = Attempt.Outcome.Unclear, title = "A sunat prea tare", tip = "Ține telefonul puțin mai departe.", keepsTake = false)
        else -> null
    }

    fun sibilant(outcome: SoundOutcome, sound: SoundDef): TakeVerdict {
        capture(outcome)?.let { return it }
        val letter = sound.shortLetter
        val other = rival(sound.id)
        return when (outcome) {
            SoundOutcome.Good -> TakeVerdict(rating = Rating.yes, outcome = Attempt.Outcome.Good, title = pick(Praise.short), detail = Praise.clear(letter))
            is SoundOutcome.WrongPlace -> {
                val fix = sound.fixes.firstOrNull { it.`when` == "sună ca $other" } ?: sound.fixes.firstOrNull { it.`when`.contains(other) }
                val times = if (outcome.of > 1) " ${timesOf(outcome.count, outcome.of)}." else ""
                TakeVerdict(
                    rating = Rating.nearly,
                    outcome = Attempt.Outcome.Almost,
                    title = "Aproape. A sunat ca $other",
                    detail = if (times.isEmpty()) null else times.drop(1),
                    tip = fix?.tip ?: sound.how.firstOrNull(),
                    pose = sound.viseme,
                )
            }
            SoundOutcome.Missing -> TakeVerdict(
                rating = Rating.notYet,
                outcome = Attempt.Outcome.NotYet,
                title = "N-am auzit $letter-ul",
                detail = "Am auzit cuvântul, dar fără $letter.",
                tip = sound.how.lastOrNull(),
                pose = sound.viseme,
            )
            is SoundOutcome.Partial -> TakeVerdict(
                rating = Rating.nearly,
                outcome = Attempt.Outcome.Almost,
                title = "Aproape. Încă o dată, mai clar",
                detail = "Am auzit $letter ${timesOf(outcome.heard, outcome.of).lowercasedFirstLetterForSentence}.",
                tip = "Lungește puțin fiecare $letter.",
            )
            SoundOutcome.Weak -> {
                val fix = sound.fixes.firstOrNull { it.`when`.contains("limba") }
                TakeVerdict(rating = Rating.nearly, outcome = Attempt.Outcome.Almost, title = "Sună puțin moale", tip = fix?.tip ?: "Ține limba în spatele dinților.", pose = sound.viseme)
            }
            SoundOutcome.Unvoiced -> {
                // Swift's `lowercased()` is locale-independent.
                val fix = sound.fixes.firstOrNull { it.tip.lowercase().contains("vocea") } ?: sound.fixes.firstOrNull()
                TakeVerdict(rating = Rating.nearly, outcome = Attempt.Outcome.Almost, title = "Pornește vocea", detail = "Gâtul n-a vibrat.", tip = fix?.tip, pose = sound.viseme)
            }
            SoundOutcome.LateralHint -> TakeVerdict(
                rating = null,
                outcome = Attempt.Outcome.Unclear,
                title = "Ascultă și tu",
                detail = "Parcă iese aerul pe o parte.",
                tip = sound.fixes.firstOrNull { it.`when`.contains("parte") }?.tip,
                pose = sound.viseme,
                asksAdult = true,
            )
            is SoundOutcome.Unclear -> when (outcome.reason) {
                UnclearReason.LOW_BANDWIDTH -> TakeVerdict(
                    rating = null,
                    outcome = Attempt.Outcome.Unclear,
                    title = "Nu aud bine sunetul",
                    tip = "Dacă ai căști, scoate-le: cu microfonul telefonului aud mai bine.",
                    asksAdult = true,
                )
                UnclearReason.NO_TARGET -> TakeVerdict(rating = null, outcome = Attempt.Outcome.NotJudged, title = "Te-am auzit", asksAdult = true)
                else -> TakeVerdict(
                    rating = null,
                    outcome = Attempt.Outcome.Unclear,
                    title = "Nu știu sigur",
                    detail = "A ieșit ceva între $letter și $other.",
                    tip = "Mai spune o dată, rar.",
                )
            }
            else -> TakeVerdict(rating = null, outcome = Attempt.Outcome.Unclear, title = "Nu știu sigur", tip = "Mai spune o dată, rar.")
        }
    }

    fun rhotic(outcome: SoundOutcome, sound: SoundDef): TakeVerdict {
        capture(outcome)?.let { return it }
        return when (outcome) {
            SoundOutcome.Tap, SoundOutcome.Good -> TakeVerdict(rating = Rating.yes, outcome = Attempt.Outcome.Good, title = pick(Praise.short), detail = "Am auzit R-ul.")
            SoundOutcome.Trill -> TakeVerdict(rating = Rating.yes, outcome = Attempt.Outcome.Good, title = "Bravo! Limba a vibrat.")
            is SoundOutcome.Partial -> TakeVerdict(
                rating = Rating.nearly,
                outcome = Attempt.Outcome.Almost,
                title = "Am auzit R, dar nu peste tot",
                detail = "${timesOf(outcome.heard, outcome.of)}.",
                tip = "Ridică vârful limbii la fiecare R.",
                pose = sound.viseme,
            )
            SoundOutcome.Missing -> {
                val fix = sound.fixes.firstOrNull { it.`when`.contains("lipsește") }
                TakeVerdict(rating = Rating.notYet, outcome = Attempt.Outcome.NotYet, title = "N-am auzit R-ul", tip = fix?.tip ?: sound.how.firstOrNull(), pose = sound.viseme)
            }
            else -> TakeVerdict(rating = null, outcome = Attempt.Outcome.Unclear, title = "Nu știu sigur", tip = "Mai spune o dată, rar.", asksAdult = true)
        }
    }

    /**
     * Ț, CE and GE said as three short bursts ("ț-ț-ț"), port-android.md §2.5. Each burst is brief and easy for the
     * check to miss, so the judging is lenient: one clear burst, or most of them in the right place, is a good take, and
     * when the phone hears none it asks the adult instead of calling the take wrong.
     */
    fun bursts(outcome: SoundOutcome, sound: SoundDef): TakeVerdict {
        val good = TakeVerdict(rating = Rating.yes, outcome = Attempt.Outcome.Good, title = pick(Praise.short), detail = Praise.clear(sound.shortLetter))
        return when (outcome) {
            SoundOutcome.NotHeard, SoundOutcome.Clipped -> capture(outcome) ?: notHeard
            SoundOutcome.Good -> good
            is SoundOutcome.Partial -> if (outcome.heard >= 1) good else personJudges(heard = true, tip = sound.how.firstOrNull(), pose = sound.viseme)
            // Headphones blur bursts as much as hisses: the same hint, before the catch-all below.
            is SoundOutcome.Unclear -> if (outcome.reason == UnclearReason.LOW_BANDWIDTH) sibilant(outcome, sound) else personJudges(heard = true, tip = sound.how.firstOrNull(), pose = sound.viseme)
            // Only a person can say whether any burst was right.
            is SoundOutcome.WrongPlace, SoundOutcome.Missing, SoundOutcome.TooShort ->
                personJudges(heard = true, tip = sound.how.firstOrNull(), pose = sound.viseme)
            else -> sibilant(outcome, sound)
        }
    }

    fun personJudges(heard: Boolean, tip: String?, pose: Viseme?): TakeVerdict {
        if (!heard) return notHeard
        return TakeVerdict(rating = null, outcome = Attempt.Outcome.NotJudged, title = "Cum a sunat?", tip = tip, pose = pose, asksAdult = true)
    }

    fun pair(outcome: PairOutcome, target: String, rival: String, cue: String?): TakeVerdict = when (outcome) {
        PairOutcome.NotHeard -> notHeard
        PairOutcome.SameText -> TakeVerdict(rating = null, outcome = Attempt.Outcome.NotJudged, title = "Cum a sunat?", asksAdult = true)
        is PairOutcome.OtherWord -> TakeVerdict(rating = Rating.notYet, outcome = Attempt.Outcome.NotYet, title = "Am auzit „${outcome.heard}”", tip = "Încearcă „$target”, rar și clar.")
        is PairOutcome.Target -> TakeVerdict(rating = Rating.yes, outcome = Attempt.Outcome.Good, title = "Am auzit „$target”")
        is PairOutcome.Rival -> TakeVerdict(rating = Rating.nearly, outcome = Attempt.Outcome.Almost, title = "A sunat ca „$rival”", tip = cue ?: "Ascultă încă o dată și mai încearcă.")
        PairOutcome.Unclear -> TakeVerdict(rating = null, outcome = Attempt.Outcome.Unclear, title = "Nu știu sigur", tip = "Mai spune o dată, rar.")
    }

    fun sentence(outcome: SentenceOutcome): TakeVerdict {
        val words = outcome.words
        return when (outcome.result) {
            SentenceOutcome.Result.NOT_HEARD -> notHeard
            SentenceOutcome.Result.ALL_HEARD -> TakeVerdict(rating = Rating.yes, outcome = Attempt.Outcome.Good, title = "Am înțeles fiecare cuvânt", words = words)
            SentenceOutcome.Result.SOME_WEAK -> {
                val word = outcome.weak.firstOrNull()?.word ?: ""
                TakeVerdict(rating = Rating.nearly, outcome = Attempt.Outcome.Almost, title = "Aproape. Mai ai puțin", tip = "Ascultă „$word” și spune-l mai clar.", words = words)
            }
            SentenceOutcome.Result.ONE_MISSED -> {
                val missed = outcome.missed.firstOrNull()
                val title = missed?.heard?.let { "„${missed.word}” a sunat ca „$it”" } ?: "N-am auzit „${missed?.word ?: ""}”"
                TakeVerdict(rating = Rating.nearly, outcome = Attempt.Outcome.Almost, title = title, tip = "Atinge cuvântul ca să-l asculți.", words = words)
            }
            SentenceOutcome.Result.UNCLEAR -> TakeVerdict(rating = null, outcome = Attempt.Outcome.Unclear, title = "Mai încercăm o dată", tip = "Spune propoziția rar, cuvânt cu cuvânt.", words = words)
            SentenceOutcome.Result.SEVERAL_MISSED -> {
                val list = outcome.missed.take(3).joinToString(", ") { "„${it.word}”" }
                TakeVerdict(rating = Rating.notYet, outcome = Attempt.Outcome.NotYet, title = "Câteva cuvinte au sunat altfel", tip = "Mai exersează $list.", words = words)
            }
        }
    }

    fun stress(outcome: StressOutcome, marked: String): TakeVerdict {
        val part = parseStress(marked)?.marked ?: marked
        return when (outcome) {
            StressOutcome.NotHeard -> notHeard
            StressOutcome.NotJudgeable -> TakeVerdict(rating = null, outcome = Attempt.Outcome.NotJudged, title = "Cum a sunat?", asksAdult = true)
            is StressOutcome.SyllablesMissing -> TakeVerdict(rating = null, outcome = Attempt.Outcome.Unclear, title = "N-am auzit toate silabele", tip = "Spune rar și apasă pe „$part”.")
            StressOutcome.Unclear -> TakeVerdict(rating = null, outcome = Attempt.Outcome.Unclear, title = "Silabele au sunat la fel", tip = "Apasă pe „$part”: mai tare, mai sus, puțin mai lung.")
            is StressOutcome.Correct -> TakeVerdict(rating = Rating.yes, outcome = Attempt.Outcome.Good, title = "Ai apăsat pe „$part”")
            is StressOutcome.Wrong -> {
                val order = listOf("prima", "a doua", "a treia", "a patra", "a cincea")
                // Swift indexes only below the count; a negative index would trap there and can't come from the judge.
                val which = if (outcome.heard < order.size) order[outcome.heard] else "altă"
                TakeVerdict(rating = Rating.nearly, outcome = Attempt.Outcome.Almost, title = "Ai apăsat pe $which silabă", tip = "Accentul cade pe „$part”. Spune-o mai tare și mai sus.")
            }
        }
    }

    fun prosody(comparison: ProsodyComparison): TakeVerdict {
        val verdict = when (comparison.outcome) {
            ProsodyOutcome.NOT_HEARD -> return notHeard
            ProsodyOutcome.GOOD -> TakeVerdict(rating = Rating.yes, outcome = Attempt.Outcome.Good, title = "Ai prins melodia")
            ProsodyOutcome.CLOSE -> TakeVerdict(rating = Rating.nearly, outcome = Attempt.Outcome.Almost, title = "Aproape. Mai ascultă o dată")
            ProsodyOutcome.FLAT -> TakeVerdict(rating = Rating.nearly, outcome = Attempt.Outcome.Almost, title = "Vocea a stat pe loc", tip = "Lasă vocea să urce și să coboare, ca în exemplu.")
            ProsodyOutcome.MELODY_DIFFERS -> TakeVerdict(rating = Rating.nearly, outcome = Attempt.Outcome.Almost, title = "Melodia a mers altfel", tip = "Ascultă unde urcă vocea în exemplu.")
            ProsodyOutcome.TOO_SLOW -> TakeVerdict(rating = Rating.nearly, outcome = Attempt.Outcome.Almost, title = "Puțin mai repede", tip = "Leagă cuvintele din aceeași idee.")
            ProsodyOutcome.TOO_FAST -> TakeVerdict(rating = Rating.nearly, outcome = Attempt.Outcome.Almost, title = "Puțin mai rar", tip = "Fă o mică pauză între idei.")
            ProsodyOutcome.PAUSES_DIFFER -> TakeVerdict(rating = Rating.nearly, outcome = Attempt.Outcome.Almost, title = "Ai făcut pauzele în alte locuri", tip = "Fă pauzele în aceleași locuri.")
        }
        return verdict.copy(curves = comparison.curves)
    }

    /** Pace, pauses and pitch are facts about a take. They're reported as facts, with no drama. */
    fun facts(rating: Rating, title: String, tip: String? = null): TakeVerdict =
        TakeVerdict(rating = rating, outcome = rating.attemptOutcome, title = title, tip = tip)
}
