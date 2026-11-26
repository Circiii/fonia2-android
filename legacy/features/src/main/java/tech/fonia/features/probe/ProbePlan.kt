package tech.fonia.features.probe

import android.content.res.AssetManager
import java.util.UUID
import tech.fonia.audio.CapturedClip
import tech.fonia.domain.Exercise
import tech.fonia.domain.FeedbackLevel
import tech.fonia.domain.Language
import tech.fonia.domain.NewWordsRecord
import tech.fonia.domain.ProbeLevel
import tech.fonia.domain.ProbeLists
import tech.fonia.domain.ProbeMark
import tech.fonia.domain.ProbeRules
import tech.fonia.domain.RepeatPrompt
import tech.fonia.domain.SessionBlock
import tech.fonia.domain.SessionFocus
import tech.fonia.domain.SoundID
import tech.fonia.domain.Trial
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.domainAge
import tech.fonia.features.grownups.orEmpty
import tech.fonia.store.Attempt
import tech.fonia.store.Profile

/**
 * A probe's take, the last one of its item: the phone's answer, and the clip for the adult to hear again. In memory
 * only; the clip is never written.
 */
class ProbeTake(val phone: Attempt.Outcome, val clip: CapturedClip?)

/**
 * A „Cuvinte noi” probe about to be said ([ProbeRules]): its items as silent trials, and whether the learner reads
 * them or hears each one first. It opens the lesson it was found before; nothing in it is stored as practice or
 * moves a rung.
 */
class ProbePlan(
    val sound: SoundID,
    val level: ProbeLevel,
    /** The learner reads each item: no model first. */
    val reads: Boolean,
    items: List<String>,
    /** The record's id, so the adult's marks land on the probe that was kept. */
    val id: UUID = UUID.randomUUID(),
) {
    // One block for the whole probe: it says nothing in the moment and nothing at its end.
    val trials: List<Trial> = SessionBlock(SessionBlock.Kind.TargetB).let { block ->
        items.map { text ->
            Trial(
                exercise = Exercise.Repeat(
                    RepeatPrompt(
                        text = text,
                        language = Language.Ro,
                        sound = sound,
                        level = if (level == ProbeLevel.Words) RepeatPrompt.Level.Word else RepeatPrompt.Level.Sentence,
                    ),
                ),
                feedback = FeedbackLevel.Silent,
                block = block,
            )
        }
    }

    val items: List<String> get() = trials.mapNotNull { (it.exercise as? Exercise.Repeat)?.prompt?.text }
}

/** The words of the probe. The child hears only the opening line and the thanks; the rest is for the adult. */
object ProbeCopy {
    const val title = "Cuvinte noi"
    const val opening = "Acum spui niște cuvinte noi. Eu doar ascult."
    const val openingSentences = "Acum spui niște propoziții noi. Eu doar ascult."
    const val thanks = "Mulțumesc!"
    const val forAdult = "Pentru adult"
    const val onward = "Mai departe"
    const val adultTitle = "Ce ai auzit?"
    const val adultHint = "Notează doar dacă ai auzit clar. Dacă nu ești sigur, lasă gol."
    const val adultWhy = "Cuvintele astea nu apar în lecții. Așa se vede dacă sunetul iese și în cuvinte pe care copilul nu le-a exersat."
    const val save = "Gata"
    const val suggestSentences = "Pe cuvinte noi merge bine. Încercați propoziții noi?"
    const val yesSentences = "Da, data viitoare"
    const val sentencesSet = "Data viitoare vin propoziții noi."
    const val tellTherapist = "Spune-i logopedului. El hotărăște ce urmează."

    fun title(mark: ProbeMark): String = when (mark) {
        ProbeMark.Good -> "Sună bine"
        ProbeMark.KeepWorking -> "Mai lucrăm"
        ProbeMark.Skip -> "Sari peste"
    }

    fun opening(level: ProbeLevel): String = if (level == ProbeLevel.Words) opening else openingSentences

    /**
     * "Cuvinte noi la S, 12 noiembrie: 14 din 25 „Sună bine”, 6 „Mai lucrăm”, 5 nenotate." Skipped items are named
     * only when there are some, the unmarked ones too.
     */
    fun reportLine(record: NewWordsRecord, letter: String, day: String): String {
        val tally = record.tally
        val kind = if (record.level == ProbeLevel.Words) "Cuvinte noi" else "Propoziții noi"
        val parts = mutableListOf("${tally.good} din ${tally.items} „Sună bine”", "${tally.keepWorking} „Mai lucrăm”")
        if (tally.skipped > 0) parts += counted(tally.skipped, "sărit", "sărite")
        if (tally.unmarked > 0) parts += counted(tally.unmarked, "nenotat", "nenotate")
        return "$kind la $letter, $day: ${parts.joinToString(", ")}."
    }

    /** "Pe 18 notate și auzite de telefon, telefonul a spus la fel la 14." Null under five such items. */
    fun phoneLine(record: NewWordsRecord): String? {
        val tally = record.tally
        if (tally.compared < 5) return null
        return "Pe ${tally.compared} notate și auzite de telefon, telefonul a spus la fel la ${tally.agreed}."
    }

    /** "Probe făcute până la capăt: 3 din 4." Counts the occasions taken up, finished or not. */
    fun doneLine(done: Int, offered: Int): String = "Probe făcute până la capăt: $done din $offered."

    /** "5 nenotate", "1 nenotat", "20 de nenotate". */
    fun counted(count: Int, one: String, many: String): String {
        if (count == 1) return "1 $one"
        val lastTwo = count % 100
        return if (count == 0 || lastTwo in 1..19) "$count $many" else "$count de $many"
    }

    val everyLine: List<String>
        get() = listOf(
            title, opening, openingSentences, thanks, forAdult, onward, adultTitle, adultHint, adultWhy, save,
            suggestSentences, yesSentences, sentencesSet, tellTherapist,
        ) + ProbeMark.entries.map(::title)
}

/** The bundled draft lists, `assets/drafts/probes.json` (iOS `ProbeLists.bundled()`); null when missing or broken. */
fun ProbeLists.Companion.bundled(assets: AssetManager): ProbeLists? = runCatching {
    parse(assets.open(PROBE_LISTS_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() })
}.getOrNull()

/** Where the lists sit, next to the other drafts. */
const val PROBE_LISTS_ASSET = "drafts/${ProbeLists.resource}.json"

/**
 * The probe to say before a lesson on [focus], or null: drafts or approved lists, a lesson on a sound from words on,
 * and three lessons on the sound since the last probe ([ProbeRules.isDue]).
 */
suspend fun AppModel.probe(focus: SessionFocus, profile: Profile): ProbePlan? {
    val lists = probeLists ?: return null
    val store = probes ?: return null
    if (profile.track != Profile.Track.Ro || focus !is SessionFocus.Sound) return null
    val sound = focus.id
    val stored = store.stored(profile.id)
    val last = stored.probes.lastOrNull { it.sound == sound.rawValue }?.date
    val sessions = orEmpty { repository.sessions(profile.id) }
    val since = sessions.count { session ->
        session.sound == sound.rawValue && session.endedAt != null && (last == null || session.startedAt > last)
    }
    if (!ProbeRules.isDue(focus.rung, lessonsSince = since, age = profile.domainAge)) return null
    val level = ProbeRules.level(sound.rawValue, sentencesFor = stored.sentences.toSet())
    val items = lists.items(sound.rawValue, level)
    if (items.size < ProbeRules.fewestItems) return null
    return ProbePlan(sound = sound, level = level, reads = ProbeRules.reads(profile.domainAge), items = items)
}
