// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils

import androidx.drawerlayout.widget.DrawerLayout
import androidx.preference.PreferenceManager
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.overlay.ButtonSlidingMode

object EmulationMenuSettings {
    private val preferences =
        PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)

    var joystickRelCenter: Boolean
        get() = preferences.getBoolean("EmulationMenuSettings_JoystickRelCenter", true)
        set(value) {
            preferences.edit()
                .putBoolean("EmulationMenuSettings_JoystickRelCenter", value)
                .apply()
        }
    var dpadSlide: Boolean
        get() = preferences.getBoolean("EmulationMenuSettings_DpadSlideEnable", true)
        set(value) {
            preferences.edit()
                .putBoolean("EmulationMenuSettings_DpadSlideEnable", value)
                .apply()
        }
    var buttonSlide: Int
        get() = preferences.getInt("EmulationMenuSettings_ButtonSlideMode", ButtonSlidingMode.Disabled.int)
        set(value) {
            preferences.edit()
                .putInt("EmulationMenuSettings_ButtonSlideMode", value)
                .apply()
        }

    var hapticFeedback: Boolean
        get() = preferences.getBoolean("EmulationMenuSettings_HapticFeedback", true)
        set(value) {
            val editor = preferences.edit()
            editor.putBoolean("EmulationMenuSettings_HapticFeedback", value)
            // 互斥：若开启完整触摸反馈，则关闭“只有按键”
            if (value) {
                editor.putBoolean("EmulationMenuSettings_HapticFeedbackButtonsOnly", false)
            }
            editor.apply()
        }
    /**
     * 当启用时，仅为“普通按钮类”（A/B/X/Y、Start/Select、L/R、ZL/ZR、Home、Swap、Turbo、QuickSave/QuickLoad、Menu 等）触发触摸反馈；
     * 对十字方向键（D-Pad）、主摇杆、C 摇杆的触摸反馈将被屏蔽。
     */
    var hapticFeedbackButtonsOnly: Boolean
        get() = preferences.getBoolean("EmulationMenuSettings_HapticFeedbackButtonsOnly", false)
        set(value) {
            val editor = preferences.edit()
            editor.putBoolean("EmulationMenuSettings_HapticFeedbackButtonsOnly", value)
            // 互斥：若开启“只有按键”，则关闭完整触摸反馈
            if (value) {
                editor.putBoolean("EmulationMenuSettings_HapticFeedback", false)
            }
            editor.apply()
        }
    var swapScreens: Boolean
        get() = preferences.getBoolean("EmulationMenuSettings_SwapScreens", false)
        set(value) {
            preferences.edit()
                .putBoolean("EmulationMenuSettings_SwapScreens", value)
                .apply()
        }
    var showOverlay: Boolean
        get() = preferences.getBoolean("EmulationMenuSettings_ShowOverlay", true)
        set(value) {
            preferences.edit()
                .putBoolean("EmulationMenuSettings_ShowOverlay", value)
                .apply()
        }
    var drawerLockMode: Int
        get() = preferences.getInt(
            "EmulationMenuSettings_DrawerLockMode",
            DrawerLayout.LOCK_MODE_LOCKED_CLOSED
        )
        set(value) {
            preferences.edit()
                .putInt("EmulationMenuSettings_DrawerLockMode", value)
                .apply()
        }
}
