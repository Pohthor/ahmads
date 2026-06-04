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

        private val _calibrationSaved = MutableStateFlow(false)
        val calibrationSaved: StateFlow<Boolean> = _calibrationSaved.asStateFlow()

        const val MODEL_URL = "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"
        const val MODEL_FILENAME = "face_landmarker.task"

        @Volatile var instance: EyeTrackingService? = null
            private set

        fun startCalibration() { instance?.detector?.startCalibration() }

        enum class ModelStatus { CHECKING, DOWNLOADING, READY, ERROR }
    }

    private val TAG = "EyeScrollService"
    private val CHANNEL_ID = "eyescroll_tracking"
    private val NOTIFICATION_ID = 1

    private var detector: GazeDetector? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var lastProcessedFrameTime = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        val model = File(filesDir, MODEL_FILENAME)
        _modelStatus.value = if (model.exists() && model.length() > 100_000) ModelStatus.READY else ModelStatus.CHECKING
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val prefs = getSharedPreferences("eyescroll", MODE_PRIVATE)
        val mode = GestureMode.valueOf(prefs.getString("gesture_mode", GestureMode.PITCH.name)!!)
        val modeLabel = when (mode) {
            GestureMode.PITCH -> "Nod up/down"
            GestureMode.ROLL  -> "Tilt left/right"
            GestureMode.YAW   -> "Turn left/right"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EyeScroll active")
            .setContentText(modeLabel)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(this, 0,
                    Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
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

    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }

    override fun onDestroy() {
        instance = null
        stopTracking()
        analysisExecutor.shutdown()
        _isTracking.value = false
        _gazeState.value = GazeState()
        super.onDestroy()
    }

    private fun startTracking() {
        val modelFile = File(filesDir, MODEL_FILENAME)
        if (!modelFile.exists()) return

        val prefs = getSharedPreferences("eyescroll", MODE_PRIVATE)
        val mode = GestureMode.valueOf(prefs.getString("gesture_mode", GestureMode.PITCH.name)!!)
        val sensitivity = prefs.getInt("sensitivity", 60)
        val savedNeutral = prefs.getFloat("neutral_${mode.name}", Float.NaN)

        // sensitivity 0%=0.20 (large movement)  60%=0.14 (medium)  100%=0.08 (small)
        val offset = 0.20f - (sensitivity / 100f) * 0.12f

        val det = GazeDetector(this, object : GazeDetector.Listener {
            override fun onGazeUpdate(state: GazeState) { _gazeState.value = state }

            override fun onScrollTriggered(direction: GazeDetector.ScrollDirection) {
                when (direction) {
                    GazeDetector.ScrollDirection.NEXT -> EyeScrollAccessibilityService.scrollToNext()
                    GazeDetector.ScrollDirection.PREV -> EyeScrollAccessibilityService.scrollToPrev()
                }
            }

            override fun onCalibrationDone(neutralValue: Float) {
                prefs.edit().putFloat("neutral_${mode.name}", neutralValue).apply()
                _calibrationSaved.value = true
                // Reset so the signal can be sent again next time
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    _calibrationSaved.value = false
                }, 100)
                Log.d(TAG, "Calibration done: neutral=$neutralValue mode=$mode")
            }

            override fun onDoubleTap() { EyeScrollAccessibilityService.doubleTap() }
            override fun onError(message: String) { Log.e(TAG, "Detector error: $message") }
        }).also {
            it.gestureMode = mode
            it.sensitivityOffset = offset
            if (!savedNeutral.isNaN()) it.calibratedNeutral = savedNeutral
        }

        try {
            det.initialize(modelFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize GazeDetector", e)
            return
        }

        detector = det

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindCamera(det)
            _isTracking.value = true
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(det: GazeDetector) {
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(320, 240))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalysis.setAnalyzer(analysisExecutor) { proxy ->
            val now = System.currentTimeMillis()
            if (now - lastProcessedFrameTime >= 67L) {
                lastProcessedFrameTime = now
                det.processFrame(proxy.toBitmap())
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
        detector?.close()
        detector = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "EyeScroll", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Head tracking active"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
