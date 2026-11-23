package tech.fonia.app.judge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.app.WavFixtures
import tech.fonia.model.Rung
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict
import java.io.File

/**
 * The real judge (spec as committed) on the fixture WAVs, child and adult talker settings. Prints the table the
 * report quotes; asserts only the policy (nothing in `spec/unlock.json` is unlocked, so no error is ever named to the
 * child) and the obvious (an S word judged as S by an adult talker is not an error).
 */
class RealJudgeFixturesTest {
    private val root = File(System.getProperty("fonia.root") ?: "..")
    private val judge = RealJudge(CoroutineScope(Dispatchers.IO)) { RealJudge.fromDir(File(root, "spec")) }

    private fun run(fixture: String, sound: SoundId, rung: Rung, text: String, talker: Talker): Judged = runBlocking {
        judge.judge(TakeRequest(sound, rung, text, JudgeContext(talker = talker)), WavFixtures.clip(fixture))
    }

    private fun describe(j: Judged): String {
        val v = j.verdict
        val core = when (v) {
            is Verdict.NamedError -> "NamedError(${v.kind.raw}, heard ${v.heardAs?.raw})"
            else -> v.key
        }
        val conf = (j.detail["confidence"] as? tech.fonia.store.JSONValue.Number)?.value
        val reason = (j.detail["retakeReason"] as? tech.fonia.store.JSONValue.String)?.value
        val adult = j.adult?.let { " | adult: ${it.kind.raw}${if (it.inTrial) " în probă" else ""} heard ${it.heardAs?.raw} p=${"%.2f".format(it.probability)}" } ?: ""
        val notes = (j.detail["notes"] as? tech.fonia.store.JSONValue.Array)?.value?.mapNotNull { (it as? tech.fonia.store.JSONValue.String)?.value }?.filter { it.startsWith("duration") }.orEmpty()
        return "$core${reason?.let { " ($it${notes.firstOrNull()?.let { n -> ": $n" } ?: ""})" } ?: ""} conf=${conf?.let { "%.2f".format(it) }}$adult"
    }

    @Test fun fixtureTable() {
        val cases = listOf(
            Triple("s_hold", SoundId.S, Rung.Isolated) to "S",
            Triple("s_hold", SoundId.Sh, Rung.Isolated) to "Ș",
            Triple("sh_hold", SoundId.Sh, Rung.Isolated) to "Ș",
            Triple("sh_hold", SoundId.S, Rung.Isolated) to "S",
            Triple("sarah_s", SoundId.S, Rung.WordsInitial) to "sare",
            Triple("sarah_s", SoundId.Sh, Rung.WordsInitial) to "șarpe",
            Triple("sarah_sh", SoundId.Sh, Rung.WordsInitial) to "șarpe",
            Triple("sarah_sh", SoundId.S, Rung.WordsInitial) to "sare",
        )
        val out = StringBuilder("| fixture | target | text | child talker | adult talker (high) |\n|---|---|---|---|---|\n")
        for ((c, text) in cases) {
            val (fx, sound, rung) = c
            val child = run(fx, sound, rung, text, Talker.Child)
            val adult = run(fx, sound, rung, text, Talker.AdultHigh)
            out.append("| $fx | ${sound.letter} | $text | ${describe(child)} | ${describe(adult)} |\n")
            // the shipped policy: nothing is unlocked, so the child never gets a named error
            assertTrue(child.verdict !is Verdict.NamedError && adult.verdict !is Verdict.NamedError)
        }
        println(out)
        File(root, "app/build/fixture-verdicts.md").apply { parentFile.mkdirs() }.writeText(out.toString())
        assertNotEquals(Verdict.Retake, run("sarah_s", SoundId.S, Rung.WordsInitial, "sare", Talker.AdultHigh).verdict)
    }

    @Test fun traceCarriesVersionsAndFeatures() {
        val j = run("sarah_s", SoundId.S, Rung.WordsInitial, "sare", Talker.AdultHigh)
        val versions = j.detail["versions"] as tech.fonia.store.JSONValue.Object
        assertEquals("2026-11-21.v0", (versions.value["unlock"] as tech.fonia.store.JSONValue.String).value)
        assertTrue((j.detail["features"] as tech.fonia.store.JSONValue.Object).value.isNotEmpty())
    }
}
