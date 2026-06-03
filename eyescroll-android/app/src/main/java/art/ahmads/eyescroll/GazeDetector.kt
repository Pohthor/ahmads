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

    // eyeBlinkRight/Left blendshape score: 0.0 = fully open, 1.0 = fully closed
    var winkThreshold: Float = 0.65f        // score to register a wink
    var winkReleaseThreshold: Float = 0.30f // score below which eye is considered open again
    var scrollCooldownMs: Long = 1_000L
    private val doubleTapWindowMs: Long = 700L

    private var faceLandmarker: FaceLandmarker? = null
    private val imageProcessingOptions = ImageProcessingOptions.builder().build()

    private var rightEyeWinking = false
    private var leftEyeWinking = false
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

        // Wink = one eye closes while the other stays relatively open (not a natural blink)
        val rightWinking = rightBlink > winkThreshold && leftBlink < 0.45f
        val leftWinking  = leftBlink  > winkThreshold && rightBlink < 0.45f

        val now = System.currentTimeMillis()

        // Right eye wink — rising edge only
        if (rightWinking && !rightEyeWinking) {
            rightEyeWinking = true
            val isDoubleTap = lastRightWinkTime > 0 && (now - lastRightWinkTime) < doubleTapWindowMs
            lastRightWinkTime = now
            if (isDoubleTap) {
                listener.onDoubleTap()
            } else if ((now - lastScrollTime) >= scrollCooldownMs) {
                lastScrollTime = now
                listener.onScrollTriggered(ScrollDirection.NEXT)
            }
        } else if (!rightWinking && rightBlink < winkReleaseThreshold) {
            rightEyeWinking = false
        }

        // Left eye wink — rising edge only
        if (leftWinking && !leftEyeWinking) {
            leftEyeWinking = true
            if ((now - lastScrollTime) >= scrollCooldownMs) {
                lastScrollTime = now
                listener.onScrollTriggered(ScrollDirection.PREV)
            }
        } else if (!leftWinking && leftBlink < winkReleaseThreshold) {
            leftEyeWinking = false
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
