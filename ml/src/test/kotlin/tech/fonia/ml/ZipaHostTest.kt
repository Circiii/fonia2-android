package tech.fonia.ml

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.judge.JudgeRequest
import tech.fonia.judge.Level
import tech.fonia.judge.Position
import tech.fonia.live.Clip
import tech.fonia.model.SoundId
import tech.fonia.phones.AcousticModel
import tech.fonia.phones.Posteriors
import tech.fonia.phones.ZipaInventory
import java.io.File
import kotlin.math.ln

/** The host's threading, laziness and timeout on a fake model (the real one runs in the instrumented test). */
class ZipaHostTest {
    private val version = ModelVersion("zipa", "test", "0", "fake")

    /** Posteriors that say "▁ s a r e" for any input, one row per 20 ms of 16 kHz audio. */
    private fun sayingSare(delayMs: Long = 0) = AcousticModel { samples ->
        if (delayMs > 0) Thread.sleep(delayMs)
        val tokens = listOf("<blk>", "▁", "s", "s", "<blk>", "a", "r", "e", "<blk>")
        val frames = maxOf(tokens.size, samples.size / 320)
        val values = FloatArray(frames * ZipaInventory.SIZE) { ln(0.01f / ZipaInventory.SIZE) }
        for (t in 0 until frames) {
            val token = tokens[minOf(t * tokens.size / frames, tokens.lastIndex)]
            values[t * ZipaInventory.SIZE + ZipaInventory.id(token)] = ln(0.99f)
        }
        Posteriors(frames, ZipaInventory.SIZE, values)
    }

    private val request = JudgeRequest(SoundId.S, Level.Word, "sare", Position.Initial)
    private val clip = Clip(FloatArray(48_000) { (it % 37 - 18) * 1e-3f }, 48_000.0)

    @Test
    fun loadsLazilyAndTrims() = runBlocking {
        var loads = 0
        val host = ZipaHost(version) { loads++; sayingSare() to AutoCloseable { } }
        assertEquals(HostState.Cold, host.state.value)
        assertEquals(0, loads)
        val evidence = host.score(clip, request)
        assertNotNull(evidence)
        assertEquals(HostState.Warm, host.state.value)
        assertTrue("target beats ʃ: ${evidence!!.lpr}", evidence.lpr.getValue("ʃ") > 0f)
        assertEquals("zipa@test", evidence.model)
        host.trim()
        host.warmUp()
        assertEquals(2, loads)
        host.close()
    }

    @Test
    fun aSlowModelTimesOutAndTheCallerGetsNull() = runBlocking {
        val host = ZipaHost(version, ZipaHostConfig(timeoutMs = 50)) { sayingSare(delayMs = 400) to AutoCloseable { } }
        val t0 = System.nanoTime()
        assertNull(host.score(clip, request))
        assertTrue("returned at the timeout, not after the inference", (System.nanoTime() - t0) / 1e6 < 300)
        host.close()
    }

    @Test
    fun aFailingLoadIsReportedNotThrown() = runBlocking {
        val host = ZipaHost(version) { error("no model") }
        assertNull(host.score(clip, request))
        assertEquals(HostState.Failed, host.state.value)
        host.close()
    }

    @Test
    fun theBlockingPortTakes16kHz() {
        val host = ZipaHost(version) { sayingSare() to AutoCloseable { } }
        val evidence = host.port().score(FloatArray(16_000) { (it % 37 - 18) * 1e-3f }, request)
        assertNotNull(evidence)
        host.close()
    }

    @Test
    fun registryReadsTheCommittedManifest() {
        val registry = ModelRegistry.parse(File("../spec/models/manifest.json").readText())
        val zipa = registry.entry(ModelRegistry.ZIPA)
        assertEquals("models/zipa-small-crctc-500k/model.int8.onnx", zipa.asset)
        assertEquals(70_677_672L, zipa.bytes)
        assertEquals("CC-BY-4.0", zipa.license)
        assertTrue(zipa.attribution.contains("CC BY 4.0"))
        assertEquals("zipa@dfe87c976626;roasr@sped-110m-ctc-int8", registry.versions())
    }

    @Test
    fun memoryPolicyReleasesWhenHiddenOrCritical() {
        assertTrue(ModelMemoryPolicy.shouldTrim(20)) // UI hidden
        assertTrue(ModelMemoryPolicy.shouldTrim(40)) // background
        assertTrue(ModelMemoryPolicy.shouldTrim(15)) // running critical
        assertTrue(!ModelMemoryPolicy.shouldTrim(10)) // running low: Whisper and the face go first (plan 02 §6)
        assertTrue(!ModelMemoryPolicy.shouldTrim(5))
    }
}
