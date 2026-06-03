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
    val isMovingRight: Boolean = false,
    val isMovingLeft: Boolean = false
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

    // Velocity threshold: how much yaw must change within the window to trigger
    var velocityThreshold: Float = 0.18f
    var scrollCooldownMs: Long = 1_500L
    private val historyWindowMs = 400L  // look at movement over last 400ms

    private var faceLandmarker: FaceLandmarker? = null
    private val imageProcessingOptions = ImageProcessingOptions.builder().build()

    // Ring buffer of (yaw, timestamp) for velocity calculation
    private val yawHistory = ArrayDeque<Pair<Float, Long>>()
    private var lastScrollTime = 0L

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
        val landmarksList = result.faceLandmarks()
        if (landmarksList.isEmpty()) {
            yawHistory.clear()
            listener.onGazeUpdate(GazeState(faceDetected = false))
            return
        }

        val lm = landmarksList[0]

        // Yaw proxy: (noseX - eyeMidX) / IOD
        // Stable, proven formula — neutral ≈ 0, turns left/right change the sign
        val leftEye  = lm[33]
        val rightEye = lm[263]
        val noseTip  = lm[1]

        val eyeMidX = (leftEye.x() + rightEye.x()) / 2f
        val iod = abs(leftEye.x() - rightEye.x())

        if (iod < 0.01f) {
            listener.onGazeUpdate(GazeState(faceDetected = true))
            return
        }

        val rawYaw = (noseTip.x() - eyeMidX) / iod
        val now = System.currentTimeMillis()

        // Add to history and trim old entries
        yawHistory.addLast(Pair(rawYaw, now))
        while (yawHistory.size > 1 && now - yawHistory.first().second > historyWindowMs) {
            yawHistory.removeFirst()
        }

        // Velocity = change in yaw over the history window
        val velocity = if (yawHistory.size >= 2) rawYaw - yawHistory.first().first else 0f

        val cooldownOk = (now - lastScrollTime) >= scrollCooldownMs

        when {
            velocity > velocityThreshold && cooldownOk -> {
                lastScrollTime = now
                yawHistory.clear()
                listener.onScrollTriggered(ScrollDirection.PREV)
                listener.onGazeUpdate(GazeState(faceDetected = true, isMovingLeft = true))
            }
            velocity < -velocityThreshold && cooldownOk -> {
                lastScrollTime = now
                yawHistory.clear()
                listener.onScrollTriggered(ScrollDirection.NEXT)
                listener.onGazeUpdate(GazeState(faceDetected = true, isMovingRight = true))
            }
            else -> {
                val moving = abs(velocity) > velocityThreshold * 0.5f
                listener.onGazeUpdate(GazeState(
                    faceDetected = true,
                    isMovingRight = velocity < -velocityThreshold * 0.5f,
                    isMovingLeft  = velocity >  velocityThreshold * 0.5f
                ))
            }
        }
    }

    fun close() {
        faceLandmarker?.close()
        faceLandmarker = null
    }
}
