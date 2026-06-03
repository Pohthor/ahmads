package art.ahmads.eyescroll

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.io.File
import java.nio.ByteBuffer

data class GazeState(
    val faceDetected: Boolean = false,
    val lookUpScore: Float = 0f,
    val lookDownScore: Float = 0f,
    val isInDwell: Boolean = false,
    val dwellProgress: Float = 0f
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
    var dwellTimeMs: Long = 0L
    var scrollCooldownMs: Long = 2_500L

    private var faceLandmarker: FaceLandmarker? = null
    private val imageProcessingOptions = ImageProcessingOptions.builder().build()

    private var lookUpStartTime = 0L
    private var lastScrollTime = 0L
    private var smoothedUp = 0f
    private var smoothedDown = 0f

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
        val blendshapesOpt = result.faceBlendshapes()
        if (!blendshapesOpt.isPresent || blendshapesOpt.get().isEmpty()) {
            smoothedUp *= 0.7f
            smoothedDown *= 0.7f
            lookUpStartTime = 0L
            listener.onGazeUpdate(GazeState(faceDetected = false))
            return
        }

        val shapes: List<Category> = blendshapesOpt.get()[0]
        val rawUp = shapes.avgScore("eyeLookUpLeft", "eyeLookUpRight")
        val rawDown = shapes.avgScore("eyeLookDownLeft", "eyeLookDownRight")

        smoothedUp = smoothedUp * 0.5f + rawUp * 0.5f
        smoothedDown = smoothedDown * 0.5f + rawDown * 0.5f

        val now = System.currentTimeMillis()
        val isLookingUp = smoothedUp > lookUpThreshold

        if (isLookingUp) {
            if (lookUpStartTime == 0L) lookUpStartTime = now
            val elapsed = now - lookUpStartTime
            val dwellProgress = if (dwellTimeMs == 0L) 1f else (elapsed.toFloat() / dwellTimeMs).coerceIn(0f, 1f)

            listener.onGazeUpdate(
                GazeState(
                    faceDetected = true,
                    lookUpScore = smoothedUp,
                    lookDownScore = smoothedDown,
                    isInDwell = true,
                    dwellProgress = dwellProgress
                )
            )

            if (elapsed >= dwellTimeMs && (now - lastScrollTime) >= scrollCooldownMs) {
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

    private fun List<Category>.avgScore(vararg names: String): Float {
        var total = 0f
        for (name in names) {
            total += firstOrNull { it.categoryName() == name }?.score() ?: 0f
        }
        return total / names.size
    }

    fun close() {
        faceLandmarker?.close()
        faceLandmarker = null
    }
}
