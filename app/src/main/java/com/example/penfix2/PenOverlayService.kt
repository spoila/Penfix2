package com.example.penfix2

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class PenOverlayService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PenOverlayService? = null

        private const val DRAW_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }

    private lateinit var wm: WindowManager
    private lateinit var drawParams: WindowManager.LayoutParams
    private lateinit var toolbarParams: WindowManager.LayoutParams
    private var drawingView: DrawingView? = null
    private var toolbar: ToolbarView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        removeOverlay()

        val dv = DrawingView(this)
        drawParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            DRAW_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,   // starts in "pass-through" mode
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(dv, drawParams)
        drawingView = dv

        toolbarParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 40
        }
        val tb = ToolbarView(this, dv, wm, toolbarParams)
        wm.addView(tb, toolbarParams)   // added last = sits above the drawing layer
        toolbar = tb
    }

    /** on = pen draws on the overlay; off = everything passes through to the app below. */
    fun setWriting(on: Boolean) {
        val v = drawingView ?: return
        drawParams.flags =
            if (on) DRAW_FLAGS else DRAW_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        wm.updateViewLayout(v, drawParams)
        v.writingEnabled = on
    }

    private fun removeOverlay() {
        if (!::wm.isInitialized) return
        try {
            toolbar?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        try {
            drawingView?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        toolbar = null
        drawingView = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        removeOverlay()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeOverlay()
        instance = null
        super.onDestroy()
    }
}
