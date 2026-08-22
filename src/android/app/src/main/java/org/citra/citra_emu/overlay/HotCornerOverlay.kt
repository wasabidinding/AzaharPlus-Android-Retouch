// Copyright 2025 AzaharPlus
// Licensed under GPLv2 or any later version

package org.citra.citra_emu.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.utils.HotCornerSettings
import org.citra.citra_emu.utils.HotCornerSettings.HotCornerBinding
import org.citra.citra_emu.utils.HotCornerSettings.HotCornerPosition
import kotlin.math.max
import kotlin.math.min

/**
 * Fullscreen overlay that hosts invisible touch zones:
 *  - bottom-left / bottom-right corners
 *  - bottom-center press-and-hold HUD trigger
 *  - left / right halves of the (non-touch) 3DS top screen
 *
 * Each zone is bound either to an emulator action (fires on tap) or to a game button
 * (pressed on touch-down, released on touch-up).
 *
 * Touch routing: this view sits above [InputOverlay]. Android assigns every new pointer to
 * a view that already owns a pointer, so once a zone is held the on-screen controls below
 * would never see further fingers. To keep virtual buttons usable while a zone is held,
 * [dispatchTouchEvent] decides per pointer whether it belongs to a zone and forwards all
 * other pointers (as split events) straight to [inputOverlay]. Top-screen zones also yield
 * to virtual controls placed over the top screen.
 */
class HotCornerOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface OnActionListener {
        fun onHotCornerAction(action: HotCornerSettings.HotCornerAction)
    }

    interface OnPressListener {
        fun onBottomCenterPress(isPressed: Boolean)
    }

    private var actionListener: OnActionListener? = null
    private var pressListener: OnPressListener? = null

    /** The on-screen controls beneath this overlay; pointers not owned by a zone go here. */
    var inputOverlay: InputOverlay? = null

    /** Top-screen rect used by the last [refresh]; null when the layout wasn't available. */
    private var builtTopRect: Rect? = null

    /** Pointer ids currently routed to zones vs. forwarded to [inputOverlay]. */
    private var ownedPointerIds = 0
    private var forwardedPointerIds = 0

    /** Reference counts per game button so two zones bound to the same button don't fight. */
    private val heldButtonCounts = HashMap<Int, Int>()

    private var bottomCenterPressed = false

    fun setOnActionListener(listener: OnActionListener) {
        actionListener = listener
    }

    fun setOnPressListener(listener: OnPressListener) {
        pressListener = listener
    }

    /**
     * Rebuild hot zones based on current orientation and settings.
     * Top-screen zones need the emulator's framebuffer layout; if it isn't available yet
     * they are skipped; [syncWithScreenLayout] will rebuild once it is.
     */
    fun refresh() {
        cancelActiveTouches()
        removeAllViews()

        val sizePx = resources.getDimensionPixelSize(R.dimen.hot_corner_size)
        val orientation = resources.configuration.orientation
        val baseBottomCenterWidth = resources.getDimensionPixelSize(R.dimen.hot_corner_bottom_center_width)
        val bottomCenterHeight = resources.getDimensionPixelSize(R.dimen.hot_corner_bottom_center_height)
        val bottomCenterWidth = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            baseBottomCenterWidth * 2
        } else {
            baseBottomCenterWidth
        }

        // Top-screen halves go first so corner zones added later sit above them in z-order.
        val topRect = getTopScreenRect()
        builtTopRect = topRect
        topRect?.let { top ->
            val midX = (top.left + top.right) / 2
            addTopScreenZone(orientation, HotCornerPosition.TOP_SCREEN_LEFT,
                Rect(top.left, top.top, midX, top.bottom))
            addTopScreenZone(orientation, HotCornerPosition.TOP_SCREEN_RIGHT,
                Rect(midX, top.top, top.right, top.bottom))
        }

        val brBinding = HotCornerSettings.getBinding(orientation, HotCornerPosition.BOTTOM_RIGHT)
        if (!brBinding.isNone) {
            addView(createZoneView(brBinding, yieldToControls = false).apply {
                layoutParams = LayoutParams(sizePx, sizePx, Gravity.BOTTOM or Gravity.END)
            })
        }

        val blBinding = HotCornerSettings.getBinding(orientation, HotCornerPosition.BOTTOM_LEFT)
        if (!blBinding.isNone) {
            addView(createZoneView(blBinding, yieldToControls = false).apply {
                layoutParams = LayoutParams(sizePx, sizePx, Gravity.BOTTOM or Gravity.START)
            })
        }

        val bcMode = HotCornerSettings.getBottomCenterMode(orientation)
        if (bcMode == HotCornerSettings.BottomCenterMode.PRESS_TO_SHOW_HUD) {
            addView(createBottomCenterPressView(bottomCenterWidth, bottomCenterHeight))
        }
    }

    /**
     * Cheap check intended to be called periodically (e.g. from the frame callback):
     * rebuilds the zones if the emulator's top-screen rect changed since the last [refresh]
     * (screen swap, layout change, surface resize) or became available for the first time.
     */
    fun syncWithScreenLayout() {
        val orientation = resources.configuration.orientation
        val hasTopZone = HotCornerPosition.entries.any {
            it.isTopScreen && !HotCornerSettings.getBinding(orientation, it).isNone
        }
        if (!hasTopZone) return
        // Rebuilding mid-gesture would drop pointer routing state; try again next tick.
        if (ownedPointerIds != 0 || forwardedPointerIds != 0) return
        val current = getTopScreenRect()
        if (current != builtTopRect) {
            refresh()
        }
    }

    /**
     * Drops every in-flight zone gesture and releases all game buttons held by zones.
     * Call before pausing emulation or tearing the view down so no button stays stuck.
     * Pointers already forwarded to [inputOverlay] keep being forwarded so that its own
     * controls still receive their UP events.
     */
    fun cancelActiveTouches() {
        if (ownedPointerIds != 0) {
            // Let the ViewGroup machinery drop its touch targets for the zones.
            val now = android.os.SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
        for (i in 0 until childCount) {
            (getChildAt(i) as? ZoneView)?.cancel()
        }
        if (bottomCenterPressed) {
            bottomCenterPressed = false
            pressListener?.onBottomCenterPress(false)
        }
        // Safety net: release anything the zones didn't account for.
        for (id in heldButtonCounts.keys.toList()) {
            NativeLibrary.onGamePadEvent(NativeLibrary.TouchScreenDevice, id, NativeLibrary.ButtonState.RELEASED)
        }
        heldButtonCounts.clear()
        ownedPointerIds = 0
    }

    override fun onDetachedFromWindow() {
        cancelActiveTouches()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------------------------------
    // Per-pointer routing between zones and InputOverlay
    // ---------------------------------------------------------------------------------------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val target = inputOverlay ?: return super.dispatchTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ownedPointerIds = 0
                forwardedPointerIds = 0
                assignPointer(ev, 0)
            }
            MotionEvent.ACTION_POINTER_DOWN -> assignPointer(ev, ev.actionIndex)
        }

        splitEvent(ev, ownedPointerIds)?.let { owned ->
            super.dispatchTouchEvent(owned)
            owned.recycle()
        }
        splitEvent(ev, forwardedPointerIds)?.let { forwarded ->
            target.dispatchTouchEvent(forwarded)
            forwarded.recycle()
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_UP -> {
                val bit = 1 shl ev.getPointerId(ev.actionIndex)
                ownedPointerIds = ownedPointerIds and bit.inv()
                forwardedPointerIds = forwardedPointerIds and bit.inv()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                ownedPointerIds = 0
                forwardedPointerIds = 0
            }
        }
        // Always claim the gesture so the parent keeps routing every pointer through here;
        // pointers that no zone wants are handed to InputOverlay above.
        return true
    }

    private fun assignPointer(ev: MotionEvent, index: Int) {
        val bit = 1 shl ev.getPointerId(index)
        if (findZoneAccepting(ev.getX(index), ev.getY(index)) != null) {
            ownedPointerIds = ownedPointerIds or bit
        } else {
            forwardedPointerIds = forwardedPointerIds or bit
        }
    }

    /** Topmost child zone that wants a new pointer at (x, y) in overlay coordinates. */
    private fun findZoneAccepting(x: Float, y: Float): View? {
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (x < child.left || x >= child.right || y < child.top || y >= child.bottom) continue
            if (child is ZoneView && !child.acceptsPointer(x, y)) continue
            return child
        }
        return null
    }

    /**
     * Returns a copy of [ev] containing only the pointers in [idBits] (with the action
     * rewritten the way ViewGroup does for split events), or null if none match.
     */
    private fun splitEvent(ev: MotionEvent, idBits: Int): MotionEvent? {
        if (idBits == 0) return null
        val count = ev.pointerCount
        val indices = (0 until count).filter { (idBits shr ev.getPointerId(it)) and 1 != 0 }
        if (indices.isEmpty()) return null

        val masked = ev.actionMasked
        val actionIndex = ev.actionIndex
        val newIndex = indices.indexOf(actionIndex)
        val action: Int = when (masked) {
            MotionEvent.ACTION_CANCEL -> MotionEvent.ACTION_CANCEL
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> when {
                newIndex < 0 -> MotionEvent.ACTION_MOVE
                indices.size == 1 -> MotionEvent.ACTION_DOWN
                else -> MotionEvent.ACTION_POINTER_DOWN or (newIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> when {
                newIndex < 0 -> MotionEvent.ACTION_MOVE
                indices.size == 1 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_POINTER_UP or (newIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            else -> MotionEvent.ACTION_MOVE
        }

        // Fast path: every pointer is in the set, only the action may differ.
        if (indices.size == count) {
            return MotionEvent.obtain(ev).apply { this.action = action }
        }

        val props = Array(indices.size) { MotionEvent.PointerProperties() }
        val coords = Array(indices.size) { MotionEvent.PointerCoords() }
        indices.forEachIndexed { i, src ->
            ev.getPointerProperties(src, props[i])
            ev.getPointerCoords(src, coords[i])
        }
        return MotionEvent.obtain(
            ev.downTime, ev.eventTime, action, indices.size, props, coords,
            ev.metaState, ev.buttonState, ev.xPrecision, ev.yPrecision,
            ev.deviceId, ev.edgeFlags, ev.source, ev.flags
        )
    }

    // ---------------------------------------------------------------------------------------
    // Zones
    // ---------------------------------------------------------------------------------------

    private fun addTopScreenZone(orientation: Int, position: HotCornerPosition, rect: Rect) {
        val binding = HotCornerSettings.getBinding(orientation, position)
        if (binding.isNone || rect.width() <= 0 || rect.height() <= 0) return
        addView(createZoneView(binding, yieldToControls = true).apply {
            layoutParams = LayoutParams(rect.width(), rect.height(), Gravity.TOP or Gravity.START).also {
                it.leftMargin = rect.left
                it.topMargin = rect.top
            }
        })
    }

    /** Top screen rect in overlay coordinates, or null if the emulator layout isn't ready. */
    private fun getTopScreenRect(): Rect? {
        val coords = try {
            NativeLibrary.getScreenLayout()
        } catch (_: Throwable) {
            null
        } ?: return null
        if (coords.size < 8) return null
        return Rect(
            min(coords[0], coords[2]), min(coords[1], coords[3]),
            max(coords[0], coords[2]), max(coords[1], coords[3])
        )
    }

    private fun pressButton(id: Int) {
        val count = (heldButtonCounts[id] ?: 0) + 1
        heldButtonCounts[id] = count
        if (count == 1) {
            NativeLibrary.onGamePadEvent(NativeLibrary.TouchScreenDevice, id, NativeLibrary.ButtonState.PRESSED)
        }
    }

    private fun releaseButton(id: Int) {
        val count = (heldButtonCounts[id] ?: return) - 1
        if (count <= 0) {
            heldButtonCounts.remove(id)
            NativeLibrary.onGamePadEvent(NativeLibrary.TouchScreenDevice, id, NativeLibrary.ButtonState.RELEASED)
        } else {
            heldButtonCounts[id] = count
        }
    }

    private fun createZoneView(binding: HotCornerBinding, yieldToControls: Boolean): View {
        return ZoneView(binding, yieldToControls).apply {
            contentDescription = when (binding) {
                is HotCornerBinding.Emulator ->
                    binding.action.accessibilityDescriptionResId?.let { resources.getString(it) }
                is HotCornerBinding.Button -> resources.getString(binding.button.labelResId)
            }
        }
    }

    /**
     * Invisible touch zone. Handles its own touch so that it can decline the DOWN event
     * (and thereby the whole gesture) when the touch lands on an on-screen control.
     */
    private inner class ZoneView(
        private val binding: HotCornerBinding,
        private val yieldToControls: Boolean
    ) : View(context) {
        private val buttonId: Int? = (binding as? HotCornerBinding.Button)?.button?.buttonId
        private var tracking = false
        private var holding = false

        init {
            isFocusable = false
            background = null
        }

        /** Whether a new pointer at (x, y) in overlay coordinates should be owned by this zone. */
        fun acceptsPointer(x: Float, y: Float): Boolean {
            if (!yieldToControls) return true
            return inputOverlay?.isPointOverControl(x, y) != true
        }

        fun cancel() {
            tracking = false
            release()
        }

        private fun press() {
            val id = buttonId ?: return
            if (holding) return
            holding = true
            pressButton(id)
        }

        private fun release() {
            val id = buttonId ?: return
            if (!holding) return
            holding = false
            releaseButton(id)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!acceptsPointer(event.x + left, event.y + top)) {
                        tracking = false
                        return false
                    }
                    tracking = true
                    press()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!tracking) return false
                    tracking = false
                    if (buttonId != null) {
                        release()
                    } else if (isInside(event)) {
                        (binding as? HotCornerBinding.Emulator)?.let {
                            actionListener?.onHotCornerAction(it.action)
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (!tracking) return false
                    cancel()
                    return true
                }
                else -> return tracking
            }
        }

        private fun isInside(event: MotionEvent): Boolean =
            event.x >= 0f && event.y >= 0f && event.x < width && event.y < height
    }

    private fun createBottomCenterPressView(widthPx: Int, heightPx: Int): View {
        return View(context).apply {
            isClickable = true
            isFocusable = false
            background = null
            layoutParams = LayoutParams(widthPx, heightPx, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        bottomCenterPressed = true
                        pressListener?.onBottomCenterPress(true)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        bottomCenterPressed = false
                        pressListener?.onBottomCenterPress(false)
                        true
                    }
                    else -> true
                }
            }
        }
    }
}
