package tech.fonia.features.session

import android.content.Context
import tech.fonia.domain.SoundDef
import tech.fonia.domain.SoundFamily
import tech.fonia.dsp.VoiceGroup
import tech.fonia.features.app.dspGroup
import tech.fonia.judge.TalkerGroup
import tech.fonia.judge.TalkerTracker
import tech.fonia.store.Profile
import java.util.UUID

/**
 * Who is really speaking for each profile, heard from the voice (speech/…/TalkerVoice.kt), remembered between
 * sessions. The profile's own voice group is only the starting guess: a teenager with a man's voice who picked
 * "Un copil" is judged as a man after his first voiced take. Thread-safe (the judge runs off the main thread).
 */
class TalkerMemory(private val read: (String) -> String?, private val write: (String, String) -> Unit) {
    private val trackers = HashMap<UUID, TalkerTracker>()

    fun tracker(profile: Profile): TalkerTracker = synchronized(trackers) {
        trackers.getOrPut(profile.id) { TalkerTracker.decode(read(key(profile.id)), SoundJudge.talker(profile.dspGroup)) }
    }

    /** The group the live analysis and the judges use for [profile] now. */
    fun group(profile: Profile): VoiceGroup = synchronized(trackers) { tracker(profile).group.voice }

    fun talker(profile: Profile): TalkerGroup = synchronized(trackers) { tracker(profile).group }

    /** True once a voiced take of [profile] has been heard (ever, on this phone). */
    fun heard(profile: Profile): Boolean = synchronized(trackers) { tracker(profile).heard }

    /** Feeds one take's median F0 (NaN or 0: no voice, nothing changes) and remembers the result. */
    fun observe(profile: Profile, f0Hz: Float): TalkerGroup = synchronized(trackers) {
        val t = tracker(profile)
        val before = t.encode()
        val group = t.observe(f0Hz)
        if (t.encode() != before) remember(profile)
        group
    }

    /** After [SoundJudge.judge] moved the tracker itself. */
    fun remember(profile: Profile) {
        val text = synchronized(trackers) { tracker(profile).encode() }
        runCatching { write(key(profile.id), text) }
    }

    /**
     * The group the held-sound exercise listens with. Once the voice is known, that group. Before any voiced take a
     * hiss says nothing about who makes it (a man's S sits where a child's Ș does), so the held bar listens with the
     * group most favourable to the target: a man's priors for S, Z, Ț (lowest boundary), a child's for Ș, J (highest).
     */
    fun holdGroup(profile: Profile, sound: SoundDef?): VoiceGroup {
        if (sound == null || heard(profile)) return group(profile)
        return if (sound.id.family == SoundFamily.WarmAir) VoiceGroup.CHILD else VoiceGroup.MALE
    }

    companion object {
        private fun key(id: UUID) = "talker.$id"

        fun inMemory(): TalkerMemory {
            val map = HashMap<String, String>()
            return TalkerMemory({ map[it] }, { k, v -> map[k] = v })
        }

        fun preferences(context: Context): TalkerMemory {
            val prefs = context.applicationContext.getSharedPreferences("fonia_talker", Context.MODE_PRIVATE)
            return TalkerMemory({ prefs.getString(it, null) }, { k, v -> prefs.edit().putString(k, v).apply() })
        }
    }
}
