package tech.fonia.collector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NamingTest {
    @Test
    fun clipPathMatchesTheSpecExample() {
        assertEquals(
            "r01/A07/s/s_w_ini_sare__Interdental__t1.wav",
            Naming.clipPath("r01", "A07", "s", "s_w_ini_sare", "Interdental", 1),
        )
        assertEquals("r01/C13/sh/sh_w_med_masina__correct__t2.wav", Naming.clipPath("r01", "C13", "sh", "sh_w_med_masina", "correct", 2))
        assertEquals("r02/T02/room/room__t3.wav", Naming.roomPath("r02", "T02", 3))
    }

    @Test
    fun slugFoldsRomanianLetters() {
        assertEquals("masina", Naming.slug("mașină"))
        assertEquals("tantar", Naming.slug("Țânțar"))
        assertEquals("inger", Naming.slug("înger"))
        assertEquals("ts_w_fin_calut", Naming.promptId("ts", Level.Word, Position.Final, "căluț"))
        assertEquals("ce_syl_cea", Naming.promptId("ce", Level.Syllable, null, "cea"))
    }

    @Test
    fun speakerCodesGiveTheRole() {
        assertEquals(Role.Adult, Naming.roleOf("A07"))
        assertEquals(Role.Therapist, Naming.roleOf("T02"))
        assertEquals(Role.Child, Naming.roleOf("C13"))
        assertNull(Naming.roleOf("X01"))
        assertNull(Naming.roleOf("A7"))
        assertNull(Naming.roleOf("Ana"))
    }

    @Test
    fun ageBandsPerRole() {
        assertEquals(listOf("4-5", "6-7", "8-10", "11-17"), Naming.ageBandsFor(Role.Child))
        assertEquals(listOf("adult"), Naming.ageBandsFor(Role.Therapist))
    }
}
