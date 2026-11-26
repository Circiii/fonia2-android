package tech.fonia.features.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One multiplication from the table an adult knows by heart, with four answers to pick from (iOS `GateQuestion`). */
class GateQuestion(
    val a: Int,
    val b: Int,
    order: (List<Int>) -> List<Int> = { it.shuffled() },
) {
    val answer: Int get() = a * b
    val text: String get() = "$a × $b"

    /**
     * The right answer and three near ones, in the order they're shown. The near misses are what a hurried guess
     * would give: one row or column off, so every answer is a real product.
     */
    val choices: List<Int>

    init {
        val answer = a * b
        val near = mutableListOf<Int>()
        for (value in listOf(a * (b + 1), (a + 1) * b, a * (b - 1), (a - 1) * b, (a + 1) * (b + 1), (a - 1) * (b - 1), answer + 10)) {
            if (value > 0 && value != answer && value !in near) near += value
        }
        choices = order(listOf(answer) + near.take(3))
    }

    override fun equals(other: Any?): Boolean = other is GateQuestion && other.a == a && other.b == b && other.choices == choices
    override fun hashCode(): Int = (a * 31 + b) * 31 + choices.hashCode()

    companion object {
        /** Factors from 6 to 9: past what a child learns early, quick for an adult. */
        val factors: IntRange = 6..9

        /** Every answer `foniaGate` may fix. */
        val products: Set<Int> get() = factors.flatMap { a -> factors.map { a * it } }.toSet()

        /** The first question whose answer is [product], with its answers in rising order. For UI tests. */
        fun fixedAnswer(product: Int): GateQuestion? {
            for (a in factors) for (b in factors) if (a * b == product) return GateQuestion(a, b) { it.sorted() }
            return null
        }
    }
}

/**
 * The check in front of what only an adult should do (iOS `AdultGate`): the settings, sharing files, deleting,
 * downloading, and anything that leaves the app. It asks a multiplication an adult answers at a glance, with four
 * answers to tap. A wrong answer brings a new question; a second wrong one in a row also asks for a few seconds'
 * rest, so tapping at random doesn't get a child through.
 *
 * Once passed it stays open until the app has been in the background for more than [BACKGROUND_CLOSE_MILLIS], the
 * process restarts, or "Șterge tot" ([close]). The clock comes in; [isOpen], [question], [isRetry] and [restsUntil]
 * are observed by the screens.
 */
class AdultGate(
    /** Debug only (`foniaGate`): always asks the question with this answer. */
    fixed: Int? = null,
    private val draw: () -> GateQuestion = ::randomQuestion,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val fixed: GateQuestion? = fixed?.let(GateQuestion::fixedAnswer)

    var isOpen: Boolean by mutableStateOf(false)
        private set

    var question: GateQuestion by mutableStateOf(this.fixed ?: draw())
        private set

    /** The last answer was wrong, so the question on screen is a new one. */
    var isRetry: Boolean by mutableStateOf(false)
        private set

    /** While set and in the future, the answers wait (epoch millis). */
    var restsUntil: Long? by mutableStateOf(null)
        private set

    private var backgroundSince: Long? = null
    private var wrongInARow = 0

    val prompt: String get() = "Cât face ${question.text}?"

    fun isResting(at: Long = clock()): Boolean = restsUntil?.let { at < it } ?: false

    /** Checks a tapped answer. Right opens the gate; wrong puts up a different question. */
    fun answer(value: Int, at: Long = clock()): Boolean {
        if (isResting(at)) return false
        if (value == question.answer) {
            isOpen = true
            isRetry = false
            wrongInARow = 0
            restsUntil = null
            return true
        }
        isRetry = true
        wrongInARow += 1
        if (wrongInARow >= 2) restsUntil = at + REST_MILLIS
        newQuestion()
        return false
    }

    fun onBackground() {
        backgroundSince = clock()
    }

    fun onForeground() {
        val since = backgroundSince ?: return
        backgroundSince = null
        if (clock() - since > BACKGROUND_CLOSE_MILLIS) close()
    }

    /** Closes the gate, as a launch would (iOS `lock`). "Șterge tot" does this too. */
    fun close() {
        isOpen = false
        isRetry = false
        wrongInARow = 0
        restsUntil = null
        backgroundSince = null
        newQuestion()
    }

    private fun newQuestion() {
        fixed?.let { question = it; return }
        // A new question, not the one just answered.
        var next = draw()
        repeat(8) { if (next.answer == question.answer) next = draw() }
        question = next
    }

    companion object {
        /** How long the app may sit in the background before the check is asked again. */
        const val BACKGROUND_CLOSE_MILLIS = 180_000L

        /** The rest after a second wrong answer in a row. */
        const val REST_MILLIS = 5_000L

        fun randomQuestion(): GateQuestion = GateQuestion(GateQuestion.factors.random(), GateQuestion.factors.random())

        /** `foniaGate`: the number is kept only when it is in the table (iOS `LaunchFlags.fixedGateNumber`). */
        fun fixedNumber(raw: Int?): Int? = raw?.takeIf { it in GateQuestion.products }
    }
}
