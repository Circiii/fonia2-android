package tech.fonia.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.speech.SpeechModelManifest

/** The pure parts of :whisper, on the JVM. */
class WhisperModuleTests {
    @Test
    fun manifestPinsFourFiles() {
        val manifest = WhisperCppModel.WHISPER_BASE_FROM_HUGGING_FACE
        assertEquals("openai_whisper-base", manifest.name)
        assertEquals(listOf("ggml-base.bin", "generation_config.json", "tokenizer.json", "tokenizer_config.json"), manifest.files.map { it.path })
        assertTrue(manifest.files.all { it.sha256.length == 64 && it.url.scheme == "https" })
        assertEquals(150_717_376L, manifest.byteCount)
        assertEquals(151L, WhisperCppModel.megabytes())
    }

    @Test
    fun tokenizerAndGenerationConfigAreTheFilesIosPins() {
        val ios = SpeechModelManifest.WHISPER_BASE_FROM_HUGGING_FACE.files.associateBy { it.path }
        for (file in WhisperCppModel.WHISPER_BASE_FROM_HUGGING_FACE.files.filter { it.path.endsWith(".json") }) {
            val pinned = ios.getValue(file.path)
            assertEquals(file.path, pinned.sha256, file.sha256)
            assertEquals(file.path, pinned.byteCount, file.byteCount)
            assertEquals(file.path, pinned.url, file.url)
        }
    }

    @Test
    fun dotprodOnlyWhenEveryCoreHasIt() {
        val a55 = setOf("fp", "asimd", "aes", "pmull", "sha1", "sha2", "crc32", "atomics", "fphp", "asimdhp", "asimdrdm", "lrcpc", "dcpop", "asimddp")
        val a53 = setOf("fp", "asimd", "evtstrm", "aes", "pmull", "sha1", "sha2", "crc32")
        assertEquals(NativeLibraryChoice.DOTPROD, NativeLibraryChoice.choose("arm64-v8a", listOf(a55, a55)))
        assertEquals(NativeLibraryChoice.BASELINE, NativeLibraryChoice.choose("arm64-v8a", listOf(a53, a53)))
        assertEquals(NativeLibraryChoice.BASELINE, NativeLibraryChoice.choose("arm64-v8a", listOf(a55, a53)))
        assertEquals(NativeLibraryChoice.BASELINE, NativeLibraryChoice.choose("arm64-v8a", emptyList()))
        assertEquals(NativeLibraryChoice.BASELINE, NativeLibraryChoice.choose("x86_64", listOf(a55)))
    }

    @Test
    fun readsFeaturesFromCpuinfo() {
        val cpuinfo = "processor\t: 0\nBogoMIPS\t: 38.40\nFeatures\t: fp asimd evtstrm asimddp\n\nprocessor\t: 1\nFeatures\t: fp asimd\n"
        assertEquals(listOf(setOf("fp", "asimd", "evtstrm", "asimddp"), setOf("fp", "asimd")), NativeLibraryChoice.parseFeatures(cpuinfo))
        // x86's cpuinfo says "flags", so nothing is found and the baseline build loads.
        assertEquals(emptyList<Set<String>>(), NativeLibraryChoice.parseFeatures("processor\t: 0\nflags\t\t: fpu sse avx2\n"))
    }

    @Test
    fun decodesUtf8WithOneReplacementPerIllFormedPart() {
        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
        assertEquals("ț", WhisperBpeTokenizer.decodeUtf8(bytes(0xC8, 0x9B)))
        assertEquals("�", WhisperBpeTokenizer.decodeUtf8(bytes(0xC8)))
        assertEquals("�", WhisperBpeTokenizer.decodeUtf8(bytes(0x9B)))
        assertEquals("�a", WhisperBpeTokenizer.decodeUtf8(bytes(0xC8, 0x61)))
        // A truncated three-byte sequence is one maximal part.
        assertEquals("�", WhisperBpeTokenizer.decodeUtf8(bytes(0xE2, 0x80)))
        assertEquals("��", WhisperBpeTokenizer.decodeUtf8(bytes(0xE2, 0x80, 0xE2)))
        // Overlong and surrogate encodings are not characters.
        assertEquals("��", WhisperBpeTokenizer.decodeUtf8(bytes(0xC0, 0xAF)))
        assertEquals("���", WhisperBpeTokenizer.decodeUtf8(bytes(0xED, 0xA0, 0x80)))
        assertEquals("😀", WhisperBpeTokenizer.decodeUtf8(bytes(0xF0, 0x9F, 0x98, 0x80)))
    }
}
