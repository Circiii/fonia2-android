package tech.fonia.features.speaking.bac

import tech.fonia.speech.SpeechLanguage
import tech.fonia.speech.SpeechText
import tech.fonia.speech.Transcript
import java.text.Normalizer

// The structure of an argued opinion, read from the connectors in its transcript with simple rules (V6, H F7:
// "local se folosesc reguli și liste de conectori"). No language model: the lists below are the whole method, and the
// reader can correct the count. It says which words were heard, never whether the argument was good.

enum class ConnectorRole {
    /** "cred că", "în opinia mea": the point of view. */
    Thesis,

    /** "în primul rând", "de asemenea": a new argument starts. */
    Argument,

    /** "pentru că", "deoarece": a reason. */
    Reason,

    /** "de exemplu". */
    Example,

    /** "în concluzie", "așadar". */
    Conclusion,
}

data class ConnectorHit(
    /** As the content lists it: „în primul rând”. */
    val phrase: String,
    val role: ConnectorRole,
    /** Index of its first word among the transcript's words. */
    val word: Int,
    val start: Double,
    val end: Double,
)

data class ArgumentCount(
    val hits: List<ConnectorHit>,
    /** Arguments counted from the connectors (see [Connectors.find]). */
    val arguments: Int,
) {
    val hasThesis: Boolean get() = hits.any { it.role == ConnectorRole.Thesis }
    val hasConclusion: Boolean get() = hits.any { it.role == ConnectorRole.Conclusion }

    /** The phrases heard for one role, each once, in the order heard. */
    fun phrases(role: ConnectorRole): List<String> = hits.filter { it.role == role }.map { it.phrase }.distinct()
}

object Connectors {
    /** At least this many arguments (V6 "experimentat": ≥ 2 argumente, [S]). */
    const val TARGET_ARGUMENTS = 2

    /** A phrase, its role, and whether it only counts at the start of a clause ("Mai mult, …", not "citesc mai mult"). */
    private class Entry(val phrase: String, val role: ConnectorRole, val clauseStart: Boolean = false) {
        val tokens: List<String> = phrase.split(' ').map(::fold)
    }

    private val entries: List<Entry> = listOf(
        // The point of view.
        Entry("consider că", ConnectorRole.Thesis),
        Entry("cred că", ConnectorRole.Thesis),
        Entry("în opinia mea", ConnectorRole.Thesis),
        Entry("după părerea mea", ConnectorRole.Thesis),
        Entry("din punctul meu de vedere", ConnectorRole.Thesis),
        Entry("sunt de părere că", ConnectorRole.Thesis),
        Entry("susțin că", ConnectorRole.Thesis),
        Entry("părerea mea este", ConnectorRole.Thesis),
        Entry("sunt convins că", ConnectorRole.Thesis),
        Entry("sunt convinsă că", ConnectorRole.Thesis),
        Entry("mi se pare că", ConnectorRole.Thesis),
        // A new argument.
        Entry("în primul rând", ConnectorRole.Argument),
        Entry("în al doilea rând", ConnectorRole.Argument),
        Entry("în al treilea rând", ConnectorRole.Argument),
        Entry("nu în ultimul rând", ConnectorRole.Argument),
        Entry("în ultimul rând", ConnectorRole.Argument),
        Entry("pe de o parte", ConnectorRole.Argument),
        Entry("pe de altă parte", ConnectorRole.Argument),
        Entry("un prim argument", ConnectorRole.Argument),
        Entry("primul argument", ConnectorRole.Argument),
        Entry("al doilea argument", ConnectorRole.Argument),
        Entry("al treilea argument", ConnectorRole.Argument),
        Entry("un alt argument", ConnectorRole.Argument),
        Entry("un argument", ConnectorRole.Argument),
        Entry("de asemenea", ConnectorRole.Argument),
        Entry("totodată", ConnectorRole.Argument),
        Entry("mai mult decât atât", ConnectorRole.Argument),
        Entry("pe lângă asta", ConnectorRole.Argument),
        Entry("pe lângă aceasta", ConnectorRole.Argument),
        Entry("în afară de asta", ConnectorRole.Argument),
        Entry("în plus", ConnectorRole.Argument, clauseStart = true),
        Entry("mai mult", ConnectorRole.Argument, clauseStart = true),
        // A reason.
        Entry("pentru că", ConnectorRole.Reason),
        Entry("deoarece", ConnectorRole.Reason),
        Entry("întrucât", ConnectorRole.Reason),
        Entry("fiindcă", ConnectorRole.Reason),
        Entry("căci", ConnectorRole.Reason),
        Entry("din cauză că", ConnectorRole.Reason),
        Entry("datorită faptului că", ConnectorRole.Reason),
        Entry("având în vedere că", ConnectorRole.Reason),
        // An example.
        Entry("de exemplu", ConnectorRole.Example),
        Entry("spre exemplu", ConnectorRole.Example),
        Entry("de pildă", ConnectorRole.Example),
        Entry("cum ar fi", ConnectorRole.Example),
        // The end.
        Entry("în concluzie", ConnectorRole.Conclusion),
        Entry("așadar", ConnectorRole.Conclusion),
        Entry("prin urmare", ConnectorRole.Conclusion),
        Entry("în încheiere", ConnectorRole.Conclusion),
        Entry("în final", ConnectorRole.Conclusion),
        Entry("pe scurt", ConnectorRole.Conclusion),
        Entry("drept urmare", ConnectorRole.Conclusion),
        Entry("în consecință", ConnectorRole.Conclusion),
    ).sortedByDescending { it.tokens.size }

    /** Lower case without diacritics, since the recogniser does not always write them: „În” → "in", "așadar" → "asadar". */
    internal fun fold(word: String): String {
        val normal = SpeechText.normalizedWord(word, SpeechLanguage.RO)
        val stripped = Normalizer.normalize(normal, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        return Normalizer.normalize(stripped, Normalizer.Form.NFC)
    }

    private val clauseEnds = setOf('.', ',', '!', '?', ':', ';', '…')

    /**
     * The connectors of [transcript], longest phrase first at each word, and the arguments they mark: one for each
     * argument connector, plus one when a reason comes before any of them ("Cred că X, pentru că Y. De asemenea, Z"
     * has two). An opinion that says "pentru că" twice without a connector between counts one: the reader corrects it.
     */
    fun find(transcript: Transcript): ArgumentCount {
        val words = transcript.words.filter { SpeechText.normalizedWord(it.word, SpeechLanguage.RO).isNotEmpty() }
        val folded = words.map { fold(it.word) }
        val hits = mutableListOf<ConnectorHit>()
        var i = 0
        while (i < words.size) {
            val atClauseStart = i == 0 || words[i - 1].word.trimEnd().lastOrNull() in clauseEnds
            val entry = entries.firstOrNull { entry ->
                i + entry.tokens.size <= folded.size &&
                    entry.tokens.indices.all { folded[i + it] == entry.tokens[it] } &&
                    (!entry.clauseStart || atClauseStart)
            }
            if (entry == null) {
                i += 1
                continue
            }
            hits.add(ConnectorHit(entry.phrase, entry.role, i, words[i].start, words[i + entry.tokens.size - 1].end))
            i += entry.tokens.size
        }
        val firstArgument = hits.firstOrNull { it.role == ConnectorRole.Argument }?.word ?: Int.MAX_VALUE
        val leadingReason = hits.any { it.role == ConnectorRole.Reason && it.word < firstArgument }
        val arguments = hits.count { it.role == ConnectorRole.Argument } + if (leadingReason) 1 else 0
        return ArgumentCount(hits, arguments)
    }
}
