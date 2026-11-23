package tech.fonia.domain

/** One lesson: a name a person can read, and the session behind it. */
data class Lesson(
    val id: String,
    val title: String,
    val detail: String,
    val focus: SessionFocus,
    /** The picture that marks it in a list. */
    val art: String,
    /** Set when the lesson stands on the sound's ladder, so its state comes from the learner's rung. */
    val rung: Rung? = null,
)

/** A group of lessons on one thing: a sound, an English contrast, a part of diction. */
data class CourseUnit(
    val id: String,
    val title: String,
    val subtitle: String,
    val track: Track,
    val sound: SoundID? = null,
    /** A few letters to stand for the unit, drawn in the practice font. */
    val mark: String,
    /** The picture that marks it in a list. Without one, the mark stands alone. */
    val art: String? = null,
    val lessons: List<Lesson>,
)

/**
 * The whole course: warm-up, sounds, listening and stories; English; diction. Everything a learner can open is listed
 * here, in the order it makes sense to do it.
 */
class Catalog(private val content: FoniaContent) {
    /** Sound units in the given order, with the warm-up first and listening after them. */
    fun soundUnits(order: List<SoundID>): List<CourseUnit> =
        listOf(warmup) + order.map { unit(content.sound(it)) } + listOf(voicing, stories)

    /** Swift's `unit(for:)`. */
    fun unit(sound: SoundDef): CourseUnit {
        val id = sound.id
        // Swift's split(separator:) cuts between Characters and drops empty pieces; count is in Characters too.
        val letter = sound.letter.splitCharacters("/").firstOrNull() ?: sound.letter
        val rival = sound.contrasts.firstOrNull()?.let { content.sound(it.with).letter.splitCharacters("/").firstOrNull() ?: "" }
        val onLadder = { rung: Rung, title: String, detail: String, art: String ->
            Lesson(id = "${id.rawValue}-${rung.rawValue}", title = title, detail = detail, focus = SessionFocus.Sound(id, rung), art = art, rung = rung)
        }
        return CourseUnit(
            id = "sound-${id.rawValue}",
            title = if (sound.letter.characterCount > 2) "Sunetele ${sound.letter}" else "Sunetul ${sound.letter}",
            subtitle = sound.hero.name,
            track = Track.Sounds,
            sound = id,
            mark = letter,
            art = sound.hero.picture,
            lessons = listOf(
                Lesson(id = "${id.rawValue}-meet", title = "Facem cunoștință", detail = "O poveste scurtă și câteva cuvinte", focus = SessionFocus.Meet(id), art = "art-bubble"),
                Lesson(id = "${id.rawValue}-warmup", title = "Încălzirea gurii", detail = "Mișcăm limba și buzele", focus = SessionFocus.Warmups(sound.warmups, breath = false), art = "art-tongue"),
                onLadder(Rung.Isolated, "Doar sunetul", "Spunem $letter, nimic altceva", "art-speaker"),
                onLadder(Rung.Syllables, "Silabe", "Silabe care încep cu $letter", "art-letters-small"),
                Lesson(id = "${id.rawValue}-back", title = "Silabe pe dos", detail = "$letter la sfârșit și la mijloc", focus = SessionFocus.SyllablesBack(id), art = "art-letters"),
                onLadder(Rung.WordsInitial, "La începutul cuvântului", "Cuvinte care încep cu $letter", "art-start"),
                onLadder(Rung.WordsMedial, "În mijlocul cuvântului", "Cuvinte cu $letter la mijloc", "art-puzzle"),
                onLadder(Rung.WordsFinal, "La sfârșitul cuvântului", "Cuvinte care se termină cu $letter", "art-finish"),
                onLadder(Rung.Clusters, "Cuvinte mai grele", "Cuvinte lungi, mai greu de spus", "art-weights"),
                onLadder(Rung.Pairs, rival?.let { "$letter sau $it?" } ?: "Cuvinte care seamănă", "Diferă doar printr-un sunet", "art-scale"),
                onLadder(Rung.Sentences, "Propoziții și frământări de limbă", "Propoziții întregi cu $letter", "art-head"),
                onLadder(Rung.Retell, "Povești", "Povestești ce vezi", "art-book"),
                onLadder(Rung.Everyday, "Acasă și afară", "$letter în ce vorbești peste zi", "art-house"),
            ),
        )
    }

    val warmup: CourseUnit
        get() = CourseUnit(
            id = "warmup",
            title = "Încălzirea",
            subtitle = "Respirația și gimnastica gurii",
            track = Track.Sounds,
            mark = "~",
            art = "art-balloon",
            lessons = listOf(
                Lesson(id = "warmup-breath", title = "Suflăm lumânările", detail = "Tragi aer și sufli lung", focus = SessionFocus.Warmups(listOf("lumanarea", "morisca"), breath = true), art = "candle"),
                Lesson(id = "warmup-lips", title = "Buze vesele", detail = "Zâmbet, pupic, balon", focus = SessionFocus.Warmups(listOf("zambet", "pupic", "zambet-pupic", "balon"), breath = false), art = "art-lips"),
                Lesson(id = "warmup-tongue", title = "Limba sprintenă", detail = "Limba sus, jos și în lături", focus = SessionFocus.Warmups(listOf("catelusul", "ceasul", "cameleon", "calul"), breath = false), art = "art-tongue"),
            ),
        )

    val voicing: CourseUnit
        get() = CourseUnit(
            id = "voicing",
            title = "Urechi de detectiv",
            subtitle = "Auzi diferența dintre P și B, T și D?",
            track = Track.Sounds,
            mark = "?",
            art = "art-mag",
            lessons = listOf(
                Lesson(id = "voicing-listen", title = "Ce ai auzit?", detail = "Alegi cuvântul pe care îl auzi", focus = SessionFocus.Voicing, art = "art-ear"),
                Lesson(id = "voicing-say", title = "Spune perechile", detail = "Două cuvinte, spuse clar", focus = SessionFocus.VoicingSay, art = "art-head"),
            ),
        )

    val stories: CourseUnit
        get() = CourseUnit(
            id = "stories",
            title = "Povești",
            subtitle = "Asculți și repeți",
            track = Track.Sounds,
            mark = "„",
            art = "art-book",
            lessons = content.sounds.map { sound ->
                Lesson(id = "story-${sound.id.rawValue}", title = sound.hero.name, detail = "Povestea sunetului ${sound.letter}", focus = SessionFocus.Story(sound.id), art = sound.hero.picture)
            },
        )

    /** What a child can do besides sounds, whatever sound they're on. */
    val playUnits: List<CourseUnit> get() = listOf(warmup, voicing, stories)

    val englishUnits: List<CourseUnit> get() = contrastUnits + listOf(melodyUnit)

    val contrastUnits: List<CourseUnit>
        get() = englishOrder(content.english.contrasts.filter { !it.needsRecording }).map { contrast ->
            val lessons = mutableListOf<Lesson>()
            if (contrast.kind != EnglishContrast.Kind.ReducedVowel) {
                lessons += Lesson(id = "en-${contrast.id}-listen", title = "Ascultă diferența", detail = "Patru voci, două cuvinte", focus = SessionFocus.English(EnglishFocus.Drill(contrast = contrast.id)), art = "art-headphones")
            }
            lessons += Lesson(id = "en-${contrast.id}-say", title = "Spune-o tu", detail = "Telefonul îți spune ce a auzit", focus = SessionFocus.English(EnglishFocus.Speaking(contrast = contrast.id)), art = "choice-adult")
            if (contrast.sentences.isNotEmpty()) {
                lessons += Lesson(id = "en-${contrast.id}-sentences", title = "În propoziții", detail = "Același sunet, în vorbire legată", focus = SessionFocus.English(EnglishFocus.Sentences(contrast = contrast.id)), art = "art-bubble")
            }
            if (contrast.kind == EnglishContrast.Kind.MinimalPairs) {
                lessons += Lesson(id = "en-${contrast.id}-check", title = "Te verifici singur", detail = "Cuvinte noi, fără ajutor", focus = SessionFocus.English(EnglishFocus.Retest(contrast = contrast.id)), art = "art-target")
            }
            val first = contrast.cue.splitCharacters(".").firstOrNull() ?: contrast.cue
            CourseUnit(id = "en-${contrast.id}", title = contrast.title, subtitle = first, track = Track.English, mark = contrast.mark, lessons = lessons)
        }

    val melodyUnit: CourseUnit
        get() {
            val melody = content.english.melody
            return CourseUnit(
                id = "en-melody",
                title = melody.title,
                subtitle = melody.subtitle,
                track = Track.English,
                mark = "♪",
                art = "art-notes",
                lessons = melody.lessons.mapIndexed { index, lesson ->
                    Lesson(id = "en-melody-$index", title = lesson.title, detail = "Spui odată cu vocea", focus = SessionFocus.English(EnglishFocus.Melody(lesson = index)), art = "art-notes")
                },
            )
        }

    val dictionUnits: List<CourseUnit>
        get() {
            val named = { name: DictionLesson, title: String, detail: String, art: String ->
                Lesson(id = "d-${name.rawValue}", title = title, detail = detail, focus = SessionFocus.DictionLesson(name), art = art)
            }
            return listOf(
                CourseUnit(
                    id = "d-rhythm", title = "Ritmul", subtitle = "Nici prea repede, nici prea rar", track = Track.Diction, mark = "Ri", art = "art-drum",
                    lessons = listOf(
                        named(DictionLesson.Pace, "Viteza potrivită", "Citești într-un ritm bun", "art-drum"),
                        named(DictionLesson.Pauses, "Pauza de aur", "Te oprești unde trebuie", "art-hourglass"),
                        named(DictionLesson.Shadow, "Ca un actor", "Spui odată cu vocea", "art-clapper"),
                    ),
                ),
                CourseUnit(
                    id = "d-articulation", title = "Articulația", subtitle = "Trezim buzele, limba și maxilarul", track = Track.Diction, mark = "A", art = "art-lips",
                    lessons = listOf(
                        named(DictionLesson.Warmup, "Încălzirea", "Patru mișcări pentru gură", "art-tongue"),
                        named(DictionLesson.FinalWords, "Finalurile care se pierd", "Cuvinte terminate în i", "art-finish"),
                        named(DictionLesson.Twisters, "Frământări pe viteze", "Întâi rar, apoi mai repede", "art-start"),
                    ),
                ),
                CourseUnit(
                    id = "d-volume", title = "Volumul", subtitle = "Să te audă și ultimul rând", track = Track.Diction, mark = "V", art = "art-megaphone",
                    lessons = listOf(
                        named(DictionLesson.VolumeLadder, "Scara volumului", "Vorbești din ce în ce mai tare", "art-speaker"),
                    ),
                ),
                CourseUnit(
                    id = "d-pitch", title = "Tonul", subtitle = "Vocea care urcă și coboară", track = Track.Diction, mark = "T", art = "art-coaster",
                    lessons = listOf(
                        named(DictionLesson.Glide, "Sus și jos", "Vocea urcă și coboară", "art-coaster"),
                        named(DictionLesson.StatementOrQuestion, "Întrebare sau afirmație?", "Aceleași cuvinte, alt ton", "art-question"),
                        named(DictionLesson.Emotion, "Aceeași frază, altă melodie", "O spui în mai multe feluri", "art-masks"),
                    ),
                ),
                CourseUnit(
                    id = "d-breath", title = "Respirația", subtitle = "Aer pentru fraze lungi", track = Track.Diction, mark = "R", art = "art-wind",
                    lessons = listOf(
                        named(DictionLesson.Candles, "Lumânările", "Suflat lung și egal", "candle"),
                        named(DictionLesson.LongSound, "Sunetul lung", "Ții un sunet cât poți", "art-speaker"),
                    ),
                ),
            )
        }

    /** Every lesson a session might have opened, by its focus. Used to show progress by name. */
    fun allUnits(order: List<SoundID>): List<CourseUnit> = soundUnits(order) + englishUnits + dictionUnits

    private companion object {
        /**
         * What gets someone understood comes first: stress and weak vowels, then long and short vowels. W and V come
         * last, because Romanian speakers rarely confuse them. `sortedBy` is stable, like Swift's sort.
         */
        fun englishOrder(contrasts: List<EnglishContrast>): List<EnglishContrast> {
            val order = listOf("stress", "schwa", "ship-sheep", "full-fool", "th", "bad-bed", "cut-cat", "pin-bin", "work-walk", "bed-bet", "w-v")
            return contrasts.sortedBy { contrast -> order.indexOfFirst { canonicallyEqual(it, contrast.id) }.let { if (it < 0) order.size else it } }
        }
    }
}

/** The sound the contrast is about, in phonetic letters. Andika carries them. */
val EnglishContrast.mark: String
    // nfc: Swift's switch on a String also matches canonically equivalent text.
    get() = when (nfc(id)) {
        "stress" -> "Áa"
        "schwa" -> "ə"
        "ship-sheep" -> "iː"
        "bad-bed" -> "æ"
        "work-walk" -> "ɜː"
        "pin-bin" -> "pʰ"
        "bed-bet" -> "d"
        "w-v" -> "w"
        "cut-cat" -> "ʌ"
        "full-fool" -> "ʊ"
        "th" -> "θ"
        else -> title.prefixCharacters(2)
    }
