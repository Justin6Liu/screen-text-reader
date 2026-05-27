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
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.screenreader.app.overlay.OverlayService
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

        statusText.text = buildString {
            appendLine(getString(R.string.status_title))
            appendLine()
            appendLine("Overlay permission: ${if (overlayGranted) "Ready" else "Missing"}")
            appendLine("Accessibility service: ${if (accessibilityReady) "Connected" else "Not connected"}")
            appendLine("Overlay service: ${if (overlayRunning) "Running" else "Stopped"}")
            append("Battery optimization: ${if (batteryIgnored) "Ignored" else "Default"}")
        }

        speechStatusText.text = ScreenReaderController.getUiStatus()
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
}
