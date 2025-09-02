package org.citra.citra_emu.utils

import android.content.res.Configuration
import androidx.preference.PreferenceManager
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.R

/**
 * Stores per-orientation hot corner actions.
 * Defaults: only bottom-right is PauseResume; bottom-left is None.
 */
object HotCornerSettings {
    private val preferences =
        PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)

    enum class HotCornerPosition { BOTTOM_LEFT, BOTTOM_RIGHT }

    enum class HotCornerAction(val keySuffix: String, val accessibilityDescriptionResId: Int?) {
        NONE("none", null),
        PAUSE_RESUME("pause_resume", R.string.pause_emulation),
        TOGGLE_TURBO("toggle_turbo", R.string.turbo_limit_hotkey),
        QUICK_SAVE("quick_save", R.string.button_quick_save),
        QUICK_LOAD("quick_load", R.string.button_quick_load),
        OPEN_MENU("open_menu", R.string.button_menu),
        SWAP_SCREENS("swap_screens", R.string.button_swap)
    }

    private fun key(orientation: Int, position: HotCornerPosition): String {
        val orient = if (orientation == Configuration.ORIENTATION_LANDSCAPE) "land" else "port"
        val pos = if (position == HotCornerPosition.BOTTOM_RIGHT) "br" else "bl"
        return "HotCorner_${orient}_${pos}_action"
    }

    fun getAction(orientation: Int, position: HotCornerPosition): HotCornerAction {
        val stored = preferences.getString(key(orientation, position), null)
        val defaultAction = when (orientation) {
            Configuration.ORIENTATION_PORTRAIT -> {
                when (position) {
                    HotCornerPosition.BOTTOM_LEFT -> HotCornerAction.TOGGLE_TURBO
                    HotCornerPosition.BOTTOM_RIGHT -> HotCornerAction.PAUSE_RESUME
                }
            }
            Configuration.ORIENTATION_LANDSCAPE -> {
                when (position) {
                    HotCornerPosition.BOTTOM_LEFT -> HotCornerAction.PAUSE_RESUME
                    HotCornerPosition.BOTTOM_RIGHT -> HotCornerAction.NONE
                }
            }
            else -> HotCornerAction.NONE
        }
        val value = stored ?: defaultAction.name
        return runCatching { HotCornerAction.valueOf(value) }.getOrElse { defaultAction }
    }

    fun setAction(orientation: Int, position: HotCornerPosition, action: HotCornerAction) {
        preferences.edit().putString(key(orientation, position), action.name).apply()
    }

    // Bottom-Center hot corner visibility setting (press to show HUD vs off)
    enum class BottomCenterMode { PRESS_TO_SHOW_HUD, OFF }

    private fun bottomCenterKey(orientation: Int): String {
        val orient = if (orientation == Configuration.ORIENTATION_LANDSCAPE) "land" else "port"
        return "HotCorner_${orient}_bc_mode"
    }

    fun getBottomCenterMode(orientation: Int): BottomCenterMode {
        val stored = preferences.getString(bottomCenterKey(orientation), null)
        val defaultMode = BottomCenterMode.PRESS_TO_SHOW_HUD
        val value = stored ?: defaultMode.name
        return runCatching { BottomCenterMode.valueOf(value) }.getOrElse { defaultMode }
    }

    fun setBottomCenterMode(orientation: Int, mode: BottomCenterMode) {
        preferences.edit().putString(bottomCenterKey(orientation), mode.name).apply()
    }
}


