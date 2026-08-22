package org.citra.citra_emu.utils

import android.content.res.Configuration
import androidx.preference.PreferenceManager
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R

/**
 * Stores per-orientation hot corner actions.
 * Defaults: only bottom-right is PauseResume; bottom-left is None.
 */
object HotCornerSettings {
    private val preferences =
        PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)

    enum class HotCornerPosition(val keySuffix: String) {
        BOTTOM_LEFT("bl"),
        BOTTOM_RIGHT("br"),
        TOP_SCREEN_LEFT("tl"),
        TOP_SCREEN_RIGHT("tr");

        /** Hot zones that cover the top (non-touch) 3DS screen. */
        val isTopScreen: Boolean
            get() = this == TOP_SCREEN_LEFT || this == TOP_SCREEN_RIGHT
    }

    enum class HotCornerAction(val keySuffix: String, val accessibilityDescriptionResId: Int?) {
        NONE("none", null),
        PAUSE_RESUME("pause_resume", R.string.pause_emulation),
        TOGGLE_TURBO("toggle_turbo", R.string.turbo_limit_hotkey),
        QUICK_SAVE("quick_save", R.string.button_quick_save),
        QUICK_LOAD("quick_load", R.string.button_quick_load),
        OPEN_MENU("open_menu", R.string.button_menu),
        SWAP_SCREENS("swap_screens", R.string.button_swap)
    }

    /** A 3DS game button that a hot zone can be mapped to. */
    enum class GameButton(val buttonId: Int, val labelResId: Int) {
        A(NativeLibrary.ButtonType.BUTTON_A, R.string.button_a),
        B(NativeLibrary.ButtonType.BUTTON_B, R.string.button_b),
        X(NativeLibrary.ButtonType.BUTTON_X, R.string.button_x),
        Y(NativeLibrary.ButtonType.BUTTON_Y, R.string.button_y),
        TRIGGER_L(NativeLibrary.ButtonType.TRIGGER_L, R.string.button_l),
        TRIGGER_R(NativeLibrary.ButtonType.TRIGGER_R, R.string.button_r),
        ZL(NativeLibrary.ButtonType.BUTTON_ZL, R.string.button_zl),
        ZR(NativeLibrary.ButtonType.BUTTON_ZR, R.string.button_zr),
        START(NativeLibrary.ButtonType.BUTTON_START, R.string.button_start),
        SELECT(NativeLibrary.ButtonType.BUTTON_SELECT, R.string.button_select),
        HOME(NativeLibrary.ButtonType.BUTTON_HOME, R.string.button_home),
        DPAD_UP(NativeLibrary.ButtonType.DPAD_UP, R.string.emulation_hot_corner_button_dpad_up),
        DPAD_DOWN(NativeLibrary.ButtonType.DPAD_DOWN, R.string.emulation_hot_corner_button_dpad_down),
        DPAD_LEFT(NativeLibrary.ButtonType.DPAD_LEFT, R.string.emulation_hot_corner_button_dpad_left),
        DPAD_RIGHT(NativeLibrary.ButtonType.DPAD_RIGHT, R.string.emulation_hot_corner_button_dpad_right);

        companion object {
            fun fromButtonId(id: Int): GameButton? = entries.firstOrNull { it.buttonId == id }
        }
    }

    /**
     * What a hot zone does when touched: either an emulator-level action (fires on tap)
     * or a game button (pressed on touch-down, released on touch-up).
     */
    sealed class HotCornerBinding {
        data class Emulator(val action: HotCornerAction) : HotCornerBinding()
        data class Button(val button: GameButton) : HotCornerBinding()

        val isNone: Boolean
            get() = this is Emulator && action == HotCornerAction.NONE

        fun serialize(): String = when (this) {
            is Emulator -> action.name
            is Button -> "$BUTTON_PREFIX${button.name}"
        }

        companion object {
            private const val BUTTON_PREFIX = "btn:"
            val NONE: HotCornerBinding = Emulator(HotCornerAction.NONE)

            fun deserialize(value: String?): HotCornerBinding? {
                if (value == null) return null
                if (value.startsWith(BUTTON_PREFIX)) {
                    val name = value.removePrefix(BUTTON_PREFIX)
                    return runCatching { Button(GameButton.valueOf(name)) }.getOrNull()
                }
                return runCatching { Emulator(HotCornerAction.valueOf(value)) }.getOrNull()
            }
        }
    }

    private fun key(orientation: Int, position: HotCornerPosition): String {
        val orient = if (orientation == Configuration.ORIENTATION_LANDSCAPE) "land" else "port"
        return "HotCorner_${orient}_${position.keySuffix}_action"
    }

    private fun defaultBinding(orientation: Int, position: HotCornerPosition): HotCornerBinding {
        val action = when (orientation) {
            Configuration.ORIENTATION_PORTRAIT -> when (position) {
                HotCornerPosition.BOTTOM_LEFT -> HotCornerAction.TOGGLE_TURBO
                HotCornerPosition.BOTTOM_RIGHT -> HotCornerAction.PAUSE_RESUME
                else -> HotCornerAction.NONE
            }
            Configuration.ORIENTATION_LANDSCAPE -> when (position) {
                HotCornerPosition.BOTTOM_LEFT -> HotCornerAction.PAUSE_RESUME
                else -> HotCornerAction.NONE
            }
            else -> HotCornerAction.NONE
        }
        return HotCornerBinding.Emulator(action)
    }

    fun getBinding(orientation: Int, position: HotCornerPosition): HotCornerBinding {
        val stored = preferences.getString(key(orientation, position), null)
        return HotCornerBinding.deserialize(stored) ?: defaultBinding(orientation, position)
    }

    fun setBinding(orientation: Int, position: HotCornerPosition, binding: HotCornerBinding) {
        preferences.edit().putString(key(orientation, position), binding.serialize()).apply()
    }

    /** Convenience accessor kept for callers that only care about emulator actions. */
    fun getAction(orientation: Int, position: HotCornerPosition): HotCornerAction {
        return (getBinding(orientation, position) as? HotCornerBinding.Emulator)?.action
            ?: HotCornerAction.NONE
    }

    fun setAction(orientation: Int, position: HotCornerPosition, action: HotCornerAction) {
        setBinding(orientation, position, HotCornerBinding.Emulator(action))
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
