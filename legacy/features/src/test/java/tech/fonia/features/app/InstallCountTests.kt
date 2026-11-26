package tech.fonia.features.app

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.features.IS_DEBUG_BUILD
import java.io.IOException
import java.util.UUID

/**
 * The install code (iOS `InstallCountTests`): sent once, a day after the first profile, only while the switch is on,
 * and quietly tried again after a failure.
 */
class InstallCountTests {
    private val start = 1_795_000_000_000L
    private val dayLater = start + InstallCount.WAIT_MILLIS

    private fun preferences() = FoniaPreferences(MemoryPreferences())

    /** Answers every request with [status] (null: no network) and keeps what was asked. */
    private class Server(private val status: Int?) : InstallCount.Send {
        val requests = ArrayList<InstallCount.Request>()

        override fun send(request: InstallCount.Request): Int {
            requests += request
            return status ?: throw IOException("no network")
        }
    }

    @Test
    fun nothingGoesBeforeAProfileNorInTheFirstDayAfterIt() = runBlocking {
        val preferences = preferences()
        val server = Server(201)
        assertFalse(InstallCount.sendIfDue(hasProfiles = false, preferences = preferences, nowMillis = start, send = server))
        assertFalse(preferences.contains(InstallCount.SINCE_KEY))
        assertFalse(InstallCount.sendIfDue(hasProfiles = true, preferences = preferences, nowMillis = start, send = server))
        assertFalse(InstallCount.sendIfDue(hasProfiles = true, preferences = preferences, nowMillis = dayLater - 60_000, send = server))
        assertTrue(server.requests.isEmpty())
        assertFalse(preferences.contains(InstallCount.ID_KEY))
        assertEquals(start, preferences.long(InstallCount.SINCE_KEY))
    }

    @Test
    fun aDayAfterTheFirstProfileTheCodeGoesOnceAndNeverAgain() = runBlocking {
        val preferences = preferences()
        val server = Server(201)
        InstallCount.sendIfDue(hasProfiles = true, preferences = preferences, nowMillis = start, send = server)
        assertTrue(InstallCount.sendIfDue(hasProfiles = true, preferences = preferences, nowMillis = dayLater, send = server))
        assertFalse(InstallCount.sendIfDue(hasProfiles = true, preferences = preferences, nowMillis = dayLater + 3_600_000, send = server))
        assertEquals(1, server.requests.size)
        assertTrue(InstallCount.wasSent(preferences))
        assertEquals(InstallCount.Step.Sent, InstallCount.step(hasProfiles = true, nowMillis = dayLater, preferences = preferences))
    }

    @Test
    fun theRequestCarriesTheCodeAndThePlatformNothingElse() {
        val request = InstallCount.request(UUID.fromString("7B0E3C5A-1D2F-4A6B-9C8D-0E1F2A3B4C5D"))
        assertEquals("POST", request.method)
        assertEquals("https://pqhwircymgqvxtamvjno.supabase.co/rest/v1/installs", request.url)
        assertTrue(request.headers.getValue("apikey").startsWith("sb_publishable_"))
        assertFalse(request.headers.keys.any { it.equals("Authorization", ignoreCase = true) })
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("return=minimal", request.headers["Prefer"])
        // Android's own User-Agent names the phone's model and its Android version; the languages are the phone's too.
        assertEquals("Fonia", request.headers["User-Agent"])
        assertEquals("ro", request.headers["Accept-Language"])
        assertEquals(setOf("apikey", "Content-Type", "Prefer", "User-Agent", "Accept-Language"), request.headers.keys)
        assertEquals("""{"id":"7b0e3c5a-1d2f-4a6b-9c8d-0e1f2a3b4c5d","platform":"android"}""", request.body)
    }

    @Test
    fun noSecretKeyShipsInTheApp() {
        assertTrue(InstallCount.PUBLISHABLE_KEY.startsWith("sb_publishable_"))
        assertFalse(InstallCount.PUBLISHABLE_KEY.contains("service_role"))
        assertFalse(InstallCount.PUBLISHABLE_KEY.startsWith("sb_secret_"))
    }

    @Test
    fun theCodeStaysTheSameFromOneTryToTheNext() = runBlocking {
        val preferences = preferences()
        val server = Server(503)
        InstallCount.sendIfDue(hasProfiles = true, preferences = preferences, nowMillis = start, send = server)
        InstallCount.sendIfDue(hasProfiles = true, preferences = preferences, nowMillis = dayLater, send = server)
        InstallCount.sendIfDue(hasProfiles = true, preferences = preferences, nowMillis = dayLater + 60_000, send = server)
        assertEquals(2, server.requests.size)
        assertEquals(1, server.requests.map { it.body }.toSet().size)
        assertTrue(server.requests[0].body.contains(preferences.string(InstallCount.ID_KEY)))
    }

    @Test
    fun offlineOrAServerErrorLeavesItForALaterLaunch() = runBlocking {
        for (status in listOf(null, 400, 401, 500, 503)) {
            val preferences = preferences()
            InstallCount.sendIfDue(hasProfiles = true, preferences = preferences, nowMillis = start, send = Server(status))
            assertFalse("$status", InstallCount.sendIfDue(hasProfiles = true, preferences = preferences, nowMillis = dayLater, send = Server(status)))
            assertFalse("$status", InstallCount.wasSent(preferences))
            assertEquals("$status", InstallCount.Step.Due, InstallCount.step(hasProfiles = true, nowMillis = dayLater, preferences = preferences))
            // The next launch gets through.
            assertTrue("$status", InstallCount.sendIfDue(hasProfiles = true, preferences = preferences, nowMillis = dayLater + 60_000, send = Server(201)))
        }
    }

    @Test
    fun aWipeWhileTheRequestIsOutDoesNotMarkTheNextInstallAsSent() = runBlocking {
        val preferences = preferences()
        InstallCount.sendIfDue(hasProfiles = true, preferences = preferences, nowMillis = start, send = Server(201))
        var asked = 0
        // "Șterge tot" lands while the answer is on its way, then the server says the row is in.
        val wipedMeanwhile = InstallCount.Send {
            asked++
            assertTrue(preferences.clear())
            201
        }
        assertFalse(InstallCount.sendIfDue(hasProfiles = true, preferences = preferences, nowMillis = dayLater, send = wipedMeanwhile))
        assertEquals(1, asked)
        assertFalse(InstallCount.wasSent(preferences))
        assertFalse(preferences.contains(InstallCount.ID_KEY))
        assertFalse(preferences.contains(InstallCount.SENT_KEY))
    }

    @Test
    fun debugBuildsNeverSend() {
        assertEquals(!IS_DEBUG_BUILD, InstallCount.IS_ENABLED)
        // The JVM tests run on the debug variant.
        assertTrue(IS_DEBUG_BUILD)
        assertFalse(InstallCount.IS_ENABLED)
    }

    @Test
    fun aCodeAlreadyInTheTableCountsAsSent() = runBlocking {
        val preferences = preferences()
        val server = Server(409)
        InstallCount.sendIfDue(hasProfiles = true, preferences = preferences, nowMillis = start, send = server)
        assertTrue(InstallCount.sendIfDue(hasProfiles = true, preferences = preferences, nowMillis = dayLater, send = server))
        assertTrue(InstallCount.wasSent(preferences))
    }

    @Test
    fun switchedOffNothingIsMadeAndNothingGoes() = runBlocking {
        val preferences = preferences()
        preferences.setBool(InstallCount.ON_KEY, false)
        val server = Server(201)
        InstallCount.sendIfDue(hasProfiles = true, preferences = preferences, nowMillis = start, send = server)
        assertFalse(InstallCount.sendIfDue(hasProfiles = true, preferences = preferences, nowMillis = dayLater, send = server))
        assertTrue(server.requests.isEmpty())
        assertFalse(preferences.contains(InstallCount.ID_KEY))
        assertEquals(InstallCount.Step.Off, InstallCount.step(hasProfiles = true, nowMillis = dayLater, preferences = preferences))
    }

    @Test
    fun onUntilAnAdultSwitchesItOff() {
        assertTrue(InstallCount.isOn(preferences()))
        assertTrue(InstallCount.DEFAULT_ON)
    }

    @Test
    fun wipingClearsTheCodeTheWaitAndTheSwitch() = runBlocking {
        val preferences = preferences()
        InstallCount.sendIfDue(hasProfiles = true, preferences = preferences, nowMillis = start, send = Server(201))
        InstallCount.sendIfDue(hasProfiles = true, preferences = preferences, nowMillis = dayLater, send = Server(201))
        preferences.setBool(InstallCount.ON_KEY, false)
        for (key in listOf(InstallCount.ID_KEY, InstallCount.SINCE_KEY, InstallCount.SENT_KEY, InstallCount.ON_KEY)) assertTrue(key, preferences.contains(key))
        // "Șterge tot" empties the settings file (LocalData.clearSettings), which is the one these keys are in.
        assertTrue(preferences.clear())
        for (key in listOf(InstallCount.ID_KEY, InstallCount.SINCE_KEY, InstallCount.SENT_KEY, InstallCount.ON_KEY)) assertFalse(key, preferences.contains(key))
        // A new install: on again, waiting again, and a new code when it goes.
        assertTrue(InstallCount.isOn(preferences))
        assertEquals(InstallCount.Step.Waiting, InstallCount.step(hasProfiles = true, nowMillis = dayLater, preferences = preferences))
    }

    @Test
    fun theKeysAreInTheSettingsFileTheWipeEmpties() {
        assertEquals("fonia", FoniaPreferences.FILE)
        for (key in listOf(InstallCount.ID_KEY, InstallCount.SINCE_KEY, InstallCount.SENT_KEY, InstallCount.ON_KEY)) {
            assertTrue(key, key.startsWith("fonia.install."))
        }
        assertNull(MemoryPreferences().getString(InstallCount.ID_KEY, null))
    }
}

/** A `SharedPreferences` in memory, for the JVM tests: writes land at once, as `apply()` does for readers in the app. */
internal class MemoryPreferences : SharedPreferences {
    private val values = LinkedHashMap<String, Any?>()
    private val listeners = LinkedHashSet<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> = values.toMap()
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = values[key] as? Set<String> ?: defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = key in values
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners += listener
    }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners -= listener
    }

    private inner class Editor : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>()
        private var clearing = false

        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { clearing = true }

        override fun commit(): Boolean {
            if (clearing) values.clear()
            for ((key, value) in changes) if (value == null) values.remove(key) else values[key] = value
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
