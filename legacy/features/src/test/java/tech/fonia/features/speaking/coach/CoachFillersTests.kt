package tech.fonia.features.speaking.coach

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.fonia.speaking.FlaggedSpan
import tech.fonia.speaking.SpanKind
import tech.fonia.speaking.SpanSource
import tech.fonia.speaking.coach.FillerMark
import tech.fonia.speaking.coach.TalkLandmarks
import tech.fonia.store.JSONValue
import tech.fonia.store.SpeechRecord
import java.util.UUID

/** What the coach's strip and habit take from a talk (iOS `CoachFillersTests`, `CoachPlanAndStoreTests.landmarks`). */
class CoachFillersTests {
    private fun span(kind: SpanKind, start: Double, label: String, source: SpanSource) =
        FlaggedSpan(0, kind, start, start + 0.4, label, 0.9, source, cut = true, unsure = false)

    @Test
    fun soundsAlwaysCountFillerWordsOnlyFromAPermissiveRecogniser() {
        val spans = listOf(
            span(SpanKind.HESITATION, 1.0, "ăăă", SpanSource.ACOUSTIC),
            span(SpanKind.HESITATION, 3.0, "ăă", SpanSource.TRANSCRIPT),
            span(SpanKind.MARKER, 5.0, " Deci ", SpanSource.TRANSCRIPT),
            span(SpanKind.REPETITION, 7.0, "că că", SpanSource.TRANSCRIPT),
        )
        assertEquals(
            listOf(FillerMark(1.0, 1.4), FillerMark(3.0, 3.4), FillerMark(5.0, 5.4, "deci")),
            CoachController.fillerMarks(spans, wordsArePermissive = true),
        )
        // The Romanian recogniser of the APK: its words do not reach the strip, the sound's „ăăă” do.
        assertEquals(listOf(FillerMark(1.0, 1.4)), CoachController.fillerMarks(spans, wordsArePermissive = false))
    }

    @Test
    fun aStoredTalkGivesItsPaceAndPitchRange() {
        fun record(metrics: Map<String, JSONValue>) = SpeechRecord(
            id = UUID.randomUUID(), profileId = UUID.randomUUID(), lang = SpeechRecord.Language.Ro, prompt = "p", duration = 60.0,
            metrics = metrics,
        )
        val whole = record(mapOf("syllablesPerSecond" to JSONValue.Number(5.2), "pitchRangeSt" to JSONValue.Number(7.5)))
        assertEquals(TalkLandmarks(5.2, 7.5, false), CoachController.landmarksOf(whole))
        // Saved before the range was kept, and a drill.
        val old = CoachController.landmarksOf(record(mapOf("syllablesPerSecond" to JSONValue.Number(4.8), "drill" to JSONValue.String("pace"))))
        assertEquals(4.8, old.syllablesPerSecond, 0.0)
        assertEquals(true, old.pitchRangeSt.isNaN())
        assertEquals(true, old.drill)
    }
}
