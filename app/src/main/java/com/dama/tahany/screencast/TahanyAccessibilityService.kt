package com.dama.tahany.screencast

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Size
import com.dama.tahany.protocol.TOUCH_SWIPE
import com.dama.tahany.protocol.TOUCH_TAP
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.dama.tahany.protocol.TahanyMessage

/**
 * Runs on the Phone (Redmi) when enabled in Accessibility settings.
 * Injects touch gestures dispatched from the tablet's remote screen view.
 */
class TahanyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Tahany accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.d(TAG, "Tahany accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    companion object {
        private const val TAG = "TahanyAccessibility"
        private var instance: TahanyAccessibilityService? = null

        fun isEnabled(): Boolean = instance != null

        fun injectTouch(touch: TahanyMessage.TouchEvent, display: Size): Boolean {
            val service = instance ?: return false
            val swiping = touch.action == TOUCH_SWIPE
            if (!swiping && touch.action != TOUCH_TAP) return false
            if (listOf(touch.xRatio, touch.yRatio, touch.endXRatio, touch.endYRatio).any { it !in 0f..1f }) {
                return false
            }
            val width = (display.width - 1).toFloat()
            val height = (display.height - 1).toFloat()
            val path = Path().apply {
                moveTo(touch.xRatio * width, touch.yRatio * height)
                if (swiping) lineTo(touch.endXRatio * width, touch.endYRatio * height)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, if (swiping) 320 else 50)
            return service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }
    }
}
