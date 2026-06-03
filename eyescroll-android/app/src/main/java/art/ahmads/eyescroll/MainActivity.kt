package art.ahmads.eyescroll

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import art.ahmads.eyescroll.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var serviceIntent: Intent? = null

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            refreshSetupSteps()
            // Auto-start model download after camera permission is granted
            val modelFile = java.io.File(filesDir, EyeTrackingService.MODEL_FILENAME)
            if (!modelFile.exists() || modelFile.length() < 100_000) downloadModel()
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
        // Auto-download model when camera permission is already granted and model is missing
        if (hasCameraPermission()) {
            val modelFile = java.io.File(filesDir, EyeTrackingService.MODEL_FILENAME)
            val status = EyeTrackingService.modelStatus.value
            if ((!modelFile.exists() || modelFile.length() < 100_000) &&
                status == EyeTrackingService.Companion.ModelStatus.IDLE) {
                downloadModel()
            }
        }
    }

    private fun setupUI() {
        // Camera permission step
        binding.btnGrantCamera.setOnClickListener {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }

        // Accessibility step
        binding.btnGrantAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Model download step
        binding.btnDownloadModel.setOnClickListener {
            downloadModel()
        }

        // Main toggle
        binding.toggleTracking.setOnCheckedChangeListener { _, enabled ->
            if (!canStartTracking()) {
                binding.toggleTracking.isChecked = false
                Toast.makeText(this, "Complete setup steps first", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            if (enabled) startTracking() else stopTracking()
        }

        // Sensitivity slider: maps 0-100 → threshold 0.15..0.50
        binding.seekSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val threshold = 0.50f - (progress / 100f) * 0.35f // high sensitivity = lower threshold
                binding.labelSensitivity.text = "Sensitivity: ${progress}%"
                serviceIntent?.let {
                    // Pass updated threshold to running service via broadcast/extra is not trivial
                    // The service reads the pref on start; live update via static field
                    EyeTrackingService.gazeState // ensure class loaded
                }
                savePrefs(threshold, currentDwellMs())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // Dwell time slider: 0-100 → 500ms..2500ms
        binding.seekDwell.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val ms = 500L + (progress / 100f * 2000f).toLong()
                val label = "%.1f".format(ms / 1000f)
                binding.labelDwell.text = "Hold for: ${label}s"
                savePrefs(currentThreshold(), ms)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // Restore saved prefs
        val prefs = getSharedPreferences("eyescroll", MODE_PRIVATE)
        val sens = prefs.getInt("sensitivity", 60)
        val dwell = prefs.getInt("dwell", 40)
        binding.seekSensitivity.progress = sens
        binding.seekDwell.progress = dwell
    }

    private fun observeServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    EyeTrackingService.gazeState.collect { state ->
                        binding.eyeView.updateState(state)
                        binding.labelStatus.text = when {
                            !EyeTrackingService.isTracking.value -> "Tracking off"
                            !state.faceDetected -> "Looking for face..."
                            state.isInDwell -> "Hold... (%.0f%%)".format(state.dwellProgress * 100)
                            else -> "Watching 👁"
                        }
                    }
                }
                launch {
                    EyeTrackingService.isTracking.collect { tracking ->
                        binding.toggleTracking.isChecked = tracking
                        binding.labelStatus.text = if (tracking) "Watching 👁" else "Tracking off"
                    }
                }
                launch {
                    EyeTrackingService.modelStatus.collect { status ->
                        refreshSetupSteps(status)
                    }
                }
            }
        }
    }

    private fun refreshSetupSteps(
        modelStatus: EyeTrackingService.Companion.ModelStatus = EyeTrackingService.modelStatus.value
    ) {
        val cameraOk = hasCameraPermission()
        val accessOk = isAccessibilityEnabled()
        val modelOk = modelStatus == EyeTrackingService.Companion.ModelStatus.READY

        binding.stepCamera.setStepDone(cameraOk)
        binding.btnGrantCamera.isEnabled = !cameraOk
        binding.btnGrantCamera.text = if (cameraOk) "✓  Permission granted" else "Grant permission"

        binding.stepAccessibility.setStepDone(accessOk)
        binding.btnGrantAccessibility.isEnabled = !accessOk
        binding.btnGrantAccessibility.text = if (accessOk) "✓  Service enabled" else "Open Accessibility Settings"

        binding.stepModel.setStepDone(modelOk)
        binding.btnDownloadModel.isEnabled = !modelOk && modelStatus != EyeTrackingService.Companion.ModelStatus.DOWNLOADING
        binding.btnDownloadModel.text = when (modelStatus) {
            EyeTrackingService.Companion.ModelStatus.READY -> "✓  Model ready"
            EyeTrackingService.Companion.ModelStatus.DOWNLOADING -> "Downloading..."
            EyeTrackingService.Companion.ModelStatus.ERROR -> "Retry download"
            else -> "Download AI model (~5MB)"
        }
        binding.progressModel.visibility = if (modelStatus == EyeTrackingService.Companion.ModelStatus.DOWNLOADING)
            android.view.View.VISIBLE else android.view.View.GONE

        binding.toggleTracking.isEnabled = cameraOk && accessOk && modelOk
    }

    private fun downloadModel() {
        // filesDir is the same for all components in the same app package
        val modelFile = java.io.File(filesDir, EyeTrackingService.MODEL_FILENAME)
        if (modelFile.exists() && modelFile.length() > 100_000) {
            refreshSetupSteps(EyeTrackingService.Companion.ModelStatus.READY)
            return
        }

        refreshSetupSteps(EyeTrackingService.Companion.ModelStatus.DOWNLOADING)

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
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
                }
            } catch (e: Exception) {
                modelFile.delete() // Remove partial download
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

    private fun canStartTracking() =
        hasCameraPermission() && isAccessibilityEnabled() &&
                EyeTrackingService.modelStatus.value == EyeTrackingService.Companion.ModelStatus.READY

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityEnabled(): Boolean {
        val pkg = packageName
        // Primary check via AccessibilityManager
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val viaManager = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { it.resolveInfo.serviceInfo.packageName == pkg }
        if (viaManager) return true
        // Fallback via Settings.Secure — more reliable on Xiaomi/MIUI
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(pkg, ignoreCase = true)
    }

    private fun currentThreshold(): Float {
        val sens = binding.seekSensitivity.progress
        return 0.50f - (sens / 100f) * 0.35f
    }

    private fun currentDwellMs(): Long {
        val dwell = binding.seekDwell.progress
        return 500L + (dwell / 100f * 2000f).toLong()
    }

    private fun savePrefs(threshold: Float, dwellMs: Long) {
        getSharedPreferences("eyescroll", MODE_PRIVATE).edit()
            .putInt("sensitivity", binding.seekSensitivity.progress)
            .putInt("dwell", binding.seekDwell.progress)
            .apply()
    }

    // Helper extension to update step indicator appearance
    private fun android.view.View.setStepDone(done: Boolean) {
        alpha = if (done) 1f else 0.45f
    }
}
