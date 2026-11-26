package tech.fonia.features.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import tech.fonia.features.IS_DEBUG_BUILD

/**
 * The one thing Fonia sends about itself (iOS `InstallCount`): a code made at random on the phone, once, so we know
 * roughly how many installs there are. It belongs to no profile and goes with nothing but the word "android".
 *
 * It waits a day after the first profile is made, so an adult who reads the privacy text can switch it off in the
 * settings before anything leaves. A failure shows nothing and is tried again at a later launch or return to the app.
 * The keys live in the settings file ([FoniaPreferences.FILE]), so "Șterge tot" clears them with the other settings
 * and the phone starts over as a new install.
 */
object InstallCount {
    const val ID_KEY = "fonia.install.id"
    const val SINCE_KEY = "fonia.install.since"
    const val SENT_KEY = "fonia.install.sent"
    const val ON_KEY = "fonia.install.countOn"

    /**
     * The `installs` table of the Supabase project in Frankfurt. The publishable key may ship in the app: with it the
     * table only takes a new row of an id and a platform. Nobody holding it can read, change or delete a row.
     */
    const val ENDPOINT = "https://pqhwircymgqvxtamvjno.supabase.co/rest/v1/installs"
    const val PUBLISHABLE_KEY = "sb_publishable_LtISz9yZ_jVHN1B7W5bkhA_sHXSBr4P"
    const val PLATFORM = "android"

    /** How long after the first profile the code waits. */
    const val WAIT_MILLIS: Long = 24L * 60 * 60 * 1000

    /** On until an adult switches it off. */
    const val DEFAULT_ON = true

    /**
     * Debug builds never send, so emulators and the developer's phones stay out of the count. A constant rather than
     * a check around the call, so debug builds compile the same trigger as the release.
     */
    const val IS_ENABLED: Boolean = !IS_DEBUG_BUILD

    enum class Step {
        /** The code has gone; nothing more is sent. */
        Sent,

        /** An adult switched it off before it went. */
        Off,

        /** No profile yet, or the first profile is less than a day old. */
        Waiting,
        Due,
    }

    /** What goes over the wire, kept apart from the connection so the tests can read it. */
    data class Request(val url: String, val method: String, val headers: Map<String, String>, val body: String)

    /** Sends [Request] and answers with the HTTP status; throws when there is no answer at all (no network). */
    fun interface Send {
        fun send(request: Request): Int
    }

    fun isOn(preferences: FoniaPreferences): Boolean = preferences.bool(ON_KEY, DEFAULT_ON)

    fun wasSent(preferences: FoniaPreferences): Boolean = preferences.bool(SENT_KEY)

    /** Where this phone stands. The first look with a profile on the phone starts the day's wait. */
    fun step(hasProfiles: Boolean, nowMillis: Long, preferences: FoniaPreferences): Step {
        if (wasSent(preferences)) return Step.Sent
        if (!isOn(preferences)) return Step.Off
        if (!hasProfiles) return Step.Waiting
        if (!preferences.contains(SINCE_KEY)) {
            preferences.setLong(SINCE_KEY, nowMillis)
            return Step.Waiting
        }
        return if (nowMillis - preferences.long(SINCE_KEY) >= WAIT_MILLIS) Step.Due else Step.Waiting
    }

    /** The random code, made the first time it is about to be sent. */
    fun installID(preferences: FoniaPreferences): UUID {
        preferences.string(ID_KEY).takeIf { it.isNotEmpty() }?.let { text -> runCatching { UUID.fromString(text) }.getOrNull()?.let { return it } }
        val id = UUID.randomUUID()
        preferences.setString(ID_KEY, id.toString())
        return id
    }

    /**
     * A POST of the code and the platform, nothing else: no Authorization header, and a fixed User-Agent and
     * Accept-Language, so neither the phone's model, its Android version nor its languages go along.
     */
    fun request(id: UUID): Request = Request(
        url = ENDPOINT,
        method = "POST",
        headers = linkedMapOf(
            "apikey" to PUBLISHABLE_KEY,
            "Content-Type" to "application/json",
            "Prefer" to "return=minimal",
            "User-Agent" to "Fonia",
            "Accept-Language" to "ro",
        ),
        // A UUID and a constant: nothing in them needs escaping.
        body = """{"id":"${id.toString().lowercase()}","platform":"$PLATFORM"}""",
    )

    /**
     * 201 is a new row. 409 means the code is in the table already: an earlier try got through and its answer never
     * reached the phone. Both mean done.
     */
    fun isDone(status: Int): Boolean = status == 201 || status == 409

    private val sending = AtomicBoolean(false)

    /**
     * Sends the code once it is due. Returns whether it has now gone. A launch and a return to the app can ask at the
     * same moment; the second one waits for the next chance instead of sending the code twice.
     */
    suspend fun sendIfDue(
        hasProfiles: Boolean,
        preferences: FoniaPreferences,
        nowMillis: Long = System.currentTimeMillis(),
        send: Send = Http,
    ): Boolean {
        if (step(hasProfiles, nowMillis, preferences) != Step.Due) return false
        if (!sending.compareAndSet(false, true)) return false
        try {
            val id = installID(preferences)
            val request = request(id)
            val status = try {
                withContext(Dispatchers.IO) { send.send(request) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return false
            }
            if (!isDone(status)) return false
            // "Șterge tot" while the request was out: the code is gone, and the next install counts anew.
            if (preferences.string(ID_KEY) != id.toString()) return false
            preferences.setBool(SENT_KEY, true)
            return true
        } finally {
            sending.set(false)
        }
    }

    /** No cache, no cookies (Fonia installs no CookieHandler), no redirects, and a short wait for a missing network. */
    val Http = Send { request ->
        val bytes = request.body.toByteArray(Charsets.UTF_8)
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            for ((name, value) in request.headers) connection.setRequestProperty(name, value)
            connection.outputStream.use { it.write(bytes) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }
}
