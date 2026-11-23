package tech.fonia.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tech.fonia.app.judge.Talker
import tech.fonia.model.SoundId

/** Who practises (ON-0): a child with an adult beside them (kid shell), or "Doar eu" (a teen or adult). */
enum class Household(val raw: String) {
    ChildWithAdult("childWithAdult"),
    Self("self"),
    ;

    companion object {
        fun of(raw: String?): Household = entries.firstOrNull { it.raw == raw } ?: ChildWithAdult
    }
}

/** Small key-value settings (onboarding state, talker group, reduce motion). Local only; wiped by „Șterge tot”. */
interface AppPrefs {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun clear()
}

/** Android's SharedPreferences (the app has `allowBackup="false"`: nothing goes to a cloud backup). */
class SharedAppPrefs(context: Context) : AppPrefs {
    private val sp = context.applicationContext.getSharedPreferences("fonia_settings", Context.MODE_PRIVATE)
    override fun getString(key: String): String? = sp.getString(key, null)
    override fun putString(key: String, value: String?) {
        sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
    override fun clear() {
        sp.edit().clear().commit()
    }
}

/** For tests and the demo. */
class MemoryPrefs : AppPrefs {
    private val map = HashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
    override fun clear() = map.clear()
}

/** The settings as Compose state, written through to [AppPrefs]. */
class AppSettings(private val prefs: AppPrefs) {
    var onboarded by mutableStateOf(prefs.getString(K_ONBOARDED) == "1"); private set
    var household by mutableStateOf(Household.of(prefs.getString(K_HOUSEHOLD))); private set
    var talker by mutableStateOf(Talker.of(prefs.getString(K_TALKER))); private set
    var reduceMotion by mutableStateOf(prefs.getString(K_REDUCE) == "1"); private set
    var chosenSound by mutableStateOf(prefs.getString(K_SOUND)?.let(SoundId::of)); private set
    /** MINUTE's step on the 7-topic ladder (0-based). */
    var minuteRung by mutableStateOf(prefs.getString(K_MINUTE)?.toIntOrNull() ?: 0); private set

    fun finishOnboarding(household: Household, talker: Talker, sound: SoundId?) {
        updateHousehold(household)
        updateTalker(talker)
        updateSound(sound)
        onboarded = true
        prefs.putString(K_ONBOARDED, "1")
    }

    fun updateHousehold(h: Household) {
        household = h
        prefs.putString(K_HOUSEHOLD, h.raw)
    }

    fun updateTalker(t: Talker) {
        talker = t
        prefs.putString(K_TALKER, t.raw)
    }

    fun updateReduceMotion(on: Boolean) {
        reduceMotion = on
        prefs.putString(K_REDUCE, if (on) "1" else null)
    }

    fun updateSound(s: SoundId?) {
        chosenSound = s
        prefs.putString(K_SOUND, s?.raw)
    }

    fun updateMinuteRung(rung: Int) {
        minuteRung = rung.coerceAtLeast(0)
        prefs.putString(K_MINUTE, minuteRung.toString())
    }

    /** „Șterge tot”: back to the first start. */
    fun clear() {
        prefs.clear()
        onboarded = false
        household = Household.ChildWithAdult
        talker = Talker.Child
        reduceMotion = false
        chosenSound = null
        minuteRung = 0
    }

    private companion object {
        const val K_ONBOARDED = "onboarded"
        const val K_HOUSEHOLD = "household"
        const val K_TALKER = "talker"
        const val K_REDUCE = "reduceMotion"
        const val K_SOUND = "chosenSound"
        const val K_MINUTE = "minuteRung"
    }
}
