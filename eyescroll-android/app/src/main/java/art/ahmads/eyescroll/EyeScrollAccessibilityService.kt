package art.ahmads.eyescroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class EyeScrollAccessibilityService : AccessibilityService() {

    companion object {
        var instance: EyeScrollAccessibilityService? = null
            private set

        fun scrollToNext() {
            instance?.performSwipeUp()
        }
    }

    override fun onServiceConnected() {
        instance = this
        // Explicitly configure the service so it's recognised on all manufacturers
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
            notificationTimeout = 0
        }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun performSwipeUp() {
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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}
}
