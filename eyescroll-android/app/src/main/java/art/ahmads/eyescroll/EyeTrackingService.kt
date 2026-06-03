package art.ahmads.eyescroll

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors

class EyeTrackingService : LifecycleService() {

    companion object {
        private val _gazeState = MutableStateFlow(GazeState())
        val gazeState: StateFlow<GazeState> = _gazeState.asStateFlow()

        private val _isTracking = MutableStateFlow(false)
        val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

        private val _modelStatus = MutableStateFlow(ModelStatus.CHECKING)
        val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

        const val MODEL_URL = "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"
        const val MODEL_FILENAME = "face_landmarker.task"

        enum class ModelStatus { CHECKING, DOWNLOADING, READY, ERROR }
    }

    private val TAG = "EyeScrollService"
    private val CHANNEL_ID = "eyescroll_tracking"
    private val NOTIFICATION_ID = 1

    private var gazeDetector: GazeDetector? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val model = File(filesDir, MODEL_FILENAME)
        _modelStatus.value = if (model.exists() && model.length() > 100_000) ModelStatus.READY else ModelStatus.CHECKING
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EyeScroll")
            .setContentText("Wink right → next  |  Wink left → prev  |  Double wink → like")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startTracking()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        stopTracking()
        analysisExecutor.shutdown()
        _isTracking.value = false
        _gazeState.value = GazeState()
        super.onDestroy()
    }

    private fun startTracking() {
        val modelFile = File(filesDir, MODEL_FILENAME)
        if (!modelFile.exists()) {
            Log.w(TAG, "Model not available, cannot start tracking")
            return
        }

        // Map sensitivity (0-100%) → velocity threshold (0.28 → 0.10)
        // Higher sensitivity = smaller threshold = less head movement needed
        val sensitivity = getSharedPreferences("eyescroll", MODE_PRIVATE).getInt("sensitivity", 60)

        val detector = GazeDetector(this, object : GazeDetector.Listener {
            override fun onGazeUpdate(state: GazeState) {
                _gazeState.value = state
            }
            override fun onScrollTriggered(direction: GazeDetector.ScrollDirection) {
                Log.d(TAG, "Scroll: $direction")
                when (direction) {
                    GazeDetector.ScrollDirection.NEXT -> EyeScrollAccessibilityService.scrollToNext()
                    GazeDetector.ScrollDirection.PREV -> EyeScrollAccessibilityService.scrollToPrev()
                }
            }
            override fun onDoubleTap() {
                Log.d(TAG, "Double tap (like)")
                EyeScrollAccessibilityService.doubleTap()
            }
            override fun onError(message: String) {
                Log.e(TAG, "GazeDetector error: $message")
            }
        }).also {
            it.velocityThreshold = 0.28f - (sensitivity / 100f) * 0.18f
        }

        try {
            detector.initialize(modelFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize GazeDetector", e)
            return
        }

        gazeDetector = detector

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindCamera(detector)
            _isTracking.value = true
        }, ContextCompat.getMainExecutor(this))
    }

    private var lastProcessedFrameTime = 0L

    private fun bindCamera(detector: GazeDetector) {
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(320, 240))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalysis.setAnalyzer(analysisExecutor) { proxy ->
            val now = System.currentTimeMillis()
            if (now - lastProcessedFrameTime >= 67L) {  // ~15fps max
                lastProcessedFrameTime = now
                detector.processFrame(proxy.toBitmap())
            }
            proxy.close()
        }
        try {
            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, imageAnalysis)
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
        }
    }

    private fun stopTracking() {
        cameraProvider?.unbindAll()
        gazeDetector?.close()
        gazeDetector = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Eye Tracking", NotificationManager.IMPORTANCE_LOW).apply {
            description = "EyeScroll background tracking"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
