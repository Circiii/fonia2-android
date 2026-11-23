package tech.fonia.domain

import java.io.File
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Whole sessions as text, one line per trial, for the element-by-element comparison with iOS that the inventory asks
 * for (§13.8, §14.2 step 1). The Swift exporter in `domain/PARITY.md` builds the same requests in the same order and
 * prints the same lines; its output goes to `src/test/resources/ios-sessions.txt`.
 */
object SessionVectors {
    fun requests(content: FoniaContent): List<Pair<String, SessionRequest>> {
        val requests = mutableListOf<Pair<String, SessionRequest>>()
        for (id in SoundID.entries) {
            for (rung in Rung.entries) {
                for ((age, completed) in listOf(AgeBand.FourToFive to 0, AgeBand.TeenAdult to 3)) {
                    requests += "sound ${id.rawValue} ${rung.rawValue} ${age.rawValue} $completed" to SessionRequest(
                        age = age,
                        focus = SessionFocus.Sound(id, rung),
                        history = AttemptsSummary(sessionsCompleted = completed, recentItems = setOf("sanie")),
                        seed = 42uL,
                        avoidingSounds = setOf(SoundID.R),
                        includesOralWarmup = completed == 0,
                    )
                }
            }
        }
        for (id in SoundID.entries) {
            requests += "meet ${id.rawValue}" to SessionRequest(age = AgeBand.SixToSeven, focus = SessionFocus.Meet(id), seed = 42uL)
            requests += "story ${id.rawValue}" to SessionRequest(age = AgeBand.SixToSeven, focus = SessionFocus.Story(id), seed = 42uL)
            requests += "back ${id.rawValue}" to SessionRequest(age = AgeBand.SixToSeven, focus = SessionFocus.SyllablesBack(id), seed = 42uL)
        }
        requests += "voicing" to SessionRequest(age = AgeBand.SixToSeven, focus = SessionFocus.Voicing, seed = 42uL)
        requests += "voicingSay" to SessionRequest(age = AgeBand.SixToSeven, focus = SessionFocus.VoicingSay, seed = 42uL)
        requests += "warmups" to SessionRequest(age = AgeBand.SixToSeven, focus = SessionFocus.Warmups(listOf("lumanarea", "morisca"), breath = true), seed = 42uL)
        for (contrast in content.english.contrasts) {
            val history = AttemptsSummary(sessionsCompleted = 1)
            val english = { focus: EnglishFocus -> SessionRequest(age = AgeBand.TeenAdult, focus = SessionFocus.English(focus), history = history, seed = 11uL) }
            requests += "drill ${contrast.id}" to english(EnglishFocus.Drill(contrast.id))
            requests += "retest ${contrast.id}" to english(EnglishFocus.Retest(contrast.id))
            requests += "speaking ${contrast.id}" to english(EnglishFocus.Speaking(contrast.id))
            requests += "sentences ${contrast.id}" to english(EnglishFocus.Sentences(contrast.id))
        }
        for (lesson in content.english.melody.lessons.indices) {
            requests += "melody $lesson" to SessionRequest(age = AgeBand.TeenAdult, focus = SessionFocus.English(EnglishFocus.Melody(lesson)), seed = 11uL)
        }
        for (area in DictionAreaID.entries) {
            requests += "diction ${area.rawValue}" to SessionRequest(age = AgeBand.TeenAdult, focus = SessionFocus.Diction(area), seed = 3uL, includesOralWarmup = true)
        }
        for (lesson in DictionLesson.entries) {
            requests += "lesson ${lesson.rawValue}" to SessionRequest(age = AgeBand.EightToTen, focus = SessionFocus.DictionLesson(lesson), seed = 3uL)
        }
        return requests
    }

    /** [build] makes each session; the adaptive tests pass the adaptive entry point to show it changes nothing when off. */
    fun render(builder: SessionBuilder, content: FoniaContent, build: (SessionRequest) -> Session = builder::build): String = buildString {
        for ((name, request) in requests(content)) {
            val session = build(request)
            append("# $name ${session.targetAttempts}\n")
            session.trials.forEach { append(line(it)).append('\n') }
        }
        append("# listeningCheck 5\n")
        builder.listeningCheck(seed = 5uL).forEach { append(line(it)).append('\n') }
    }

    private fun line(trial: Trial): String {
        val exercise = trial.exercise
        return "${trial.block.kind.rawValue} ${trial.block.index} ${trial.feedback.rawValue} ${trial.prosody?.rawValue ?: "-"} " +
            "${exercise.kind.rawValue} ${exercise.itemText ?: "-"} | ${detail(exercise)}"
    }

    private fun detail(exercise: Exercise): String = when (exercise) {
        is Exercise.Choose -> "${exercise.prompt.other.text}|${exercise.prompt.voice?.rawValue ?: "-"}"
        is Exercise.Hear -> "${exercise.has}"
        is Exercise.Sort -> exercise.answer.rawValue
        is Exercise.Sustain -> "${exercise.seconds}|${exercise.role.rawValue}"
        is Exercise.Repeat -> "${exercise.prompt.level.rawValue}|${exercise.prompt.rival ?: "-"}"
        is Exercise.Warmup -> exercise.id
        else -> "-"
    }
}

@DisplayName("Session vectors")
class SessionVectorTests {
    @Test
    fun sessionsMatchTheIOSExport() {
        val android = SessionVectors.render(Fixture.builder, Fixture.content)
        // Kept for a manual diff against an iOS export.
        File("build/session-vectors").apply { mkdirs() }.resolve("android-sessions.txt").writeText(android, Charsets.UTF_8)
        val ios = SessionVectors::class.java.getResourceAsStream("/ios-sessions.txt")?.use { it.readBytes().decodeToString() }
        assumeTrue(ios != null, "No iOS export yet: run the Swift exporter from domain/PARITY.md on a Mac")
        assertEquals(ios!!.replace("\r\n", "\n"), android)
    }
}
