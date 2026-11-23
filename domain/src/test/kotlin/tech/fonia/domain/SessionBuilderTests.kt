package tech.fonia.domain

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

// Port of FoniaDomainTests/SessionBuilderTests.swift: 4 suites, 61 test functions.

@DisplayName("Session builder")
class SessionBuilderTests {
    private val builder = Fixture.builder
    private val content = Fixture.content

    private fun session(
        id: SoundID = SoundID.S,
        rung: Rung = Rung.WordsInitial,
        age: AgeBand = AgeBand.SixToSeven,
        history: AttemptsSummary = AttemptsSummary(),
        seed: ULong = 7uL,
        avoiding: Set<SoundID> = emptySet(),
        warmup: Boolean = false,
    ): Session = builder.build(
        SessionRequest(age = age, focus = SessionFocus.Sound(id, rung), history = history, seed = seed, avoidingSounds = avoiding, includesOralWarmup = warmup),
    )

    // Determinism and variety

    @ParameterizedTest
    @MethodSource("agesAndRungs")
    fun sameRequestGivesTheSameSession(age: AgeBand, rung: Rung) {
        assertEquals(session(SoundID.R, rung, age = age, seed = 42uL), session(SoundID.R, rung, age = age, seed = 42uL))
    }

    @ParameterizedTest
    @EnumSource(Rung::class)
    fun newSeedsGiveNewOrders(rung: Rung) {
        val orders = (0 until 25).map { seed -> session(SoundID.J, rung, seed = seed.toULong()).trials.map { it.exercise } }
        assertEquals(orders.size, orders.toSet().size)
    }

    @ParameterizedTest
    @EnumSource(Rung::class)
    fun theNextSessionNeverRepeatsTheOrderOnTheSameSeed(rung: Rung) {
        val orders = (0 until 25).map { completed ->
            session(SoundID.Ge, rung, history = AttemptsSummary(sessionsCompleted = completed), seed = 9uL).trials.map { it.exercise }
        }
        assertEquals(orders.size, orders.toSet().size)
    }

    @Test
    fun itemsFromLastSessionComeAfterNewOnes() {
        val recent = setOf("sanie", "supă", "suc")
        val history = AttemptsSummary(sessionsCompleted = 2, recentItems = recent)
        for (seed in 0 until 20) {
            val targetA = session(SoundID.S, Rung.WordsInitial, history = history, seed = seed.toULong()).trialsIn(SessionBlock.Kind.TargetA)
            val firstItems = targetA.take(content.sound(SoundID.S).words.initial.size - recent.size).mapNotNull { it.exercise.itemText }
            assertTrue(firstItems.none { it in recent }, "$firstItems")
        }
    }

    @Test
    fun noItemComesTwiceInARowInABlock() {
        for (seed in 0 until 20) {
            val targetA = session(SoundID.R, Rung.WordsInitial, age = AgeBand.EightToTen, seed = seed.toULong()).trialsIn(SessionBlock.Kind.TargetA)
            for ((first, second) in targetA.zipWithNext()) {
                assertNotEquals(first.exercise, second.exercise)
            }
        }
    }

    // Attempt counts

    @ParameterizedTest
    @MethodSource("agesAndRungs")
    fun attemptsFitTheAgeBand(age: AgeBand, rung: Rung) {
        for (id in SoundID.entries) {
            for (completed in listOf(0, 1, 2, 3, 4, 20)) {
                val built = session(id, rung, age = age, history = AttemptsSummary(sessionsCompleted = completed))
                assertEquals(built.targetAttempts, built.attempts.size, "$id $rung")
                assertTrue(built.targetAttempts in age.attemptRange, "$age got ${built.targetAttempts}")
            }
        }
    }

    @ParameterizedTest
    @MethodSource("doseTable")
    fun bandsMatchTheDoseTable(age: AgeBand, low: Int, high: Int) {
        assertEquals(low..high, age.attemptRange)
    }

    @Test
    fun sessionsGrowAndEaseOffOnceTheWeekIsOnTarget() {
        val first = session(age = AgeBand.SixToSeven, history = AttemptsSummary(sessionsCompleted = 0))
        val later = session(age = AgeBand.SixToSeven, history = AttemptsSummary(sessionsCompleted = 3))
        val onTarget = session(age = AgeBand.SixToSeven, history = AttemptsSummary(sessionsCompleted = 3, attemptsThisWeek = 150))
        assertEquals(30, first.targetAttempts)
        assertEquals(48, later.targetAttempts)
        assertEquals(30, onTarget.targetAttempts)
    }

    @Test
    fun sessionsStopAtTenMinutes() {
        assertEquals(600, session().timeLimitSeconds)
        assertEquals("Gata pe azi", session().endButton)
    }

    // Template

    @ParameterizedTest
    @EnumSource(Rung::class)
    fun trialsFollowTheTemplate(rung: Rung) {
        val order = listOf(
            SessionBlock.Kind.Opening, SessionBlock.Kind.Listening, SessionBlock.Kind.TargetA,
            SessionBlock.Kind.TargetB, SessionBlock.Kind.Mixed, SessionBlock.Kind.Closing, SessionBlock.Kind.Mission,
        )
        val kinds = session(SoundID.L, rung).trials.map { it.block.kind }
        val seen = mutableListOf<SessionBlock.Kind>()
        for (kind in kinds) {
            if (seen.lastOrNull() != kind) seen += kind
        }
        // Swift force-unwraps firstIndex(of:), so a kind outside the template fails the test.
        val positions = seen.map { kind -> order.indexOf(kind).also { assertTrue(it >= 0, "$kind is not in the template") } }
        assertEquals(positions.sorted(), positions)
        assertTrue(SessionBlock.Kind.Listening in seen)
        assertTrue(SessionBlock.Kind.TargetA in seen)
        assertTrue(SessionBlock.Kind.TargetB in seen)
        assertTrue(SessionBlock.Kind.Mixed in seen)
    }

    @Test
    fun targetsAlternateFromOneSessionToTheNext() {
        val even = session(history = AttemptsSummary(sessionsCompleted = 2)).trials.map { it.block.kind }
        val odd = session(history = AttemptsSummary(sessionsCompleted = 3)).trials.map { it.block.kind }
        assertTrue(SessionBlock.Kind.TargetA in even && SessionBlock.Kind.TargetB in even)
        assertTrue(SessionBlock.Kind.TargetA in odd && SessionBlock.Kind.TargetB in odd)
        assertTrue(even.indexOf(SessionBlock.Kind.TargetA) < even.indexOf(SessionBlock.Kind.TargetB))
        assertTrue(odd.indexOf(SessionBlock.Kind.TargetB) < odd.indexOf(SessionBlock.Kind.TargetA))
    }

    @ParameterizedTest
    @EnumSource(AgeBand::class)
    fun listeningWarmupIsFiveToTenIdentifications(age: AgeBand) {
        for (id in SoundID.entries) {
            val listening = session(id, age = age).trialsIn(SessionBlock.Kind.Listening)
            assertTrue(listening.size in 5..10)
            assertTrue(listening.all { it.exercise.isIdentification && it.feedback == FeedbackLevel.Quick })
        }
    }

    @Test
    fun listeningStartsWithTargetVersusErrorFromTheFirstRung() {
        for (id in SoundID.entries) {
            val listening = session(id, Rung.Isolated, age = AgeBand.TeenAdult).trialsIn(SessionBlock.Kind.Listening)
            assertTrue(listening.any { it.exercise.kind == Exercise.Kind.Choose }, "$id")
        }
    }

    @Test
    fun listeningOnlyPlaysRecordedWords() {
        for (id in SoundID.entries) {
            for (seed in 0 until 10) {
                for (trial in session(id, Rung.Pairs, age = AgeBand.TeenAdult, seed = seed.toULong()).trials.filter { it.exercise.isIdentification }) {
                    when (val exercise = trial.exercise) {
                        is Exercise.Choose -> assertTrue(exercise.prompt.answer.recorded && exercise.prompt.other.recorded, exercise.prompt.answer.text)
                        is Exercise.Hear -> assertTrue(exercise.item.recorded, exercise.item.text)
                        is Exercise.Sort -> assertTrue(exercise.item.recorded, exercise.item.text)
                        else -> fail("unexpected ${exercise.kind}")
                    }
                }
            }
        }
    }

    @Test
    fun introComesOnlyOnTheFirstSession() {
        assertEquals(Exercise.Intro(sound = SoundID.S), session(history = AttemptsSummary()).trials.firstOrNull()?.exercise)
        assertFalse(session(history = AttemptsSummary(sessionsCompleted = 1)).trials.any { it.exercise.kind == Exercise.Kind.Intro })
    }

    @Test
    fun hearingAnswersMatchTheWords() {
        for (id in SoundID.entries) {
            for (seed in 0 until 10) {
                for (trial in session(id, Rung.WordsInitial, age = AgeBand.TeenAdult, seed = seed.toULong()).trialsIn(SessionBlock.Kind.Listening)) {
                    val exercise = trial.exercise
                    if (exercise is Exercise.Hear) {
                        assertEquals(exercise.has, exercise.sound in Romanian.targetSounds(exercise.item.text), exercise.item.text)
                    }
                }
            }
        }
    }

    // Rungs

    @Test
    fun pairsComeBeforeSentences() {
        assertTrue(Rung.Pairs < Rung.Sentences)
        assertTrue(Rung.Clusters < Rung.Pairs)
        assertEquals(Rung.Isolated, Rung.entries.first())
        assertEquals(Rung.Everyday, Rung.entries.last())
    }

    @ParameterizedTest
    @EnumSource(SoundID::class)
    fun hardToPictureWordsAreNeverNamed(id: SoundID) {
        for (rung in Rung.entries) {
            for (trial in session(id, rung, age = AgeBand.TeenAdult).trials) {
                val exercise = trial.exercise
                if (exercise is Exercise.Name) {
                    assertTrue(exercise.item.namable, exercise.item.text)
                }
            }
        }
    }

    @Test
    fun wordRungsPreferWordsWithoutTheAvoidedSounds() {
        val built = session(SoundID.S, Rung.WordsInitial, seed = 3uL, avoiding = setOf(SoundID.R, SoundID.L))
        val words = built.trialsIn(SessionBlock.Kind.TargetA).mapNotNull { it.exercise.itemText }
        assertEquals(setOf("sanie", "supă", "suc"), words.toSet())
    }

    @Test
    fun wordRungsLeaveClustersForTheClusterRung() {
        for (id in listOf(SoundID.R, SoundID.L, SoundID.S, SoundID.Sh, SoundID.Z)) {
            for (trial in session(id, Rung.WordsInitial, age = AgeBand.TeenAdult).trialsIn(SessionBlock.Kind.TargetA)) {
                val text = trial.exercise.itemText ?: continue
                assertTrue(Romanian.clusters(text).isEmpty(), text)
            }
        }
    }

    @Test
    fun clusterRungHoldsClustersAndLongWords() {
        val vowels = "aăâeiîou".characters().map(::nfc).toSet()
        val built = session(SoundID.R, Rung.Clusters, age = AgeBand.TeenAdult)
        for (text in built.trialsIn(SessionBlock.Kind.TargetA).mapNotNull { it.exercise.itemText }) {
            val long = text.characters().count { nfc(it) in vowels } >= 3
            assertTrue(Romanian.clusters(text).isNotEmpty() || long, text)
        }
    }

    @ParameterizedTest
    @EnumSource(SoundID::class)
    fun pairRungPlacesPerceptionBeforeProduction(id: SoundID) {
        val targetA = session(id, Rung.Pairs, age = AgeBand.TeenAdult).trialsIn(SessionBlock.Kind.TargetA).filter { it.block.index == 0 }
        assertTrue(targetA.take(SessionBuilder.perceptionPerPairBlock).all { it.exercise.isIdentification })
        assertTrue(targetA.drop(SessionBuilder.perceptionPerPairBlock).all { it.exercise.isProduction })
    }

    @Test
    fun rIsPractisedInTapsWithTheTrillAsAnOptionalHelper() {
        val built = session(SoundID.R, Rung.Isolated, age = AgeBand.EightToTen)
        assertFalse(built.attempts.any { it.exercise.kind == Exercise.Kind.Sustain })
        val helpers = built.trials.filter { trial ->
            val exercise = trial.exercise
            exercise is Exercise.Sustain && exercise.role == SustainRole.Facilitation
        }
        assertEquals(1, helpers.size)
        assertTrue(helpers.all { !it.countsAsAttempt })
    }

    @Test
    fun otherSoundsAreHeldAtTheIsolatedRung() {
        assertTrue(session(SoundID.S, Rung.Isolated).trialsIn(SessionBlock.Kind.TargetA).all { it.exercise.kind == Exercise.Kind.Sustain })
        assertTrue(session(SoundID.L, Rung.Isolated).trialsIn(SessionBlock.Kind.TargetA).all { it.exercise.kind == Exercise.Kind.Repeat })
    }

    @Test
    fun isolatedRungAddsFourSyllableVariants() {
        val variants = session(SoundID.S, Rung.Isolated, age = AgeBand.EightToTen).trialsIn(SessionBlock.Kind.TargetB).mapNotNull { it.exercise.itemText }.toSet()
        assertEquals(content.sound(SoundID.S).syllables.direct.take(4).toSet(), variants)
    }

    @Test
    fun retellRungBringsRetellAndDescribe() {
        val kinds = session(SoundID.Z, Rung.Retell, age = AgeBand.TeenAdult).trials.map { it.exercise.kind }.toSet()
        assertTrue(Exercise.Kind.Retell in kinds)
        assertTrue(Exercise.Kind.Describe in kinds)
    }

    @ParameterizedTest
    @EnumSource(SoundID::class)
    fun everydayRungEndsWithASilentMission(id: SoundID) {
        val last = assertNotNull(session(id, Rung.Everyday).trials.lastOrNull())
        assertEquals(Exercise.Kind.Mission, last.exercise.kind)
        assertEquals(FeedbackLevel.Silent, last.feedback)
        assertFalse(last.countsAsAttempt)
    }

    @Test
    fun twistersAreAnExtraForOlderLearnersOnly() {
        for (seed in 0 until 15) {
            val young = session(SoundID.S, Rung.Sentences, age = AgeBand.SixToSeven, seed = seed.toULong()).trials
            assertFalse(young.any { it.exercise.kind == Exercise.Kind.Twister })
            val early = session(SoundID.S, Rung.Pairs, age = AgeBand.TeenAdult, seed = seed.toULong()).trials
            assertFalse(early.any { it.exercise.kind == Exercise.Kind.Twister })
        }
        val older = (0 until 15).flatMap { session(SoundID.S, Rung.Sentences, age = AgeBand.TeenAdult, seed = it.toULong()).trials }
        assertTrue(older.any { it.exercise.kind == Exercise.Kind.Twister })
    }

    // Oral warm-ups

    @Test
    fun oralWarmupsAreOffByDefault() {
        for (rung in Rung.entries) {
            assertFalse(session(SoundID.S, rung).trials.any { it.exercise.kind == Exercise.Kind.Warmup })
        }
    }

    @Test
    fun oralWarmupsNeverCountAsAttempts() {
        val plain = session(SoundID.S, Rung.WordsInitial)
        val warmed = session(SoundID.S, Rung.WordsInitial, warmup = true)
        assertTrue(warmed.trials.any { it.exercise.kind == Exercise.Kind.Warmup })
        assertTrue(warmed.trials.count { it.exercise.kind == Exercise.Kind.Warmup } <= 2)
        assertEquals(plain.attempts.size, warmed.attempts.size)
    }

    // Feedback

    @Test
    fun blocksFollowTheStageTable() {
        val cases = listOf(
            Rung.Isolated to Stage.Establish, Rung.WordsInitial to Stage.ConsolidateEarly, Rung.Clusters to Stage.ConsolidateLate,
            Rung.Pairs to Stage.Differentiate, Rung.Sentences to Stage.Automate, Rung.Everyday to Stage.Generalise,
        )
        for ((rung, stage) in cases) {
            assertEquals(stage, rung.stage)
            val block = session(SoundID.S, rung, age = AgeBand.EightToTen).trialsIn(SessionBlock.Kind.TargetA)
                .filter { it.block.index == 0 && it.exercise.isProduction }
            val answered = block.count { it.feedback != FeedbackLevel.Silent }
            when (stage) {
                Stage.Establish -> assertEquals(5, answered)
                Stage.ConsolidateEarly -> assertEquals(5, answered)
                Stage.ConsolidateLate -> assertEquals(4, answered)
                Stage.Differentiate -> assertEquals(3, answered)
                Stage.Automate, Stage.Generalise -> assertEquals(0, answered)
            }
        }
    }

    @Test
    fun mixedPracticeRespondsToEveryOtherAttempt() {
        val mixed = session(SoundID.S, Rung.Sentences, age = AgeBand.EightToTen).trialsIn(SessionBlock.Kind.Mixed)
        // Twelve mixed takes, the last of which gives its place to the easy closing item.
        assertEquals(11, mixed.size)
        assertEquals(5, mixed.count { it.feedback == FeedbackLevel.Silent })
    }

    @Test
    fun mixedPracticeKeepsSyllablesOnTheLiveVisual() {
        val mixed = session(SoundID.S, Rung.Syllables, age = AgeBand.EightToTen).trialsIn(SessionBlock.Kind.Mixed)
        assertTrue(mixed.all { it.feedback == FeedbackLevel.Teach })
    }

    @Test
    fun mixedPracticeSpinsProsody() {
        val prosodies = (0 until 10).flatMap { session(SoundID.S, Rung.Sentences, age = AgeBand.EightToTen, seed = it.toULong()).trialsIn(SessionBlock.Kind.Mixed) }
            .mapNotNull { it.prosody }
        assertEquals(Prosody.entries.toSet(), prosodies.toSet())
        val young = (0 until 10).flatMap { session(SoundID.S, Rung.Sentences, age = AgeBand.FourToFive, seed = it.toULong()).trialsIn(SessionBlock.Kind.Mixed) }
            .mapNotNull { it.prosody }
        assertFalse(Prosody.Question in young)
    }

    @Test
    fun automationHidesEveryResponseInTheBlocks() {
        assertTrue(session(SoundID.S, Rung.Sentences).trialsIn(SessionBlock.Kind.TargetA).all { it.feedback == FeedbackLevel.Silent })
    }

    // Voicing and the listening check

    @Test
    fun voicingSessionHearsThenSays() {
        val built = builder.build(SessionRequest(age = AgeBand.SixToSeven, focus = SessionFocus.Voicing, seed = 1uL))
        assertEquals(6, built.trialsIn(SessionBlock.Kind.Listening).size)
        assertEquals(6, built.attempts.size)
        assertEquals(6, built.targetAttempts)
        assertEquals(Track.Sounds, SessionFocus.Voicing.track)
    }

    @Test
    fun listeningCheckHoldsNoVerdict() {
        val trials = builder.listeningCheck(seed = 5uL)
        assertEquals(content.listening.check.size, trials.size)
        assertTrue(trials.all { it.feedback == FeedbackLevel.Silent && it.exercise.kind == Exercise.Kind.Choose })
        assertEquals(builder.listeningCheck(seed = 5uL), trials)
    }

    // Tracks

    @Test
    fun focusNamesItsTrack() {
        assertEquals(Track.Sounds, SessionFocus.Sound(SoundID.S, Rung.Isolated).track)
        assertEquals(Track.English, SessionFocus.English(EnglishFocus.Drill(contrast = "th")).track)
        assertEquals(Track.Diction, SessionFocus.Diction(DictionAreaID.Pitch).track)
    }

    companion object {
        @JvmStatic
        fun agesAndRungs(): List<Arguments> = AgeBand.entries.flatMap { age -> Rung.entries.map { Arguments.of(age, it) } }

        @JvmStatic
        fun doseTable(): List<Arguments> = listOf(
            Arguments.of(AgeBand.FourToFive, 20, 30),
            Arguments.of(AgeBand.SixToSeven, 30, 50),
            Arguments.of(AgeBand.EightToTen, 50, 70),
            Arguments.of(AgeBand.TeenAdult, 50, 70),
        )
    }
}

@DisplayName("English sessions")
class EnglishSessionTests {
    private val builder = Fixture.builder
    private val english = Fixture.content.english

    private fun build(focus: EnglishFocus, age: AgeBand = AgeBand.TeenAdult, seed: ULong = 11uL, history: AttemptsSummary = AttemptsSummary()): Session =
        builder.build(SessionRequest(age = age, focus = SessionFocus.English(focus), history = history, seed = seed))

    private val drillable: List<String>
        get() = english.contrasts.filter { it.kind == EnglishContrast.Kind.MinimalPairs && !it.needsRecording }.map { it.id }

    @Test
    fun everyRecordedContrastGetsAFullDrillDose() {
        assertTrue(drillable.size >= 7)
        for (id in drillable) {
            val drill = build(EnglishFocus.Drill(contrast = id))
            assertTrue(drill.targetAttempts in 40..60, "$id has ${drill.targetAttempts}")
            assertEquals(drill.targetAttempts, drill.attempts.size, id)
        }
    }

    @Test
    fun drillUsesAllFourVoicesOnEveryWord() {
        for (id in drillable) {
            val voicesByWord = mutableMapOf<String, MutableSet<Voice>>()
            for (trial in build(EnglishFocus.Drill(contrast = id)).trials) {
                val exercise = trial.exercise
                if (exercise !is Exercise.Choose) {
                    fail("unexpected ${exercise.kind}")
                }
                voicesByWord.getOrPut(exercise.prompt.answer.text) { mutableSetOf() } += assertNotNull(exercise.prompt.voice)
                assertTrue(exercise.prompt.cue != null)
                assertEquals(FeedbackLevel.Quick, trial.feedback)
                assertEquals(SessionBlock.Kind.Drill, trial.block.kind)
            }
            assertTrue(voicesByWord.values.all { it == Voice.english.toSet() }, id)
        }
    }

    @Test
    fun retestUsesOnlyHeldOutWordsAndStaysSilent() {
        for (id in drillable) {
            val contrast = assertNotNull(english.contrast(id))
            val trained = contrast.trainingPairs.flatMap { listOf(it.a, it.b) }.toSet()
            val retest = build(EnglishFocus.Retest(contrast = id))
            assertEquals(16, retest.targetAttempts, id)
            for (trial in retest.trials) {
                val exercise = trial.exercise
                if (exercise !is Exercise.Choose) {
                    fail("unexpected ${exercise.kind}")
                }
                assertFalse(exercise.prompt.answer.text in trained, exercise.prompt.answer.text)
                assertEquals(FeedbackLevel.Silent, trial.feedback)
                assertEquals(SessionBlock.Kind.Retest, trial.block.kind)
            }
        }
    }

    @Test
    fun drillsChangeOrderButRepeatOnTheSameSeed() {
        assertEquals(build(EnglishFocus.Drill(contrast = "th"), seed = 1uL), build(EnglishFocus.Drill(contrast = "th"), seed = 1uL))
        assertNotEquals(build(EnglishFocus.Drill(contrast = "th"), seed = 1uL), build(EnglishFocus.Drill(contrast = "th"), seed = 2uL))
    }

    @Test
    fun drillRarelyRepeatsAWordInARow() {
        val trials = build(EnglishFocus.Drill(contrast = "ship-sheep")).trials
        val repeats = trials.zipWithNext().count { (first, second) -> first.exercise.itemText == second.exercise.itemText }
        assertEquals(0, repeats)
    }

    @Test
    fun contrastsWithoutClipsGiveEmptySessions() {
        val ids = english.contrasts.filter { it.needsRecording }.map { it.id } + "not-a-contrast"
        for (id in ids) {
            assertTrue(build(EnglishFocus.Drill(contrast = id)).trials.isEmpty(), id)
            assertTrue(build(EnglishFocus.Retest(contrast = id)).trials.isEmpty(), id)
            assertTrue(build(EnglishFocus.Speaking(contrast = id)).trials.isEmpty(), id)
        }
    }

    @Test
    fun pairsAwaitingClipsStayOutOfDrills() {
        for (id in drillable) {
            val contrast = assertNotNull(english.contrast(id))
            val recorded = contrast.pairs.filter { it.recorded }.flatMap { listOf(it.a, it.b) }
            val awaiting = contrast.pairs.filter { !it.recorded }.flatMap { listOf(it.a, it.b) }.toSet() - recorded.toSet()
            val words = build(EnglishFocus.Drill(contrast = id)).trials.mapNotNull { it.exercise.itemText }.toSet()
            assertTrue(words.none { it in awaiting }, id)
        }
    }

    @Test
    fun stressDrillAsksAboutStress() {
        val drill = build(EnglishFocus.Drill(contrast = "stress"))
        assertFalse(drill.trials.isEmpty())
        assertTrue(drill.trials.all { it.exercise.kind == Exercise.Kind.Stress })
    }

    @ParameterizedTest
    @EnumSource(AgeBand::class)
    fun speakingFitsTheAgeBand(age: AgeBand) {
        for (contrast in english.contrasts.filter { !it.needsRecording }) {
            val session = build(EnglishFocus.Speaking(contrast = contrast.id), age = age)
            assertEquals(session.targetAttempts, session.attempts.size, contrast.id)
            assertTrue(session.targetAttempts in age.attemptRange, contrast.id)
        }
    }

    @Test
    fun speakingPractiseLeavesRetestWordsNew() {
        for (id in drillable) {
            val contrast = assertNotNull(english.contrast(id))
            val held = contrast.retestPairs.flatMap { listOf(it.a, it.b) }.toSet()
            for (seed in 0 until 5) {
                for (trial in build(EnglishFocus.Speaking(contrast = id), seed = seed.toULong()).trials) {
                    val exercise = trial.exercise
                    if (exercise is Exercise.Repeat) {
                        assertFalse(exercise.prompt.text in held, exercise.prompt.text)
                    }
                }
            }
        }
    }

    @Test
    fun schwaHasNoListeningBlock() {
        val session = build(EnglishFocus.Speaking(contrast = "schwa"))
        assertTrue(session.trialsIn(SessionBlock.Kind.Listening).isEmpty())
        assertFalse(session.attempts.isEmpty())
    }

    @Test
    fun melodyIsShadowing() {
        for (lesson in english.melody.lessons.indices) {
            val session = build(EnglishFocus.Melody(lesson = lesson))
            assertEquals(12, session.targetAttempts)
            assertTrue(session.trials.all { it.exercise.kind == Exercise.Kind.Shadow })
        }
        assertTrue(build(EnglishFocus.Melody(lesson = 99)).trials.isEmpty())
    }
}

@DisplayName("Diction sessions")
class DictionSessionTests {
    private val builder = Fixture.builder

    private fun build(area: DictionAreaID, age: AgeBand = AgeBand.TeenAdult, warmup: Boolean = false): Session =
        builder.build(SessionRequest(age = age, focus = SessionFocus.Diction(area), seed = 3uL, includesOralWarmup = warmup))

    @ParameterizedTest
    @EnumSource(DictionAreaID::class)
    fun everyAreaBuildsASession(area: DictionAreaID) {
        val session = build(area)
        assertEquals(12, session.targetAttempts)
        assertEquals(12, session.attempts.size)
        assertEquals(4, session.trialsIn(SessionBlock.Kind.TargetA).size)
        assertEquals(4, session.trialsIn(SessionBlock.Kind.TargetB).size)
        assertEquals(4, session.trialsIn(SessionBlock.Kind.Mixed).size)
    }

    @Test
    fun pitchPractisesIntonationAndExpression() {
        val kinds = (0uL until 20uL).flatMap { seed ->
            builder.build(SessionRequest(age = AgeBand.TeenAdult, focus = SessionFocus.Diction(DictionAreaID.Pitch), seed = seed)).trials.map { it.exercise.kind }
        }.toSet()
        assertTrue(kinds.containsAll(setOf(Exercise.Kind.Intonation, Exercise.Kind.Expression, Exercise.Kind.Glide)))
    }

    @Test
    fun breathingHoldsStayWithinTheNormForAge() {
        for (age in AgeBand.entries) {
            val norm = Fixture.content.diction.phonationNormSeconds.seconds(age)
            for (trial in build(DictionAreaID.Breathing, age = age).trials) {
                val exercise = trial.exercise
                if (exercise is Exercise.Sustain) {
                    assertTrue(exercise.seconds <= norm)
                }
            }
        }
    }

    @Test
    fun warmupsAreOptionalHereToo() {
        assertFalse(build(DictionAreaID.Articulation).trials.any { it.exercise.kind == Exercise.Kind.Warmup })
        assertTrue(build(DictionAreaID.Articulation, warmup = true).trials.any { it.exercise.kind == Exercise.Kind.Warmup })
    }

    @Test
    fun warmupIDsExist() {
        for (trial in build(DictionAreaID.Articulation, warmup = true).trials + build(DictionAreaID.Breathing, warmup = true).trials) {
            val exercise = trial.exercise
            if (exercise is Exercise.Warmup) {
                assertTrue(Fixture.content.warmup(exercise.id) != null, exercise.id)
            }
        }
    }
}

@DisplayName("Feedback schedule")
class FeedbackScheduleTests {
    private fun count(levels: List<FeedbackLevel>, level: FeedbackLevel): Int = levels.count { it == level }

    @Test
    fun establishTeachesOnFiveOfSix() {
        val levels = FeedbackSchedule.levels(stage = Stage.Establish, blockIndex = 0, seed = 1uL)
        assertEquals(6, levels.size)
        assertEquals(5, count(levels, FeedbackLevel.Teach))
        assertEquals(FeedbackLevel.Teach, levels.firstOrNull())
    }

    @Test
    fun earlyConsolidationAlternatesLiveVisualBetweenBlocks() {
        val first = FeedbackSchedule.levels(stage = Stage.ConsolidateEarly, blockIndex = 0, seed = 1uL)
        val second = FeedbackSchedule.levels(stage = Stage.ConsolidateEarly, blockIndex = 1, seed = 1uL)
        assertEquals(5, count(first, FeedbackLevel.Teach))
        assertEquals(4, count(second, FeedbackLevel.Quick))
        assertEquals(0, count(second, FeedbackLevel.Teach))
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1])
    fun lateConsolidationSelfJudgesOnHalf(block: Int) {
        val levels = FeedbackSchedule.levels(stage = Stage.ConsolidateLate, blockIndex = block, seed = 2uL)
        assertEquals(3, count(levels, FeedbackLevel.SelfJudge))
        assertTrue(levels.size - count(levels, FeedbackLevel.Silent) in 3..4)
    }

    @Test
    fun differentiationAnswersThreeOfSix() {
        val levels = FeedbackSchedule.levels(stage = Stage.Differentiate, blockIndex = 0, seed = 3uL)
        assertEquals(3, count(levels, FeedbackLevel.Quick))
        assertEquals(3, count(levels, FeedbackLevel.Silent))
    }

    @ParameterizedTest
    @EnumSource(value = Stage::class, names = ["Automate", "Generalise"])
    fun laterStagesStaySilent(stage: Stage) {
        assertTrue(FeedbackSchedule.levels(stage = stage, blockIndex = 0, seed = 4uL).all { it == FeedbackLevel.Silent })
    }

    @Test
    fun sameSeedGivesTheSameBlock() {
        assertEquals(
            FeedbackSchedule.levels(stage = Stage.Differentiate, blockIndex = 0, seed = 5uL),
            FeedbackSchedule.levels(stage = Stage.Differentiate, blockIndex = 0, seed = 5uL),
        )
    }
}
