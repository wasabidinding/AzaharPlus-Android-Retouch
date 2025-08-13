// Copyright 2025 AzaharPlus
// Licensed under GPLv2 or any later version

package org.citra.citra_emu.overlay

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import org.citra.citra_emu.R
import org.citra.citra_emu.utils.HotCornerSettings

/**
 * Fullscreen overlay that hosts one or more invisible clickable hot corners.
 * Currently only enables the bottom-right hot corner by default, mapped to Pause/Resume.
 * Designed for future expansion: multiple corners and orientation-specific actions.
 */
class HotCornerOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface OnActionListener {
        fun onHotCornerAction(action: HotCornerSettings.HotCornerAction)
    }

    private var actionListener: OnActionListener? = null

    fun setOnActionListener(listener: OnActionListener) {
        actionListener = listener
    }

    /**
     * Rebuild hot corners based on current orientation and settings.
     */
    fun refresh() {
        removeAllViews()

        val sizePx = resources.getDimensionPixelSize(R.dimen.hot_corner_size)
        val orientation = resources.configuration.orientation

        // Bottom-Right corner
        val brAction = HotCornerSettings.getAction(
            orientation,
            HotCornerSettings.HotCornerPosition.BOTTOM_RIGHT
        )
        if (brAction != HotCornerSettings.HotCornerAction.NONE) {
            addView(createCornerView(sizePx, Gravity.BOTTOM or Gravity.END, brAction))
        }

        // Bottom-Left corner (not enabled by default; kept for future extensibility)
        val blAction = HotCornerSettings.getAction(
            orientation,
            HotCornerSettings.HotCornerPosition.BOTTOM_LEFT
        )
        if (blAction != HotCornerSettings.HotCornerAction.NONE) {
            addView(createCornerView(sizePx, Gravity.BOTTOM or Gravity.START, blAction))
        }
    }

    private fun createCornerView(
        sizePx: Int,
        gravity: Int,
        action: HotCornerSettings.HotCornerAction
    ): View {
        return View(context).apply {
            isClickable = true
            isFocusable = false
            // Production: invisible hot area
            background = null
            contentDescription = action.accessibilityDescriptionResId?.let {
                resources.getString(it)
            }
            layoutParams = LayoutParams(sizePx, sizePx, gravity)
            setOnClickListener { actionListener?.onHotCornerAction(action) }
        }
    }
}


