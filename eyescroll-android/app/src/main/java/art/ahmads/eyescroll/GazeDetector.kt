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
    val isLookingUp: Boolean = false,
    val isLookingDown: Boolean = false,
    val isInDwell: Boolean = false,
    val dwellProgress: Float = 0f
)

class GazeDetector(
    private val context: Context,
    private val listener: Listener
) {
    enum class ScrollDirection { NEXT, PREV }

    interface Listener {
        fun onGazeUpdate(state: GazeState)
        fun onScrollTriggered(direction: ScrollDirection)
        fun onError(message: String)
    }

    // Normalized pitch = (noseY - eyeMidY) / IOD
    // Neutral ≈ 0.70 | Head tilts UP (back) → decreases | Head tilts DOWN (forward) → increases
    var headUpThreshold: Float = 0.50f
    var headDownThreshold: Float = 0.90f
    var dwellTimeMs: Long = 0L
    var scrollCooldownMs: Long = 2_500L

    private var faceLandmarker: FaceLandmarker? = null
    private val imageProcessingOptions = ImageProcessingOptions.builder().build()

    private var lookUpStartTime = 0L
    private var lookDownStartTime = 0L
    private var lastScrollTime = 0L
    private var smoothedPitch = 0.7f

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
            .setOutputFaceBlendshapes(false)
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
        val landmarksList = result.faceLandmarks()   // List<List<NormalizedLandmark>> — no Optional
        if (landmarksList.isEmpty()) {
            lookUpStartTime = 0L
            lookDownStartTime = 0L
            smoothedPitch = 0.7f
            listener.onGazeUpdate(GazeState(faceDetected = false))
            return
        }

        val landmarks = landmarksList[0]

        // Landmark 33: left eye outer corner | 263: right eye outer | 1: nose tip
        val leftEye  = landmarks[33]
        val rightEye = landmarks[263]
        val noseTip  = landmarks[1]

        val eyeMidY = (leftEye.y() + rightEye.y()) / 2f
        val iod = abs(leftEye.x() - rightEye.x())

        if (iod < 0.01f) {
            listener.onGazeUpdate(GazeState(faceDetected = true))
            return
        }

        val rawPitch = (noseTip.y() - eyeMidY) / iod
        smoothedPitch = smoothedPitch * 0.6f + rawPitch * 0.4f

        val now = System.currentTimeMillis()

        when {
            smoothedPitch < headUpThreshold -> {
                lookDownStartTime = 0L
                if (lookUpStartTime == 0L) lookUpStartTime = now
                val elapsed = now - lookUpStartTime
                val progress = if (dwellTimeMs == 0L) 1f
                               else (elapsed.toFloat() / dwellTimeMs).coerceIn(0f, 1f)
                listener.onGazeUpdate(GazeState(
                    faceDetected = true, isLookingUp = true,
                    isInDwell = true, dwellProgress = progress
                ))
                if (elapsed >= dwellTimeMs && (now - lastScrollTime) >= scrollCooldownMs) {
                    lastScrollTime = now
                    lookUpStartTime = 0L
                    listener.onScrollTriggered(ScrollDirection.NEXT)
                }
            }
            smoothedPitch > headDownThreshold -> {
                lookUpStartTime = 0L
                if (lookDownStartTime == 0L) lookDownStartTime = now
                val elapsed = now - lookDownStartTime
                val progress = if (dwellTimeMs == 0L) 1f
                               else (elapsed.toFloat() / dwellTimeMs).coerceIn(0f, 1f)
                listener.onGazeUpdate(GazeState(
                    faceDetected = true, isLookingDown = true,
                    isInDwell = true, dwellProgress = progress
                ))
                if (elapsed >= dwellTimeMs && (now - lastScrollTime) >= scrollCooldownMs) {
                    lastScrollTime = now
                    lookDownStartTime = 0L
                    listener.onScrollTriggered(ScrollDirection.PREV)
                }
            }
            else -> {
                lookUpStartTime = 0L
                lookDownStartTime = 0L
                listener.onGazeUpdate(GazeState(faceDetected = true))
            }
        }
    }

    fun close() {
        faceLandmarker?.close()
        faceLandmarker = null
    }
}
