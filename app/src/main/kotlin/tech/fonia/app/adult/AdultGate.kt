package tech.fonia.app.adult

import kotlin.random.Random

/**
 * `AdultGate` (chapter 01 §9): a gate a small child cannot pass. Two parts: hold a button for [HOLD_MILLIS], then
 * "Scrie cu cifre:" a number written in words, 21–99, never round; a wrong answer brings another number. Once passed it
 * stays open until the app has been in the background for [BACKGROUND_CLOSE_MILLIS], the process restarts, or
 * „Șterge tot” ([close]). Pure: the clock and the randomness come in.
 */
class AdultGate(private val random: Random = Random.Default, private val clock: () -> Long = System::currentTimeMillis) {
    var number: Int = next(null)
        private set

    var isOpen: Boolean = false
        private set

    private var backgroundSince: Long? = null

    /** The number in Romanian words ("patruzeci și șapte"). */
    val words: String get() = RomanianNumbers.words(number)

    /** True when [input] (digits, spaces ignored) is the number; a wrong answer draws a new number. */
    fun check(input: String): Boolean {
        val ok = input.filter { it.isDigit() }.toIntOrNull() == number
        if (ok) {
            isOpen = true
        } else {
            number = next(number)
        }
        return ok
    }

    /** A hold that lasted [heldMillis]: enough to show the number? */
    fun holdDone(heldMillis: Long): Boolean = heldMillis >= HOLD_MILLIS

    fun onBackground() {
        backgroundSince = clock()
    }

    fun onForeground() {
        val since = backgroundSince ?: return
        backgroundSince = null
        if (clock() - since >= BACKGROUND_CLOSE_MILLIS) close()
    }

    fun close() {
        isOpen = false
        number = next(number)
    }

    private fun next(previous: Int?): Int {
        while (true) {
            val n = random.nextInt(21, 100)
            if (n % 10 != 0 && n != previous) return n
        }
    }

    companion object {
        const val HOLD_MILLIS = 3000L
        const val BACKGROUND_CLOSE_MILLIS = 180_000L
    }
}

/** Romanian cardinal words for 1–99, as the gate writes them. */
object RomanianNumbers {
    private val units = listOf("", "unu", "doi", "trei", "patru", "cinci", "șase", "șapte", "opt", "nouă")
    private val teens = listOf("zece", "unsprezece", "doisprezece", "treisprezece", "paisprezece", "cincisprezece", "șaisprezece", "șaptesprezece", "optsprezece", "nouăsprezece")
    private val tens = listOf("", "", "douăzeci", "treizeci", "patruzeci", "cincizeci", "șaizeci", "șaptezeci", "optzeci", "nouăzeci")

    fun words(n: Int): String {
        require(n in 1..99) { "1–99 only: $n" }
        return when {
            n < 10 -> units[n]
            n < 20 -> teens[n - 10]
            n % 10 == 0 -> tens[n / 10]
            else -> "${tens[n / 10]} și ${units[n % 10]}"
        }
    }
}
