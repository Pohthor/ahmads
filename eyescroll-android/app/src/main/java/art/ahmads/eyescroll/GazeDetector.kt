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

enum class GestureMode { PITCH, ROLL, YAW }

data class GazeState(
    val faceDetected: Boolean = false,
    val isLookingNext: Boolean = false,
    val isLookingPrev: Boolean = false,
    val isCalibrating: Boolean = false,
    val calibrationProgress: Float = 0f,
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
        fun onCalibrationDone(neutralValue: Float)
        fun onDoubleTap()
        fun onError(message: String)
    }

    var gestureMode: GestureMode = GestureMode.PITCH
    var calibratedNeutral: Float = Float.NaN  // NaN = not yet calibrated, uses default
    var sensitivityOffset: Float = 0.14f      // how far from neutral to trigger
    var dwellTimeMs: Long = 0L
    var scrollCooldownMs: Long = 1_500L

    private val calibrationDurationMs = 3_000L
    private var calibrating = false
    private var calibrationStartTime = 0L
    private val calibrationSamples = mutableListOf<Float>()

    private var faceLandmarker: FaceLandmarker? = null
    private val imageProcessingOptions = ImageProcessingOptions.builder().build()

    private var smoothed = Float.NaN
    private var lookNextStartTime = 0L
    private var lookPrevStartTime = 0L
    private var lastScrollTime = 0L

    fun initialize(modelFile: File) {
        val bytes = modelFile.readBytes()
        val modelBuffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }

        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(modelBuffer).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(false)
            .setResultListener { result: FaceLandmarkerResult, _: MPImage -> onResult(result) }
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

    /** Call to begin 3-second auto-calibration. User should look straight at phone. */
    fun startCalibration() {
        calibrating = true
        calibrationStartTime = System.currentTimeMillis()
        calibrationSamples.clear()
        smoothed = Float.NaN
        lookNextStartTime = 0L
        lookPrevStartTime = 0L
    }

    private fun onResult(result: FaceLandmarkerResult) {
        val landmarksList = result.faceLandmarks()
        if (landmarksList.isEmpty()) {
            smoothed = Float.NaN
            lookNextStartTime = 0L
            lookPrevStartTime = 0L
            listener.onGazeUpdate(GazeState(faceDetected = false))
            return
        }

        val lm = landmarksList[0]
        val raw = computeMetric(lm) ?: run {
            listener.onGazeUpdate(GazeState(faceDetected = true))
            return
        }

        // Exponential smoothing
        smoothed = if (smoothed.isNaN()) raw else smoothed * 0.65f + raw * 0.35f

        val now = System.currentTimeMillis()

        // ── Calibration mode ────────────────────────────────────────────────────────
        if (calibrating) {
            calibrationSamples.add(smoothed)
            val elapsed = now - calibrationStartTime
            val progress = (elapsed.toFloat() / calibrationDurationMs).coerceIn(0f, 1f)
            listener.onGazeUpdate(GazeState(
                faceDetected = true,
                isCalibrating = true,
                calibrationProgress = progress
            ))
            if (elapsed >= calibrationDurationMs) {
                calibrating = false
                if (calibrationSamples.isNotEmpty()) {
                    val neutral = calibrationSamples.average().toFloat()
                    calibratedNeutral = neutral
                    listener.onCalibrationDone(neutral)
                }
            }
            return
        }

        // ── Detection mode ───────────────────────────────────────────────────────────
        val neutral = if (calibratedNeutral.isNaN()) defaultNeutral() else calibratedNeutral

        val lookingNext = smoothed < neutral - sensitivityOffset
        val lookingPrev = smoothed > neutral + sensitivityOffset * 1.2f

        when {
            lookingNext -> {
                lookPrevStartTime = 0L
                if (lookNextStartTime == 0L) lookNextStartTime = now
                val elapsed = now - lookNextStartTime
                val progress = if (dwellTimeMs == 0L) 1f
                               else (elapsed.toFloat() / dwellTimeMs).coerceIn(0f, 1f)
                listener.onGazeUpdate(GazeState(
                    faceDetected = true, isLookingNext = true,
                    dwellProgress = progress
                ))
                if (elapsed >= dwellTimeMs && (now - lastScrollTime) >= scrollCooldownMs) {
                    lastScrollTime = now
                    lookNextStartTime = 0L
                    listener.onScrollTriggered(ScrollDirection.NEXT)
                }
            }
            lookingPrev -> {
                lookNextStartTime = 0L
                if (lookPrevStartTime == 0L) lookPrevStartTime = now
                val elapsed = now - lookPrevStartTime
                val progress = if (dwellTimeMs == 0L) 1f
                               else (elapsed.toFloat() / dwellTimeMs).coerceIn(0f, 1f)
                listener.onGazeUpdate(GazeState(
                    faceDetected = true, isLookingPrev = true,
                    dwellProgress = progress
                ))
                if (elapsed >= dwellTimeMs && (now - lastScrollTime) >= scrollCooldownMs) {
                    lastScrollTime = now
                    lookPrevStartTime = 0L
                    listener.onScrollTriggered(ScrollDirection.PREV)
                }
            }
            else -> {
                lookNextStartTime = 0L
                lookPrevStartTime = 0L
                listener.onGazeUpdate(GazeState(faceDetected = true))
            }
        }
    }

    private fun computeMetric(lm: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Float? {
        val leftEye  = lm[33]   // person's right eye outer corner
        val rightEye = lm[263]  // person's left eye outer corner
        val nose     = lm[1]    // nose tip
        val iod = abs(leftEye.x() - rightEye.x())
        if (iod < 0.01f) return null

        return when (gestureMode) {
            // Low = head up/back → NEXT  |  High = head down/forward → PREV
            GestureMode.PITCH -> (nose.y() - (leftEye.y() + rightEye.y()) / 2f) / iod
            // Low = head turns right → NEXT  |  High = head turns left → PREV
            GestureMode.YAW   -> (nose.x() - (leftEye.x() + rightEye.x()) / 2f) / iod
            // Low = tilt right (right ear down) → NEXT  |  High = tilt left → PREV
            GestureMode.ROLL  -> (rightEye.y() - leftEye.y()) / iod
        }
    }

    private fun defaultNeutral() = when (gestureMode) {
        GestureMode.PITCH -> 0.70f
        GestureMode.YAW   -> 0.0f
        GestureMode.ROLL  -> 0.0f
    }

    fun close() {
        faceLandmarker?.close()
        faceLandmarker = null
    }
}
