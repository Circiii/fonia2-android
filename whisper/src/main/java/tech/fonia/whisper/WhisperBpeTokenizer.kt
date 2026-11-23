package tech.fonia.whisper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tech.fonia.speech.WhisperSpecialTokens
import tech.fonia.speech.WhisperTokenizer
import java.io.File
import java.io.IOException
import java.util.regex.Pattern

/**
 * The tokenizer WhisperKit gives WhisperSession on iOS: swift-transformers' `PreTrainedTokenizer`
 * (vendored in argmax-oss-swift 1.1.1, `ArgmaxCore/External/Tokenizers`) over the model folder's
 * `tokenizer.json` and `tokenizer_config.json`, wrapped by `WhisperTokenizerWrapper`. The same rules,
 * step for step, because the judges read its output: token texts decide where words start, the
 * U+FFFD of a split diacritic keeps Romanian words apart, and the transcript is compared word for word.
 *
 * - [encode]: added tokens split out first, the GPT-2 byte-level pre-tokenizer regex, byte-to-unicode
 *   mapping, BPE by merge rank, then the template post-processor (`<|startoftranscript|>
 *   <|notimestamps|> … <|endoftext|>`), which WhisperSession filters out again.
 * - [decode]: ids to token strings, the ByteLevel decoder (bytes back to UTF-8, each ill-formed part
 *   replaced by one U+FFFD, as Swift's `String(decoding:as:)` does), then `cleanUp` (" ." becomes ".",
 *   and so on) since `clean_up_tokenization_spaces` is true.
 * - [convertTokenToId]: vocabulary and added tokens; an unknown name answers the `unk_token`,
 *   `<|endoftext|>`, never null.
 */
class WhisperBpeTokenizer private constructor(
    private val tokensToIds: HashMap<String, Int>,
    private val idsToTokens: HashMap<Int, String>,
    private val bpeRanks: HashMap<String, Int>,
    private val addedTokens: Set<String>,
    private val unknownTokenId: Int?,
    private val cleanUpTokenizationSpaces: Boolean,
    private val template: List<TemplatePiece>?,
) : WhisperTokenizer {
    private sealed interface TemplatePiece {
        data class Special(val token: String) : TemplatePiece

        data object Sequence : TemplatePiece
    }

    /** `WhisperTokenizerWrapper.init`: each id looked up by name, WhisperKit's defaults when missing. */
    override val specialTokens: WhisperSpecialTokens = WhisperSpecialTokens(
        endToken = convertTokenToId("<|endoftext|>") ?: 50257,
        startOfTranscriptToken = convertTokenToId("<|startoftranscript|>") ?: 50258,
        transcribeToken = convertTokenToId("<|transcribe|>") ?: 50359,
        noTimestampsToken = convertTokenToId("<|notimestamps|>") ?: 50363,
        startOfPreviousToken = convertTokenToId("<|startofprev|>") ?: 50361,
        noSpeechToken = convertTokenToId("<|nospeech|>") ?: 50362,
        specialTokenBegin = convertTokenToId("<|endoftext|>") ?: 50257,
    )

    override fun convertTokenToId(token: String): Int? = tokensToIds[token] ?: unknownTokenId

    fun convertIdToToken(id: Int): String? = idsToTokens[id]

    override fun encode(text: String): List<Int> {
        val tokens = tokenize(text)
        val processed = template?.flatMap { piece ->
            when (piece) {
                is TemplatePiece.Special -> listOf(piece.token)
                TemplatePiece.Sequence -> tokens
            }
        } ?: tokens
        // `model.convertTokenToId($0)!`: every token is in the vocabulary or falls back to unk.
        return processed.map { convertTokenToId(it) ?: throw IllegalStateException("no id for token '$it'") }
    }

    override fun decode(tokens: List<Int>): String {
        val strings = tokens.mapNotNull { idsToTokens[it] }
        return cleanUp(byteLevelDecode(strings).joinToString(""))
    }

    /** `PreTrainedTokenizer.tokenize`: added tokens kept whole, the rest pre-tokenized and run through BPE. */
    fun tokenize(text: String): List<String> =
        splitAddedTokens(text).flatMap { section ->
            if (section in addedTokens) listOf(section) else preTokenize(section).flatMap { bpeTokenize(it) }
        }

    /**
     * `String.split(by: addedTokensRegex)`: the added tokens, longest first, cut out of the text. Every
     * added token of Whisper's tokenizer has the form `<|…|>` with no `|>` inside, so the longest one
     * matching at a position is the one that ends at the next `|>`.
     */
    private fun splitAddedTokens(text: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = text.indexOf("<|")
        while (i >= 0) {
            val end = text.indexOf("|>", i + 2)
            if (end < 0) break
            val candidate = text.substring(i, end + 2)
            if (candidate in addedTokens) {
                if (start < i) out.add(text.substring(start, i))
                out.add(candidate)
                start = end + 2
                i = text.indexOf("<|", start)
            } else {
                i = text.indexOf("<|", i + 1)
            }
        }
        if (start < text.length) out.add(text.substring(start))
        return out
    }

    /** `ByteLevelPreTokenizer` with `use_regex`, no prefix space: regex pieces, each mapped to byte characters. */
    private fun preTokenize(text: String): List<String> {
        val matcher = PRE_TOKENIZER.matcher(text)
        val out = mutableListOf<String>()
        while (matcher.find()) {
            val piece = matcher.group()
            val bytes = piece.toByteArray(Charsets.UTF_8)
            val mapped = StringBuilder(bytes.size)
            for (b in bytes) mapped.append(BYTE_ENCODER[b.toInt() and 0xFF])
            out.add(mapped.toString())
        }
        return out
    }

    /** `BPETokenizer.tokenize`. */
    private fun bpeTokenize(token: String): List<String> {
        val out = mutableListOf<String>()
        for (piece in bpe(token).split(' ')) {
            if (convertTokenToId(piece) != unknownTokenId) {
                out.add(piece)
            } else {
                // hexaEncode, which byte-level vocabularies never reach.
                for (b in piece.toByteArray(Charsets.UTF_8)) out.add("<0x%02X>".format(b.toInt() and 0xFF))
            }
        }
        return out
    }

    /** `BPETokenizer.bpe`, loop for loop: merge the lowest-ranked adjacent pair everywhere until none is left. */
    private fun bpe(token: String): String {
        if (token.length <= 1) return token
        var word: List<String> = token.map { it.toString() }
        var pairs = pairsOf(word)
        while (true) {
            var bigram: Pair<String, String>? = null
            var bestRank = Int.MAX_VALUE
            for (pair in pairs) {
                val rank = bpeRanks[pair.first + " " + pair.second] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bigram = pair
                }
            }
            if (bigram == null) break
            val (first, second) = bigram
            val newWord = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                val j = indexOf(word, first, i)
                if (j < 0) {
                    newWord.addAll(word.subList(i, word.size))
                    break
                }
                newWord.addAll(word.subList(i, j))
                i = j
                if (word[i] == first && i < word.size - 1 && word[i + 1] == second) {
                    newWord.add(first + second)
                    i += 2
                } else {
                    newWord.add(word[i])
                    i += 1
                }
            }
            word = newWord
            if (word.size == 1) break
            pairs = pairsOf(word)
        }
        return word.joinToString(" ")
    }

    private fun indexOf(word: List<String>, value: String, from: Int): Int {
        for (k in from until word.size) if (word[k] == value) return k
        return -1
    }

    private fun pairsOf(word: List<String>): Set<Pair<String, String>> {
        val pairs = LinkedHashSet<Pair<String, String>>()
        for (i in 0 until word.size - 1) pairs.add(word[i] to word[i + 1])
        return pairs
    }

    /** `ByteLevelDecoder.decode`: runs of ordinary tokens become UTF-8 text; added tokens pass through. */
    private fun byteLevelDecode(tokens: List<String>): List<String> {
        val subTexts = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isEmpty()) return
            val bytes = ByteArray(current.length)
            for (k in current.indices) {
                bytes[k] = (BYTE_DECODER[current[k]] ?: throw IllegalStateException("not a byte-level character: ${current[k]}")).toByte()
            }
            subTexts.add(decodeUtf8(bytes))
            current.setLength(0)
        }
        for (token in tokens) {
            if (token in addedTokens) {
                flush()
                subTexts.add(token)
            } else {
                current.append(token)
            }
        }
        flush()
        return subTexts
    }

    /** `PreTrainedTokenizer.cleanUp`. */
    private fun cleanUp(text: String): String {
        if (!cleanUpTokenizationSpaces) return text
        return text
            .replace(" .", ".")
            .replace(" ?", "?")
            .replace(" !", "!")
            .replace(" ,", ",")
            .replace(" ' ", "'")
            .replace(" n't", "n't")
            .replace(" 'm", "'m")
            .replace(" 's", "'s")
            .replace(" 've", "'ve")
            .replace(" 're", "'re")
    }

    companion object {
        /**
         * The GPT-2 pattern swift-transformers runs through NSRegularExpression (ICU). ICU's `\s` is
         * `[\t\n\f\r\p{Z}]`; spelled out here so java.util.regex, which defines `\s` differently on the
         * JVM, matches the same characters.
         */
        private val PRE_TOKENIZER: Pattern = run {
            val space = "\\t\\n\\f\\r\\p{Z}"
            Pattern.compile(
                "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^$space\\p{L}\\p{N}]+|[$space]+(?![^$space])|[$space]+",
            )
        }

        /** GPT-2's `bytes_to_unicode`: printable Latin-1 bytes stand for themselves, the rest from U+0100 up. */
        private val BYTE_ENCODER: CharArray = run {
            val printable = (0x21..0x7E) + (0xA1..0xAC) + (0xAE..0xFF)
            val table = CharArray(256)
            for (b in printable) table[b] = b.toChar()
            var n = 0
            for (b in 0 until 256) {
                if (b !in printable) {
                    table[b] = (256 + n).toChar()
                    n += 1
                }
            }
            table
        }

        private val BYTE_DECODER: Map<Char, Int> = BYTE_ENCODER.withIndex().associate { (byte, char) -> char to byte }

        /**
         * UTF-8 to text with one U+FFFD for each maximal ill-formed part (the Unicode and WHATWG practice
         * Swift's `String(decoding:as: UTF8.self)` follows). Written out because Android's decoder may
         * count replacements differently, and a lone half of "ț" must read as exactly one U+FFFD.
         */
        fun decodeUtf8(bytes: ByteArray): String {
            val out = StringBuilder(bytes.size)
            var i = 0
            while (i < bytes.size) {
                val b0 = bytes[i].toInt() and 0xFF
                if (b0 < 0x80) {
                    out.append(b0.toChar())
                    i += 1
                    continue
                }
                val need: Int
                var lower = 0x80
                var upper = 0xBF
                var codePoint: Int
                when (b0) {
                    in 0xC2..0xDF -> {
                        need = 1
                        codePoint = b0 and 0x1F
                    }
                    in 0xE0..0xEF -> {
                        need = 2
                        codePoint = b0 and 0x0F
                        if (b0 == 0xE0) lower = 0xA0
                        if (b0 == 0xED) upper = 0x9F
                    }
                    in 0xF0..0xF4 -> {
                        need = 3
                        codePoint = b0 and 0x07
                        if (b0 == 0xF0) lower = 0x90
                        if (b0 == 0xF4) upper = 0x8F
                    }
                    else -> {
                        out.append('�')
                        i += 1
                        continue
                    }
                }
                var j = i + 1
                var complete = true
                for (k in 0 until need) {
                    val b = if (j < bytes.size) bytes[j].toInt() and 0xFF else -1
                    if (b < lower || b > upper) {
                        complete = false
                        break
                    }
                    codePoint = (codePoint shl 6) or (b and 0x3F)
                    lower = 0x80
                    upper = 0xBF
                    j += 1
                }
                if (complete) out.appendCodePoint(codePoint) else out.append('�')
                // The byte that broke the sequence starts the next one.
                i = j
            }
            return out.toString()
        }

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * `AutoTokenizer.from(modelFolder:)`: `tokenizer.json` is required; `tokenizer_config.json`
         * supplies `unk_token` and `clean_up_tokenization_spaces` (true when absent). Throws on a
         * missing or malformed file; nothing here touches the network.
         */
        fun fromModelFolder(folder: File): WhisperBpeTokenizer {
            val data = File(folder, "tokenizer.json")
            if (!data.isFile) throw IOException("no tokenizer.json in $folder")
            val config = File(folder, "tokenizer_config.json").takeIf { it.isFile }
                ?.let { json.parseToJsonElement(it.readText(Charsets.UTF_8)).jsonObject }
                ?: JsonObject(emptyMap())
            return from(json.parseToJsonElement(data.readText(Charsets.UTF_8)).jsonObject, config)
        }

        fun from(tokenizerData: JsonObject, tokenizerConfig: JsonObject): WhisperBpeTokenizer {
            val model = tokenizerData["model"]?.jsonObject ?: throw IOException("tokenizer.json has no model")
            val modelType = model["type"]?.jsonPrimitive?.contentOrNull
            if (modelType != null && modelType != "BPE") throw IOException("unsupported tokenizer model $modelType")
            val vocab = model["vocab"]?.jsonObject ?: throw IOException("tokenizer.json has no vocab")
            val merges = model["merges"]?.jsonArray ?: throw IOException("tokenizer.json has no merges")

            val tokensToIds = HashMap<String, Int>(vocab.size * 2)
            for ((token, id) in vocab) tokensToIds[token] = id.jsonPrimitive.intOrNull ?: throw IOException("bad id for $token")
            val addedTokens = LinkedHashSet<String>()
            for (element in tokenizerData["added_tokens"]?.jsonArray ?: JsonArray(emptyList())) {
                val added = element.jsonObject
                val id = added["id"]?.jsonPrimitive?.intOrNull ?: continue
                val content = added["content"]?.jsonPrimitive?.contentOrNull ?: continue
                tokensToIds[content] = id
                addedTokens.add(content)
            }
            val idsToTokens = HashMap<Int, String>(tokensToIds.size * 2)
            for ((token, id) in tokensToIds) idsToTokens[id] = token

            val ranks = HashMap<String, Int>(merges.size * 2)
            for ((rank, merge) in merges.withIndex()) {
                val key = when (merge) {
                    is JsonPrimitive -> merge.content
                    is JsonArray -> merge.joinToString(" ") { it.jsonPrimitive.content }
                    else -> throw IOException("bad merge $merge")
                }
                // Swift's `bpeRanks[bp] = i` keeps the last rank of a repeated pair.
                ranks[key] = rank
            }

            val unknownToken = tokenString(tokenizerConfig["unk_token"])
            val unknownTokenId = unknownToken?.let { tokensToIds[it] }
            val cleanUp = (tokenizerConfig["clean_up_tokenization_spaces"] as? JsonPrimitive)?.booleanOrNull ?: true

            val template = (tokenizerData["post_processor"] as? JsonObject)
                ?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "TemplateProcessing" }
                ?.get("single")?.jsonArray
                ?.map { piece ->
                    val item = piece.jsonObject
                    val special = item["SpecialToken"]?.jsonObject
                    if (special != null) {
                        TemplatePiece.Special(special["id"]?.jsonPrimitive?.content ?: throw IOException("bad template $item"))
                    } else {
                        TemplatePiece.Sequence
                    }
                }

            return WhisperBpeTokenizer(tokensToIds, idsToTokens, ranks, addedTokens, unknownTokenId, cleanUp, template)
        }

        /** `addedTokenAsString`: a token in tokenizer_config.json is either a string or `{ "content": … }`. */
        private fun tokenString(element: JsonElement?): String? = when (element) {
            null, JsonNull -> null
            is JsonPrimitive -> element.contentOrNull
            is JsonObject -> element["content"]?.jsonPrimitive?.contentOrNull
            else -> null
        }
    }
}
