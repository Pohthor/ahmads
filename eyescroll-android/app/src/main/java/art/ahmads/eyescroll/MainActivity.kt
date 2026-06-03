package art.ahmads.eyescroll

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import android.view.accessibility.AccessibilityManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import art.ahmads.eyescroll.databinding.ActivityMainBinding
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var serviceIntent: Intent? = null

    // Face preview (active only when not tracking)
    private var previewFaceLandmarker: FaceLandmarker? = null
    private var previewCameraProvider: ProcessCameraProvider? = null
    private val previewExecutor = Executors.newSingleThreadExecutor()

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            refreshSetupSteps()
            val modelFile = java.io.File(filesDir, EyeTrackingService.MODEL_FILENAME)
            if (!modelFile.exists() || modelFile.length() < 100_000) downloadModel()
            else startPreviewSession()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeServiceState()
        refreshSetupSteps()
    }

    override fun onResume() {
        super.onResume()
        refreshSetupSteps()
        if (hasCameraPermission() && !EyeTrackingService.isTracking.value) {
            val modelFile = java.io.File(filesDir, EyeTrackingService.MODEL_FILENAME)
            val status = EyeTrackingService.modelStatus.value
            if ((!modelFile.exists() || modelFile.length() < 100_000) &&
                status != EyeTrackingService.Companion.ModelStatus.READY &&
                status != EyeTrackingService.Companion.ModelStatus.DOWNLOADING) {
                downloadModel()
            } else if (modelFile.exists() && modelFile.length() > 100_000) {
                startPreviewSession()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (!EyeTrackingService.isTracking.value) stopPreviewSession()
    }

    private fun setupUI() {
        binding.btnGrantCamera.setOnClickListener {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.btnGrantAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnDownloadModel.setOnClickListener {
            downloadModel()
        }

        binding.toggleTracking.setOnCheckedChangeListener { _, enabled ->
            if (!canStartTracking()) {
                binding.toggleTracking.isChecked = false
                Toast.makeText(this, "Complete setup steps first", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            if (enabled) {
                stopPreviewSession()
                startTracking()
            } else {
                stopTracking()
            }
        }

        // Sensitivity slider: higher = more sensitive (less head movement needed)
        binding.seekSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.labelSensitivity.text = "Sensitivity: ${progress}%"
                savePrefs()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        binding.seekDwell.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val ms = (progress / 100f * 1000f).toLong()
                binding.labelDwell.text = if (ms == 0L) "Hold for: Instant"
                                         else "Hold for: ${"%.1f".format(ms / 1000f)}s"
                savePrefs()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        val prefs = getSharedPreferences("eyescroll", MODE_PRIVATE)
        binding.seekSensitivity.progress = prefs.getInt("sensitivity", 60)
        binding.seekDwell.progress = prefs.getInt("dwell", 0)
    }

    private fun observeServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    EyeTrackingService.gazeState.collect { state ->
                        binding.eyeView.updateState(state)
                        if (EyeTrackingService.isTracking.value) {
                            binding.labelStatus.text = when {
                                !state.faceDetected  -> "Looking for face..."
                                state.isMovingRight  -> "→ Turning right — next"
                                state.isMovingLeft   -> "← Turning left — prev"
                                else                 -> "Face detected — turn head to scroll"
                            }
                        }
                    }
                }
                launch {
                    EyeTrackingService.isTracking.collect { tracking ->
                        binding.toggleTracking.isChecked = tracking
                        if (!tracking) {
                            binding.labelStatus.text = "Tracking off"
                            // Restart preview when service stops
                            if (hasCameraPermission() && canStartTracking()) {
                                startPreviewSession()
                            }
                        }
                    }
                }
                launch {
                    EyeTrackingService.modelStatus.collect { status ->
                        refreshSetupSteps(status)
                        // Start preview when model becomes ready
                        if (status == EyeTrackingService.Companion.ModelStatus.READY &&
                            hasCameraPermission() && !EyeTrackingService.isTracking.value) {
                            startPreviewSession()
                        }
                    }
                }
            }
        }
    }

    // ── Face preview ──────────────────────────────────────────────────────────────

    private fun startPreviewSession() {
        if (previewCameraProvider != null) return  // already running
        val modelFile = java.io.File(filesDir, EyeTrackingService.MODEL_FILENAME)
        if (!modelFile.exists() || modelFile.length() < 100_000) return

        binding.previewContainer.visibility = android.view.View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (previewFaceLandmarker == null) {
                    val bytes = modelFile.readBytes()
                    val buf = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }
                    val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buf).build())
                        .setRunningMode(RunningMode.IMAGE)
                        .setNumFaces(1)
                        .build()
                    previewFaceLandmarker = FaceLandmarker.createFromOptions(this@MainActivity, opts)
                }
            } catch (e: Exception) { return@launch }

            runOnUiThread { bindPreviewCamera() }
        }
    }

    private fun bindPreviewCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            previewCameraProvider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(320, 240))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(previewExecutor) { proxy ->
                val bitmap = proxy.toBitmap()
                val mp = BitmapImageBuilder(bitmap).build()
                val result = try { previewFaceLandmarker?.detect(mp) } catch (e: Exception) { null }
                val detected = result?.faceLandmarks()?.isNotEmpty() == true
                runOnUiThread {
                    binding.labelFaceStatus.text = if (detected)
                        "Face detected — wink right=next  left=prev  double=like"
                    else
                        "Position your face in frame"
                    binding.labelFaceStatus.setTextColor(Color.parseColor(
                        if (detected) "#C4A97A" else "#6E6E68"
                    ))
                }
                proxy.close()
            }
            try {
                previewCameraProvider?.unbindAll()
                previewCameraProvider?.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis
                )
            } catch (e: Exception) { /* camera unavailable */ }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopPreviewSession() {
        previewCameraProvider?.unbindAll()
        previewCameraProvider = null
        previewFaceLandmarker?.close()
        previewFaceLandmarker = null
        binding.previewContainer.visibility = android.view.View.GONE
    }

    // ── Setup steps ───────────────────────────────────────────────────────────────

    private fun refreshSetupSteps(
        modelStatus: EyeTrackingService.Companion.ModelStatus = EyeTrackingService.modelStatus.value
    ) {
        val cameraOk = hasCameraPermission()
        val accessOk = isAccessibilityEnabled()
        val modelFile = java.io.File(filesDir, EyeTrackingService.MODEL_FILENAME)
        val modelOk = modelStatus == EyeTrackingService.Companion.ModelStatus.READY ||
                (modelFile.exists() && modelFile.length() > 100_000 &&
                        modelStatus != EyeTrackingService.Companion.ModelStatus.DOWNLOADING)

        binding.stepCamera.setStepDone(cameraOk)
        binding.btnGrantCamera.isEnabled = !cameraOk
        binding.btnGrantCamera.text = if (cameraOk) "✓  Permission granted" else "Grant permission"

        binding.stepAccessibility.setStepDone(accessOk)
        binding.btnGrantAccessibility.isEnabled = !accessOk
        binding.btnGrantAccessibility.text = if (accessOk) "✓  Service enabled" else "Open Accessibility Settings"

        binding.stepModel.setStepDone(modelOk)
        binding.btnDownloadModel.isEnabled = !modelOk && modelStatus != EyeTrackingService.Companion.ModelStatus.DOWNLOADING
        binding.btnDownloadModel.text = when {
            modelOk -> "✓  Model ready"
            modelStatus == EyeTrackingService.Companion.ModelStatus.DOWNLOADING -> "Downloading..."
            modelStatus == EyeTrackingService.Companion.ModelStatus.ERROR -> "Retry download"
            else -> "Download AI model (~5MB)"
        }
        binding.progressModel.visibility = if (modelStatus == EyeTrackingService.Companion.ModelStatus.DOWNLOADING)
            android.view.View.VISIBLE else android.view.View.GONE

        binding.toggleTracking.isEnabled = cameraOk && accessOk && modelOk
    }

    private fun downloadModel() {
        val modelFile = java.io.File(filesDir, EyeTrackingService.MODEL_FILENAME)
        if (modelFile.exists() && modelFile.length() > 100_000) {
            refreshSetupSteps(EyeTrackingService.Companion.ModelStatus.READY)
            startPreviewSession()
            return
        }

        refreshSetupSteps(EyeTrackingService.Companion.ModelStatus.DOWNLOADING)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL(EyeTrackingService.MODEL_URL)
                val connection = url.openConnection()
                connection.connect()
                val total = connection.contentLengthLong.toFloat()
                var downloaded = 0L

                connection.getInputStream().use { input ->
                    modelFile.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            downloaded += n
                            val progress = if (total > 0) (downloaded * 100 / total).toInt() else 0
                            runOnUiThread { binding.progressModel.progress = progress }
                        }
                    }
                }

                runOnUiThread {
                    refreshSetupSteps(EyeTrackingService.Companion.ModelStatus.READY)
                    Toast.makeText(this@MainActivity, "Model ready!", Toast.LENGTH_SHORT).show()
                    startPreviewSession()
                }
            } catch (e: Exception) {
                modelFile.delete()
                runOnUiThread {
                    refreshSetupSteps(EyeTrackingService.Companion.ModelStatus.ERROR)
                    Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startTracking() {
        serviceIntent = Intent(this, EyeTrackingService::class.java)
        startForegroundService(serviceIntent)
    }

    private fun stopTracking() {
        serviceIntent?.let { stopService(it) }
        serviceIntent = null
    }

    private fun canStartTracking(): Boolean {
        if (!hasCameraPermission() || !isAccessibilityEnabled()) return false
        val modelFile = java.io.File(filesDir, EyeTrackingService.MODEL_FILENAME)
        return modelFile.exists() && modelFile.length() > 100_000
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityEnabled(): Boolean {
        val pkg = packageName
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val viaManager = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { it.resolveInfo.serviceInfo.packageName == pkg }
        if (viaManager) return true
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(pkg, ignoreCase = true)
    }

    private fun savePrefs() {
        getSharedPreferences("eyescroll", MODE_PRIVATE).edit()
            .putInt("sensitivity", binding.seekSensitivity.progress)
            .putInt("dwell", binding.seekDwell.progress)
            .apply()
    }

    private fun android.view.View.setStepDone(done: Boolean) {
        alpha = if (done) 1f else 0.45f
    }
}
