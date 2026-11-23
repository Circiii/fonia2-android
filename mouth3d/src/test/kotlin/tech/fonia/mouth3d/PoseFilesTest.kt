package tech.fonia.mouth3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.model.SoundId
import java.io.File

class PoseFilesTest {
    private val lib = TestData.library

    @Test
    fun `nine sounds and rest, each file names itself`() {
        assertEquals(PoseLibrary.FILES.toSet(), TestData.posesDir.listFiles()!!.filter { it.extension == "json" }.map { it.nameWithoutExtension }.toSet())
        SoundId.entries.forEach { assertEquals(it, lib.forSound(it).sound) }
        assertNull(lib.rest.sound)
    }

    @Test
    fun `cut-away is not approved in data until a therapist signs (N17)`() {
        lib.specs.values.forEach {
            assertFalse("${it.key} insideApproved", it.insideApproved)
            assertEquals("draft", it.reviewStatus)
            assertTrue(it.therapistNote.length > 40)
        }
        val data = Mouth3DData(lib)
        assertFalse(data.insideAvailable(SoundId.S))
        assertTrue(data.insideAvailable(SoundId.S, allowUnapproved = true))
    }

    @Test
    fun `voiced pairs share the pose of the voiceless sound`() {
        assertEquals(lib.forSound(SoundId.S).pose, lib.forSound(SoundId.Z).pose)
        assertEquals(lib.forSound(SoundId.Sh).pose, lib.forSound(SoundId.J).pose)
        assertEquals(lib.forSound(SoundId.Ce).pose, lib.forSound(SoundId.Ge).pose)
        assertEquals(lib.forSound(SoundId.Ce).animation, lib.forSound(SoundId.Ge).animation)
    }

    @Test
    fun `logopedic convention holds in the parameters`() {
        val s = lib.forSound(SoundId.S).pose
        val sh = lib.forSound(SoundId.Sh).pose
        val r = lib.forSound(SoundId.R).pose
        val l = lib.forSound(SoundId.L).pose
        assertTrue("S lips spread", s[PoseChannel.LipSpread] > 0.5f && s[PoseChannel.LipRound] == 0f)
        assertTrue("S tip down", s[PoseChannel.TongueTip] < 0f && s[PoseChannel.TongueGroove] > 0.5f)
        assertTrue("S teeth nearly closed", s[PoseChannel.JawOpen] <= 0.15f)
        assertTrue("Ș lips round", sh[PoseChannel.LipRound] > 0.7f && sh[PoseChannel.LipSpread] == 0f)
        assertTrue("Ș cupped, back", sh[PoseChannel.TongueCup] > 0.5f && sh[PoseChannel.TongueForward] < 0f && sh[PoseChannel.TongueRaise] > 0.3f)
        assertTrue("R open, tip up", r[PoseChannel.JawOpen] >= 0.4f && r[PoseChannel.TongueTip] > 0.5f)
        assertTrue("L open, tip up, sides down", l[PoseChannel.JawOpen] >= 0.4f && l[PoseChannel.TongueTip] > 0.5f && l[PoseChannel.TongueSidesDown] > 0.5f)
        assertTrue("CE lips round", lib.forSound(SoundId.Ce).pose[PoseChannel.LipRound] > 0.3f)
    }

    @Test
    fun `animations where the convention asks for them`() {
        listOf(SoundId.Ts, SoundId.Ce, SoundId.Ge).forEach { sid ->
            val a = lib.forSound(sid).animation
            assertNotNull("$sid animation", a)
            a!!
            assertEquals("$sid closes then releases", 2, a.keys.size)
            assertTrue("$sid closure raises the tip", a.keys[0].pose[PoseChannel.TongueTip] > a.keys[1].pose[PoseChannel.TongueTip])
            assertEquals("$sid releases into its pose", lib.forSound(sid).pose, a.keys[1].pose)
        }
        assertNotNull(lib.forSound(SoundId.R).flutter)
        assertEquals(PoseChannel.TongueTip, lib.forSound(SoundId.R).flutter!!.channel)
        assertNotNull(lib.forSound(SoundId.L).animation)
        listOf(SoundId.S, SoundId.Z, SoundId.Sh, SoundId.J).forEach { assertNull(lib.forSound(it).animation) }
    }

    @Test
    fun `child texts follow the product rules`() {
        val banned = listOf("greșit", "gresit", "diagnostic", "terapie", "tratează", "risc", "severitate", "tulburare", "ş", "ţ", "Ş", "Ţ")
        lib.specs.values.forEach { p ->
            listOf(p.caption, p.description).forEach { t -> banned.forEach { b -> assertFalse("${p.key}: '$b' in '$t'", t.contains(b, ignoreCase = true)) } }
            assertTrue("${p.key} caption ≤ 8 words", p.caption.trim().split(Regex("\\s+")).size <= 8)
            assertEquals("mouth3d.caption.${p.key}", p.captionKey)
        }
    }

    @Test
    fun `camera presets are front and side`() {
        lib.specs.values.forEach {
            assertEquals(0f, it.front.yawDeg, 1f)
            // the side view, turned a little toward the front so the diorama shows its depth
            assertTrue(it.key, it.inside.yawDeg in 70f..90f)
        }
    }

    @Test
    fun `every sound has its air, and the voiced ones say so`() {
        val voiced = setOf("z", "j", "ge", "r", "l")
        lib.specs.values.forEach {
            if (it.key == PoseLibrary.REST) assertEquals(null, it.air) else assertTrue(it.key, it.air != null)
            assertEquals(it.key, it.key in voiced, it.voiced)
        }
        assertEquals(AirStream.Narrow, lib.specs.getValue("s").air!!.stream)
        assertEquals(AirStream.Wide, lib.specs.getValue("sh").air!!.stream)
        assertTrue(lib.specs.getValue("ts").air!!.stop && lib.specs.getValue("ce").air!!.stop && lib.specs.getValue("ge").air!!.stop)
        assertEquals(AirStream.Lateral, lib.specs.getValue("l").air!!.stream)
        assertTrue(lib.specs.getValue("sh").air!!.warm && !lib.specs.getValue("s").air!!.warm)
    }

    @Test
    fun `guided steps are short and end with the whole pose`() {
        SoundId.entries.forEach { sid ->
            val steps = lib.forSound(sid).steps
            assertTrue("$sid has 2–3 steps", steps.size in 2..3)
            steps.forEach { st ->
                assertTrue("$sid '${st.label}' ≤ 2 words", st.label.trim().split(' ').size <= 2)
                assertTrue("$sid icon ${st.icon}", st.icon in setOf("lips", "teeth", "tongue", "air"))
            }
            val moved = steps.flatMap { it.reveals }.toSet()
            assertTrue("$sid steps reach jaw and tongue", PoseGroup.Jaw in moved && PoseGroup.Tongue in moved)
        }
        assertTrue(lib.rest.steps.isEmpty())
    }

    @Test
    fun `a group left out stays at rest`() {
        val scene = MouthScene()
        val s = lib.forSound(SoundId.S)
        val f = scene.step(0f, SceneInputs(s, lib.rest, 1f, inside = false, groups = setOf(PoseGroup.Lips)))
        assertEquals(s.pose[PoseChannel.LipSpread], f.pose[PoseChannel.LipSpread], 1e-4f)
        assertEquals(lib.rest.pose[PoseChannel.TongueTip], f.pose[PoseChannel.TongueTip], 1e-4f)
    }
}
