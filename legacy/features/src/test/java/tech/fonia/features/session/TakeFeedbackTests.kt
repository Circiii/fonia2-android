package tech.fonia.features.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.Exercise
import tech.fonia.domain.FeedbackLevel
import tech.fonia.domain.Language
import tech.fonia.domain.RepeatPrompt
import tech.fonia.domain.Rung
import tech.fonia.domain.SessionFocus
import tech.fonia.domain.SoundID
import tech.fonia.dsp.VoiceGroup
import tech.fonia.judge.ClosureCue
import tech.fonia.judge.Gauge
import tech.fonia.judge.Hint
import tech.fonia.judge.HintKey
import tech.fonia.judge.Level
import tech.fonia.judge.PersonalReference
import tech.fonia.judge.PlaceAxis
import tech.fonia.judge.PolicyLevel
import tech.fonia.judge.TakeVisual
import tech.fonia.judge.TalkerGroup
import tech.fonia.judge.VoicingCue
import tech.fonia.model.Verdict
import java.nio.file.Files
import java.util.UUID

/** After every take he sees what happened; his reference and his dots are kept per profile and go with "Șterge tot". */
class TakeFeedbackTests {
    // --- policy ---------------------------------------------------------------------------------------------------

    @Test
    fun aManOnAChildProfilePractisesUnderTheSelfPolicy() {
        assertEquals(PolicyLevel.Self, PolicyLevel.select(TalkerGroup.Man, profileIsAdult = false))
        assertEquals(PolicyLevel.Self, PolicyLevel.select(TalkerGroup.Woman, profileIsAdult = false))
        assertEquals(PolicyLevel.Self, PolicyLevel.select(TalkerGroup.Child, profileIsAdult = true))
        assertEquals(PolicyLevel.Child, PolicyLevel.select(TalkerGroup.Child, profileIsAdult = false))
    }

    @Test
    fun burstsGoToTheNewJudge() {
        for ((sound, text) in listOf(SoundID.Ts to "ț-ț-ț", SoundID.Ce to "ci-ci-ci", SoundID.Ge to "ge-ge-ge")) {
            val exercise = Exercise.Repeat(RepeatPrompt(text = text, language = Language.Ro, sound = sound, level = RepeatPrompt.Level.Syllable))
            val request = SoundJudge.request(exercise, SessionFocus.Sound(sound, Rung.Isolated), VoiceGroup.MALE, null)
            assertNotNull(text, request)
            assertEquals(Level.Syllable, request!!.level)
        }
    }

    // --- visual -> hint and strip ------------------------------------------------------------------------------------

    @Test
    fun everyHintIsAtMostThreeKindWords() {
        for (key in HintKey.entries) {
            val words = HintWords.of(key)
            assertTrue(words, words.split(' ').size <= 3)
            for (bad in listOf("greșit", "gresit", "nu e bine", "rău")) assertFalse(words, words.lowercase().contains(bad))
            assertFalse(words, words.contains('ş') || words.contains('ţ')) // comma-below only
        }
    }

    @Test
    fun aHintOpensTheGuideAtItsStep() {
        val s = listOf("Fă ca mine: ssss.", "Zâmbește larg, ca la poză.", "Apropie dinții.", "Culcă limba jos, după dinți.", "Suflă aer rece: ssss.")
        val sh = listOf("Fă ca mine: șșșș.", "Fă buzele ca pentru un pupic.", "Apropie dinții.", "Ridică limba, ca o ceșcuță.", "Suflă aer cald: șșșș.")
        val z = listOf("Fă ca mine: zzzz.", "Zâmbește și apropie dinții.", "Culcă limba jos.", "Pune palma pe gât.", "Bâzâie ca albina: zzzz!")
        assertEquals(1, HintWords.step(HintKey.LipsSpread, s))
        assertEquals(3, HintWords.step(HintKey.TongueTipDown, s))
        assertEquals(4, HintWords.step(HintKey.MoreAir, s))
        assertEquals(1, HintWords.step(HintKey.LipsRound, sh))
        assertEquals(3, HintWords.step(HintKey.AddVoice, z))
        assertEquals(0, HintWords.step(HintKey.TapTongue, s)) // nothing matches: the first step
    }

    @Test
    fun theStripShowsTheCuesThatMattered() {
        // Z said without voice: the voice chip, off
        val z = TakeVisual(voicing = VoicingCue(present = false, expected = true, fraction = 0.1f), hint = Hint(HintKey.AddVoice, 0.9f, "kind"))
        assertEquals(listOf<Cue>(Cue.Voice(on = false, good = false)), chipsOf(z))
        // S said in a whisper, as it should be: no voice chip (voicing did not matter)
        assertTrue(chipsOf(TakeVisual(voicing = VoicingCue(present = false, expected = false, fraction = 0f))).isEmpty())
        // a voice crept into S: shown
        assertEquals(listOf<Cue>(Cue.Voice(on = true, good = false)), chipsOf(TakeVisual(voicing = VoicingCue(true, false, 0.8f))))
        // ... but not on a right S (the vowels around it hum)
        assertTrue(chipsOf(TakeVisual(voicing = VoicingCue(true, false, 0.8f)), correct = true).isEmpty())
        // R: the flutter beads
        assertEquals(listOf<Cue>(Cue.Trill(0.4f, 0.5f, false)), chipsOf(TakeVisual(trill = Gauge(0.4f, 0.5f, false))))
        // Ț: closure then release
        assertEquals(listOf<Cue>(Cue.Closure(true)), chipsOf(TakeVisual(closure = ClosureCue(true, true, 40f, 12f))))
        // a held time only when it was short
        assertTrue(chipsOf(TakeVisual(duration = Gauge(300f, 120f, true))).isEmpty())
        assertEquals(1, chipsOf(TakeVisual(duration = Gauge(60f, 120f, false))).size)
    }

    @Test
    fun aHissTheJudgeCouldNotPlaceIsStillShownWhereTheMeterHeardIt() {
        val v = TakeFeedbackRules.withLivePlace(TakeVisual(), SoundID.S, 0.08f)!!
        assertEquals(0.08f, v.placeAxis!!.value, 0f)
        assertFalse(v.placeAxis!!.inZone)
        assertEquals("live", v.placeAxis!!.reference)
        val sh = TakeFeedbackRules.withLivePlace(null, SoundID.Sh, 0.1f)!!
        assertTrue(sh.placeAxis!!.inZone)
        // the judge's own axis wins; no hiss, or R, changes nothing
        val judged = TakeVisual(placeAxis = PlaceAxis(0.7f, 0.6f, 1f, "group"))
        assertEquals(judged, TakeFeedbackRules.withLivePlace(judged, SoundID.S, 0.1f))
        assertNull(TakeFeedbackRules.withLivePlace(null, SoundID.S, Float.NaN))
        assertNull(TakeFeedbackRules.withLivePlace(null, SoundID.R, 0.5f))
    }

    // --- persistence ------------------------------------------------------------------------------------------------

    @Test
    fun aDotKeepsTheFewNumbersOfATake() {
        val v = TakeVisual(placeAxis = PlaceAxis(0.82f, 0.6f, 1f, "group"), voicing = VoicingCue(false, false, 0f))
        val dot = TakeDot.of(v, "s", Verdict.Correct, 1_000L)
        assertTrue(dot.inZone)
        val back = TakeDot.parse(dot.line())!!
        assertEquals(dot.place, back.place, 1e-3f)
        assertEquals("correct", back.outcome)
        assertEquals(0f, back.voiced, 0f)
        assertTrue(back.trill.isNaN())
        assertNull(TakeDot.parse("garbage"))
    }

    @Test
    fun hisReferenceAndDotsSurviveARestartAndGoWithTheProfile() {
        val dir = Files.createTempDirectory("voice").toFile()
        try {
            val him = UUID.randomUUID()
            val other = UUID.randomUUID()
            val store = VoiceStore(dir)
            store.personal(him)
            store.savePersonal(him)
            store.addDot(him, TakeDot(1L, "s", "almost", 0.3f, 0.6f, 1f))
            store.addDot(him, TakeDot(2L, "s", "correct", 0.8f, 0.6f, 1f))
            store.addDot(other, TakeDot(3L, "sh", "correct", 0.1f, 0f, 0.4f))
            assertTrue(dir.listFiles()!!.any { it.name == "personal-$him.json" })

            val again = VoiceStore(dir) // a new session: loaded from the files
            assertEquals(2, again.dots(him, "s").size)
            assertFalse(again.calibrated(him))
            assertEquals(PersonalReference().toJson(), again.personal(him).toJson())

            again.keepOnly(setOf(other)) // his profile deleted
            assertTrue(again.dots(him).isEmpty())
            assertFalse(dir.listFiles()!!.any { it.name.contains(him.toString()) })
            assertEquals(1, again.dots(other).size)
            again.keepOnly(emptySet()) // "Șterge tot"
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun progressComparesTheFirstDayWithToday() {
        val day = 86_400_000L
        val dots = listOf(
            TakeDot(1L, "s", "almost", 0.2f, 0.6f, 1f),
            TakeDot(2L, "s", "almost", 0.4f, 0.6f, 1f),
            TakeDot(5 * day + 1, "s", "correct", 0.8f, 0.6f, 1f),
            TakeDot(5 * day + 2, "s", "correct", 0.9f, 0.6f, 1f),
            TakeDot(5 * day + 3, "s", "almost", 0.5f, 0.6f, 1f),
            TakeDot(5 * day + 4, "s", "unsure"),
        )
        val p = SoundProgress.of(dots)!!
        assertEquals(2, p.first.size)
        assertEquals(0, p.firstGood)
        assertEquals(3, p.latestTotal)
        assertEquals(2, p.latestGood)
        assertFalse(p.sameDay)
        assertNull(SoundProgress.of(emptyList()))
        // one day, twenty-four takes: the first ten against the last ten
        val oneDay = (0 until 24).map { i -> TakeDot(i.toLong(), "s", if (i < 12) "almost" else "correct", if (i < 12) 0.3f else 0.8f, 0.6f, 1f) }
        val q = SoundProgress.of(oneDay)!!
        assertFalse(q.sameDay)
        assertEquals(0, q.firstGood)
        assertEquals(10, q.latestGood)
        assertEquals(10, q.latestTotal)
    }

    /** a silent slot hides the verdict, except the learner's question and a take nobody heard */
    @Test
    fun hiddenVerdicts() {
        assertTrue(TakeFeedbackRules.hidesVerdict(FeedbackLevel.Silent, asks = false, notHeard = false))
        assertFalse(TakeFeedbackRules.hidesVerdict(FeedbackLevel.Silent, asks = true, notHeard = false))
        assertFalse(TakeFeedbackRules.hidesVerdict(FeedbackLevel.Silent, asks = false, notHeard = true))
        for (level in listOf(FeedbackLevel.Teach, FeedbackLevel.Quick, FeedbackLevel.SelfJudge)) {
            assertFalse(TakeFeedbackRules.hidesVerdict(level, asks = false, notHeard = false))
        }
    }

    /** a hidden verdict's beat is shorter than a good take's */
    @Test
    fun heardBeat() {
        assertTrue(TakeFeedbackRules.HEARD_BEAT_MS > 0)
        assertTrue(TakeFeedbackRules.HEARD_BEAT_MS < Pacing.current.verdictGoodMs)
    }

    /** no live meter at automatizare, where a meter would tell which takes get an answer */
    @Test
    fun noMetersAtAutomatizare() {
        assertTrue(MeterPlan.shows(FeedbackLevel.Teach))
        assertTrue(MeterPlan.shows(FeedbackLevel.Quick))
        assertFalse(MeterPlan.shows(FeedbackLevel.Silent))
        for (level in FeedbackLevel.entries) assertFalse(MeterPlan.shows(level, automatizare = true))
    }
}
