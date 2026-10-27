package app.hypnotica.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * What survives a restart.
 *
 * Small and boring on purpose: favourites and the handful of choices a person
 * would be annoyed to set twice. Sim parameters are not kept, because every run
 * is meant to start from the sim's own tuned defaults.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("hypnotica", Context.MODE_PRIVATE)

    var favourites: Set<String>
        get() = sp.getStringSet(KEY_FAVOURITES, emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_FAVOURITES, value).apply()

    var paletteName: String
        get() = sp.getString(KEY_PALETTE, "Spectrum") ?: "Spectrum"
        set(value) = sp.edit().putString(KEY_PALETTE, value).apply()

    var soundPreset: String
        get() = sp.getString(KEY_SOUND, "Music box") ?: "Music box"
        set(value) = sp.edit().putString(KEY_SOUND, value).apply()

    var volume: Float
        get() = sp.getFloat(KEY_VOLUME, 0.8f)
        set(value) = sp.edit().putFloat(KEY_VOLUME, value).apply()

    var reduceFlashing: Boolean
        get() = sp.getBoolean(KEY_REDUCE_FLASH, false)
        set(value) = sp.edit().putBoolean(KEY_REDUCE_FLASH, value).apply()

    var reduceMotion: Boolean
        get() = sp.getBoolean(KEY_REDUCE_MOTION, false)
        set(value) = sp.edit().putBoolean(KEY_REDUCE_MOTION, value).apply()

    var haptics: Boolean
        get() = sp.getBoolean(KEY_HAPTICS, true)
        set(value) = sp.edit().putBoolean(KEY_HAPTICS, value).apply()

    var showCounters: Boolean
        get() = sp.getBoolean(KEY_COUNTERS, true)
        set(value) = sp.edit().putBoolean(KEY_COUNTERS, value).apply()

    var timeCheckMinutes: Int
        get() = sp.getInt(KEY_TIME_CHECK, 0)
        set(value) = sp.edit().putInt(KEY_TIME_CHECK, value).apply()

    /** The sim the person was last watching, so the app picks up where it left off. */
    var lastSimId: String?
        get() = sp.getString(KEY_LAST_SIM, null)
        set(value) = sp.edit().putString(KEY_LAST_SIM, value).apply()

    var seenFirstLaunch: Boolean
        get() = sp.getBoolean(KEY_FIRST_LAUNCH, false)
        set(value) = sp.edit().putBoolean(KEY_FIRST_LAUNCH, value).apply()

    private companion object {
        const val KEY_FAVOURITES = "favourites"
        const val KEY_PALETTE = "palette"
        const val KEY_SOUND = "sound"
        const val KEY_VOLUME = "volume"
        const val KEY_REDUCE_FLASH = "reduce_flashing"
        const val KEY_REDUCE_MOTION = "reduce_motion"
        const val KEY_HAPTICS = "haptics"
        const val KEY_COUNTERS = "counters"
        const val KEY_TIME_CHECK = "time_check"
        const val KEY_LAST_SIM = "last_sim"
        const val KEY_FIRST_LAUNCH = "first_launch"
    }
}
