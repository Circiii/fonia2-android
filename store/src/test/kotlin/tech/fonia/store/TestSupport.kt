package tech.fonia.store

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * An in-memory database and a throwaway recordings folder for one test.
 *
 * Swift removes the folder in `deinit`; here the test closes the store with `use`.
 */
internal class TestStore(retention: RecordingRetentionPolicy = RecordingRetentionPolicy.default) : AutoCloseable {
    val folder: File = temporaryFolder("fonia-tests")
    val database: FoniaDatabase = FoniaDatabase.inMemory(recordingsDirectory = folder)
    val repository: FoniaRepository = FoniaRepository(database, retention)

    override fun close() {
        database.close()
        folder.deleteRecursively()
    }

    suspend fun addChild(name: String? = null): Profile {
        val profile = Profile.fixtureChild(name = name)
        repository.saveProfile(profile)
        return profile
    }

    suspend fun addSession(profile: Profile, at: Instant = bucharest(2026, 11, 4, 10), seed: Long = 7): SessionRecord {
        val session = SessionRecord(
            profileId = profile.id,
            track = Profile.Track.Ro,
            sound = "s",
            startedAt = at,
            seed = seed,
            timeZone = Bucharest,
        )
        repository.addSession(session)
        return session
    }

    /** Writes a small file for a take and registers it. */
    suspend fun addTake(
        profile: Profile,
        sound: String? = "s",
        at: Instant = bucharest(2026, 11, 4, 10),
        pinned: Boolean = false,
        now: Instant? = null,
    ): Recording {
        val recording = Recording(profileId = profile.id, ts = at, label = "take", sound = sound, duration = 1.5, pinned = pinned)
        val file = repository.recordingFileURL(recording)
        file.writeBytes(byteArrayOf(1, 2, 3))
        repository.addRecording(recording, now = now ?: at)
        return recording
    }

    fun fileExists(recording: Recording): Boolean = database.files.url(recording.fileName).exists()
}

internal fun Profile.Companion.fixtureChild(name: String? = null): Profile = Profile(
    name = name,
    kind = Profile.Kind.Child,
    ageBand = Profile.AgeBand.SixToSeven,
    voiceGroup = Profile.VoiceGroup.Child,
    color = "sky",
    // Whole seconds, because the database keeps dates to the millisecond.
    createdAt = bucharest(2026, 11, 1, 9),
    track = Profile.Track.Ro,
)

internal val Bucharest: ZoneId = ZoneId.of("Europe/Bucharest")

/** A moment given in Bucharest local time. */
internal fun bucharest(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Instant =
    ZonedDateTime.of(year, month, day, hour, minute, 0, 0, Bucharest).toInstant()

/** `FileManager.default.temporaryDirectory.appending(path: "<prefix>-<UUID>")`, not created yet. */
internal fun temporaryFolder(prefix: String): File =
    File(System.getProperty("java.io.tmpdir"), "$prefix-${UUID.randomUUID().uuidString}")

/**
 * `stream.makeAsyncIterator()`: collects the flow in [scope]; each `receive()` is a `next()`.
 * The channel keeps only the newest value, as the `AsyncStream` with `bufferingNewest(1)` does.
 */
internal fun <T> Flow<T>.iterator(scope: CoroutineScope): ReceiveChannel<T> {
    val channel = Channel<T>(Channel.CONFLATED)
    scope.launch {
        collect { channel.send(it) }
        channel.close()
    }
    return channel
}
