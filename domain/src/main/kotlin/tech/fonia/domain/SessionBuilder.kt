package tech.fonia.domain

/**
 * Builds a session from the curriculum. Pure: the same content and request give the same session.
 *
 * The template: opening, listening warm-up, target A in blocks, target B in blocks, mixed practice, fixed end. A new
 * `seed` or a higher `sessionsCompleted` gives new items in a new order.
 *
 * The order of every draw from [SeededRandom] is part of the specification (inventory §13.8): each step below runs in
 * the same order as `SessionBuilder.swift`, so the same request gives the same session as on iOS. Swift's `inout`
 * generator and decks are plain mutable objects passed along here.
 */
class SessionBuilder(
    private val content: FoniaContent,
    /**
     * Whether a person recorded this English text (iOS `voices?.clipPath(text:language: .en) != nil`). An English
     * sentence is shadowed only when it was recorded: shadowing the device voice would teach its accent. Null means no
     * clips, so nothing is shadowed.
     */
    private val recordedEnglish: ((String) -> Boolean)? = null,
) {
    fun build(request: SessionRequest): Session {
        val random = SeededRandom(sessionSeed(request))
        return when (val focus = request.focus) {
            is SessionFocus.Sound -> soundSession(content.sound(focus.id), focus.rung, request, random)
            is SessionFocus.Meet -> meetSession(content.sound(focus.id), random)
            is SessionFocus.Warmups -> {
                val moves: List<Exercise> = focus.ids.map { Exercise.Warmup(id = it) }
                lesson(
                    if (focus.breath) moves + Exercise.Sustain(SustainTarget.Breath, seconds = 4.0, role = SustainRole.Practice) else moves,
                    feedback = FeedbackLevel.Silent,
                )
            }
            is SessionFocus.SyllablesBack -> {
                val syllables = content.sound(focus.id).syllables
                lesson(
                    (syllables.inverse + syllables.between).map {
                        Exercise.Repeat(RepeatPrompt(text = it, language = Language.Ro, sound = focus.id, level = RepeatPrompt.Level.Syllable))
                    },
                )
            }
            is SessionFocus.Story -> {
                val sound = content.sound(focus.id)
                val lines: List<Exercise> = sound.sentences.map {
                    Exercise.Repeat(RepeatPrompt(text = it, language = Language.Ro, sound = focus.id, level = RepeatPrompt.Level.Sentence))
                }
                val twister: List<Exercise> = sound.twisters.take(1).map { Exercise.Twister(text = it.text, language = Language.Ro, sound = focus.id) }
                lesson(listOf<Exercise>(Exercise.Intro(sound = focus.id)) + lines + twister, feedback = FeedbackLevel.Quick)
            }
            SessionFocus.Voicing -> voicingSession(random)
            SessionFocus.VoicingSay -> lesson(
                random.shuffled(content.listening.voicingPairs).mapNotNull { pair ->
                    pair.spoken?.let { Exercise.Repeat(RepeatPrompt(text = it, language = Language.Ro, level = RepeatPrompt.Level.Word)) }
                },
                feedback = FeedbackLevel.Quick,
            )
            is SessionFocus.English -> when (val english = focus.focus) {
                is EnglishFocus.Sentences -> {
                    val id = english.contrast
                    val lines: List<Exercise> = (content.english.contrast(id)?.sentences ?: emptyList()).map {
                        Exercise.Repeat(RepeatPrompt(text = it, language = Language.En, level = RepeatPrompt.Level.Sentence, englishContrast = id))
                    }
                    val twisters: List<Exercise> = content.english.twisters.take(1).map { Exercise.Twister(text = it, language = Language.En, sound = null) }
                    lesson(lines + twisters, feedback = FeedbackLevel.Quick)
                }
                is EnglishFocus.Drill -> drillSession(english.contrast, SessionBlock.Kind.Drill, random)
                is EnglishFocus.Retest -> drillSession(english.contrast, SessionBlock.Kind.Retest, random)
                is EnglishFocus.Speaking -> speakingSession(english.contrast, request, random)
                is EnglishFocus.Melody -> melodySession(english.lesson, random)
            }
            is SessionFocus.DictionLesson -> dictionLesson(focus.lesson, request)
            is SessionFocus.Diction -> dictionSession(focus.area, request, random)
        }
    }

    /**
     * The optional check an adult can run before choosing sounds. It holds no verdict and no recommendation: the adult
     * reads the answers and decides.
     */
    fun listeningCheck(seed: ULong): List<Trial> {
        val random = SeededRandom(seed)
        return random.shuffled(content.listening.check).map { pair ->
            val (answer, other) = if (random.below(2) == 0) pair.a to pair.b else pair.b to pair.a
            Trial(
                exercise = Exercise.Choose(ChoosePrompt(answer = answer, other = other, language = Language.Ro)),
                feedback = FeedbackLevel.Silent,
                block = SessionBlock(SessionBlock.Kind.Listening),
            )
        }
    }

    /** A named lesson: its exercises in order, once each. It teaches or checks, and never moves a rung. */
    private fun lesson(exercises: List<Exercise>, feedback: FeedbackLevel = FeedbackLevel.Teach): Session {
        val trials = exercises.map { Trial(exercise = it, feedback = feedback, block = SessionBlock(SessionBlock.Kind.Mixed)) }
        return Session(trials = trials, targetAttempts = trials.count { it.countsAsAttempt })
    }

    private fun meetSession(sound: SoundDef, random: SeededRandom): Session {
        val with = random.shuffled(hearQuestions(sound).filter { it is Exercise.Hear && it.has }).take(3)
        val without = random.shuffled(hearQuestions(sound).filter { it is Exercise.Hear && !it.has }).take(3)
        val questions = random.shuffled(with + without)
        val opening = Trial(exercise = Exercise.Intro(sound = sound.id), feedback = FeedbackLevel.Teach, block = SessionBlock(SessionBlock.Kind.Opening))
        val trials = questions.map { Trial(exercise = it, feedback = FeedbackLevel.Quick, block = SessionBlock(SessionBlock.Kind.Listening)) }
        return Session(trials = listOf(opening) + trials, targetAttempts = 0)
    }

    private fun dictionLesson(name: DictionLesson, request: SessionRequest): Session {
        val diction = content.diction
        val norm = diction.phonationNormSeconds.seconds(request.age)
        // Swift's min(x, y) is `y < x ? y : x`.
        val hold = { target: SustainTarget, seconds: Double ->
            Exercise.Sustain(target = target, seconds = if (norm < seconds) norm else seconds, role = SustainRole.Practice)
        }
        return when (name) {
            DictionLesson.Candles -> lesson(listOf(Exercise.Warmup(id = "lumanarea"), hold(SustainTarget.Breath, 5.0)), feedback = FeedbackLevel.Teach)
            DictionLesson.LongSound -> lesson(
                listOf(hold(SustainTarget.Sound(SoundID.S), 8.0), hold(SustainTarget.Sound(SoundID.S), 12.0), hold(SustainTarget.Sound(SoundID.Z), 10.0)),
                feedback = FeedbackLevel.Teach,
            )
            DictionLesson.Warmup -> lesson(listOf("zambet-pupic", "hipopotam", "ceasul", "tic-tac").map { Exercise.Warmup(id = it) }, feedback = FeedbackLevel.Silent)
            DictionLesson.FinalWords -> lesson(
                diction.finalIWords.take(8).map { Exercise.Repeat(RepeatPrompt(text = it, language = Language.Ro, level = RepeatPrompt.Level.Word)) },
                feedback = FeedbackLevel.Quick,
            )
            DictionLesson.Twisters -> lesson(
                content.sounds.flatMap { it.twisters }.filter { it.folk }.map { Exercise.Twister(text = it.text, language = Language.Ro, sound = null) },
                feedback = FeedbackLevel.Quick,
            )
            DictionLesson.VolumeLadder -> lesson(listOf(Exercise.Volume, Exercise.Volume), feedback = FeedbackLevel.Teach)
            DictionLesson.Glide -> lesson(listOf(Exercise.Glide, Exercise.Glide), feedback = FeedbackLevel.Teach)
            DictionLesson.StatementOrQuestion -> lesson(
                diction.intonationPairs.flatMap { pair ->
                    listOf(
                        Exercise.Intonation(statement = pair.statement, question = pair.question, ask = IntonationAsk.Question),
                        Exercise.Intonation(statement = pair.statement, question = pair.question, ask = IntonationAsk.Statement),
                    )
                },
                feedback = FeedbackLevel.Quick,
            )
            DictionLesson.Emotion -> lesson(diction.expressionLines.map { Exercise.Expression(text = it.text, cue = it.cue) }, feedback = FeedbackLevel.Quick)
            DictionLesson.Pace -> lesson(
                diction.paceTexts.map { Exercise.Pace(text = it.text, targetSyllablesPerSecond = it.targetSyllablesPerSecond) },
                feedback = FeedbackLevel.Quick,
            )
            DictionLesson.Pauses -> lesson(diction.pauseTexts.map { Exercise.Pauses(text = it.text, spoken = it.spoken) }, feedback = FeedbackLevel.Quick)
            DictionLesson.Shadow -> lesson(diction.paceTexts.map { Exercise.Shadow(text = it.text, language = Language.Ro) }, feedback = FeedbackLevel.Quick)
        }
    }

    // Romanian sounds

    private data class Target(val exercises: List<Exercise>, val stage: Stage)

    private fun soundSession(sound: SoundDef, rung: Rung, request: SessionRequest, random: SeededRandom): Session {
        val attempts = attemptTarget(request)
        val (primary, secondary) = targets(rung, sound, request)
        val deckA = Deck(primary.exercises, request.history.recentItems, random)
        val deckB = Deck(secondary.exercises, request.history.recentItems, random)

        val blocks = (attempts - mixedTrials) / Progression.blockSize
        val perception = if (rung == Rung.Pairs) pairPerception(sound) else emptyList()
        val perceptionDeck = Deck(perception, emptySet(), random)
        val blocksA = mutableListOf<Trial>()
        for (index in 0 until (blocks + 1) / 2) {
            repeat(minOf(perceptionPerPairBlock, perception.size)) {
                blocksA += Trial(exercise = perceptionDeck.draw(random), feedback = FeedbackLevel.Quick, block = SessionBlock(SessionBlock.Kind.TargetA, index))
            }
            blocksA += block(index, SessionBlock.Kind.TargetA, primary.stage, deckA, random)
        }
        val blocksB = mutableListOf<Trial>()
        for (index in 0 until blocks / 2) {
            blocksB += block(index, SessionBlock.Kind.TargetB, secondary.stage, deckB, random)
        }

        var extras: List<Exercise> = emptyList()
        if (request.age.getsTwisters && rung >= Rung.Sentences) {
            extras = sound.twisters.map { Exercise.Twister(text = it.text, language = Language.Ro, sound = sound.id) }
        }

        val trials = mutableListOf<Trial>()
        trials += opening(sound, rung, request, random)
        trials += listeningWarmup(sound, request.age, random)
        trials += if (request.history.sessionsCompleted % 2 == 0) blocksA + blocksB else blocksB + blocksA
        // The last mixed item gives its place to an easy one, so the dose stays the same and the session ends on
        // something the learner can already do. The mixed block is drawn whole first, so the items before it come out
        // of the random stream exactly as they always have.
        val mixed = mixedBlock(mixedTrials, deckA, deckB, extras = extras, age = request.age, delaysVerdicts = true, random = random)
        trials += mixed.dropLast(1)
        val mission = if (rung == Rung.Everyday) random.shuffled(sound.missions).firstOrNull() else null
        val easy = closingPool(sound, rung, request)
        if (easy.isNotEmpty()) {
            val exercise = easy[random.below(easy.size)]
            val feedback = if (isStillBeingSetUp(exercise)) FeedbackLevel.Teach else FeedbackLevel.Quick
            trials += Trial(exercise = exercise, feedback = feedback, block = SessionBlock(SessionBlock.Kind.Closing))
        } else {
            mixed.lastOrNull()?.let { trials += it }
        }
        if (mission != null) {
            trials += Trial(exercise = Exercise.Mission(mission), feedback = FeedbackLevel.Silent, block = SessionBlock(SessionBlock.Kind.Mission))
        }
        // Drawn last, so the items of every session come out of the random stream as they always have.
        if (rung.stage.isAutomatizare) {
            return Session(trials = FeedbackSchedule.automatizare(trials, random.next()), targetAttempts = attempts)
        }
        return Session(trials = trials, targetAttempts = attempts)
    }

    /**
     * Easy material for the last item: a step the learner has already passed, or the isolated sound itself when there
     * is none below. Words come as repeats with their picture, so nothing has to be recalled.
     */
    private fun closingPool(sound: SoundDef, rung: Rung, request: SessionRequest): List<Exercise> = when (rung) {
        Rung.Isolated, Rung.Syllables -> sound.isolatedPractice
        Rung.WordsInitial -> sound.syllables.direct.map {
            Exercise.Repeat(RepeatPrompt(text = it, language = Language.Ro, sound = sound.id, level = RepeatPrompt.Level.Syllable))
        }
        else -> simpleWords(sound, WordLists::initial, request).map {
            Exercise.Repeat(RepeatPrompt(text = it.text, language = Language.Ro, sound = sound.id, picture = it.picture, level = RepeatPrompt.Level.Word))
        }
    }

    private fun targets(rung: Rung, sound: SoundDef, request: SessionRequest): Pair<Target, Target> {
        val id = sound.id
        val syllableRepeat = { syllables: List<String> ->
            syllables.map { Exercise.Repeat(RepeatPrompt(text = it, language = Language.Ro, sound = id, level = RepeatPrompt.Level.Syllable)) }
        }
        val wordsAt = { list: (WordLists) -> List<WordItem> ->
            simpleWords(sound, list, request).map { exercise(it, id) }
        }
        val clusterWords = sound.words(WordLists::clusters, avoiding = request.avoidingSounds).map { exercise(it, id) }
        val sentences: List<Exercise> = sound.sentences.map {
            Exercise.Repeat(RepeatPrompt(text = it, language = Language.Ro, sound = id, level = RepeatPrompt.Level.Sentence))
        }
        val allSyllables = sound.syllables.direct + sound.syllables.inverse + sound.syllables.between
        val retell = sound.retell.map { Exercise.Retell(it) }
        val describe = sound.describe.map { Exercise.Describe(it) }

        return when (rung) {
            Rung.Isolated ->
                Target(sound.isolatedPractice, Stage.Establish) to Target(syllableRepeat(sound.syllables.direct.take(4)), Stage.Establish)
            Rung.Syllables ->
                Target(syllableRepeat(sound.syllables.direct), Stage.ConsolidateEarly) to
                    Target(syllableRepeat(sound.syllables.inverse + sound.syllables.between), Stage.ConsolidateEarly)
            Rung.WordsInitial ->
                Target(wordsAt(WordLists::initial), Stage.ConsolidateEarly) to Target(syllableRepeat(allSyllables), Stage.ConsolidateEarly)
            Rung.WordsMedial ->
                Target(wordsAt(WordLists::medial), Stage.ConsolidateEarly) to Target(wordsAt(WordLists::initial), Stage.ConsolidateEarly)
            Rung.WordsFinal ->
                Target(wordsAt(WordLists::final), Stage.ConsolidateEarly) to Target(wordsAt(WordLists::medial), Stage.ConsolidateEarly)
            Rung.Clusters -> {
                // Interference filter: long words that hold a sound the child is avoiding stay out.
                val longWords = sound.words.positional
                    .filter { word -> isLong(word.text) && word.otherSounds.none { it in request.avoidingSounds } }
                    .map { exercise(it, id) }
                Target(clusterWords + longWords, Stage.ConsolidateLate) to Target(wordsAt(WordLists::final), Stage.ConsolidateEarly)
            }
            Rung.Pairs -> Target(pairProduction(sound), Stage.Differentiate) to Target(clusterWords, Stage.ConsolidateLate)
            Rung.Sentences -> Target(sentences, Stage.Automate) to Target(pairProduction(sound), Stage.Differentiate)
            Rung.Retell -> Target(retell + describe + sentences, Stage.Automate) to Target(sentences, Stage.Automate)
            Rung.Everyday -> Target(describe + retell + sentences, Stage.Generalise) to Target(sentences, Stage.Automate)
        }
    }

    private fun opening(sound: SoundDef, rung: Rung, request: SessionRequest, random: SeededRandom): List<Trial> {
        val block = SessionBlock(SessionBlock.Kind.Opening)
        val trials = mutableListOf<Trial>()
        if (request.history.sessionsCompleted == 0) {
            trials += Trial(exercise = Exercise.Intro(sound = sound.id), feedback = FeedbackLevel.Teach, block = block)
        }
        if (request.includesOralWarmup) {
            trials += random.shuffled(sound.warmups).take(2).map {
                Trial(exercise = Exercise.Warmup(id = it), feedback = FeedbackLevel.Silent, block = block)
            }
        }
        if (rung <= Rung.Syllables) {
            trials += Trial(exercise = Exercise.How(HowTarget.Sound(sound.id)), feedback = FeedbackLevel.Teach, block = block)
        }
        val facilitation = sound.facilitation
        if (rung == Rung.Isolated && facilitation != null) {
            val helper = Exercise.Sustain(target = SustainTarget.Sound(sound.id), seconds = facilitation.seconds, role = SustainRole.Facilitation)
            trials += Trial(exercise = helper, feedback = FeedbackLevel.Teach, block = block)
        }
        return trials
    }

    /** Target-versus-error identification first, with a few "does it hold the sound" questions. */
    private fun listeningWarmup(sound: SoundDef, age: AgeBand, random: SeededRandom): List<Trial> {
        val hearCount = age.listeningTrials / 3
        val identification = Deck(soundIdentification(sound), emptySet(), random)
        val hearing = Deck(hearQuestions(sound), emptySet(), random)
        val chosen = MutableList(age.listeningTrials - hearCount) { identification.draw(random) }
        repeat(hearCount) { chosen += hearing.draw(random) }
        return random.shuffled(chosen).map {
            Trial(exercise = it, feedback = FeedbackLevel.Quick, block = SessionBlock(SessionBlock.Kind.Listening))
        }
    }

    /** Only recorded words: listening to synthesised speech could teach the wrong sound. */
    private fun soundIdentification(sound: SoundDef): List<Exercise> {
        val result = mutableListOf<Exercise>()
        for (contrast in sound.contrasts) {
            val other = content.sound(contrast.with)
            val mine = WordItem(text = sound.hero.says, picture = sound.hero.picture)
            val theirs = WordItem(text = other.hero.says, picture = other.hero.picture)
            result += bothWays(mine, theirs)
            for (pair in contrast.pairs) {
                if (pair.isRecorded) result += bothWays(pair.a, pair.b)
            }
        }
        return result
    }

    private fun hearQuestions(sound: SoundDef): List<Exercise> {
        val with = sound.words.positional.filter { it.recorded }.map {
            Exercise.Hear(sound = sound.id, item = it, has = true)
        }
        // Interference filter: a word that holds the sound cannot be a "no" answer.
        val without = content.sounds
            .filter { it.id != sound.id }
            .flatMap { it.words.positional }
            .filter { it.recorded && !it.otherSounds.contains(sound.id) }
            .map { Exercise.Hear(sound = sound.id, item = it, has = false) }
        return with + without
    }

    private fun pairPerception(sound: SoundDef): List<Exercise> {
        val result = soundIdentification(sound).toMutableList()
        for (contrast in sound.contrasts) {
            val other = content.sound(contrast.with)
            // Interference filters: an own word with the rival sound, or a rival word with the own sound, would sort both ways.
            result += sound.words.positional
                .filter { it.recorded && !it.otherSounds.contains(contrast.with) }
                .map { Exercise.Sort(a = sound.id, b = contrast.with, item = it, answer = SortSide.A) }
            result += other.words.positional
                .filter { it.recorded && !it.otherSounds.contains(sound.id) }
                .map { Exercise.Sort(a = sound.id, b = contrast.with, item = it, answer = SortSide.B) }
        }
        return result
    }

    private fun pairProduction(sound: SoundDef): List<Exercise> =
        sound.contrasts.flatMap { it.pairs }.flatMap { pair ->
            val result = mutableListOf<Exercise>()
            for ((word, rival) in listOf(pair.a to pair.b, pair.b to pair.a)) {
                result += Exercise.Repeat(
                    RepeatPrompt(text = word.text, language = Language.Ro, sound = sound.id, picture = word.picture, level = RepeatPrompt.Level.Word, rival = rival.text),
                )
            }
            pair.spoken?.let { spoken ->
                result += Exercise.Repeat(RepeatPrompt(text = spoken, language = Language.Ro, sound = sound.id, level = RepeatPrompt.Level.Word))
            }
            result
        }

    private fun voicingSession(random: SeededRandom): Session {
        val pairs = random.shuffled(content.listening.voicingPairs)
        val listening = pairs.map { pair ->
            val (answer, other) = if (random.below(2) == 0) pair.a to pair.b else pair.b to pair.a
            Trial(
                exercise = Exercise.Choose(ChoosePrompt(answer = answer, other = other, language = Language.Ro)),
                feedback = FeedbackLevel.Quick,
                block = SessionBlock(SessionBlock.Kind.Listening),
            )
        }
        val speaking = random.shuffled(pairs).mapNotNull { pair ->
            pair.spoken?.let { Exercise.Repeat(RepeatPrompt(text = it, language = Language.Ro, level = RepeatPrompt.Level.Word)) }
        }
        val levels = FeedbackSchedule.levels(stage = Stage.Differentiate, blockIndex = 0, seed = random.next())
        val trials = speaking.zip(levels).map { (exercise, level) ->
            Trial(exercise = exercise, feedback = level, block = SessionBlock(SessionBlock.Kind.TargetA))
        }
        return Session(trials = listening + trials, targetAttempts = trials.size)
    }

    // English

    private fun drillSession(id: String, kind: SessionBlock.Kind, random: SeededRandom): Session {
        val contrast = content.english.contrast(id) ?: return Session(trials = emptyList(), targetAttempts = 0)
        val pairs = if (kind == SessionBlock.Kind.Retest) contrast.retestPairs else contrast.trainingPairs
        val feedback = if (kind == SessionBlock.Kind.Retest) FeedbackLevel.Silent else FeedbackLevel.Quick
        val exercises: List<Exercise> = if (contrast.kind == EnglishContrast.Kind.Stress) {
            pairs.map { Exercise.Stress(word = it.a, wrong = it.b) }
        } else {
            pairs.flatMap { pair ->
                Voice.english.flatMap { voice ->
                    bothWays(WordItem(text = pair.a), WordItem(text = pair.b), language = Language.En, voice = voice, cue = contrast.cue)
                }
            }
        }
        val trials = withoutImmediateRepeats(random.shuffled(exercises)).map {
            Trial(exercise = it, feedback = feedback, block = SessionBlock(kind))
        }
        return Session(trials = trials, targetAttempts = trials.size)
    }

    private fun speakingSession(id: String, request: SessionRequest, random: SeededRandom): Session {
        val contrast = content.english.contrast(id)
        if (contrast == null || contrast.needsRecording) return Session(trials = emptyList(), targetAttempts = 0)
        val words = englishWordPractice(contrast)
        val sentences: List<Exercise> = contrast.sentences.map {
            Exercise.Repeat(RepeatPrompt(text = it, language = Language.En, level = RepeatPrompt.Level.Sentence, englishContrast = contrast.id))
        }
        val attempts = attemptTarget(request)
        val blocks = (attempts - mixedTrials) / Progression.blockSize
        val deckA = Deck(words, request.history.recentItems, random)
        // The first time round, each recorded sentence is said together with the voice, then alone: the shadowing comes
        // before the solo take. Later rounds are solo only, so the session keeps its length.
        val shadowLead = { exercise: Exercise ->
            if (exercise is Exercise.Repeat && recordedEnglish?.invoke(exercise.prompt.text) == true) {
                Exercise.Shadow(text = exercise.prompt.text, language = Language.En)
            } else {
                null
            }
        }
        val deckB = if (sentences.isEmpty()) {
            Deck(words, request.history.recentItems, random)
        } else {
            Deck(sentences, request.history.recentItems, random, firstRoundLead = shadowLead)
        }

        val blocksA = mutableListOf<Trial>()
        for (index in 0 until (blocks + 1) / 2) {
            blocksA += block(index, SessionBlock.Kind.TargetA, Stage.Differentiate, deckA, random)
        }
        val blocksB = mutableListOf<Trial>()
        for (index in 0 until blocks / 2) {
            blocksB += block(index, SessionBlock.Kind.TargetB, Stage.Automate, deckB, random)
        }

        val trials = mutableListOf<Trial>()
        if (request.history.sessionsCompleted == 0) {
            trials += Trial(exercise = Exercise.How(HowTarget.English(contrast.id)), feedback = FeedbackLevel.Teach, block = SessionBlock(SessionBlock.Kind.Opening))
        }
        trials += englishListening(contrast, request.age, random)
        trials += if (request.history.sessionsCompleted % 2 == 0) blocksA + blocksB else blocksB + blocksA
        val extras: List<Exercise> = if (request.age.getsTwisters) {
            content.english.twisters.map { Exercise.Twister(text = it, language = Language.En, sound = null) }
        } else {
            emptyList()
        }
        trials += mixedBlock(mixedTrials, deckA, deckB, extras = extras, age = request.age, random = random)
        return Session(trials = trials, targetAttempts = attempts)
    }

    /** Retest words stay out of speaking practice too, so they are new when the retest comes. */
    private fun englishWordPractice(contrast: EnglishContrast): List<Exercise> {
        val practice = { word: String, rival: String? ->
            Exercise.Repeat(
                RepeatPrompt(text = word, language = Language.En, level = RepeatPrompt.Level.Word, cue = contrast.cue, englishContrast = contrast.id, rival = rival),
            )
        }
        return when (contrast.kind) {
            EnglishContrast.Kind.Stress -> contrast.trainingPairs.map { practice(it.a, null) }
            EnglishContrast.Kind.ReducedVowel -> (contrast.words ?: emptyList()).map { practice(it, null) }
            EnglishContrast.Kind.MinimalPairs -> contrast.trainingPairs.flatMap { listOf(practice(it.a, it.b), practice(it.b, it.a)) }
        }
    }

    private fun englishListening(contrast: EnglishContrast, age: AgeBand, random: SeededRandom): List<Trial> {
        val pool: List<Exercise> = when (contrast.kind) {
            EnglishContrast.Kind.Stress -> contrast.trainingPairs.map { Exercise.Stress(word = it.a, wrong = it.b) }
            EnglishContrast.Kind.ReducedVowel -> emptyList()
            EnglishContrast.Kind.MinimalPairs -> contrast.trainingPairs.flatMap { pair ->
                Voice.english.flatMap {
                    bothWays(WordItem(text = pair.a), WordItem(text = pair.b), language = Language.En, voice = it, cue = contrast.cue)
                }
            }
        }
        if (pool.isEmpty()) return emptyList()
        val deck = Deck(pool, emptySet(), random)
        return List(age.listeningTrials) {
            Trial(exercise = deck.draw(random), feedback = FeedbackLevel.Quick, block = SessionBlock(SessionBlock.Kind.Listening))
        }
    }

    private fun melodySession(lesson: Int, random: SeededRandom): Session {
        val lessons = content.english.melody.lessons
        if (lesson !in lessons.indices) return Session(trials = emptyList(), targetAttempts = 0)
        val shadow = { melodyLesson: MelodyLesson -> melodyLesson.lines.map { Exercise.Shadow(text = it, language = Language.En) } }
        val others = lessons.indices.filter { it != lesson }.flatMap { shadow(lessons[it]) }
        return shortSession(
            primary = shadow(lessons[lesson]),
            secondary = others,
            extras = emptyList(),
            opening = emptyList(),
            random = random,
        )
    }

    // Diction

    private fun dictionSession(area: DictionAreaID, request: SessionRequest, random: SeededRandom): Session {
        val diction = content.diction
        val norm = diction.phonationNormSeconds.seconds(request.age)
        // Swift's min(x, y) is `y < x ? y : x`.
        val hold = { target: SustainTarget, seconds: Double ->
            Exercise.Sustain(target = target, seconds = if (norm < seconds) norm else seconds, role = SustainRole.Practice)
        }
        val primary: List<Exercise>
        val secondary: List<Exercise>
        var extras: List<Exercise> = emptyList()
        var warmups: List<String> = emptyList()
        when (area) {
            DictionAreaID.Breathing -> {
                primary = listOf(hold(SustainTarget.Breath, 5.0), hold(SustainTarget.Sound(SoundID.S), 8.0))
                secondary = listOf(hold(SustainTarget.Sound(SoundID.S), 12.0), hold(SustainTarget.Sound(SoundID.Z), 10.0))
                warmups = listOf("lumanarea")
            }
            DictionAreaID.Articulation -> {
                primary = diction.finalIWords.map { Exercise.Repeat(RepeatPrompt(text = it, language = Language.Ro, level = RepeatPrompt.Level.Word)) }
                secondary = content.sounds.flatMap { it.twisters }.filter { it.folk }.map { Exercise.Twister(text = it.text, language = Language.Ro, sound = null) }
                warmups = listOf("zambet-pupic", "hipopotam", "ceasul", "tic-tac")
            }
            DictionAreaID.Volume -> {
                primary = listOf(Exercise.Volume)
                secondary = listOf(Exercise.Volume)
            }
            DictionAreaID.Pitch -> {
                primary = diction.intonationPairs.flatMap { pair ->
                    listOf(
                        Exercise.Intonation(statement = pair.statement, question = pair.question, ask = IntonationAsk.Question),
                        Exercise.Intonation(statement = pair.statement, question = pair.question, ask = IntonationAsk.Statement),
                    )
                }
                secondary = diction.expressionLines.map { Exercise.Expression(text = it.text, cue = it.cue) } + Exercise.Glide
            }
            DictionAreaID.Rhythm -> {
                primary = diction.paceTexts.map { Exercise.Pace(text = it.text, targetSyllablesPerSecond = it.targetSyllablesPerSecond) }
                secondary = diction.pauseTexts.map { Exercise.Pauses(text = it.text, spoken = it.spoken) }
                extras = diction.paceTexts.map { Exercise.Shadow(text = it.text, language = Language.Ro) }
            }
        }
        // Drawn before shortSession builds its decks, as on iOS.
        val opening: List<Trial> = if (request.includesOralWarmup) {
            random.shuffled(warmups).take(2).map {
                Trial(exercise = Exercise.Warmup(id = it), feedback = FeedbackLevel.Silent, block = SessionBlock(SessionBlock.Kind.Opening))
            }
        } else {
            emptyList()
        }
        return shortSession(primary = primary, secondary = secondary, extras = extras, opening = opening, random = random)
    }

    private fun shortSession(
        primary: List<Exercise>,
        secondary: List<Exercise>,
        extras: List<Exercise>,
        opening: List<Trial>,
        random: SeededRandom,
    ): Session {
        val count = shortSessionTrialsPerPart
        val deckA = Deck(primary, emptySet(), random)
        val deckB = Deck(secondary, emptySet(), random)
        val part = { deck: Deck, kind: SessionBlock.Kind ->
            val levels = FeedbackSchedule.levels(stage = Stage.ConsolidateLate, blockIndex = 0, seed = random.next())
            levels.take(count).map { level ->
                Trial(exercise = deck.draw(random), feedback = level, block = SessionBlock(kind))
            }
        }
        val trials = opening.toMutableList()
        trials += part(deckA, SessionBlock.Kind.TargetA)
        trials += part(deckB, SessionBlock.Kind.TargetB)
        trials += mixedBlock(count, deckA, deckB, extras = extras, age = AgeBand.TeenAdult, usesProsody = false, random = random)
        return Session(trials = trials, targetAttempts = count * 3)
    }

    // Shared parts

    private fun block(index: Int, kind: SessionBlock.Kind, stage: Stage, deck: Deck, random: SeededRandom): List<Trial> =
        FeedbackSchedule.levels(stage = stage, blockIndex = index, seed = random.next()).map { level ->
            Trial(exercise = deck.draw(random), feedback = level, block = SessionBlock(kind, index))
        }

    /**
     * Random across both targets, with a response on every other attempt. Syllable-level items and held sounds always
     * get the live visual, because they are still being set up.
     *
     * With [delaysVerdicts], the takes that would answer at once say nothing in the moment either: the learner hears
     * only the soft "heard you", and the block ends by playing back a few of their own good takes (`SessionRun`).
     * Speech Motor Chaining delays knowledge of results in its random practice (Preston et al. 2019). The evidence on
     * how often to give it is mixed (Maas et al. 2012), so the change is cheap to undo. Items come out of the random
     * stream as they always have.
     */
    private fun mixedBlock(
        count: Int,
        deckA: Deck,
        deckB: Deck,
        extras: List<Exercise>,
        age: AgeBand,
        usesProsody: Boolean = true,
        delaysVerdicts: Boolean = false,
        random: SeededRandom,
    ): List<Trial> {
        val extraDeck = Deck(extras, emptySet(), random)
        return List(count) { index ->
            val exercise = when (random.below(if (extras.isEmpty()) 2 else 3)) {
                0 -> deckA.draw(random)
                1 -> deckB.draw(random)
                else -> extraDeck.draw(random)
            }
            val answers = !delaysVerdicts && index % 2 == 0
            val feedback = when {
                isStillBeingSetUp(exercise) -> FeedbackLevel.Teach
                answers -> FeedbackLevel.Quick
                else -> FeedbackLevel.Silent
            }
            // Only drawn when both hold, as Swift's && short-circuits.
            val prosody = if (usesProsody && takesProsody(exercise)) age.prosodies[random.below(age.prosodies.size)] else null
            Trial(exercise = exercise, feedback = feedback, block = SessionBlock(SessionBlock.Kind.Mixed), prosody = prosody)
        }
    }

    internal companion object {
        /** About two minutes at six attempts a minute. */
        val mixedTrials = 12

        /** Diction and melody exercises are longer than a word, so their sessions hold fewer. */
        val shortSessionTrialsPerPart = 4
        val perceptionPerPairBlock = 3

        /**
         * Sessions grow with practice and stay grown: more tries in a session help, so a busy week doesn't shorten
         * them (Edeal & Gildersleeve-Neumann 2011). The ten-minute limit still ends a session.
         */
        private fun attemptTarget(request: SessionRequest): Int {
            val steps = request.age.attemptSteps
            return steps[minOf(request.history.sessionsCompleted, steps.size - 1)]
        }

        private fun exercise(word: WordItem, sound: SoundID): Exercise {
            if (word.namable && word.picture != null) {
                return Exercise.Name(sound = sound, item = word)
            }
            return Exercise.Repeat(RepeatPrompt(text = word.text, language = Language.Ro, sound = sound, picture = word.picture, level = RepeatPrompt.Level.Word))
        }

        /** Words at a position for this child. Cluster-free words come first when there are enough. */
        private fun simpleWords(sound: SoundDef, list: (WordLists) -> List<WordItem>, request: SessionRequest): List<WordItem> {
            val kept = sound.words(list, avoiding = request.avoidingSounds)
            val clean = kept.filter { it.clusters.isEmpty() }
            return if (clean.size >= 3) clean else kept
        }

        private val vowels: Set<String> = "aăâeiîou".characters().map(::nfc).toSet()

        /** At least three groups of vowels, counted over Characters as Swift does. */
        private fun isLong(word: String): Boolean {
            var groups = 0
            var inVowels = false
            for (letter in word.characters()) {
                val vowel = nfc(letter) in vowels
                if (vowel && !inVowels) groups += 1
                inVowels = vowel
            }
            return groups >= 3
        }

        private fun bothWays(
            first: WordItem,
            second: WordItem,
            language: Language = Language.Ro,
            voice: Voice? = null,
            cue: String? = null,
        ): List<Exercise> = listOf(
            Exercise.Choose(ChoosePrompt(answer = first, other = second, language = language, voice = voice, cue = cue)),
            Exercise.Choose(ChoosePrompt(answer = second, other = first, language = language, voice = voice, cue = cue)),
        )

        private fun isStillBeingSetUp(exercise: Exercise): Boolean = when (exercise) {
            is Exercise.Sustain -> true
            is Exercise.Repeat -> exercise.prompt.level == RepeatPrompt.Level.Syllable
            else -> false
        }

        private fun takesProsody(exercise: Exercise): Boolean = when (exercise) {
            is Exercise.Repeat, is Exercise.Name, is Exercise.Twister -> true
            else -> false
        }

        /** Moves items so the same word does not come twice in a row, when the list allows it. */
        private fun withoutImmediateRepeats(exercises: List<Exercise>): List<Exercise> {
            val result = exercises.toMutableList()
            if (result.size <= 2) return result
            for (index in 1 until result.size) {
                if (result[index].itemText != result[index - 1].itemText) continue
                val swap = (index + 1 until result.size).firstOrNull { result[it].itemText != result[index - 1].itemText }
                if (swap != null) {
                    val held = result[index]
                    result[index] = result[swap]
                    result[swap] = held
                }
            }
            return result
        }

        private fun sessionSeed(request: SessionRequest): ULong {
            // UInt64(truncatingIfNeeded:) of the sign-extended count, then a wrapping multiply.
            val completed = request.history.sessionsCompleted.toLong().toULong() * 0x9E37_79B9_7F4A_7C15uL
            return request.seed xor completed xor SeededRandom.hash(focusKey(request.focus))
        }

        private fun focusKey(focus: SessionFocus): String = when (focus) {
            is SessionFocus.Sound -> "sound|${focus.id.rawValue}|${focus.rung.rawValue}"
            is SessionFocus.Meet -> "meet|${focus.id.rawValue}"
            is SessionFocus.Warmups -> "warmups|${focus.ids.joinToString(",")}"
            is SessionFocus.SyllablesBack -> "back|${focus.id.rawValue}"
            is SessionFocus.Story -> "story|${focus.id.rawValue}"
            SessionFocus.Voicing -> "voicing"
            SessionFocus.VoicingSay -> "voicing-say"
            is SessionFocus.English -> when (val english = focus.focus) {
                is EnglishFocus.Sentences -> "sentences|${english.contrast}"
                is EnglishFocus.Drill -> "drill|${english.contrast}"
                is EnglishFocus.Retest -> "retest|${english.contrast}"
                is EnglishFocus.Speaking -> "speaking|${english.contrast}"
                is EnglishFocus.Melody -> "melody|${english.lesson}"
            }
            is SessionFocus.DictionLesson -> "lesson|${focus.lesson.rawValue}"
            is SessionFocus.Diction -> "diction|${focus.area.rawValue}"
        }
    }
}

/**
 * Draws items in shuffled rounds, starting with those not used last session, and avoiding the same item twice in a
 * row. A deck with no items fails on its first draw, where Swift's `removeFirst()` traps.
 */
/**
 * `firstRoundLead` puts an exercise before an item, in the first round only. It uses no random numbers, so a deck with
 * no lead draws exactly what it drew before.
 */
private class Deck(
    private val items: List<Exercise>,
    recent: Set<String>,
    random: SeededRandom,
    firstRoundLead: ((Exercise) -> Exercise?)? = null,
) {
    private var queue: MutableList<Exercise>
    private var last: Exercise? = null

    init {
        // Swift's Set<String> matches canonically equivalent text, so the comparison runs on NFC.
        val recentText = recent.mapTo(HashSet()) { nfc(it) }
        val isRecent = { exercise: Exercise -> exercise.itemText?.let { nfc(it) in recentText } ?: false }
        val fresh = random.shuffled(items.filter { !isRecent(it) })
        val used = random.shuffled(items.filter(isRecent))
        val first = fresh + used
        queue = if (firstRoundLead == null) {
            first.toMutableList()
        } else {
            first.flatMap { item -> firstRoundLead(item)?.let { listOf(it, item) } ?: listOf(item) }.toMutableList()
        }
    }

    fun draw(random: SeededRandom): Exercise {
        if (queue.isEmpty()) {
            queue = random.shuffled(items).toMutableList()
            if (queue.size > 1 && queue[0] == last) {
                val held = queue[0]
                queue[0] = queue[1]
                queue[1] = held
            }
        }
        val next = queue.removeAt(0)
        last = next
        return next
    }
}
