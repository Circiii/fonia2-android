package tech.fonia.features.session

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tech.fonia.design.Field
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.MarkedSentence
import tech.fonia.design.MarkedWord
import tech.fonia.design.MelodyOverlay
import tech.fonia.design.Palette
import tech.fonia.design.PracticeSheet
import tech.fonia.design.PracticeWeight
import tech.fonia.design.TargetWord
import tech.fonia.design.TextRole
import tech.fonia.design.WordMark
import tech.fonia.design.WordPicture
import tech.fonia.design.easeOut
import tech.fonia.design.fBody
import tech.fonia.design.fCaption
import tech.fonia.design.fDisplay
import tech.fonia.design.fHeadline
import tech.fonia.design.fLead
import tech.fonia.design.practice
import tech.fonia.domain.Exercise
import tech.fonia.domain.IntonationAsk
import tech.fonia.domain.Language
import tech.fonia.domain.RepeatPrompt
import tech.fonia.domain.SoundDef
import tech.fonia.domain.WordItem
import tech.fonia.features.app.field
import tech.fonia.features.app.shortLetter
import tech.fonia.speech.WordStatus

/**
 * What to say, on one sheet. The form follows the size of the thing: a giant syllable, a word with its picture, a
 * sentence that wraps.
 */
@Composable
fun PromptView(
    exercise: Exercise,
    sound: SoundDef?,
    field: Field,
    verdict: TakeVerdict?,
    phase: SpeakPhase,
    modifier: Modifier = Modifier,
    /** The living target letter (null: his static word). */
    letter: LetterLink? = null,
    onTapWord: (MarkedWord) -> Unit,
) {
    val accent = sound?.id?.family?.field?.deep ?: Palette.accent
    val family = sound?.id?.family?.field
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        when (exercise) {
            is Exercise.Repeat -> {
                val prompt = exercise.prompt
                when (prompt.level) {
                    RepeatPrompt.Level.Syllable -> BigSyllable(prompt.text, accent, sound, family, letter)

                    RepeatPrompt.Level.Sentence -> Sentence(
                        prompt.text,
                        prompt.cue,
                        accent,
                        verdict,
                        onTapWord,
                        hero = if (prompt.language == Language.Ro) sound else null,
                        family = family,
                        letter = letter,
                    )

                    else -> Word(prompt.text, prompt.picture, prompt.cue, isEnglish = prompt.language == Language.En, sound, accent, family, letter)
                }
            }

            is Exercise.Name -> Naming(exercise.item, sound, accent, phase, family, letter)

            is Exercise.Twister -> Sentence(
                exercise.text,
                null,
                accent,
                verdict,
                onTapWord,
                hero = if (exercise.language == Language.Ro) sound else null,
                family = family,
                letter = letter,
            )

            Exercise.Volume -> VoiceTask("Vorbește tare", "Spune o frază, ca să te audă și ultimul rând.", FoniaIcon.loud, accent)

            Exercise.Glide -> VoiceTask("Urcă și coboară", "Spune „aaa” de jos în sus, apoi înapoi.", FoniaIcon.glide, accent)

            is Exercise.Intonation -> Sentence(
                if (exercise.ask == IntonationAsk.Question) exercise.question else exercise.statement,
                if (exercise.ask == IntonationAsk.Question) "Ca o întrebare" else "Ca o afirmație",
                accent,
                verdict,
                onTapWord,
            )

            is Exercise.Expression -> Sentence(exercise.text, exercise.cue, accent, verdict, onTapWord)

            is Exercise.Pace -> Sentence(exercise.text, null, accent, verdict, onTapWord)

            is Exercise.Pauses -> Pauses(exercise.text, accent)

            is Exercise.Shadow -> Shadow(exercise.text, accent, verdict)

            is Exercise.Retell -> Pictures(exercise.prompt.pictures, exercise.prompt.title, exercise.prompt.prompt)

            is Exercise.Describe -> Pictures(exercise.prompt.pictures, null, exercise.prompt.prompt)

            else -> Unit
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun BigSyllable(text: String, accent: Color, sound: SoundDef?, family: Field?, letter: LetterLink?) {
    if (letter != null && sound != null && family != null) {
        PracticeSheet {
            LiveTargetWord(text, sound.graphemes, family, letter, Modifier.padding(vertical = 28.dp), size = 104f, weight = PracticeWeight.bold, ink = accent)
        }
        return
    }
    PracticeSheet {
        FoniaText(
            text,
            Modifier.padding(vertical = 28.dp),
            style = practice(104, PracticeWeight.bold, TextRole.largeTitle),
            color = accent,
            maxLines = 1,
            minimumScaleFactor = 0.4f,
        )
    }
}

@Composable
private fun Word(text: String, picture: String?, cue: String?, isEnglish: Boolean, sound: SoundDef?, accent: Color, family: Field?, letter: LetterLink?) {
    PracticeSheet {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (picture != null) WordPicture(picture, Modifier.heightIn(max = 210.dp))
            if (!isEnglish && letter != null && sound != null && family != null) {
                LiveTargetWord(text, sound.graphemes, family, letter, Modifier.padding(vertical = if (picture == null) 36.dp else 0.dp), size = 52f)
            } else {
                TargetWord(
                    text,
                    graphemes = if (isEnglish) emptyList() else sound?.graphemes ?: emptyList(),
                    target = accent,
                    modifier = Modifier.padding(vertical = if (picture == null) 36.dp else 0.dp),
                    size = 52f,
                )
            }
            if (cue != null) FoniaText(cue, style = fBody, color = Palette.inkSoft, textAlign = TextAlign.Center)
        }
    }
}

/** With no model and no letters for a pre-reader: the picture alone calls up the word. The written word appears once the take is over, so an adult can follow. */
@Composable
private fun Naming(item: WordItem, sound: SoundDef?, accent: Color, phase: SpeakPhase, family: Field?, letter: LetterLink?) {
    val shown by animateFloatAsState(if (phase == SpeakPhase.Result) 1f else 0f, easeOut(0.15), label = "word")
    PracticeSheet {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            item.picture?.let { WordPicture(it, Modifier.heightIn(max = 220.dp)) }
            // While he names the picture the word stays hidden; the sound's letter answers his voice in its place.
            if (phase != SpeakPhase.Result && letter != null && sound != null && family != null) {
                EchoLetter(sound.shortLetter, letter, family, Modifier.alpha(1f - shown), height = 150.dp)
            }
            Box(Modifier.heightIn(min = 56.dp).alpha(shown), contentAlignment = Alignment.Center) {
                if (letter != null && sound != null && family != null) {
                    LiveTargetWord(item.text, sound.graphemes, family, letter, size = 44f)
                } else {
                    TargetWord(item.text, graphemes = sound?.graphemes ?: emptyList(), target = accent, size = 44f)
                }
            }
        }
    }
}

/**
 * A sentence to say. With a target sound ([hero]) its letter stands alive above the words, so something reacts while
 * he speaks: it grows with his voice and lights while the sound itself is heard.
 */
@Composable
private fun Sentence(
    text: String,
    cue: String?,
    accent: Color,
    verdict: TakeVerdict?,
    onTapWord: (MarkedWord) -> Unit,
    hero: SoundDef? = null,
    family: Field? = null,
    letter: LetterLink? = null,
) {
    PracticeSheet {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (hero != null && family != null && letter != null) HeldLetter(hero.shortLetter, letter, family, height = 168.dp)
            MarkedSentence(marks(text, verdict), accent = accent, modifier = Modifier.padding(vertical = 12.dp), size = 32f, ink = Palette.ink, onTap = onTapWord)
            if (cue != null) FoniaText(cue, style = fBody, color = Palette.inkSoft, textAlign = TextAlign.Center)
        }
    }
}

/** Each word of the text, marked with what the model heard for it, when it said anything. */
private fun marks(text: String, verdict: TakeVerdict?): List<MarkedWord> {
    val pieces = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    return pieces.mapIndexed { index, piece ->
        val outcome = verdict?.words?.firstOrNull { it.index == index }
        val mark = when (outcome?.status) {
            WordStatus.GOOD -> WordMark.good
            WordStatus.WEAK -> WordMark.weak
            WordStatus.MISSED -> WordMark.missed
            WordStatus.EXTRA -> WordMark.extra
            null -> WordMark.plain
        }
        MarkedWord(id = index, text = piece, mark = mark)
    }
}

@Composable
private fun Pauses(text: String, accent: Color) {
    PracticeSheet {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Swift's `split(separator:)` leaves out empty pieces before they are trimmed.
            val lines = text.split("|").filter { it.isNotEmpty() }.map { it.trim() }
            lines.forEachIndexed { index, line ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    FoniaText(line, style = practice(26, PracticeWeight.regular, TextRole.title2), color = Palette.ink, textAlign = TextAlign.Center)
                    if (index < lines.size - 1) Box(Modifier.size(width = 36.dp, height = 5.dp).background(accent, RoundedCornerShape(50)))
                }
            }
        }
    }
}

@Composable
private fun Shadow(text: String, accent: Color, verdict: TakeVerdict?) {
    PracticeSheet {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            MarkedSentence(marks(text, verdict), accent = accent, size = 27f)
            val curves = verdict?.curves
            if (curves != null) {
                MelodyOverlay(
                    model = curves.model.map { it?.toDouble() },
                    take = curves.take.map { it?.toDouble() },
                    modelColor = Palette.cold,
                    takeColor = Palette.warm,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .semantics { contentDescription = "Melodia ta peste cea din exemplu" },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Legend("Exemplul", Palette.cold)
                    Legend("Tu", Palette.warm)
                }
            } else {
                FoniaText("Spune odată cu vocea sau imediat după ea.", style = fBody, color = Palette.inkSoft, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Legend(title: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(color, CircleShape))
        FoniaText(title, style = fCaption, color = Palette.inkSoft)
    }
}

@Composable
private fun Pictures(names: List<String>, title: String?, prompt: String) {
    PracticeSheet {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (title != null) FoniaText(title, style = fHeadline, color = Palette.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                for (name in names) WordPicture(name, Modifier.weight(1f, fill = false).heightIn(max = 96.dp))
            }
            FoniaText(prompt, style = fLead, color = Palette.ink, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun VoiceTask(title: String, detail: String, icon: FoniaIcon, accent: Color) {
    PracticeSheet {
        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FoniaIconView(icon, size = 56f, tint = accent)
            FoniaText(title, style = fDisplay, color = Palette.ink)
            FoniaText(detail, style = fLead, color = Palette.inkSoft, textAlign = TextAlign.Center)
        }
    }
}
