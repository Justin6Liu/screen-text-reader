package com.screenreader.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.screenreader.app.overlay.OverlayService
import com.screenreader.app.runtime.AppPreferences
import com.screenreader.app.runtime.ScreenReaderController

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var speechStatusText: TextView
    private lateinit var overlayPermissionButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var batteryButton: Button
    private lateinit var startOverlayButton: Button
    private lateinit var stopOverlayButton: Button
    private lateinit var stopSpeechButton: Button
    private lateinit var testReadButton: Button
    private lateinit var developerModeButton: Button
    private lateinit var developerPasswordInput: EditText
    private lateinit var debugModeCheckBox: CheckBox
    private lateinit var saveDebugScreenshotsCheckBox: CheckBox
    private lateinit var recognizedTextConsoleCheckBox: CheckBox
    private lateinit var highlightReadingLineCheckBox: CheckBox
    private lateinit var pauseResumeReadingCheckBox: CheckBox
    private lateinit var recognizedTextConsoleTitle: TextView
    private lateinit var recognizedTextConsoleText: TextView

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ScreenReaderController.initialize(applicationContext)

        statusText = findViewById(R.id.statusText)
        speechStatusText = findViewById(R.id.speechStatusText)
        overlayPermissionButton = findViewById(R.id.overlayPermissionButton)
        accessibilityButton = findViewById(R.id.accessibilityButton)
        batteryButton = findViewById(R.id.batteryButton)
        startOverlayButton = findViewById(R.id.startOverlayButton)
        stopOverlayButton = findViewById(R.id.stopOverlayButton)
        stopSpeechButton = findViewById(R.id.stopSpeechButton)
        testReadButton = findViewById(R.id.testReadButton)
        developerModeButton = findViewById(R.id.developerModeButton)
        developerPasswordInput = findViewById(R.id.developerPasswordInput)
        debugModeCheckBox = findViewById(R.id.debugModeCheckBox)
        saveDebugScreenshotsCheckBox = findViewById(R.id.saveDebugScreenshotsCheckBox)
        recognizedTextConsoleCheckBox = findViewById(R.id.recognizedTextConsoleCheckBox)
        highlightReadingLineCheckBox = findViewById(R.id.highlightReadingLineCheckBox)
        pauseResumeReadingCheckBox = findViewById(R.id.pauseResumeReadingCheckBox)
        recognizedTextConsoleTitle = findViewById(R.id.recognizedTextConsoleTitle)
        recognizedTextConsoleText = findViewById(R.id.recognizedTextConsoleText)

        debugModeCheckBox.isChecked = AppPreferences.isOcrDebugModeEnabled(this)
        debugModeCheckBox.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            AppPreferences.setOcrDebugModeEnabled(this, isChecked)
            ScreenReaderController.setOcrDebugModeEnabled(isChecked)
            refreshStatus()
        }

        saveDebugScreenshotsCheckBox.isChecked = AppPreferences.isSaveDebugScreenshotsEnabled(this)
        saveDebugScreenshotsCheckBox.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            AppPreferences.setSaveDebugScreenshotsEnabled(this, isChecked)
            refreshStatus()
        }

        recognizedTextConsoleCheckBox.isChecked = AppPreferences.isRecognizedTextConsoleEnabled(this)
        recognizedTextConsoleCheckBox.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            AppPreferences.setRecognizedTextConsoleEnabled(this, isChecked)
            refreshStatus()
        }

        highlightReadingLineCheckBox.isChecked = AppPreferences.isHighlightReadingLineEnabled(this)
        highlightReadingLineCheckBox.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            AppPreferences.setHighlightReadingLineEnabled(this, isChecked)
            ScreenReaderController.setHighlightReadingLineEnabled(isChecked)
            refreshStatus()
        }

        pauseResumeReadingCheckBox.isChecked = AppPreferences.isPauseResumeReadingEnabled(this)
        pauseResumeReadingCheckBox.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            AppPreferences.setPauseResumeReadingEnabled(this, isChecked)
            refreshStatus()
        }

        developerModeButton.setOnClickListener {
            if (AppPreferences.isDeveloperModeEnabled(this)) {
                AppPreferences.setDeveloperModeEnabled(this, false)
                developerPasswordInput.text.clear()
                Toast.makeText(this, getString(R.string.developer_mode_off), Toast.LENGTH_SHORT).show()
            } else {
                val password = developerPasswordInput.text.toString()
                if (password.isBlank()) {
                    developerPasswordInput.requestFocus()
                    val inputManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    inputManager.showSoftInput(developerPasswordInput, InputMethodManager.SHOW_IMPLICIT)
                    Toast.makeText(this, "Enter developer password.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (password == DEVELOPER_PASSWORD) {
                    AppPreferences.setDeveloperModeEnabled(this, true)
                    developerPasswordInput.text.clear()
                    Toast.makeText(this, getString(R.string.developer_mode_on), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Incorrect password.", Toast.LENGTH_SHORT).show()
                }
            }
            refreshStatus()
        }

        overlayPermissionButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri()))
        }

        accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        batteryButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        startOverlayButton.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant overlay permission first.", Toast.LENGTH_SHORT).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri()))
                return@setOnClickListener
            }
            requestNotificationPermissionIfNeeded()
            OverlayService.start(this)
            refreshStatus()
        }

        stopOverlayButton.setOnClickListener {
            OverlayService.stop(this)
            refreshStatus()
        }

        stopSpeechButton.setOnClickListener {
            ScreenReaderController.stopSpeaking()
            refreshStatus()
        }

        testReadButton.setOnClickListener {
            ScreenReaderController.readDemoText()
            refreshStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val overlayGranted = Settings.canDrawOverlays(this)
        val batteryIgnored = isIgnoringBatteryOptimizations()
        val accessibilityReady = ScreenReaderController.isAccessibilityReady()
        val overlayRunning = OverlayService.isRunning
        val developerMode = AppPreferences.isDeveloperModeEnabled(this)

        statusText.text = buildString {
            appendLine(getString(R.string.status_title))
            appendLine()
            appendLine("Overlay permission: ${if (overlayGranted) "Ready" else "Missing"}")
            appendLine("Accessibility service: ${if (accessibilityReady) "Connected" else "Not connected"}")
            appendLine("Overlay service: ${if (overlayRunning) "Running" else "Stopped"}")
            appendLine("Battery optimization: ${if (batteryIgnored) "Ignored" else "Default"}")
            append("Developer mode: ${if (developerMode) "On" else "Off"}")
            if (developerMode) {
                appendLine()
                appendLine("OCR debug mode: ${if (AppPreferences.isOcrDebugModeEnabled(this@MainActivity)) "On" else "Off"}")
                appendLine("Save debug screenshots: ${if (AppPreferences.isSaveDebugScreenshotsEnabled(this@MainActivity)) "On" else "Off"}")
                appendLine("Text console: ${if (AppPreferences.isRecognizedTextConsoleEnabled(this@MainActivity)) "On" else "Off"}")
                appendLine("Reading line highlight: ${if (AppPreferences.isHighlightReadingLineEnabled(this@MainActivity)) "On" else "Off"}")
                append("Tap pause/resume: ${if (AppPreferences.isPauseResumeReadingEnabled(this@MainActivity)) "On" else "Off"}")
            }
        }

        speechStatusText.text = ScreenReaderController.getUiStatus()
        updateDeveloperControlVisibility(developerMode)
        val showConsole = developerMode && AppPreferences.isRecognizedTextConsoleEnabled(this)
        val visibility = if (showConsole) android.view.View.VISIBLE else android.view.View.GONE
        recognizedTextConsoleTitle.visibility = visibility
        recognizedTextConsoleText.visibility = visibility
        recognizedTextConsoleText.text = ScreenReaderController.getLastRecognizedText()
    }

    private fun updateDeveloperControlVisibility(developerMode: Boolean) {
        val developerVisibility = if (developerMode) android.view.View.VISIBLE else android.view.View.GONE
        debugModeCheckBox.visibility = developerVisibility
        saveDebugScreenshotsCheckBox.visibility = developerVisibility
        recognizedTextConsoleCheckBox.visibility = developerVisibility
        highlightReadingLineCheckBox.visibility = developerVisibility
        pauseResumeReadingCheckBox.visibility = developerVisibility
        batteryButton.visibility = developerVisibility
        testReadButton.visibility = developerVisibility
        stopSpeechButton.visibility = developerVisibility
        developerPasswordInput.visibility = if (developerMode) android.view.View.GONE else android.view.View.VISIBLE
        developerModeButton.text = getString(R.string.developer_mode_button)
    }

    private fun packageUri(): Uri = Uri.parse("package:$packageName")

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        private const val DEVELOPER_PASSWORD = "12345678"
    }
}
