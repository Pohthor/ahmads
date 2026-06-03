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

    // Wink detection thresholds
    var winkThreshold: Float = 0.72f        // score to start a wink (raised to avoid natural blink triggers)
    var winkReleaseThreshold: Float = 0.45f // score below which eye is "open" again (raised for easier double wink)
    var scrollCooldownMs: Long = 1_200L
    private val minWinkDurationMs: Long = 80L   // must hold wink this long — filters out accidental blinks
    private val doubleTapWindowMs: Long = 900L  // window for second wink to count as double-tap

    private var faceLandmarker: FaceLandmarker? = null
    private val imageProcessingOptions = ImageProcessingOptions.builder().build()

    // Per-eye state
    private var rightEyeWinking = false
    private var leftEyeWinking = false
    private var rightWinkOnsetTime = 0L   // when right eye first crossed threshold
    private var leftWinkOnsetTime = 0L
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
            .setOutputFaceBlendshapes(true)
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
        if (result.faceLandmarks().isEmpty()) {
            rightEyeWinking = false
            leftEyeWinking = false
            rightWinkOnsetTime = 0L
            leftWinkOnsetTime = 0L
            listener.onGazeUpdate(GazeState(faceDetected = false))
            return
        }

        val blendshapes = result.faceBlendshapes()
        if (!blendshapes.isPresent || blendshapes.get().isEmpty()) {
            listener.onGazeUpdate(GazeState(faceDetected = true))
            return
        }

        val categories = blendshapes.get()[0]
        val rightBlink = categories.find { it.categoryName() == "eyeBlinkRight" }?.score() ?: 0f
        val leftBlink  = categories.find { it.categoryName() == "eyeBlinkLeft"  }?.score() ?: 0f

        val now = System.currentTimeMillis()

        // A genuine wink: one eye clearly closed + other eye clearly open + strong asymmetry
        // This triple check eliminates natural blinks where both eyes close together
        val rightIsWinking = rightBlink > winkThreshold
                          && leftBlink < 0.40f
                          && (rightBlink - leftBlink) > 0.30f
        val leftIsWinking  = leftBlink  > winkThreshold
                          && rightBlink < 0.40f
                          && (leftBlink - rightBlink) > 0.30f

        // Track onset time (for minimum-duration filter)
        if (rightIsWinking) {
            if (rightWinkOnsetTime == 0L) rightWinkOnsetTime = now
        } else {
            rightWinkOnsetTime = 0L
            if (rightBlink < winkReleaseThreshold) rightEyeWinking = false
        }

        if (leftIsWinking) {
            if (leftWinkOnsetTime == 0L) leftWinkOnsetTime = now
        } else {
            leftWinkOnsetTime = 0L
            if (leftBlink < winkReleaseThreshold) leftEyeWinking = false
        }

        // Fire only after wink is held for minWinkDurationMs (rising edge)
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
            isWinkingLeft = leftEyeWinking
        ))
    }

    fun close() {
        faceLandmarker?.close()
        faceLandmarker = null
    }
}
