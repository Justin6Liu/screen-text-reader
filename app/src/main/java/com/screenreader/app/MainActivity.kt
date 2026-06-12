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
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.screenreader.app.llm.DEEPSEEK_CHAT_MODEL
import com.screenreader.app.llm.DEFAULT_DEEPSEEK_BASE_URL
import com.screenreader.app.llm.DEFAULT_DEEPSEEK_MODEL
import com.screenreader.app.llm.LlmCorrectionEngine
import com.screenreader.app.llm.LlmPreferences
import com.screenreader.app.llm.Provider
import com.screenreader.app.overlay.OverlayService
import com.screenreader.app.runtime.AppPreferences
import com.screenreader.app.runtime.DeveloperAccessLevel
import com.screenreader.app.runtime.OcrMode
import com.screenreader.app.runtime.ScreenReaderController
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class MainActivity : AppCompatActivity(), ScreenReaderController.StateListener {

    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var speechStatusText: TextView
    private lateinit var overlayPermissionButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var batteryButton: Button
    private lateinit var startOverlayButton: Button
    private lateinit var stopOverlayButton: Button
    private lateinit var speechRateTitle: TextView
    private lateinit var speechRateValueText: TextView
    private lateinit var speechRateSeekBar: SeekBar
    private lateinit var playbackProgressPanel: LinearLayout
    private lateinit var playbackProgressTitle: TextView
    private lateinit var playbackProgressText: TextView
    private lateinit var playbackProgressSeekBar: SeekBar
    private lateinit var stopSpeechButton: Button
    private lateinit var testReadButton: Button
    private lateinit var testReadNoResetButton: Button
    private lateinit var developerModeButton: Button
    private lateinit var languageSwitchButton: Button
    private lateinit var developerPasswordInput: EditText
    private lateinit var debugModeCheckBox: CheckBox
    private lateinit var saveDebugScreenshotsCheckBox: CheckBox
    private lateinit var recognizedTextConsoleCheckBox: CheckBox
    private lateinit var highlightReadingLineCheckBox: CheckBox
    private lateinit var pauseResumeReadingCheckBox: CheckBox
    private lateinit var autoScrollCaptureCheckBox: CheckBox
    private lateinit var autoScrollMaxCapturesInput: EditText
    private lateinit var autoScrollMinSettleDelayInput: EditText
    private lateinit var ocrModeTitle: TextView
    private lateinit var ocrModeGroup: RadioGroup
    private lateinit var lastRunTimingText: TextView
    private lateinit var autoScrollStopReasonText: TextView
    private lateinit var aiCorrectionTitle: TextView
    private lateinit var aiCorrectionCheckBox: CheckBox
    private lateinit var aiProviderText: TextView
    private lateinit var aiProviderGroup: RadioGroup
    private lateinit var aiModelText: TextView
    private lateinit var aiModelGroup: RadioGroup
    private lateinit var aiApiKeyStatusText: TextView
    private lateinit var aiApiKeyInput: EditText
    private lateinit var aiConfirmApiKeyButton: Button
    private lateinit var aiTestConnectionButton: Button
    private lateinit var recognizedTextConsoleTitle: TextView
    private lateinit var recognizedTextConsoleText: TextView
    private lateinit var aiResponseConsoleTitle: TextView
    private lateinit var aiResponseConsoleText: TextView
    private var suppressOcrModeSelection = false
    private var aiDisclosureVisible = false
    private var isSeekingPlayback = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ScreenReaderController.initialize(applicationContext)

        titleText = findViewById(R.id.titleText)
        subtitleText = findViewById(R.id.subtitleText)
        statusText = findViewById(R.id.statusText)
        speechStatusText = findViewById(R.id.speechStatusText)
        overlayPermissionButton = findViewById(R.id.overlayPermissionButton)
        accessibilityButton = findViewById(R.id.accessibilityButton)
        batteryButton = findViewById(R.id.batteryButton)
        startOverlayButton = findViewById(R.id.startOverlayButton)
        stopOverlayButton = findViewById(R.id.stopOverlayButton)
        speechRateTitle = findViewById(R.id.speechRateTitle)
        speechRateValueText = findViewById(R.id.speechRateValueText)
        speechRateSeekBar = findViewById(R.id.speechRateSeekBar)
        playbackProgressPanel = findViewById(R.id.playbackProgressPanel)
        playbackProgressTitle = findViewById(R.id.playbackProgressTitle)
        playbackProgressText = findViewById(R.id.playbackProgressText)
        playbackProgressSeekBar = findViewById(R.id.playbackProgressSeekBar)
        stopSpeechButton = findViewById(R.id.stopSpeechButton)
        testReadButton = findViewById(R.id.testReadButton)
        testReadNoResetButton = findViewById(R.id.testReadNoResetButton)
        developerModeButton = findViewById(R.id.developerModeButton)
        languageSwitchButton = findViewById(R.id.languageSwitchButton)
        developerPasswordInput = findViewById(R.id.developerPasswordInput)
        debugModeCheckBox = findViewById(R.id.debugModeCheckBox)
        saveDebugScreenshotsCheckBox = findViewById(R.id.saveDebugScreenshotsCheckBox)
        recognizedTextConsoleCheckBox = findViewById(R.id.recognizedTextConsoleCheckBox)
        highlightReadingLineCheckBox = findViewById(R.id.highlightReadingLineCheckBox)
        pauseResumeReadingCheckBox = findViewById(R.id.pauseResumeReadingCheckBox)
        autoScrollCaptureCheckBox = findViewById(R.id.autoScrollCaptureCheckBox)
        autoScrollMaxCapturesInput = findViewById(R.id.autoScrollMaxCapturesInput)
        autoScrollMinSettleDelayInput = findViewById(R.id.autoScrollMinSettleDelayInput)
        ocrModeTitle = findViewById(R.id.ocrModeTitle)
        ocrModeGroup = findViewById(R.id.ocrModeGroup)
        lastRunTimingText = findViewById(R.id.lastRunTimingText)
        autoScrollStopReasonText = findViewById(R.id.autoScrollStopReasonText)
        aiCorrectionTitle = findViewById(R.id.aiCorrectionTitle)
        aiCorrectionCheckBox = findViewById(R.id.aiCorrectionCheckBox)
        aiProviderText = findViewById(R.id.aiProviderText)
        aiProviderGroup = findViewById(R.id.aiProviderGroup)
        aiModelText = findViewById(R.id.aiModelText)
        aiModelGroup = findViewById(R.id.aiModelGroup)
        aiApiKeyStatusText = findViewById(R.id.aiApiKeyStatusText)
        aiApiKeyInput = findViewById(R.id.aiApiKeyInput)
        aiConfirmApiKeyButton = findViewById(R.id.aiConfirmApiKeyButton)
        aiTestConnectionButton = findViewById(R.id.aiTestConnectionButton)
        recognizedTextConsoleTitle = findViewById(R.id.recognizedTextConsoleTitle)
        recognizedTextConsoleText = findViewById(R.id.recognizedTextConsoleText)
        aiResponseConsoleTitle = findViewById(R.id.aiResponseConsoleTitle)
        aiResponseConsoleText = findViewById(R.id.aiResponseConsoleText)

        speechRateSeekBar.max = SPEECH_RATE_SLIDER_MAX
        speechRateSeekBar.progress = speechRateToSlider(AppPreferences.getSpeechRate(this))
        speechRateSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                speechRateValueText.text = speechRateLabel(sliderToSpeechRate(progress))
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                ScreenReaderController.setSpeechRate(
                    sliderToSpeechRate(seekBar?.progress ?: speechRateSeekBar.progress)
                )
                refreshStatus()
            }
        })

        playbackProgressSeekBar.max = ScreenReaderController.PLAYBACK_PROGRESS_MAX
        playbackProgressSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val playback = ScreenReaderController.getPlaybackProgress() ?: return
                val target = progressToSegment(progress, playback.totalSegments)
                playbackProgressText.text = playbackPositionLabel(target, playback.totalSegments)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeekingPlayback = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: playbackProgressSeekBar.progress
                isSeekingPlayback = false
                ScreenReaderController.seekToPlaybackProgress(progress)
                refreshStatus()
            }
        })

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

        autoScrollCaptureCheckBox.isChecked = AppPreferences.isAutoScrollCaptureEnabled(this)
        autoScrollCaptureCheckBox.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            AppPreferences.setAutoScrollCaptureEnabled(this, isChecked)
            refreshStatus()
        }

        autoScrollMaxCapturesInput.setText(AppPreferences.getAutoScrollMaxCaptures(this).toString())
        autoScrollMaxCapturesInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val value = s?.toString()?.toIntOrNull() ?: return
                AppPreferences.setAutoScrollMaxCaptures(this@MainActivity, value)
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        autoScrollMinSettleDelayInput.setText(
            AppPreferences.getAutoScrollMinSettleDelayMs(this).toString()
        )
        autoScrollMinSettleDelayInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val value = s?.toString()?.toIntOrNull() ?: return
                if (value in AppPreferences.MIN_AUTO_SCROLL_SETTLE_DELAY_MS..
                    AppPreferences.MAX_AUTO_SCROLL_SETTLE_DELAY_MS
                ) {
                    AppPreferences.setAutoScrollMinSettleDelayMs(this@MainActivity, value)
                }
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
        autoScrollMinSettleDelayInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                autoScrollMinSettleDelayInput.setText(
                    AppPreferences.getAutoScrollMinSettleDelayMs(this).toString()
                )
            }
        }

        ocrModeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (suppressOcrModeSelection) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.ocrFastModeButton -> OcrMode.FAST
                R.id.ocrAiBoostModeButton -> OcrMode.AI_BOOST
                else -> OcrMode.ACCURATE
            }
            if (mode == OcrMode.AI_BOOST) {
                val previousMode = AppPreferences.getOcrMode(this)
                showAiModeDisclosure(
                    onAccept = {
                        LlmPreferences.setDisclosureAccepted(this, true)
                        AppPreferences.setOcrMode(this, OcrMode.AI_BOOST)
                        LlmPreferences.setProvider(this, Provider.DEEPSEEK)
                        LlmPreferences.setBaseUrl(this, DEFAULT_DEEPSEEK_BASE_URL)
                        refreshStatus()
                    },
                    onCancel = {
                        setOcrModeSelection(previousMode)
                        refreshStatus()
                    }
                )
            } else {
                AppPreferences.setOcrMode(this, mode)
                refreshStatus()
            }
        }

        aiCorrectionCheckBox.isChecked = LlmPreferences.isEnabled(this)
        aiCorrectionCheckBox.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            if (isChecked && !LlmPreferences.isSecureStorageAvailable(this)) {
                aiCorrectionCheckBox.isChecked = false
                showSecureStorageUnavailable()
                return@setOnCheckedChangeListener
            }
            LlmPreferences.setEnabled(this, isChecked)
            refreshStatus()
        }

        val llmConfig = LlmPreferences.getConfig(this)
        aiApiKeyInput.setText("")
        aiProviderGroup.check(R.id.deepSeekProviderButton)
        aiProviderGroup.setOnCheckedChangeListener { _, _ ->
            LlmPreferences.setProvider(this, Provider.DEEPSEEK)
            LlmPreferences.setBaseUrl(this, DEFAULT_DEEPSEEK_BASE_URL)
            refreshStatus()
        }
        aiModelGroup.check(
            if (llmConfig.model == DEEPSEEK_CHAT_MODEL) {
                R.id.deepSeekChatModelButton
            } else {
                R.id.deepSeekFlashModelButton
            }
        )
        aiModelGroup.setOnCheckedChangeListener { _, checkedId ->
            LlmPreferences.setProvider(this, Provider.DEEPSEEK)
            LlmPreferences.setBaseUrl(this, DEFAULT_DEEPSEEK_BASE_URL)
            LlmPreferences.setModel(
                this,
                if (checkedId == R.id.deepSeekChatModelButton) DEEPSEEK_CHAT_MODEL else DEFAULT_DEEPSEEK_MODEL
            )
            refreshStatus()
        }
        aiConfirmApiKeyButton.setOnClickListener {
            val apiKey = aiApiKeyInput.text.toString().trim()
            if (apiKey.isBlank()) {
                Toast.makeText(this, text("Enter an API key first.", "请先输入 API Key。"), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            LlmPreferences.setProvider(this, Provider.DEEPSEEK)
            LlmPreferences.setBaseUrl(this, DEFAULT_DEEPSEEK_BASE_URL)
            if (!LlmPreferences.setApiKey(this, apiKey)) {
                showSecureStorageUnavailable()
                refreshStatus()
                return@setOnClickListener
            }
            aiApiKeyInput.text.clear()
            Toast.makeText(this, text("API key saved.", "API Key 已保存。"), Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
        aiTestConnectionButton.setOnClickListener {
            Toast.makeText(this, text("Testing AI connection...", "正在测试 AI 连接..."), Toast.LENGTH_SHORT).show()
            Thread {
                val result = LlmCorrectionEngine.testConnection(applicationContext)
                runOnUiThread {
                    val message = result.fold(
                        onSuccess = { corrected ->
                            text(
                                "AI connection works. Result: $corrected",
                                "AI 连接成功。结果：$corrected"
                            )
                        },
                        onFailure = { error ->
                            text(
                                "AI connection failed: ${error.message ?: "unknown error"}",
                                "AI 连接失败：${error.message ?: "未知错误"}"
                            )
                        }
                    )
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }.start()
        }

        developerModeButton.setOnClickListener {
            val currentAccess = AppPreferences.getDeveloperAccessLevel(this)
            if (currentAccess != DeveloperAccessLevel.NONE) {
                AppPreferences.setDeveloperAccessLevel(this, DeveloperAccessLevel.NONE)
                developerPasswordInput.text.clear()
                Toast.makeText(this, text("Software settings are locked.", "软件设置已锁定。"), Toast.LENGTH_SHORT).show()
            } else {
                val password = developerPasswordInput.text.toString()
                if (password.isBlank()) {
                    developerPasswordInput.requestFocus()
                    val inputManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    inputManager.showSoftInput(developerPasswordInput, InputMethodManager.SHOW_IMPLICIT)
                    Toast.makeText(this, text("Enter developer password.", "请输入开发者密码。"), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val accessLevel = when (password) {
                    CONFIGURATION_PASSWORD -> DeveloperAccessLevel.CONFIGURATION
                    else -> if (isFullDeveloperPassword(password)) {
                        DeveloperAccessLevel.FULL
                    } else {
                        null
                    }
                }
                if (accessLevel == null) {
                    Toast.makeText(this, text("Incorrect password.", "密码错误。"), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                AppPreferences.setDeveloperAccessLevel(this, accessLevel)
                developerPasswordInput.text.clear()
                Toast.makeText(
                    this,
                    if (accessLevel == DeveloperAccessLevel.FULL) {
                        text("Full developer mode is on.", "完整开发者模式已开启。")
                    } else {
                        text("Configuration mode is on.", "配置模式已开启。")
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
            refreshStatus()
        }

        languageSwitchButton.setOnClickListener {
            val nextChinese = !AppPreferences.isChineseUiEnabled(this)
            AppPreferences.setChineseUiEnabled(this, nextChinese)
            Toast.makeText(
                this,
                if (nextChinese) "已切换到中文。" else "Switched to English.",
                Toast.LENGTH_SHORT
            ).show()
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
                Toast.makeText(this, text("Grant overlay permission first.", "请先授予悬浮窗权限。"), Toast.LENGTH_SHORT).show()
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

        testReadNoResetButton.setOnClickListener {
            ScreenReaderController.readDemoTextWithoutEngineReset()
            refreshStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (
            AppPreferences.getOcrMode(this) == OcrMode.AI_BOOST &&
            !LlmPreferences.hasAcceptedDisclosure(this) &&
            !aiDisclosureVisible
        ) {
            showAiModeDisclosure(
                onAccept = {
                    LlmPreferences.setDisclosureAccepted(this, true)
                    refreshStatus()
                },
                onCancel = {
                    AppPreferences.setOcrMode(this, OcrMode.ACCURATE)
                    setOcrModeSelection(OcrMode.ACCURATE)
                    refreshStatus()
                }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        ScreenReaderController.addStateListener(this)
    }

    override fun onStop() {
        ScreenReaderController.removeStateListener(this)
        super.onStop()
    }

    override fun onStateChanged(state: ScreenReaderController.ReaderState) {
        runOnUiThread { refreshStatus() }
    }

    private fun refreshStatus() {
        val overlayGranted = Settings.canDrawOverlays(this)
        val batteryIgnored = isIgnoringBatteryOptimizations()
        val accessibilityReady = ScreenReaderController.isAccessibilityReady()
        val overlayRunning = OverlayService.isRunning
        val accessLevel = AppPreferences.getDeveloperAccessLevel(this)
        val advancedMode = accessLevel != DeveloperAccessLevel.NONE
        val fullDeveloperMode = accessLevel == DeveloperAccessLevel.FULL
        applyLanguage(accessLevel)
        updateOcrModeSelection()
        updateAiModelSelection()

        statusText.text = buildString {
            appendLine(text("Device Status", "设备状态"))
            appendLine()
            appendLine("${text("Overlay permission", "悬浮窗权限")}: ${readyText(overlayGranted)}")
            appendLine("${text("Accessibility service", "无障碍服务")}: ${if (accessibilityReady) text("Connected", "已连接") else text("Not connected", "未连接")}")
            appendLine("${text("Floating button", "悬浮按钮")}: ${if (overlayRunning) text("Running", "运行中") else text("Stopped", "已停止")}")
            appendLine("${text("Battery optimization", "电池优化")}: ${if (batteryIgnored) text("Ignored", "已忽略") else text("Default", "默认")}")
            append("${text("Software settings access", "软件设置权限")}: ${developerAccessLabel(accessLevel)}")
            if (advancedMode) {
                appendLine()
                if (fullDeveloperMode) {
                    appendLine("${text("OCR debug mode", "OCR 调试框")}: ${onOffText(AppPreferences.isOcrDebugModeEnabled(this@MainActivity))}")
                    appendLine("${text("Save debug screenshots", "保存调试截图")}: ${onOffText(AppPreferences.isSaveDebugScreenshotsEnabled(this@MainActivity))}")
                    appendLine("${text("Text console", "识别文字显示")}: ${onOffText(AppPreferences.isRecognizedTextConsoleEnabled(this@MainActivity))}")
                    appendLine("${text("Reading highlight", "朗读高亮")}: ${onOffText(AppPreferences.isHighlightReadingLineEnabled(this@MainActivity))}")
                    appendLine("${text("Tap pause/resume", "点击暂停/继续")}: ${onOffText(AppPreferences.isPauseResumeReadingEnabled(this@MainActivity))}")
                    appendLine("${text("Auto scroll capture", "自动滚动整图识别")}: ${onOffText(AppPreferences.isAutoScrollCaptureEnabled(this@MainActivity))}")
                    appendLine("${text("Max captures", "最大截图次数")}: ${AppPreferences.getAutoScrollMaxCaptures(this@MainActivity)}")
                    appendLine(
                        "${text("Minimum scroll settle delay", "最短滚动稳定等待时间")}: " +
                            "${AppPreferences.getAutoScrollMinSettleDelayMs(this@MainActivity)} ms"
                    )
                }
                appendLine("${text("OCR mode", "OCR 模式")}: ${ocrModeLabel(AppPreferences.getOcrMode(this@MainActivity))}")
                append("${text("AI correction", "AI 修正")}: ${aiCorrectionStatusText()}")
            }
        }

        speechStatusText.text = localizeStatus(ScreenReaderController.getUiStatus())
        updateSpeechRateControl()
        updatePlaybackProgressControl()
        lastRunTimingText.text = lastRunTimingLabel()
        autoScrollStopReasonText.text = autoScrollStopReasonLabel()
        updateDeveloperControlVisibility(accessLevel)
        val showConsole = fullDeveloperMode && AppPreferences.isRecognizedTextConsoleEnabled(this)
        val visibility = if (showConsole) android.view.View.VISIBLE else android.view.View.GONE
        val showAiDetails = AppPreferences.getOcrMode(this) == OcrMode.AI_BOOST
        recognizedTextConsoleTitle.visibility = visibility
        recognizedTextConsoleText.visibility = visibility
        recognizedTextConsoleText.text = ScreenReaderController.getLastRecognizedTextConsole(
            showAiDetails = showAiDetails,
            beforeLabel = text("Raw OCR text before AI:", "AI 修正前的原始 OCR 文字："),
            afterLabel = text("Final text used for TTS:", "最终实际朗读文字：")
        )
        val aiConsoleVisibility = if (fullDeveloperMode && showAiDetails) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        aiResponseConsoleTitle.visibility = aiConsoleVisibility
        aiResponseConsoleText.visibility = aiConsoleVisibility
        aiResponseConsoleText.text = ScreenReaderController.getLastAiResponseText(
            statusLabel = text("Request status:", "请求状态："),
            responseBodyLabel = text("Raw HTTP response body:", "在线模型原始 HTTP 返回正文："),
            emptyLabel = text("No response body received.", "未收到返回正文。")
        )
    }

    private fun applyLanguage(accessLevel: DeveloperAccessLevel) {
        titleText.text = text("Screen Reader", "屏幕朗读")
        subtitleText.text = text(
            "Large controls only. Start the overlay, open a WeChat image, then tap the floating button to read text aloud.",
            "操作很简单：先启动悬浮按钮，打开微信图片，再点击悬浮按钮朗读文字。"
        )
        overlayPermissionButton.text = text("Grant Overlay Permission", "授予悬浮窗权限")
        accessibilityButton.text = text("Open Accessibility Settings", "打开无障碍设置")
        batteryButton.text = text("Open Battery Settings", "打开电池设置")
        startOverlayButton.text = text("Start Floating Button", "启动悬浮按钮")
        stopOverlayButton.text = text("Stop Floating Button", "关闭悬浮按钮")
        speechRateTitle.text = text("Speech speed", "朗读速度")
        playbackProgressTitle.text = text("Reading progress", "朗读进度")
        testReadButton.text = text("Test Chinese Speech", "测试中文朗读")
        testReadNoResetButton.text = text("Test Speech Without Engine Reset", "测试朗读（不重置语音引擎）")
        stopSpeechButton.text = text("Stop Speech", "停止朗读")
        debugModeCheckBox.text = text("Debug OCR boxes after capture", "截图后显示 OCR 调试框")
        saveDebugScreenshotsCheckBox.text = text("Save debug screenshots locally", "保存调试截图到本机")
        recognizedTextConsoleCheckBox.text = text("Show recognized text in app", "在应用内显示识别文字")
        highlightReadingLineCheckBox.text = text("Highlight line while reading", "朗读时高亮当前区域")
        pauseResumeReadingCheckBox.text = text("Tap to pause and resume reading", "点击悬浮按钮暂停/继续朗读")
        autoScrollCaptureCheckBox.text = text("Auto scroll capture full image", "自动滚动识别长图")
        autoScrollMaxCapturesInput.hint = text("Max captures 1-15", "最大截图次数 1-15")
        autoScrollMinSettleDelayInput.hint = text(
            "Minimum scroll settle delay 300-1000 ms",
            "最短滚动稳定等待时间 300-1000 毫秒"
        )
        ocrModeTitle.text = text("OCR Mode", "OCR 模式")
        findViewById<RadioButton>(R.id.ocrFastModeButton).text = text("Fast", "快速")
        findViewById<RadioButton>(R.id.ocrAccurateModeButton).text = text("Accurate", "准确")
        findViewById<RadioButton>(R.id.ocrAiBoostModeButton).text = text("AI Boost", "AI 增强")
        aiCorrectionTitle.text = text("AI Correction", "AI 修正")
        aiCorrectionCheckBox.text = text("Enable AI correction", "启用 AI 修正")
        aiProviderText.text = text("Provider", "服务商")
        findViewById<RadioButton>(R.id.deepSeekProviderButton).text = "DeepSeek"
        aiModelText.text = text("Model", "模型")
        aiApiKeyStatusText.text = when {
            !LlmPreferences.isSecureStorageAvailable(this) -> {
                text(
                    "Secure storage unavailable. API keys cannot be saved.",
                    "安全存储不可用，无法保存 API Key。"
                )
            }
            LlmPreferences.hasApiKey(this) -> {
                text("API key already configured.", "API Key 已配置。")
            }
            else -> {
                text("API key not configured.", "API Key 未配置。")
            }
        }
        aiApiKeyInput.hint = if (LlmPreferences.hasApiKey(this)) {
            text("Adjust/update API key", "调整/更新 API Key")
        } else {
            text("API Key", "API Key")
        }
        findViewById<RadioButton>(R.id.deepSeekFlashModelButton).text = DEFAULT_DEEPSEEK_MODEL
        findViewById<RadioButton>(R.id.deepSeekChatModelButton).text = DEEPSEEK_CHAT_MODEL
        aiConfirmApiKeyButton.text = text("Confirm API Key", "确认 API Key")
        aiTestConnectionButton.text = text("Test AI Connection", "测试 AI 连接")
        recognizedTextConsoleTitle.text = text("Recognized Text", "识别文字")
        aiResponseConsoleTitle.text = text("Raw AI Response", "AI 原始返回内容")
        developerPasswordInput.hint = text("Password", "密码")
        developerModeButton.text = if (accessLevel != DeveloperAccessLevel.NONE) {
            text("Lock Software Settings", "锁定软件设置")
        } else {
            text("Unlock Software Settings", "解锁软件设置")
        }
        languageSwitchButton.text = if (AppPreferences.isChineseUiEnabled(this)) {
            "Switch UI to English"
        } else {
            "切换界面为中文"
        }
    }

    private fun updateDeveloperControlVisibility(accessLevel: DeveloperAccessLevel) {
        val advancedMode = accessLevel != DeveloperAccessLevel.NONE
        val fullDeveloperMode = accessLevel == DeveloperAccessLevel.FULL
        val advancedVisibility = if (advancedMode) android.view.View.VISIBLE else android.view.View.GONE
        val fullVisibility = if (fullDeveloperMode) android.view.View.VISIBLE else android.view.View.GONE
        val aiVisibility = if (
            advancedMode &&
            AppPreferences.getOcrMode(this) == OcrMode.AI_BOOST
        ) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        debugModeCheckBox.visibility = fullVisibility
        saveDebugScreenshotsCheckBox.visibility = fullVisibility
        recognizedTextConsoleCheckBox.visibility = fullVisibility
        highlightReadingLineCheckBox.visibility = fullVisibility
        pauseResumeReadingCheckBox.visibility = fullVisibility
        autoScrollCaptureCheckBox.visibility = fullVisibility
        autoScrollMaxCapturesInput.visibility = fullVisibility
        autoScrollMinSettleDelayInput.visibility = fullVisibility
        ocrModeTitle.visibility = advancedVisibility
        ocrModeGroup.visibility = advancedVisibility
        lastRunTimingText.visibility = advancedVisibility
        autoScrollStopReasonText.visibility = fullVisibility
        aiCorrectionTitle.visibility = aiVisibility
        aiCorrectionCheckBox.visibility = aiVisibility
        aiProviderText.visibility = aiVisibility
        aiProviderGroup.visibility = aiVisibility
        aiModelText.visibility = aiVisibility
        aiModelGroup.visibility = aiVisibility
        aiApiKeyStatusText.visibility = aiVisibility
        aiApiKeyInput.visibility = aiVisibility
        aiConfirmApiKeyButton.visibility = aiVisibility
        aiTestConnectionButton.visibility = aiVisibility
        languageSwitchButton.visibility = advancedVisibility
        overlayPermissionButton.visibility = advancedVisibility
        accessibilityButton.visibility = advancedVisibility
        batteryButton.visibility = advancedVisibility
        testReadButton.visibility = advancedVisibility
        testReadNoResetButton.visibility = advancedVisibility
        stopSpeechButton.visibility = advancedVisibility
        developerPasswordInput.visibility =
            if (advancedMode) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun text(english: String, chinese: String): String {
        return if (AppPreferences.isChineseUiEnabled(this)) chinese else english
    }

    private fun onOffText(enabled: Boolean): String {
        return if (enabled) text("On", "开") else text("Off", "关")
    }

    private fun readyText(enabled: Boolean): String {
        return if (enabled) text("Ready", "已就绪") else text("Missing", "未授予")
    }

    private fun developerAccessLabel(accessLevel: DeveloperAccessLevel): String {
        return when (accessLevel) {
            DeveloperAccessLevel.NONE -> text("Off", "关闭")
            DeveloperAccessLevel.CONFIGURATION -> text("Configuration", "配置模式")
            DeveloperAccessLevel.FULL -> text("Full developer", "完整开发者模式")
        }
    }

    private fun isFullDeveloperPassword(password: String): Boolean {
        val spec = PBEKeySpec(
            password.toCharArray(),
            FULL_DEVELOPER_PASSWORD_SALT.toByteArray(Charsets.UTF_8),
            FULL_DEVELOPER_PASSWORD_ITERATIONS,
            FULL_DEVELOPER_PASSWORD_KEY_LENGTH_BITS
        )
        val digest = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
        val expected = FULL_DEVELOPER_PASSWORD_HASH.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
        return MessageDigest.isEqual(digest, expected)
    }

    private fun updateSpeechRateControl() {
        val rate = AppPreferences.getSpeechRate(this)
        val progress = speechRateToSlider(rate)
        if (!speechRateSeekBar.isPressed && speechRateSeekBar.progress != progress) {
            speechRateSeekBar.progress = progress
        }
        speechRateValueText.text = speechRateLabel(rate)
    }

    private fun updatePlaybackProgressControl() {
        val playback = ScreenReaderController.getPlaybackProgress()
        val visible = playback != null &&
            (ScreenReaderController.isSpeaking() || ScreenReaderController.isPaused())
        playbackProgressPanel.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        if (!visible || playback == null) return

        if (!isSeekingPlayback) {
            playbackProgressSeekBar.progress = segmentToProgress(
                playback.currentSegment,
                playback.totalSegments
            )
            playbackProgressText.text = playbackPositionLabel(
                playback.currentSegment,
                playback.totalSegments
            )
        }
    }

    private fun speechRateToSlider(rate: Float): Int {
        val range = AppPreferences.MAX_SPEECH_RATE - AppPreferences.MIN_SPEECH_RATE
        return (((rate - AppPreferences.MIN_SPEECH_RATE) / range) * SPEECH_RATE_SLIDER_MAX)
            .toInt()
            .coerceIn(0, SPEECH_RATE_SLIDER_MAX)
    }

    private fun sliderToSpeechRate(progress: Int): Float {
        val fraction = progress.coerceIn(0, SPEECH_RATE_SLIDER_MAX).toFloat() /
            SPEECH_RATE_SLIDER_MAX.toFloat()
        return AppPreferences.MIN_SPEECH_RATE +
            fraction * (AppPreferences.MAX_SPEECH_RATE - AppPreferences.MIN_SPEECH_RATE)
    }

    private fun speechRateLabel(rate: Float): String {
        return text(
            "Current speed: %.1fx".format(rate),
            "当前速度：%.1fx".format(rate)
        )
    }

    private fun segmentToProgress(currentSegment: Int, totalSegments: Int): Int {
        if (totalSegments <= 1) return 0
        return (
            currentSegment.coerceIn(0, totalSegments - 1).toFloat() /
                (totalSegments - 1).toFloat() *
                ScreenReaderController.PLAYBACK_PROGRESS_MAX
            ).toInt()
    }

    private fun progressToSegment(progress: Int, totalSegments: Int): Int {
        if (totalSegments <= 1) return 0
        return (
            progress.coerceIn(0, ScreenReaderController.PLAYBACK_PROGRESS_MAX).toFloat() /
                ScreenReaderController.PLAYBACK_PROGRESS_MAX.toFloat() *
                (totalSegments - 1)
            ).toInt()
    }

    private fun playbackPositionLabel(currentSegment: Int, totalSegments: Int): String {
        val current = (currentSegment + 1).coerceAtMost(totalSegments)
        return text(
            "Phrase $current of $totalSegments. Drag to choose where to continue.",
            "第 $current / $totalSegments 段。拖动可选择继续朗读的位置。"
        )
    }

    private fun updateOcrModeSelection() {
        setOcrModeSelection(AppPreferences.getOcrMode(this))
    }

    private fun setOcrModeSelection(mode: OcrMode) {
        val checkedId = when (mode) {
            OcrMode.FAST -> R.id.ocrFastModeButton
            OcrMode.ACCURATE -> R.id.ocrAccurateModeButton
            OcrMode.AI_BOOST -> R.id.ocrAiBoostModeButton
        }
        if (ocrModeGroup.checkedRadioButtonId != checkedId) {
            suppressOcrModeSelection = true
            try {
                ocrModeGroup.check(checkedId)
            } finally {
                suppressOcrModeSelection = false
            }
        }
    }

    private fun showAiModeDisclosure(
        onAccept: () -> Unit,
        onCancel: () -> Unit
    ) {
        aiDisclosureVisible = true
        AlertDialog.Builder(this)
            .setTitle(text("Enable online AI correction?", "启用在线 AI 修正？"))
            .setMessage(
                text(
                    "Screen text recognized by OCR will be sent to the selected third-party AI provider for correction. It may contain personal, confidential, financial, medical, or other sensitive information. The provider may process or retain submitted text under its own terms and privacy policy.\n\nAPI usage may incur charges billed by the provider to the owner of the API key. The app developer does not control provider pricing, billing, availability, data handling, or model responses and is not responsible for resulting API charges.\n\nAI output may be inaccurate, incomplete, delayed, or refused. Avoid using this mode for information you do not want transmitted online.",
                    "OCR 识别出的屏幕文字将发送至所选的第三方在线 AI 服务商进行修正，其中可能包含个人、机密、财务、医疗或其他敏感信息。服务商可能依据其自身的条款和隐私政策处理或保存所提交的文字。\n\n调用 API 可能产生费用，并由 API Key 所有者向服务商支付。应用开发者无法控制服务商的定价、计费、可用性、数据处理方式或模型输出，也不承担由此产生的 API 费用。\n\nAI 输出可能不准确、不完整、延迟或被拒绝。请勿使用此模式处理您不希望上传到网络的信息。"
                )
            )
            .setPositiveButton(text("I understand, enable", "我已了解，继续启用")) { _, _ ->
                onAccept()
            }
            .setNegativeButton(text("Cancel", "取消")) { _, _ ->
                onCancel()
            }
            .setOnCancelListener { onCancel() }
            .setOnDismissListener { aiDisclosureVisible = false }
            .show()
    }

    private fun updateAiModelSelection() {
        val model = LlmPreferences.getConfig(this).model
        val checkedId = if (model == DEEPSEEK_CHAT_MODEL) {
            R.id.deepSeekChatModelButton
        } else {
            R.id.deepSeekFlashModelButton
        }
        if (aiModelGroup.checkedRadioButtonId != checkedId) {
            aiModelGroup.check(checkedId)
        }
    }

    private fun ocrModeLabel(mode: OcrMode): String {
        return when (mode) {
            OcrMode.FAST -> text("Fast", "快速")
            OcrMode.ACCURATE -> text("Accurate", "准确")
            OcrMode.AI_BOOST -> text("AI Boost", "AI 增强")
        }
    }

    private fun aiCorrectionStatusText(): String {
        if (!LlmPreferences.isSecureStorageAvailable(this)) {
            return text("Unavailable: secure storage failed", "不可用：安全存储失败")
        }
        if (AppPreferences.getOcrMode(this) != OcrMode.AI_BOOST) {
            return text("Off (AI Boost mode not selected)", "关（未选择 AI 增强模式）")
        }
        val enabledText = onOffText(LlmPreferences.isEnabled(this))
        val keyText = if (LlmPreferences.hasApiKey(this)) {
            text("API key configured", "API Key 已配置")
        } else {
            text("API key missing", "缺少 API Key")
        }
        return "$enabledText, $keyText"
    }

    private fun showSecureStorageUnavailable() {
        val detail = LlmPreferences.getSecureStorageError(this).orEmpty()
        val message = text(
            "Secure encrypted storage is unavailable, so the API key was not saved. ${detail.ifBlank { "Check the device security/Keystore configuration." }}",
            "安全加密存储不可用，因此 API Key 未保存。${detail.ifBlank { "请检查设备安全设置或 Android Keystore。" }}"
        )
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun lastRunTimingLabel(): String {
        val durationMs = ScreenReaderController.getLastRunDurationMs()
        return if (durationMs == null) {
            text("Last run timing: not available yet.", "上次耗时：暂无。")
        } else {
            val seconds = durationMs / 1000.0
            text(
                "Last run: %.2fs from capture/scroll complete to reading start.".format(seconds),
                "上次耗时：%.2f 秒（截图/滚动完成到开始朗读）。".format(seconds)
            )
        }
    }

    private fun autoScrollStopReasonLabel(): String {
        val stored = AppPreferences.getAutoScrollLastStopReason(this)
        if (stored.isBlank()) {
            return text(
                "Last auto-scroll stop reason:\nNo auto-scroll run recorded yet.",
                "上次停止滚动原因：\n尚未记录自动滚动识别。"
            )
        }

        val parts = stored.split('|', limit = 3)
        val code = parts.getOrNull(0).orEmpty()
        val captures = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val detail = parts.getOrNull(2).orEmpty()
        val reason = when (code) {
            "RUNNING" -> text("Capture is currently running", "当前正在截图")
            "VISUALLY_SIMILAR" -> text(
                "Next screenshot was judged visually unchanged",
                "下一张截图被判断为画面未变化"
            )
            "MAX_CAPTURES" -> text(
                "Configured screenshot limit reached",
                "已达到设置的最大截图次数"
            )
            "GESTURE_FAILED" -> text(
                "Scroll gesture was rejected or cancelled",
                "滚动手势被系统拒绝或取消"
            )
            "SCREENSHOT_FAILED" -> text(
                "Screenshot capture failed",
                "屏幕截图失败"
            )
            else -> text("Unknown reason", "未知原因")
        }
        val localizedDetail = when (code) {
            "VISUALLY_SIMILAR" -> text(
                detail,
                "系统认为新截图与上一张几乎相同，因此停止继续滚动。"
            )
            "MAX_CAPTURES" -> text(detail, "已达到开发者设置中的最大截图数量。")
            "GESTURE_FAILED" -> text(detail, "Android 或 HyperOS 未接受下一次自动滚动手势。")
            else -> detail
        }
        return buildString {
            appendLine(text("Last auto-scroll stop reason:", "上次停止滚动原因："))
            appendLine(reason)
            appendLine("${text("Accepted screenshots", "已接受截图数量")}: $captures")
            if (localizedDetail.isNotBlank()) {
                append(localizedDetail)
            }
        }
    }

    private fun localizeStatus(status: String): String {
        if (!AppPreferences.isChineseUiEnabled(this)) return status
        return when {
            status.startsWith("Ready.") -> "准备好了。请先授权并启动悬浮按钮，然后点击悬浮按钮朗读。"
            status.startsWith("Floating button started") -> "悬浮按钮已启动。"
            status.startsWith("Speech stopped") -> "朗读已停止。"
            status.startsWith("Reading paused") -> "朗读已暂停。"
            status.startsWith("Reading resumed") -> "继续朗读。"
            status.startsWith("Reading halted") -> "朗读已终止。"
            status.startsWith("Capturing screen") -> "正在截取屏幕..."
            status.startsWith("Recognizing text") -> "正在识别文字..."
            status.startsWith("Correcting OCR text") -> "正在进行 AI 文字修正..."
            status.startsWith("Reading aloud") -> "正在朗读..."
            status.startsWith("Ready for the next read") -> "已完成，可以再次朗读。"
            status.startsWith("No text found") -> "没有识别到文字。"
            status.startsWith("Accessibility connected") -> "无障碍服务已连接，可以开始测试。"
            status.startsWith("Accessibility not connected") -> "无障碍服务未连接，请在无障碍设置中开启。"
            status.startsWith("Overlay permission is missing") -> "缺少悬浮窗权限，请先授权。"
            status.startsWith("Speech is not ready") -> "语音朗读还没准备好。"
            status.startsWith("Playing demo speech") -> "正在播放测试语音。"
            status.startsWith("Playing raw demo speech") -> "正在播放原始测试语音。"
            else -> status
        }
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
        private const val CONFIGURATION_PASSWORD = "12345678"
        private const val FULL_DEVELOPER_PASSWORD_SALT = "screen-reader-full-access-v1"
        private const val FULL_DEVELOPER_PASSWORD_ITERATIONS = 120_000
        private const val FULL_DEVELOPER_PASSWORD_KEY_LENGTH_BITS = 256
        private const val FULL_DEVELOPER_PASSWORD_HASH =
            "e49e923206ef87e2ed57f0af186bca47203a6c02a6e5ea03d98b4abf06990453"
        private const val SPEECH_RATE_SLIDER_MAX = 100
    }
}
