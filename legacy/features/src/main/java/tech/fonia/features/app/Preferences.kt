package tech.fonia.features.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * `UserDefaults.standard`: the few switches an adult sets (`Settings.effectsOff`, `Settings.keepRecordings`, the talk
 * being prepared). The app is never backed up (`allowBackup="false"`), so they stay on the phone, as on iOS.
 */
class FoniaPreferences(context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences("fonia", Context.MODE_PRIVATE)

    fun bool(key: String, default: Boolean = false): Boolean = preferences.getBoolean(key, default)

    fun setBool(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }

    fun string(key: String, default: String = ""): String = preferences.getString(key, default) ?: default

    fun setString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    fun int(key: String, default: Int = 0): Int = preferences.getInt(key, default)

    fun setInt(key: String, value: Int) {
        preferences.edit().putInt(key, value).apply()
    }

    internal fun observe(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        preferences.registerOnSharedPreferenceChangeListener(listener)

    internal fun forget(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
}

/** `@AppStorage(key) var value = default`: a state that reads and writes one stored switch, and follows other writers. */
@Composable
fun rememberStoredBoolean(preferences: FoniaPreferences?, key: String, default: Boolean = false): MutableState<Boolean> {
    val state = remember(preferences, key) { mutableStateOf(preferences?.bool(key, default) ?: default) }
    DisposableEffect(preferences, key) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == key && preferences != null) state.value = preferences.bool(key, default)
        }
        preferences?.observe(listener)
        onDispose { preferences?.forget(listener) }
    }
    return remember(state) {
        object : MutableState<Boolean> {
            override var value: Boolean
                get() = state.value
                set(value) {
                    state.value = value
                    preferences?.setBool(key, value)
                }

            override fun component1(): Boolean = value

            override fun component2(): (Boolean) -> kotlin.Unit = { value = it }
        }
    }
}

/** `@AppStorage(key) var value = default` for a string. */
@Composable
fun rememberStoredString(preferences: FoniaPreferences?, key: String, default: String = ""): MutableState<String> =
    rememberStored(preferences, key, default, read = { it.string(key, default) }, write = { prefs, value -> prefs.setString(key, value) })

/** `@AppStorage(key) var value = default` for a whole number. */
@Composable
fun rememberStoredInt(preferences: FoniaPreferences?, key: String, default: Int = 0): MutableState<Int> =
    rememberStored(preferences, key, default, read = { it.int(key, default) }, write = { prefs, value -> prefs.setInt(key, value) })

@Composable
private fun <T> rememberStored(
    preferences: FoniaPreferences?,
    key: String,
    default: T,
    read: (FoniaPreferences) -> T,
    write: (FoniaPreferences, T) -> kotlin.Unit,
): MutableState<T> {
    val state = remember(preferences, key) { mutableStateOf(preferences?.let(read) ?: default) }
    DisposableEffect(preferences, key) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == key && preferences != null) state.value = read(preferences)
        }
        preferences?.observe(listener)
        onDispose { preferences?.forget(listener) }
    }
    return remember(state) {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    state.value = value
                    preferences?.let { write(it, value) }
                }

            override fun component1(): T = value

            override fun component2(): (T) -> kotlin.Unit = { value = it }
        }
    }
}
