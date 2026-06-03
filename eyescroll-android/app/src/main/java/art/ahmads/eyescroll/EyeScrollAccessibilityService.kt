package art.ahmads.eyescroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class EyeScrollAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: EyeScrollAccessibilityService? = null
            private set

        fun scrollToNext() { instance?.performSwipeUp() }
        fun scrollToPrev() { instance?.performSwipeDown() }
        fun doubleTap()    { instance?.performDoubleTap() }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun performSwipeUp() {
        try {
            val m = resources.displayMetrics
            val cx = m.widthPixels / 2f
            val path = Path().apply {
                moveTo(cx, m.heightPixels * 0.72f)
                lineTo(cx, m.heightPixels * 0.28f)
            }
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 350L))
                    .build(), null, null
            )
        } catch (e: Exception) {}
    }

    fun performSwipeDown() {
        try {
            val m = resources.displayMetrics
            val cx = m.widthPixels / 2f
            val path = Path().apply {
                moveTo(cx, m.heightPixels * 0.28f)
                lineTo(cx, m.heightPixels * 0.72f)
            }
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 350L))
                    .build(), null, null
            )
        } catch (e: Exception) {}
    }

    fun performDoubleTap() {
        try {
            val m = resources.displayMetrics
            val cx = m.widthPixels / 2f
            val cy = m.heightPixels / 2f
            // Two taps 120ms apart — Instagram registers this as a like
            val tap1 = Path().apply { moveTo(cx, cy - 1f); lineTo(cx, cy + 1f) }
            val tap2 = Path().apply { moveTo(cx, cy - 1f); lineTo(cx, cy + 1f) }
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(tap1, 0L,   60L))
                    .addStroke(GestureDescription.StrokeDescription(tap2, 120L, 60L))
                    .build(), null, null
            )
        } catch (e: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}
}
