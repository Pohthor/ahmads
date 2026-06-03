package art.ahmads.eyescroll

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

data class GazeState(
    val faceDetected: Boolean = false,
    val isWinkingRight: Boolean = false,
    val isWinkingLeft: Boolean = false
)

class GazeDetector(
    private val context: Context,
    private val listener: Listener
) {
    enum class ScrollDirection { NEXT, PREV }

    interface Listener {
        fun onGazeUpdate(state: GazeState)
        fun onScrollTriggered(direction: ScrollDirection)
        fun onDoubleTap()
        fun onError(message: String)
    }

    // Eye Aspect Ratio thresholds (EAR = eye_height / eye_width)
    // Open eye ≈ 0.20-0.35 | Closed/winking eye ≈ 0.02-0.10
    var earCloseThreshold: Float = 0.14f  // EAR below this = eye is winking
    var earOpenThreshold: Float  = 0.18f  // EAR above this = eye is open
    var scrollCooldownMs: Long = 1_200L
    private val minWinkDurationMs: Long = 80L
    private val doubleTapWindowMs: Long = 900L

    private var faceLandmarker: FaceLandmarker? = null
    private val imageProcessingOptions = ImageProcessingOptions.builder().build()

    private var rightEyeWinking = false
    private var leftEyeWinking  = false
    private var rightWinkOnsetTime = 0L
    private var leftWinkOnsetTime  = 0L
    private var lastScrollTime = 0L
    private var lastRightWinkTime = 0L

    fun initialize(modelFile: File) {
        val bytes = modelFile.readBytes()
        val modelBuffer = ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            rewind()
        }

        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetBuffer(modelBuffer)
                    .build()
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(false)  // not needed — we use EAR from landmarks
            .setResultListener { result: FaceLandmarkerResult, _: MPImage ->
                onResult(result)
            }
            .setErrorListener { error: RuntimeException ->
                listener.onError(error.message ?: "MediaPipe error")
            }
            .build()

        faceLandmarker = FaceLandmarker.createFromOptions(context, options)
    }

    fun processFrame(bitmap: Bitmap) {
        val mpImage = BitmapImageBuilder(bitmap).build()
        faceLandmarker?.detectAsync(mpImage, imageProcessingOptions, SystemClock.uptimeMillis())
    }

    private fun onResult(result: FaceLandmarkerResult) {
        val landmarksList = result.faceLandmarks()
        if (landmarksList.isEmpty()) {
            rightEyeWinking = false
            leftEyeWinking  = false
            rightWinkOnsetTime = 0L
            leftWinkOnsetTime  = 0L
            listener.onGazeUpdate(GazeState(faceDetected = false))
            return
        }

        val lm = landmarksList[0]

        // Right eye (person's right): outer=33, inner=133, top=159, bottom=145
        // Left eye  (person's left):  outer=263, inner=362, top=386, bottom=374
        val rightEAR = ear(lm[33], lm[133], lm[159], lm[145])
        val leftEAR  = ear(lm[263], lm[362], lm[386], lm[374])

        // Wink = one eye clearly closed, other clearly open, strong asymmetry
        val rightIsWinking = rightEAR < earCloseThreshold
                          && leftEAR  > earOpenThreshold
                          && (leftEAR - rightEAR) > 0.06f
        val leftIsWinking  = leftEAR  < earCloseThreshold
                          && rightEAR > earOpenThreshold
                          && (rightEAR - leftEAR) > 0.06f

        val now = System.currentTimeMillis()

        // Track onset times
        if (rightIsWinking) {
            if (rightWinkOnsetTime == 0L) rightWinkOnsetTime = now
        } else {
            rightWinkOnsetTime = 0L
            if (rightEAR > earOpenThreshold) rightEyeWinking = false
        }

        if (leftIsWinking) {
            if (leftWinkOnsetTime == 0L) leftWinkOnsetTime = now
        } else {
            leftWinkOnsetTime = 0L
            if (leftEAR > earOpenThreshold) leftEyeWinking = false
        }

        // Fire on rising edge after minWinkDurationMs
        val rightConfirmed = rightWinkOnsetTime > 0 && (now - rightWinkOnsetTime) >= minWinkDurationMs
        if (rightConfirmed && !rightEyeWinking) {
            rightEyeWinking = true
            val isDoubleTap = lastRightWinkTime > 0 && (now - lastRightWinkTime) < doubleTapWindowMs
            lastRightWinkTime = now
            if (isDoubleTap) {
                listener.onDoubleTap()
            } else if ((now - lastScrollTime) >= scrollCooldownMs) {
                lastScrollTime = now
                listener.onScrollTriggered(ScrollDirection.NEXT)
            }
        }

        val leftConfirmed = leftWinkOnsetTime > 0 && (now - leftWinkOnsetTime) >= minWinkDurationMs
        if (leftConfirmed && !leftEyeWinking) {
            leftEyeWinking = true
            if ((now - lastScrollTime) >= scrollCooldownMs) {
                lastScrollTime = now
                listener.onScrollTriggered(ScrollDirection.PREV)
            }
        }

        listener.onGazeUpdate(GazeState(
            faceDetected = true,
            isWinkingRight = rightEyeWinking,
            isWinkingLeft  = leftEyeWinking
        ))
    }

    // Eye Aspect Ratio = vertical opening / horizontal width
    private fun ear(
        outer: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        inner: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        top:   com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        bottom: com.google.mediapipe.tasks.components.containers.NormalizedLandmark
    ): Float {
        val width = abs(outer.x() - inner.x())
        if (width < 0.001f) return 0.25f
        return abs(top.y() - bottom.y()) / width
    }

    fun close() {
        faceLandmarker?.close()
        faceLandmarker = null
    }
}
