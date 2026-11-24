package tech.fonia.features.speaking

import tech.fonia.design.Rating
import tech.fonia.domain.Exercise
import tech.fonia.domain.FeedbackLevel
import tech.fonia.domain.IntonationAsk

/**
 * "Compară" (iOS `GuidedCompare`): the recorded example, then the adult's own take, back to back, then one question about
 * what to listen for. Listening to yourself alone does little; hearing the two side by side with one thing to listen for
 * is what helped in the studies. It asks, it never rules: the adult answers in their head.
 */
object GuidedCompare {
    const val TITLE = "Compară"

    /** A short breath between the example and the take, so the two don't run into one another. */
    const val GAP_MS = 400L

    /**
     * Offered to an adult when the phone is unsure about a take, or, with full feedback, when it says the take isn't
     * there yet. Never to a child, never without a take to play, and never without a recorded example.
     */
    fun offers(
        rating: Rating?,
        keepsTake: Boolean,
        feedback: FeedbackLevel,
        isChild: Boolean,
        hasTake: Boolean,
        hasRecordedModel: Boolean,
    ): Boolean {
        if (isChild || !hasTake || !keepsTake || !hasRecordedModel) return false
        return when (rating) {
            null -> true
            Rating.nearly, Rating.notYet -> feedback == FeedbackLevel.Teach
            Rating.yes -> false
        }
    }

    /** One question, taken from what the lesson says to listen for. */
    fun question(exercise: Exercise): String = when (exercise) {
        is Exercise.Repeat -> exercise.prompt.englishContrast?.let { CONTRAST_QUESTIONS[it] } ?: ANY_DIFFERENCE
        is Exercise.Intonation -> if (exercise.ask == IntonationAsk.Question) {
            "La final, vocea urcă și la tine, ca în exemplu?"
        } else {
            "La final, vocea coboară și la tine, ca în exemplu?"
        }
        is Exercise.Shadow -> "Unde urcă vocea în exemplu? Urcă și la tine tot acolo?"
        is Exercise.Pace -> "Vorbești la fel de repede ca în exemplu?"
        is Exercise.Pauses -> "Te oprești în aceleași locuri ca în exemplu?"
        is Exercise.Expression -> "Vocea ta urcă și coboară cât cea din exemplu?"
        else -> ANY_DIFFERENCE
    }

    const val ANY_DIFFERENCE = "Ce sună altfel la tine față de exemplu?"

    /** From each contrast's cue in the course: the one thing the ear should catch. */
    val CONTRAST_QUESTIONS = mapOf(
        "stress" to "Pe ce silabă apasă exemplul? Dar tu?",
        "schwa" to "Silabele fără accent sunt la fel de scurte la tine?",
        "ship-sheep" to "Vocala ta e la fel de lungă, sau de scurtă, ca în exemplu?",
        "bad-bed" to "Vocala ta e la fel de deschisă ca în exemplu?",
        "cut-cat" to "Vocala ta e la fel de deschisă ca în exemplu?",
        "work-walk" to "În exemplu auzi un „ă” lung sau un „o” lung? Dar la tine?",
        "pin-bin" to "Auzi suflul de aer de la început? Îl auzi și la tine?",
        "full-fool" to "Vocala ta e la fel de lungă, sau de scurtă, ca în exemplu?",
        "bed-bet" to "Vocala dinaintea ultimului sunet ține la fel de mult ca în exemplu?",
        "w-v" to "La început, auzi un „u” scurt sau un V? Dar la tine?",
        "th" to "Auzi suflul ușor de la TH? La tine sună la fel, sau ca S ori T?",
    )
}
