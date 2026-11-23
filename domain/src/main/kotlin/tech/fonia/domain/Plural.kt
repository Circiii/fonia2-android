package tech.fonia.domain

import kotlin.math.abs

/**
 * Romanian counted nouns: 1 zi, 3 zile, 20 de zile. Follows the CLDR rule, where "de" appears when the count's last two
 * digits are 0 or at least 20, and never for 0 itself.
 */
object Plural {
    enum class Noun(internal val singular: String, internal val plural: String) {
        Zi("zi", "zile"),
        Incercare("încercare", "încercări"),
        Minut("minut", "minute"),
        Cuvant("cuvânt", "cuvinte"),
        Sunet("sunet", "sunete"),
        Sedinta("ședință", "ședințe"),
        Punct("punct", "puncte"),
        Stea("stea", "stele"),
        Exercitiu("exercițiu", "exerciții"),

        /** "O dată", "de 3 ori": a count of tries. */
        Oara("dată", "ori"),
    }

    /** "20 de zile". Counts of a thousand or more group digits the Romanian way: "1.000 de zile". */
    fun format(count: Int, noun: Noun): String = "${grouped(count)} ${form(count, noun)}"

    /** The noun alone, for a label under a number: never with "de". */
    fun word(count: Int, noun: Noun): String = if (count == 1) noun.singular else noun.plural

    /** The words after the number: "zi", "zile" or "de zile". */
    fun form(count: Int, noun: Noun): String {
        if (count == 1) return noun.singular
        // Long, so abs() of Int.MIN_VALUE stays positive as it does with Swift's 64-bit Int.
        val lastTwo = abs(count.toLong()) % 100
        return if (count == 0 || lastTwo in 1..19) noun.plural else "de ${noun.plural}"
    }

    private fun grouped(count: Int): String {
        var digits = abs(count.toLong()).toString()
        val groups = mutableListOf<String>()
        while (digits.length > 3) {
            groups.add(0, digits.takeLast(3))
            digits = digits.dropLast(3)
        }
        groups.add(0, digits)
        return (if (count < 0) "-" else "") + groups.joinToString(".")
    }
}
