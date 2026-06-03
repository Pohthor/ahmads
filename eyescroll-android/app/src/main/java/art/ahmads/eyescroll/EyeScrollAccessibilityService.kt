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
            val metrics = resources.displayMetrics
            val cx = metrics.widthPixels / 2f
            val startY = metrics.heightPixels * 0.72f
            val endY = metrics.heightPixels * 0.28f

            val path = Path().apply {
                moveTo(cx, startY)
                lineTo(cx, endY)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 350L))
                .build()

            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            // Ignore gesture failures silently
        }
    }

    fun performSwipeDown() {
        try {
            val metrics = resources.displayMetrics
            val cx = metrics.widthPixels / 2f
            val startY = metrics.heightPixels * 0.28f
            val endY = metrics.heightPixels * 0.72f
            val path = Path().apply {
                moveTo(cx, startY)
                lineTo(cx, endY)
            }
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 350L))
                    .build(),
                null, null
            )
        } catch (e: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}
}
