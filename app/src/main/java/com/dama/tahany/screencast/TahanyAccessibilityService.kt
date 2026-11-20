package com.dama.tahany.screencast

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.DisplayMetrics
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

        fun injectTouch(touch: TahanyMessage.TouchEvent): Boolean {
            val service = instance ?: return false
            val metrics: DisplayMetrics = service.resources.displayMetrics
            val x = (touch.xRatio * metrics.widthPixels).coerceIn(0f, metrics.widthPixels.toFloat())
            val y = (touch.yRatio * metrics.heightPixels).coerceIn(0f, metrics.heightPixels.toFloat())

            val path = Path().apply {
                moveTo(x, y)
                // Small delta for a tap
                lineTo(x + 1f, y + 1f)
            }

            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            return service.dispatchGesture(gesture, null, null)
        }
    }
}
