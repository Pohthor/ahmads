package art.ahmads.eyescroll

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL
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

        fun modelFile(service: EyeTrackingService) = File(service.filesDir, MODEL_FILENAME)

        enum class ModelStatus { CHECKING, DOWNLOADING, READY, ERROR }
    }

    private val TAG = "EyeScrollService"
    private val CHANNEL_ID = "eyescroll_tracking"
    private val NOTIFICATION_ID = 1

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var gazeDetector: GazeDetector? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    var lookUpThreshold: Float = 0.30f
        set(value) {
            field = value
            gazeDetector?.lookUpThreshold = value
        }

    var dwellTimeMs: Long = 1_000L
        set(value) {
            field = value
            gazeDetector?.dwellTimeMs = value
        }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        checkModel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EyeScroll")
            .setContentText("Eye tracking active — look up to scroll")
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

    private fun checkModel() {
        val model = File(filesDir, MODEL_FILENAME)
        _modelStatus.value = if (model.exists() && model.length() > 100_000) {
            ModelStatus.READY
        } else {
            ModelStatus.CHECKING
        }
    }

    fun downloadModel(onProgress: (Float) -> Unit, onDone: (Boolean) -> Unit) {
        _modelStatus.value = ModelStatus.DOWNLOADING
        serviceScope.launch(Dispatchers.IO) {
            try {
                val out = File(filesDir, MODEL_FILENAME)
                val connection = URL(MODEL_URL).openConnection()
                connection.connect()
                val total = connection.contentLengthLong.toFloat()
                var downloaded = 0L

                connection.getInputStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) onProgress(downloaded / total)
                        }
                    }
                }

                _modelStatus.value = ModelStatus.READY
                onDone(true)
            } catch (e: Exception) {
                Log.e(TAG, "Model download failed", e)
                _modelStatus.value = ModelStatus.ERROR
                onDone(false)
            }
        }
    }

    private fun startTracking() {
        val modelFile = File(filesDir, MODEL_FILENAME)
        if (!modelFile.exists()) {
            Log.w(TAG, "Model not available, cannot start tracking")
            return
        }

        val detector = GazeDetector(this, object : GazeDetector.Listener {
            override fun onGazeUpdate(state: GazeState) {
                _gazeState.value = state
            }

            override fun onScrollTriggered() {
                Log.d(TAG, "Scroll triggered by gaze")
                EyeScrollAccessibilityService.scrollToNext()
            }

            override fun onError(message: String) {
                Log.e(TAG, "GazeDetector error: $message")
            }
        }).also {
            it.lookUpThreshold = lookUpThreshold
            it.dwellTimeMs = dwellTimeMs
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

    private fun bindCamera(detector: GazeDetector) {
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(320, 240))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            processImageProxy(imageProxy, detector)
        }

        try {
            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                imageAnalysis
            )
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
        }
    }

    private fun processImageProxy(imageProxy: ImageProxy, detector: GazeDetector) {
        val bitmap = imageProxy.toBitmap()
        detector.processFrame(bitmap)
        imageProxy.close()
    }

    private fun stopTracking() {
        cameraProvider?.unbindAll()
        gazeDetector?.close()
        gazeDetector = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Eye Tracking",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "EyeScroll background tracking"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
