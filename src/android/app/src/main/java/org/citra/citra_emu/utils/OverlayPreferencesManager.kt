package org.citra.citra_emu.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.NativeLibrary

/**
 * Central place to decide which SharedPreferences instance the input overlay
 * should use. Allows switching between the legacy global profile and per-game
 * profiles that start as copies of the global values.
 */
object OverlayPreferencesManager {
    private const val PROFILE_PREFIX = "overlay_profile_"
    private const val SEEDED_FLAG = "__overlay_profile_seeded"
    private const val INDEPENDENT_PREFIX = "overlay_independent_"

    sealed class Scope {
        data object General : Scope()
        data class Game(val titleId: Long, val fallbackKey: String?) : Scope()
    }

    private val appContext: Context = CitraApplication.appContext
    private val generalPreferences: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(appContext)
    }

    private val lock = Any()

    @Volatile
    private var activeScope: Scope = Scope.General

    @Volatile
    private var activePrefs: SharedPreferences = generalPreferences

    private val perButtonScaleKeys: Array<String> by lazy {
        val ids = intArrayOf(
            NativeLibrary.ButtonType.BUTTON_A,
            NativeLibrary.ButtonType.BUTTON_B,
            NativeLibrary.ButtonType.BUTTON_X,
            NativeLibrary.ButtonType.BUTTON_Y,
            NativeLibrary.ButtonType.TRIGGER_L,
            NativeLibrary.ButtonType.TRIGGER_R,
            NativeLibrary.ButtonType.BUTTON_ZL,
            NativeLibrary.ButtonType.BUTTON_ZR,
            NativeLibrary.ButtonType.BUTTON_START,
            NativeLibrary.ButtonType.BUTTON_SELECT,
            NativeLibrary.ButtonType.DPAD,
            NativeLibrary.ButtonType.STICK_LEFT,
            NativeLibrary.ButtonType.STICK_C,
            NativeLibrary.ButtonType.BUTTON_HOME,
            NativeLibrary.ButtonType.BUTTON_SWAP,
            NativeLibrary.ButtonType.BUTTON_TURBO,
            NativeLibrary.ButtonType.BUTTON_QUICK_SAVE,
            NativeLibrary.ButtonType.BUTTON_QUICK_LOAD,
            NativeLibrary.ButtonType.BUTTON_MENU
        )
        ids.map { "controlScale-$it" }.toTypedArray()
    }

    /** Returns whether the given game is marked to use an independent overlay profile. */
    fun isIndependentEnabled(titleId: Long, fallbackKey: String?): Boolean {
        val key = independentToggleKey(titleId, fallbackKey)
        return generalPreferences.getBoolean(key, false)
    }

    /** Stores the independent overlay toggle for a specific game. */
    fun setIndependentEnabled(titleId: Long, fallbackKey: String?, enabled: Boolean) {
        val key = independentToggleKey(titleId, fallbackKey)
        generalPreferences.edit().putBoolean(key, enabled).apply()
    }

    /**
     * Ensures a per-game profile exists (creating it as a copy of the general profile
     * on first use). Returns the associated SharedPreferences instance.
     */
    fun ensureGameProfile(titleId: Long, fallbackKey: String?): SharedPreferences {
        val prefs = appContext.getSharedPreferences(profileName(titleId, fallbackKey), Context.MODE_PRIVATE)
        seedProfileIfNeeded(prefs)
        return prefs
    }

    /** Switches the active overlay preferences scope. */
    fun setActiveScope(scope: Scope) {
        synchronized(lock) {
            activeScope = scope
            activePrefs = when (scope) {
                Scope.General -> generalPreferences
                is Scope.Game -> ensureGameProfile(scope.titleId, scope.fallbackKey)
            }
        }
    }

    fun resetToGeneral() {
        setActiveScope(Scope.General)
    }

    /** Returns the currently active SharedPreferences (respecting scope). */
    fun getActivePreferences(): SharedPreferences {
        return activePrefs
    }

    fun getCurrentScope(): Scope = activeScope

    private fun profileName(titleId: Long, fallbackKey: String?): String {
        val profileId = if (titleId != 0L) {
            "%016X".format(titleId)
        } else {
            // Use a stable hash when titleId is missing
            val safe = fallbackKey?.takeIf { it.isNotBlank() } ?: "unknown"
            Integer.toHexString(safe.hashCode())
        }
        return PROFILE_PREFIX + profileId.lowercase()
    }

    private fun independentToggleKey(titleId: Long, fallbackKey: String?): String {
        val label = if (titleId != 0L) {
            "%016X".format(titleId)
        } else {
            val safe = fallbackKey?.takeIf { it.isNotBlank() } ?: "unknown"
            Integer.toHexString(safe.hashCode())
        }
        return INDEPENDENT_PREFIX + label.lowercase()
    }

    private fun seedProfileIfNeeded(target: SharedPreferences) {
        if (target.getBoolean(SEEDED_FLAG, false)) return

        val editor = target.edit()

        // Copy simple known keys
        copyIfPresent(editor, "OverlayInit")
        copyIfPresent(editor, "isTouchEnabled")
        copyIfPresent(editor, "controlOpacity")
        copyIfPresent(editor, "controlScale")
        perButtonScaleKeys.forEach { copyIfPresent(editor, it) }

        // Toggle flags buttonToggle0..18
        for (i in 0..18) {
            copyIfPresent(editor, "buttonToggle$i")
        }

        // Copy coordinates like "700-X", "700-Portrait-Y", etc.
        val generalEntries = generalPreferences.all
        generalEntries.forEach { (key, value) ->
            if (isCoordinateKey(key)) {
                when (value) {
                    is Float -> editor.putFloat(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                }
            }
        }

        editor.putBoolean(SEEDED_FLAG, true)
        editor.apply()
    }

    private fun copyIfPresent(editor: SharedPreferences.Editor, key: String) {
        if (!generalPreferences.contains(key)) return
        when (val value = generalPreferences.all[key]) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
        }
    }

    private fun isCoordinateKey(key: String): Boolean {
        if (!key.contains('-')) return false
        val suffix = key.substringAfterLast('-')
        if (suffix != "X" && suffix != "Y") return false
        val base = key.substringBeforeLast('-')
        val numericBase = base.removeSuffix("-Portrait")
        return numericBase.all { it.isDigit() }
    }
}
