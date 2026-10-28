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

    init {
        migrate()
    }

    /**
     * Settings written under an old default, brought up to the new one once.
     *
     * A default only reaches a fresh install: everything here is written back
     * on every launch, so a phone that has run the app before keeps whatever
     * was in force then. Haptics going off by default and 8-bit becoming the
     * default sound both came from the person using the app saying so — and
     * neither would have reached the phone they said it on.
     */
    private fun migrate() {
        val from = sp.getInt(KEY_SCHEMA, 1)
        if (from >= SCHEMA) return
        val edit = sp.edit()
        if (from < 2) {
            edit.putBoolean(KEY_HAPTICS, false)
            // Cleared rather than set, so the sim's own default — 8-bit — applies.
            edit.remove(KEY_SOUND)
        }
        edit.putInt(KEY_SCHEMA, SCHEMA).apply()
    }

    var favourites: Set<String>
        get() = sp.getStringSet(KEY_FAVOURITES, emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_FAVOURITES, value).apply()

    /**
     * The palette the person picked, or null if they never have.
     *
     * Null matters: these were stored as a name defaulting to "Spectrum", and
     * nothing ever read them back, so the choice was discarded on the next sim
     * change and gone on the next launch. Telling "they chose Spectrum" from
     * "they chose nothing" is what lets the choice survive without overriding
     * every sim's own default for people who never set one.
     */
    var paletteName: String?
        get() = sp.getString(KEY_PALETTE, null)
        set(value) = sp.edit().putString(KEY_PALETTE, value).apply()

    var soundPreset: String?
        get() = sp.getString(KEY_SOUND, null)
        set(value) = sp.edit().putString(KEY_SOUND, value).apply()

    /** The beat under the sound: "Auto", "Off" or a groove's name. */
    var beat: String
        get() = sp.getString(KEY_BEAT, BEAT_AUTO) ?: BEAT_AUTO
        set(value) = sp.edit().putString(KEY_BEAT, value).apply()

    var volume: Float
        get() = sp.getFloat(KEY_VOLUME, 0.8f)
        set(value) = sp.edit().putFloat(KEY_VOLUME, value).apply()

    var cameraDrag: Boolean
        get() = sp.getBoolean(KEY_CAMERA_DRAG, false)
        set(value) = sp.edit().putBoolean(KEY_CAMERA_DRAG, value).apply()

    var reduceFlashing: Boolean
        get() = sp.getBoolean(KEY_REDUCE_FLASH, false)
        set(value) = sp.edit().putBoolean(KEY_REDUCE_FLASH, value).apply()

    var reduceMotion: Boolean
        get() = sp.getBoolean(KEY_REDUCE_MOTION, false)
        set(value) = sp.edit().putBoolean(KEY_REDUCE_MOTION, value).apply()

    var haptics: Boolean
        // Off until it is asked for. A phone buzzing at every collision is a
        // lot to have chosen for you.
        get() = sp.getBoolean(KEY_HAPTICS, false)
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

    /** The Studio project, as ProjectJson writes it, so it outlives the process. */
    var studioProject: String?
        get() = sp.getString(KEY_PROJECT, null)
        set(value) = sp.edit().putString(KEY_PROJECT, value).apply()

    /** How many more times the controls show the one-line gesture tip. */
    var gestureTipsLeft: Int
        get() = sp.getInt(KEY_GESTURE_TIPS, 3)
        set(value) = sp.edit().putInt(KEY_GESTURE_TIPS, value).apply()

    var mutedFormats: Set<String>
        get() = sp.getStringSet(KEY_MUTED, emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_MUTED, value).apply()

    private companion object {
        const val KEY_FAVOURITES = "favourites"
        const val KEY_PALETTE = "palette"
        const val KEY_SOUND = "sound"
        const val KEY_BEAT = "beat"
        const val KEY_VOLUME = "volume"
        const val KEY_CAMERA_DRAG = "cameraDrag"
        const val KEY_REDUCE_FLASH = "reduce_flashing"
        const val KEY_REDUCE_MOTION = "reduce_motion"
        const val KEY_HAPTICS = "haptics"
        const val KEY_COUNTERS = "counters"
        const val KEY_TIME_CHECK = "time_check"
        const val KEY_ADVANCED = "advanced"
        const val KEY_LAST_SIM = "last_sim"
        const val KEY_TIER = "device_tier"
        const val KEY_BENCH = "bench_note"
        const val KEY_TASTE = "taste"
        const val KEY_MUTED = "muted_formats"
        const val KEY_SCHEMA = "schema"
        const val KEY_PROJECT = "studio_project"
        const val KEY_GESTURE_TIPS = "gesture_tips_left"

        /** Bump with a step in [migrate] whenever a default changes for people already using the app. */
        const val SCHEMA = 2
    }
}

/** The beat setting that gives each sim, and each TickTock clip, a groove of its own. */
internal const val BEAT_AUTO = "Auto"
