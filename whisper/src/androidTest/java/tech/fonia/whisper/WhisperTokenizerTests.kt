package tech.fonia.whisper

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not iOS tests: the Kotlin tokenizer against the real tokenizer.json. Expected ids come from Hugging
 * Face `tokenizers` 0.22.1 (`Tokenizer.from_file("tokenizer.json").encode(text).ids`), the reference
 * swift-transformers follows; the decode cases are the swift-transformers rules WhisperSession relies on.
 */
@RunWith(AndroidJUnit4::class)
class WhisperTokenizerTests {
    private lateinit var tokenizer: WhisperBpeTokenizer

    @Before
    fun load() {
        val folder = SpeechPaths.modelFolder
        assumeTrue("needs the cached model folder", folder != null)
        tokenizer = WhisperBpeTokenizer.fromModelFolder(folder!!)
    }

    private val reference = listOf(
        " sink" to listOf(9500),
        " ship" to listOf(5374),
        " sheep" to listOf(14213),
        " Sheep." to listOf(1240, 595, 13),
        " Ship." to listOf(38407, 13),
        " I think three things." to listOf(286, 519, 1045, 721, 13),
        " The sheep is on the ship." to listOf(440, 14213, 307, 322, 264, 5374, 13),
        " șapte țări înțeleg" to listOf(15318, 569, 975, 220, 15918, 4089, 470, 15351, 15918, 68, 6363),
        " Ăăă, deci, practic, mmm... Păi, adică, gen, nu știu, ăăă, mă rog." to listOf(
            2127, 224, 4089, 4089, 11, 46358, 11, 1927, 299, 11, 26159, 485, 430, 4089, 72, 11, 614, 299, 4089, 11, 1049, 11, 3822, 15318, 83, 5951, 11,
            26790, 4089, 4089, 11, 275, 4089, 744, 70, 13,
        ),
        " Umm, let me think like, hmm... Okay, here's what I'm, like, thinking. So, um, basically, uh, you know." to listOf(
            18918, 11, 718, 385, 519, 411, 11, 16478, 485, 1033, 11, 510, 311, 437, 286, 478, 11, 411, 11, 1953, 13, 407, 11, 1105, 11, 1936, 11, 2232, 11,
            291, 458, 13,
        ),
        " don't  stop\tnow 12345 ¿qué? 日本語 <|endoftext|> x" to listOf(500, 380, 220, 1590, 197, 3785, 34466, 8465, 3841, 16412, 30, 220, 27311, 31348, 220, 50257, 2031),
    )

    @Test
    fun encodesLikeTheReferenceTokenizer() {
        for ((text, ids) in reference) {
            // The template post-processor wraps every encoding: <|startoftranscript|> <|notimestamps|> … <|endoftext|>.
            assertEquals(text, listOf(50258, 50363) + ids + 50257, tokenizer.encode(text))
        }
    }

    @Test
    fun decodesBackToTheText() {
        for ((text, ids) in reference.dropLast(1)) {
            assertEquals(text, text, tokenizer.decode(ids))
        }
    }

    @Test
    fun specialTokensAreWhisperKits() {
        val specials = tokenizer.specialTokens
        assertEquals(50257, specials.endToken)
        assertEquals(50258, specials.startOfTranscriptToken)
        assertEquals(50359, specials.transcribeToken)
        assertEquals(50363, specials.noTimestampsToken)
        assertEquals(50361, specials.startOfPreviousToken)
        // <|nospeech|> is not in this vocabulary: the unknown token, <|endoftext|>, answers, as on iOS.
        assertEquals(50257, specials.noSpeechToken)
        assertEquals(50257, specials.specialTokenBegin)
        assertEquals(50362, tokenizer.convertTokenToId("<|nocaptions|>"))
        assertEquals(50259, tokenizer.convertTokenToId("<|en|>"))
        assertEquals(50284, tokenizer.convertTokenToId("<|ro|>"))
        assertEquals(50257, tokenizer.convertTokenToId("no such token"))
    }

    @Test
    fun aSplitDiacriticDecodesToOneReplacementCharacterPerHalf() {
        // " Ă" is two byte-level tokens, " " plus the first byte of Ă, then its second byte.
        assertEquals(" �", tokenizer.decode(listOf(2127)))
        assertEquals("�", tokenizer.decode(listOf(224)))
        assertEquals(" Ă", tokenizer.decode(listOf(2127, 224)))
        // " țări" opens with a lone space token, as TokenTiming expects.
        assertEquals(" ", tokenizer.decode(listOf(220)))
        assertEquals("ț", tokenizer.decode(listOf(15918)))
    }

    @Test
    fun cleansUpSpacesBeforePunctuation() {
        // clean_up_tokenization_spaces: " ." decodes to ".".
        assertEquals(".", tokenizer.decode(tokenizer.encode(" .").filter { it < 50257 }))
        assertEquals("<|endoftext|>", tokenizer.decode(listOf(50257)))
    }

    @Test
    fun modelFolderMatchesTheManifest() {
        val folder = SpeechPaths.modelFolder!!
        for (file in WhisperCppModel.WHISPER_BASE_FROM_HUGGING_FACE.files) {
            assertEquals(file.path, file.byteCount, File(folder, file.path).length())
        }
    }
}
