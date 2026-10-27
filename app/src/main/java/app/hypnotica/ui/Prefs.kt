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

    /**
     * Whether the drawer opens on the exact controls.
     *
     * The audience that wants them was re-tapping Advanced on every cold start,
     * which is precisely the sort of choice this file exists to keep.
     */
    var advanced: Boolean
        get() = sp.getBoolean(KEY_ADVANCED, false)
        set(value) = sp.edit().putBoolean(KEY_ADVANCED, value).apply()

    /** The sim the person was last watching, so the app picks up where it left off. */
    var lastSimId: String?
        get() = sp.getString(KEY_LAST_SIM, null)
        set(value) = sp.edit().putString(KEY_LAST_SIM, value).apply()

    /** What the benchmark measured, so it runs once rather than every launch. */
    var deviceTier: String?
        get() = sp.getString(KEY_TIER, null)
        set(value) = sp.edit().putString(KEY_TIER, value).apply()

    var benchNote: String?
        get() = sp.getString(KEY_BENCH, null)
        set(value) = sp.edit().putString(KEY_BENCH, value).apply()

    /** TickTock's learned weighting, as the director exports it. */
    var taste: String
        get() = sp.getString(KEY_TASTE, "") ?: ""
        set(value) = sp.edit().putString(KEY_TASTE, value).apply()

    var mutedFormats: Set<String>
        get() = sp.getStringSet(KEY_MUTED, emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_MUTED, value).apply()

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
        const val KEY_ADVANCED = "advanced"
        const val KEY_LAST_SIM = "last_sim"
        const val KEY_FIRST_LAUNCH = "first_launch"
        const val KEY_TIER = "device_tier"
        const val KEY_BENCH = "bench_note"
        const val KEY_TASTE = "taste"
        const val KEY_MUTED = "muted_formats"
    }
}
