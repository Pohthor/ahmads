package art.ahmads.eyescroll

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerOptions
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.io.File
import java.nio.ByteBuffer

data class GazeState(
    val faceDetected: Boolean = false,
    val lookUpScore: Float = 0f,
    val lookDownScore: Float = 0f,
    val isInDwell: Boolean = false,
    val dwellProgress: Float = 0f   // 0..1, how close to scroll trigger
)

class GazeDetector(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onGazeUpdate(state: GazeState)
        fun onScrollTriggered()
        fun onError(message: String)
    }

    var lookUpThreshold: Float = 0.30f
    var dwellTimeMs: Long = 1_000L
    var scrollCooldownMs: Long = 2_500L

    private var faceLandmarker: FaceLandmarker? = null
    private var lookUpStartTime = 0L
    private var lastScrollTime = 0L
    private var smoothedUp = 0f
    private var smoothedDown = 0f

    fun initialize(modelFile: File) {
        val modelBytes = modelFile.readBytes()
        val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size).apply {
            put(modelBytes)
            rewind()
        }

        val options = FaceLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetBuffer(modelBuffer)
                    .build()
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setResultListener(::onResult)
            .setErrorListener { error ->
                listener.onError(error.message ?: "MediaPipe error")
            }
            .build()

        faceLandmarker = FaceLandmarker.createFromOptions(context, options)
    }

    fun processFrame(bitmap: Bitmap) {
        val mpImage = BitmapImageBuilder(bitmap).build()
        faceLandmarker?.detectAsync(mpImage, SystemClock.uptimeMillis())
    }

    private fun onResult(result: FaceLandmarkerResult, @Suppress("UNUSED_PARAMETER") input: com.google.mediapipe.framework.image.MPImage) {
        val blendshapes = result.faceBlendshapes()
        if (blendshapes.isEmpty || blendshapes.get().isEmpty()) {
            smoothedUp = smoothedUp * 0.7f
            smoothedDown = smoothedDown * 0.7f
            lookUpStartTime = 0L
            listener.onGazeUpdate(GazeState(faceDetected = false))
            return
        }

        val shapes = blendshapes.get()[0]
        val rawUp = shapes.avgScore("eyeLookUpLeft", "eyeLookUpRight")
        val rawDown = shapes.avgScore("eyeLookDownLeft", "eyeLookDownRight")

        // Exponential smoothing to reduce jitter
        smoothedUp = smoothedUp * 0.5f + rawUp * 0.5f
        smoothedDown = smoothedDown * 0.5f + rawDown * 0.5f

        val now = System.currentTimeMillis()
        val isLookingUp = smoothedUp > lookUpThreshold

        if (isLookingUp) {
            if (lookUpStartTime == 0L) lookUpStartTime = now
            val elapsed = now - lookUpStartTime
            val dwellProgress = (elapsed.toFloat() / dwellTimeMs).coerceIn(0f, 1f)

            listener.onGazeUpdate(
                GazeState(
                    faceDetected = true,
                    lookUpScore = smoothedUp,
                    lookDownScore = smoothedDown,
                    isInDwell = true,
                    dwellProgress = dwellProgress
                )
            )

            val sinceLast = now - lastScrollTime
            if (elapsed >= dwellTimeMs && sinceLast >= scrollCooldownMs) {
                lastScrollTime = now
                lookUpStartTime = 0L
                listener.onScrollTriggered()
            }
        } else {
            lookUpStartTime = 0L
            listener.onGazeUpdate(
                GazeState(
                    faceDetected = true,
                    lookUpScore = smoothedUp,
                    lookDownScore = smoothedDown,
                    isInDwell = false,
                    dwellProgress = 0f
                )
            )
        }
    }

    private fun List<com.google.mediapipe.tasks.components.containers.Category>.avgScore(
        vararg names: String
    ): Float {
        var total = 0f
        var count = 0
        for (name in names) {
            val score = firstOrNull { it.categoryName() == name }?.score() ?: 0f
            total += score
            count++
        }
        return if (count == 0) 0f else total / count
    }

    fun close() {
        faceLandmarker?.close()
        faceLandmarker = null
    }
}
