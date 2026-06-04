package art.ahmads.eyescroll

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import android.view.accessibility.AccessibilityManager
import android.widget.RadioGroup
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
        refreshCalibStatus()
    }

    override fun onPause() {
        super.onPause()
        if (!EyeTrackingService.isTracking.value) stopPreviewSession()
    }

    private fun setupUI() {
        val prefs = getSharedPreferences("eyescroll", MODE_PRIVATE)

        binding.btnGrantCamera.setOnClickListener { cameraPermLauncher.launch(Manifest.permission.CAMERA) }
        binding.btnGrantAccessibility.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        binding.btnDownloadModel.setOnClickListener { downloadModel() }

        // ── Toggle tracking ────────────────────────────────────────────────────────
        binding.toggleTracking.setOnCheckedChangeListener { _, enabled ->
            if (!canStartTracking()) {
                binding.toggleTracking.isChecked = false
                Toast.makeText(this, "Complete setup steps first", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            if (enabled) {
                stopPreviewSession()
                startTracking()
                binding.btnCalibrate.isEnabled = true
            } else {
                stopTracking()
                binding.btnCalibrate.isEnabled = false
            }
        }

        // ── Gesture mode selector ──────────────────────────────────────────────────
        val savedMode = GestureMode.valueOf(prefs.getString("gesture_mode", GestureMode.PITCH.name)!!)
        when (savedMode) {
            GestureMode.PITCH -> binding.radioPitch.isChecked = true
            GestureMode.ROLL  -> binding.radioRoll.isChecked  = true
            GestureMode.YAW   -> binding.radioYaw.isChecked   = true
        }

        binding.radioGestureMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radioPitch -> GestureMode.PITCH
                R.id.radioRoll  -> GestureMode.ROLL
                else            -> GestureMode.YAW
            }
            prefs.edit().putString("gesture_mode", mode.name).apply()
            refreshCalibStatus()
            // Restart service if running so it picks up new mode
            if (EyeTrackingService.isTracking.value) {
                stopTracking()
                stopPreviewSession()
                startTracking()
            }
        }

        // ── Calibration ────────────────────────────────────────────────────────────
        binding.btnCalibrate.setOnClickListener {
            binding.btnCalibrate.isEnabled = false
            binding.btnCalibrate.text = "Look straight at phone…"
            binding.progressCalib.visibility = android.view.View.VISIBLE
            binding.progressCalib.progress = 0
            EyeTrackingService.startCalibration()
        }

        // ── Sensitivity ────────────────────────────────────────────────────────────
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

        binding.seekSensitivity.progress = prefs.getInt("sensitivity", 60)
        binding.seekDwell.progress = prefs.getInt("dwell", 0)
    }

    private fun observeServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    EyeTrackingService.gazeState.collect { state ->
                        binding.eyeView.updateState(state)

                        // Calibration progress bar
                        if (state.isCalibrating) {
                            binding.progressCalib.progress = (state.calibrationProgress * 100).toInt()
                        }

                        if (EyeTrackingService.isTracking.value) {
                            binding.labelStatus.text = when {
                                state.isCalibrating  -> "Calibrating… ${(state.calibrationProgress * 100).toInt()}%"
                                !state.faceDetected  -> "Looking for face…"
                                state.isLookingNext  -> "→ Gesture detected — next"
                                state.isLookingPrev  -> "← Gesture detected — prev"
                                else                 -> "Face detected — move to scroll"
                            }
                        }
                    }
                }
                launch {
                    EyeTrackingService.isTracking.collect { tracking ->
                        binding.toggleTracking.isChecked = tracking
                        binding.btnCalibrate.isEnabled = tracking
                        if (!tracking) {
                            binding.labelStatus.text = "Tracking off"
                            binding.progressCalib.visibility = android.view.View.GONE
                            if (hasCameraPermission() && canStartTracking()) startPreviewSession()
                        }
                    }
                }
                launch {
                    EyeTrackingService.modelStatus.collect { status ->
                        refreshSetupSteps(status)
                        if (status == EyeTrackingService.Companion.ModelStatus.READY &&
                            hasCameraPermission() && !EyeTrackingService.isTracking.value) {
                            startPreviewSession()
                        }
                    }
                }
                launch {
                    EyeTrackingService.calibrationSaved.collect { saved ->
                        if (saved) {
                            binding.progressCalib.visibility = android.view.View.GONE
                            binding.btnCalibrate.isEnabled = EyeTrackingService.isTracking.value
                            binding.btnCalibrate.text = "Calibrate (hold head neutral 3s)"
                            refreshCalibStatus()
                            Toast.makeText(this@MainActivity, "✓ Calibrated!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    // ── Face preview ───────────────────────────────────────────────────────────────

    private fun startPreviewSession() {
        if (previewCameraProvider != null) return
        val modelFile = java.io.File(filesDir, EyeTrackingService.MODEL_FILENAME)
        if (!modelFile.exists() || modelFile.length() < 100_000) return
        binding.previewContainer.visibility = android.view.View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (previewFaceLandmarker == null) {
                    val bytes = modelFile.readBytes()
                    val buf = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }
                    previewFaceLandmarker = FaceLandmarker.createFromOptions(this@MainActivity,
                        FaceLandmarker.FaceLandmarkerOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buf).build())
                            .setRunningMode(RunningMode.IMAGE).setNumFaces(1).build()
                    )
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
                val mp = BitmapImageBuilder(proxy.toBitmap()).build()
                val result = try { previewFaceLandmarker?.detect(mp) } catch (e: Exception) { null }
                val detected = result?.faceLandmarks()?.isNotEmpty() == true
                runOnUiThread {
                    val modeHint = when (savedGestureMode()) {
                        GestureMode.PITCH -> "Nod head up/down"
                        GestureMode.ROLL  -> "Tilt head left/right"
                        GestureMode.YAW   -> "Turn head left/right"
                    }
                    binding.labelFaceStatus.text = if (detected) "Face detected — $modeHint"
                                                   else "Position your face in frame"
                    binding.labelFaceStatus.setTextColor(Color.parseColor(if (detected) "#C4A97A" else "#6E6E68"))
                }
                proxy.close()
            }
            try {
                previewCameraProvider?.unbindAll()
                previewCameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (e: Exception) {}
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopPreviewSession() {
        previewCameraProvider?.unbindAll()
        previewCameraProvider = null
        previewFaceLandmarker?.close()
        previewFaceLandmarker = null
        binding.previewContainer.visibility = android.view.View.GONE
    }

    // ── Setup steps ────────────────────────────────────────────────────────────────

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
            modelStatus == EyeTrackingService.Companion.ModelStatus.DOWNLOADING -> "Downloading…"
            modelStatus == EyeTrackingService.Companion.ModelStatus.ERROR -> "Retry download"
            else -> "Download AI model (~5MB)"
        }
        binding.progressModel.visibility =
            if (modelStatus == EyeTrackingService.Companion.ModelStatus.DOWNLOADING)
                android.view.View.VISIBLE else android.view.View.GONE
        binding.toggleTracking.isEnabled = cameraOk && accessOk && modelOk
    }

    private fun refreshCalibStatus() {
        val mode = savedGestureMode()
        val neutral = getSharedPreferences("eyescroll", MODE_PRIVATE)
            .getFloat("neutral_${mode.name}", Float.NaN)
        binding.labelCalibStatus.text = if (neutral.isNaN())
            "Not calibrated — using defaults"
        else
            "✓ Calibrated for ${mode.name.lowercase()} mode  (neutral=${"%.2f".format(neutral)})"
    }

    private fun savedGestureMode(): GestureMode {
        val s = getSharedPreferences("eyescroll", MODE_PRIVATE)
            .getString("gesture_mode", GestureMode.PITCH.name)!!
        return GestureMode.valueOf(s)
    }

    // ── Download model ─────────────────────────────────────────────────────────────

    private fun downloadModel() {
        val modelFile = java.io.File(filesDir, EyeTrackingService.MODEL_FILENAME)
        if (modelFile.exists() && modelFile.length() > 100_000) {
            refreshSetupSteps(EyeTrackingService.Companion.ModelStatus.READY)
            startPreviewSession(); return
        }
        refreshSetupSteps(EyeTrackingService.Companion.ModelStatus.DOWNLOADING)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL(EyeTrackingService.MODEL_URL)
                val conn = url.openConnection().also { it.connect() }
                val total = conn.contentLengthLong.toFloat()
                var downloaded = 0L
                conn.getInputStream().use { input ->
                    modelFile.outputStream().use { output ->
                        val buf = ByteArray(8192); var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n); downloaded += n
                            val p = if (total > 0) (downloaded * 100 / total).toInt() else 0
                            runOnUiThread { binding.progressModel.progress = p }
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
        if (am.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            ).any { it.resolveInfo.serviceInfo.packageName == pkg }) return true
        return Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(pkg, ignoreCase = true) == true
    }

    private fun savePrefs() {
        getSharedPreferences("eyescroll", MODE_PRIVATE).edit()
            .putInt("sensitivity", binding.seekSensitivity.progress)
            .putInt("dwell", binding.seekDwell.progress)
            .apply()
    }

    private fun android.view.View.setStepDone(done: Boolean) { alpha = if (done) 1f else 0.45f }
}
