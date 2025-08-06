// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import org.citra.citra_emu.NativeLibrary

/**
 * Custom view to draw borders around 3DS top and bottom screens
 */
class BorderOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    init {
        // Make sure this view doesn't intercept touch events and is transparent
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        setBackgroundColor(Color.TRANSPARENT)
    }
    
    /**
     * Call this method to refresh the border when screen layout changes
     */
    fun refreshBorders() {
        post { 
            invalidate() 
        }
    }
    
    /**
     * Initialize borders immediately when first attached to window
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Border will be enabled when game loading is complete
    }

    private val borderPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val topScreenRect = RectF()
    private val bottomScreenRect = RectF()

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        )
    }

    private var borderEnabled = false
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Set dynamic stroke width (ensuring 4dp)
        borderPaint.strokeWidth = dpToPx(4f)
        
        // Only draw if border is enabled
        if (!borderEnabled) {
            return
        }
        
        // Get real screen coordinates from native library
        try {
            val screenLayout = NativeLibrary.getScreenLayout()
            if (screenLayout != null && screenLayout.size >= 8) {
                drawScreenBordersWithRealCoords(canvas, screenLayout)
            } else {
                // Fallback: use estimated positions
                val isPortrait = NativeLibrary.isPortraitMode
                drawScreenBorders(canvas, isPortrait)
            }
        } catch (e: Exception) {
            // Fallback: silently fail and don't draw anything
        }
    }
    
    /**
     * Enable border drawing after game is loaded
     */
    fun enableBorder() {
        borderEnabled = true
        refreshBorders()
    }

    private fun drawScreenBorders(canvas: Canvas, isPortrait: Boolean) {
        val borderWidth = borderPaint.strokeWidth
        val halfBorder = borderWidth / 2f
        val cornerRadius = dpToPx(4f) // 4dp corner radius
        
        if (isPortrait) {
            // Portrait mode: typically top screen above, bottom screen below
            // Estimate screen positions based on typical 3DS aspect ratios
            val screenWidth = width * 0.9f // Leave some margin
            val screenStartX = (width - screenWidth) / 2f
            
            // Top screen (usually 400x240 aspect ratio ≈ 1.67:1)
            val topScreenHeight = screenWidth / 1.67f
            val topScreenTop = height * 0.1f
            
            topScreenRect.set(
                screenStartX + halfBorder,
                topScreenTop + halfBorder,
                screenStartX + screenWidth - halfBorder,
                topScreenTop + topScreenHeight - halfBorder
            )
            
            // Bottom screen (320x240 aspect ratio ≈ 1.33:1)
            val bottomScreenHeight = screenWidth / 1.33f
            val bottomScreenTop = topScreenTop + topScreenHeight + dpToPx(8f) // 8dp gap
            
            bottomScreenRect.set(
                screenStartX + halfBorder,
                bottomScreenTop + halfBorder,
                screenStartX + screenWidth - halfBorder,
                bottomScreenTop + bottomScreenHeight - halfBorder
            )
            
        } else {
            // Landscape mode: screens side by side or vertically stacked
            // Assume default layout with screens stacked vertically
            val screenWidth = width * 0.8f
            val screenStartX = (width - screenWidth) / 2f
            
            // Top screen
            val topScreenHeight = height * 0.45f
            val topScreenTop = height * 0.05f
            
            topScreenRect.set(
                screenStartX + halfBorder,
                topScreenTop + halfBorder,
                screenStartX + screenWidth - halfBorder,
                topScreenTop + topScreenHeight - halfBorder
            )
            
            // Bottom screen
            val bottomScreenTop = topScreenTop + topScreenHeight + dpToPx(8f)
            val bottomScreenHeight = height * 0.4f
            
            bottomScreenRect.set(
                screenStartX + halfBorder,
                bottomScreenTop + halfBorder,
                screenStartX + screenWidth - halfBorder,
                bottomScreenTop + bottomScreenHeight - halfBorder
            )
        }
        
        // Draw borders for both screens
        canvas.drawRoundRect(topScreenRect, cornerRadius, cornerRadius, borderPaint)
        canvas.drawRoundRect(bottomScreenRect, cornerRadius, cornerRadius, borderPaint)
        

    }

    private fun drawScreenBordersWithRealCoords(canvas: Canvas, coords: IntArray) {
        val borderGap = dpToPx(1f) // 1dp gap between game screen and border
        val cornerRadius = dpToPx(3f) // 3dp corner radius
        
        // coords array: [top_left_x, top_left_y, top_right_x, top_right_y, 
        //                bottom_left_x, bottom_left_y, bottom_right_x, bottom_right_y]
        
        // Top screen coordinates
        val topLeft = coords[0].toFloat()
        val topTop = coords[1].toFloat()
        val topRight = coords[2].toFloat()
        val topBottom = coords[3].toFloat()
        
        // Bottom screen coordinates
        val bottomLeft = coords[4].toFloat()
        val bottomTop = coords[5].toFloat()
        val bottomRight = coords[6].toFloat()
        val bottomBottom = coords[7].toFloat()
        
        // Set rectangles for OUTER border with small gap from game screen
        topScreenRect.set(
            topLeft - borderGap,
            topTop - borderGap,
            topRight + borderGap,
            topBottom + borderGap
        )
        
        bottomScreenRect.set(
            bottomLeft - borderGap,
            bottomTop - borderGap,
            bottomRight + borderGap,
            bottomBottom + borderGap
        )
        
        // Only draw borders if screens are enabled and have valid dimensions
        if (topScreenRect.width() > 0 && topScreenRect.height() > 0) {
            canvas.drawRoundRect(topScreenRect, cornerRadius, cornerRadius, borderPaint)
        }
        
        if (bottomScreenRect.width() > 0 && bottomScreenRect.height() > 0) {
            canvas.drawRoundRect(bottomScreenRect, cornerRadius, cornerRadius, borderPaint)
        }
    }
}