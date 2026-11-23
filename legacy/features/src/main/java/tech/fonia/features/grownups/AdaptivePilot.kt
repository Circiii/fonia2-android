package tech.fonia.features.grownups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.PracticeWeight
import tech.fonia.design.TextRole
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fCaption
import tech.fonia.design.practice
import tech.fonia.domain.Rung
import tech.fonia.domain.SoundDef
import tech.fonia.domain.adaptive.AdaptiveDecision
import tech.fonia.domain.adaptive.AdaptiveProfile
import tech.fonia.domain.adaptive.Bkt
import tech.fonia.domain.adaptive.ItemPosition
import tech.fonia.domain.adaptive.KeyMastery
import tech.fonia.domain.adaptive.ModeSetting
import tech.fonia.domain.adaptive.PracticeMode
import tech.fonia.domain.adaptive.SoundAdaptive
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.HeaderPage
import tech.fonia.features.app.InsetGroupedList
import tech.fonia.features.app.ListSection
import tech.fonia.features.app.SegmentedPicker
import tech.fonia.features.app.ToggleRow
import tech.fonia.features.app.ValueRow
import tech.fonia.features.app.field
import tech.fonia.features.app.orderedSounds
import tech.fonia.features.app.shortLetter
import tech.fonia.features.app.title
import tech.fonia.features.pilot.AdaptiveRecord
import tech.fonia.store.Profile
import java.time.Instant
import java.util.UUID

/**
 * The adaptive pilot of one child (docs/PLAN.md §7.4): the switch, the mode of each sound the logoped chooses (P5), and
 * what the estimates say, in a parent's words. The child sees none of this, only the exercises it picks. Every change
 * here goes to the pilot log.
 */
@Composable
fun AdaptiveScreen(profileID: UUID, app: AppModel) {
    val profile = app.profiles.firstOrNull { it.id == profileID } ?: return
    val store = app.adaptive
    var data by remember { mutableStateOf<AdaptiveProfile?>(null) }
    LaunchedEffect(profileID) {
        data = try {
            store?.load(profileID)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AdaptiveProfile()
        }
    }

    fun change(decision: AdaptiveDecision, edit: (AdaptiveProfile) -> AdaptiveProfile) {
        val target = store ?: return
        data = edit(data ?: AdaptiveProfile())
        app.scope.launch {
            try {
                data = target.update(profileID, edit)
                app.pilot?.adaptive(AdaptiveRecord(profileID, sessionId = null, at = Instant.now(), decision = decision))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
    }

    HeaderPage("Mod adaptiv (pilot)") {
        InsetGroupedList {
            if (store == null) {
                ListSection { row { ValueRow("Nu merge pe acest telefon", "") } }
                return@InsetGroupedList
            }
            val loaded = data ?: return@InsetGroupedList
            ListSection(
                footer = "Pentru pilotul făcut cu logopedul. Pornit, telefonul alege exercițiile după cum au mers cele de dinainte: " +
                    "mai ușoare când nu ies, mai grele când ies ușor, și spune tot mai rar cum a sunat, pe măsură ce sunetul iese. " +
                    "Copilul vede doar exercițiile. Oprit, totul merge ca înainte.",
            ) {
                row {
                    ToggleRow("Mod adaptiv (pilot)", loaded.enabled) { on ->
                        change(AdaptiveDecision(AdaptiveDecision.Kind.Setting, sound = null, choice = if (on) "on" else "off", reason = "adult")) { it.copy(enabled = on) }
                    }
                }
            }
            val sounds = soundsOf(profile, app, loaded)
            if (sounds.isNotEmpty()) {
                ListSection(
                    header = "Cum exersează fiecare sunet",
                    footer = "Achiziție: la fiecare încercare telefonul arată cum a sunat. Puține cuvinte, fiecare spus de câteva ori la rând, după model. " +
                        "Generalizare: telefonul răspunde doar la unele încercări și spune la sfârșitul rundei câte au sunat bine. Cuvintele și locul sunetului se amestecă. " +
                        "Automat: începe cu achiziția, trece la generalizare după 2 ședințe în care cel puțin 8 din 10 încercări au sunat bine și se întoarce când sunt mai puțin de 6 din 10. " +
                        "Alegeți împreună cu logopedul.",
                ) {
                    for (sound in sounds) {
                        row {
                            ModeRow(sound, loaded.sound(sound.id)) { setting ->
                                change(AdaptiveDecision(AdaptiveDecision.Kind.Setting, sound = sound.id, choice = setting.rawValue, reason = "adult")) {
                                    it.with(sound.id, it.sound(sound.id).copy(setting = setting))
                                }
                            }
                        }
                    }
                }
            }
            EstimateSections(sounds, loaded)
        }
    }
}

/** The sounds the child works on, in the adult's order, then any other sound the pilot already has something on. */
private fun soundsOf(profile: Profile, app: AppModel, data: AdaptiveProfile): List<SoundDef> =
    profile.orderedSounds(app.content).filter { it.id.rawValue in profile.targetSounds || it.id in data.sounds.keys }

private val settings = listOf(ModeSetting.Auto, ModeSetting.Acquisition, ModeSetting.Generalization)

/** One sound: its letter, the three-way choice, and what it is doing now. */
@Composable
private fun ModeRow(sound: SoundDef, state: SoundAdaptive, onPick: (ModeSetting) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            FoniaText(sound.shortLetter, style = practice(26, PracticeWeight.bold, TextRole.title2), color = sound.family.field.deep)
            FoniaText(nowLine(sound, state), Modifier.padding(bottom = 3.dp), style = fCaption, color = Palette.inkSoft)
        }
        SegmentedPicker(listOf("Automat", "Achiziție", "Generalizare"), selected = settings.indexOf(state.setting).coerceAtLeast(0)) { onPick(settings[it]) }
    }
}

/** "Acum: achiziție, la Silabe cu S": the mode of the last session and the step the controller stands on. */
internal fun nowLine(sound: SoundDef, state: SoundAdaptive): String {
    val mode = when (state.mode) {
        PracticeMode.Acquisition -> "achiziție"
        PracticeMode.Generalization -> "generalizare"
        null -> return "Încă nu a exersat în modul adaptiv"
    }
    val step = state.challenge?.step ?: return "Acum: $mode"
    val pace = if (step.fast) ", spuse repede" else ""
    return "Acum: $mode, la „${step.rung.title(sound.shortLetter)}”$pace"
}

/**
 * The estimates in a parent's words: what is still being practised, then what came out right on two different days.
 * The numbers stay in the file for the logoped.
 */
@Composable
private fun EstimateSections(sounds: List<SoundDef>, data: AdaptiveProfile) {
    val practising = mutableListOf<Pair<String, String>>()
    val sure = mutableListOf<Pair<String, String>>()
    for (sound in sounds) {
        for (key in data.sound(sound.id).mastery.filter { it.takes > 0 }) {
            val title = keyTitle(sound, key)
            if (key.isMastered) sure += title to "În 2 zile diferite" else practising += title to estimateWords(key)
        }
    }
    if (practising.isEmpty() && sure.isEmpty()) return
    if (practising.isNotEmpty()) {
        ListSection(
            header = "Sunete de exersat",
            footer = "După încercările de până acum. Se schimbă de la o zi la alta. Logopedul vede cifrele în fișierul pe care i-l trimiți.",
        ) {
            for ((title, words) in practising) row { EstimateRow(title, words) }
        }
    }
    if (sure.isNotEmpty()) {
        ListSection(header = "Ies sigur") {
            for ((title, words) in sure) row { EstimateRow(title, words) }
        }
    }
}

@Composable
private fun EstimateRow(title: String, words: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FoniaText(title, style = fBodyStrong, color = Palette.ink)
        FoniaText(words, style = fCaption, color = Palette.inkSoft)
    }
}

/** One estimate in words, never as a number or a verdict about the child. */
internal fun estimateWords(key: KeyMastery): String = when {
    key.masteredOn.isNotEmpty() && key.pKnown >= Bkt.MASTERED_AT -> "A ieșit sigur într-o zi. Mai vedem și în altă zi."
    key.pKnown >= ESTIMATE_OFTEN -> "Iese tot mai des"
    else -> "Încă îl exersăm"
}

/** Under this P(mastered) the words say "still practising". */
private const val ESTIMATE_OFTEN = 0.5

/** The rung's own title, with the place of the sound when the rung mixes places. */
internal fun keyTitle(sound: SoundDef, key: KeyMastery): String {
    val title = key.rung.title(sound.shortLetter)
    val mixes = key.rung == Rung.Syllables || key.rung == Rung.Clusters || key.rung == Rung.Pairs
    val place = when (key.position) {
        ItemPosition.Initial -> "la început"
        ItemPosition.Medial -> "la mijloc"
        ItemPosition.Final -> "la sfârșit"
        ItemPosition.Isolated, ItemPosition.Sentence -> null
    }
    return if (mixes && place != null) "$title ($place)" else title
}
