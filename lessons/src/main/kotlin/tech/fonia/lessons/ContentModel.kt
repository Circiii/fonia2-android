package tech.fonia.lessons

import kotlinx.serialization.Serializable

/*
 * The v2 content as it is stored in spec/content/v2 (schema 2, chapter 06 §3). Field names are the JSON keys, so
 * they are the contract with iOS. Raw strings stay strings here (sound ids, rungs, kinds); `LessonContent` maps them
 * to the shared enums of :core:model.
 */

@Serializable
data class SoundsIndex(
    val schema: Int,
    val contentVersion: String,
    val rungs: List<String>,
    val sounds: List<SoundEntry>,
    val warmups: List<WarmupRef>,
    val files: List<String>,
)

@Serializable
data class SoundEntry(
    val id: String,
    val slug: String,
    val letter: String,
    val file: String,
    val hero: String,
    val emoji: String,
    val holdMode: String,
    val voiced: Boolean,
    val confusions: List<String>,
)

@Serializable
data class WarmupRef(val id: String, val say: String)

@Serializable
data class SoundContent(
    val schema: Int,
    val contentVersion: String,
    val sound: String,
    val slug: String,
    val letter: String,
    val graphemes: List<String>,
    val hero: Hero,
    val voiced: Boolean,
    val holdMode: String,
    val typicalByAge: Int,
    val confusions: List<String>,
    val howParent: List<String>,
    val warmups: List<String>,
    val rungs: List<String>,
    val plan: Map<String, Int>,
    val errors: List<ErrorRef>,
    val items: List<ContentItem>,
)

@Serializable
data class Hero(
    val name: String,
    val who: String,
    val emoji: String,
    val says: String,
    val saysVoice: String,
    val line: String,
    val lineVoice: String,
    val chapters: Map<String, Chapter>,
)

@Serializable
data class Chapter(val key: String, val text: String, val voice: String)

@Serializable
data class ErrorRef(val kind: String, val instructionKey: String, val adultKey: String)

@Serializable
data class Review(val status: String, val by: String? = null, val date: String? = null)

/** One practice item. Which optional fields are set depends on [type] (chapter 06 §3.2). */
@Serializable
data class ContentItem(
    val id: String,
    val type: String,
    val rung: String,
    val text: String,
    val difficulty: Int = 1,
    val ageBand: String = "4+",
    val needsTherapistCheck: Boolean = false,
    val review: Review = Review("draft"),
    val source: String = "original",
    val voice: String? = null,
    // word
    val pos: String? = null,
    val emoji: String? = null,
    val icon: String? = null,
    val syllables: Int? = null,
    val otherSounds: List<String> = emptyList(),
    val cluster: Boolean = false,
    // listen
    val foil: String? = null,
    val mimics: String? = null,
    val foilVoice: String? = null,
    // hold
    val mode: String? = null,
    val prompt: String? = null,
    val promptVoice: String? = null,
    // syllable
    val shape: String? = null,
    // pair
    val a: String? = null,
    val b: String? = null,
    val contrast: String? = null,
    val emojiA: String? = null,
    val emojiB: String? = null,
    val voiceA: String? = null,
    val voiceB: String? = null,
    // sentence
    val words: Int? = null,
    // story
    val pictures: List<String> = emptyList(),
    val lines: List<String> = emptyList(),
    val lineVoices: List<String> = emptyList(),
)

@Serializable
data class TwistersFile(val schema: Int, val contentVersion: String, val twisters: List<Twister>)

@Serializable
data class Twister(
    val id: String,
    val type: String,
    val rung: String,
    val sounds: List<String>,
    val text: String,
    val source: String,
    val folkRef: String? = null,
    val tempos: List<String>,
    val ageBand: String,
    val difficulty: Int,
    val syllables: Int,
    val voice: String,
    val needsTherapistCheck: Boolean,
    val review: Review,
)

@Serializable
data class FoniLinesFile(
    val schema: Int,
    val contentVersion: String,
    val feedbackVersion: String,
    val lines: List<FoniLine>,
)

@Serializable
data class FoniLine(
    val key: String,
    val category: String,
    val `when`: String,
    val text: String,
    val maxSeconds: Double,
    val voice: String,
    val sound: String? = null,
    val kind: String? = null,
)

@Serializable
data class ParentTexts(
    val schema: Int,
    val contentVersion: String,
    val cards: List<ParentCard>,
    val tips: List<String>,
    val summaries: List<Template>,
    val reminders: List<Template>,
    val privacy: Privacy,
    val rungNames: Map<String, String>,
    val positionNames: Map<String, String>,
    val partners: Map<String, String>,
)

/** One of the six cards in "Pentru adult" (chapter 06 §9.1). `{letter}`, `{partner}`, `{syllable}`, `{split}` are filled in. */
@Serializable
data class ParentCard(
    val id: String,
    val title: String,
    val rungs: List<String>,
    val target: String,
    val goodAttempt: String,
    val ifNot: String,
)

@Serializable
data class Template(val id: String, val text: String, val vars: List<String>)

@Serializable
data class Privacy(val title: String, val paragraphs: List<String>)

@Serializable
data class LessonSpec(
    val schema: Int,
    val contentVersion: String,
    val template: TemplateSpec,
    val blocks: Map<String, BlockSpec>,
    val block1Counts: Map<String, Int>,
    val takes: Map<String, Int>,
    val adaptation: AdaptationSpec,
    val review: ReviewSpec,
)

@Serializable
data class TemplateSpec(
    val opening: List<Int>,
    val warmup: List<Int>,
    val block1: List<Int>,
    val move: List<Int>,
    val block2: List<Int>,
    val block3: List<Int>,
    val final: List<Int>,
    val warmupMaxSeconds: Int,
    val warmupExercises: Int,
    val oneMore: OneMoreSpec,
    val longPauseDays: Int,
    val surpriseSlot: List<Int>,
)

@Serializable
data class OneMoreSpec(val block: Int, val seconds: Int)

/** What a rung uses: [block1] names the material from below (null = none), [block2]/[block3] count current items. */
@Serializable
data class BlockSpec(val block1: String? = null, val block2: Int, val block3: Int)

@Serializable
data class AdaptationSpec(
    val targetLow: Double,
    val targetHigh: Double,
    val window: Int,
    val harderAbove: Double,
    val harderBlocks: Int,
    val easierBelow: Double,
    val firstSessionNewShare: Double,
    val levels: Map<String, AdaptParams>,
)

/** The knobs the hidden adaptation moves (chapter 06 §2.4). */
@Serializable
data class AdaptParams(
    val holdSeconds: Double,
    val trainLength: Int,
    val newShare: Double,
    val modelBeforeEach: Boolean,
    val tempo: String,
)

@Serializable
data class ReviewSpec(val intervals: List<Int>, val maxPerSession: Int)

@Serializable
data class ContentLock(val schema: Int, val contentVersion: String, val sha256: Map<String, String>)
