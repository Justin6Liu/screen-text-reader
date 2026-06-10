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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.screenreader.app.runtime.OcrMode
import com.screenreader.app.runtime.ScreenReaderController

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var speechStatusText: TextView
    private lateinit var overlayPermissionButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var batteryButton: Button
    private lateinit var startOverlayButton: Button
    private lateinit var stopOverlayButton: Button
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
    private lateinit var ocrModeTitle: TextView
    private lateinit var ocrModeGroup: RadioGroup
    private lateinit var lastRunTimingText: TextView
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
        ocrModeTitle = findViewById(R.id.ocrModeTitle)
        ocrModeGroup = findViewById(R.id.ocrModeGroup)
        lastRunTimingText = findViewById(R.id.lastRunTimingText)
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

        ocrModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.ocrFastModeButton -> OcrMode.FAST
                R.id.ocrAiBoostModeButton -> OcrMode.AI_BOOST
                else -> OcrMode.ACCURATE
            }
            AppPreferences.setOcrMode(this, mode)
            if (mode == OcrMode.AI_BOOST) {
                LlmPreferences.setProvider(this, Provider.DEEPSEEK)
                LlmPreferences.setBaseUrl(this, DEFAULT_DEEPSEEK_BASE_URL)
            }
            refreshStatus()
        }

        aiCorrectionCheckBox.isChecked = LlmPreferences.isEnabled(this)
        aiCorrectionCheckBox.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
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
            LlmPreferences.setApiKey(this, apiKey)
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
            if (AppPreferences.isDeveloperModeEnabled(this)) {
                AppPreferences.setDeveloperModeEnabled(this, false)
                developerPasswordInput.text.clear()
                Toast.makeText(this, text("Developer mode is off.", "开发者模式已关闭。"), Toast.LENGTH_SHORT).show()
            } else {
                val password = developerPasswordInput.text.toString()
                if (password.isBlank()) {
                    developerPasswordInput.requestFocus()
                    val inputManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    inputManager.showSoftInput(developerPasswordInput, InputMethodManager.SHOW_IMPLICIT)
                    Toast.makeText(this, text("Enter developer password.", "请输入开发者密码。"), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (password == DEVELOPER_PASSWORD) {
                    AppPreferences.setDeveloperModeEnabled(this, true)
                    developerPasswordInput.text.clear()
                    Toast.makeText(this, text("Developer mode is on.", "开发者模式已开启。"), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, text("Incorrect password.", "密码错误。"), Toast.LENGTH_SHORT).show()
                }
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
    }

    private fun refreshStatus() {
        val overlayGranted = Settings.canDrawOverlays(this)
        val batteryIgnored = isIgnoringBatteryOptimizations()
        val accessibilityReady = ScreenReaderController.isAccessibilityReady()
        val overlayRunning = OverlayService.isRunning
        val developerMode = AppPreferences.isDeveloperModeEnabled(this)
        applyLanguage(developerMode)
        updateOcrModeSelection()
        updateAiModelSelection()

        statusText.text = buildString {
            appendLine(text("Device Status", "设备状态"))
            appendLine()
            appendLine("${text("Overlay permission", "悬浮窗权限")}: ${readyText(overlayGranted)}")
            appendLine("${text("Accessibility service", "无障碍服务")}: ${if (accessibilityReady) text("Connected", "已连接") else text("Not connected", "未连接")}")
            appendLine("${text("Floating button", "悬浮按钮")}: ${if (overlayRunning) text("Running", "运行中") else text("Stopped", "已停止")}")
            appendLine("${text("Battery optimization", "电池优化")}: ${if (batteryIgnored) text("Ignored", "已忽略") else text("Default", "默认")}")
            append("${text("Developer mode", "开发者模式")}: ${onOffText(developerMode)}")
            if (developerMode) {
                appendLine()
                appendLine("${text("OCR debug mode", "OCR 调试框")}: ${onOffText(AppPreferences.isOcrDebugModeEnabled(this@MainActivity))}")
                appendLine("${text("Save debug screenshots", "保存调试截图")}: ${onOffText(AppPreferences.isSaveDebugScreenshotsEnabled(this@MainActivity))}")
                appendLine("${text("Text console", "识别文字显示")}: ${onOffText(AppPreferences.isRecognizedTextConsoleEnabled(this@MainActivity))}")
                appendLine("${text("Reading highlight", "朗读高亮")}: ${onOffText(AppPreferences.isHighlightReadingLineEnabled(this@MainActivity))}")
                appendLine("${text("Tap pause/resume", "点击暂停/继续")}: ${onOffText(AppPreferences.isPauseResumeReadingEnabled(this@MainActivity))}")
                appendLine("${text("Auto scroll capture", "自动滚动整图识别")}: ${onOffText(AppPreferences.isAutoScrollCaptureEnabled(this@MainActivity))}")
                appendLine("${text("Max captures", "最大截图次数")}: ${AppPreferences.getAutoScrollMaxCaptures(this@MainActivity)}")
                appendLine("${text("OCR mode", "OCR 模式")}: ${ocrModeLabel(AppPreferences.getOcrMode(this@MainActivity))}")
                append("${text("AI correction", "AI 修正")}: ${aiCorrectionStatusText()}")
            }
        }

        speechStatusText.text = localizeStatus(ScreenReaderController.getUiStatus())
        lastRunTimingText.text = lastRunTimingLabel()
        updateDeveloperControlVisibility(developerMode)
        val showConsole = developerMode && AppPreferences.isRecognizedTextConsoleEnabled(this)
        val visibility = if (showConsole) android.view.View.VISIBLE else android.view.View.GONE
        recognizedTextConsoleTitle.visibility = visibility
        recognizedTextConsoleText.visibility = visibility
        recognizedTextConsoleText.text = ScreenReaderController.getLastRecognizedText()
    }

    private fun applyLanguage(developerMode: Boolean) {
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
        ocrModeTitle.text = text("OCR Mode", "OCR 模式")
        findViewById<RadioButton>(R.id.ocrFastModeButton).text = text("Fast", "快速")
        findViewById<RadioButton>(R.id.ocrAccurateModeButton).text = text("Accurate", "准确")
        findViewById<RadioButton>(R.id.ocrAiBoostModeButton).text = text("AI Boost", "AI 增强")
        aiCorrectionTitle.text = text("AI Correction", "AI 修正")
        aiCorrectionCheckBox.text = text("Enable AI correction", "启用 AI 修正")
        aiProviderText.text = text("Provider", "服务商")
        findViewById<RadioButton>(R.id.deepSeekProviderButton).text = "DeepSeek"
        aiModelText.text = text("Model", "模型")
        aiApiKeyStatusText.text = if (LlmPreferences.hasApiKey(this)) {
            text("API key already configured.", "API Key 已配置。")
        } else {
            text("API key not configured.", "API Key 未配置。")
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
        developerPasswordInput.hint = text("Password", "密码")
        developerModeButton.text = if (developerMode) {
            text("Turn Off Developer Mode", "关闭开发者模式")
        } else {
            text("Developer Mode", "开发者模式")
        }
        languageSwitchButton.text = if (AppPreferences.isChineseUiEnabled(this)) {
            "Switch UI to English"
        } else {
            "切换界面为中文"
        }
    }

    private fun updateDeveloperControlVisibility(developerMode: Boolean) {
        val developerVisibility = if (developerMode) android.view.View.VISIBLE else android.view.View.GONE
        val aiVisibility = if (
            developerMode &&
            AppPreferences.getOcrMode(this) == OcrMode.AI_BOOST
        ) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        debugModeCheckBox.visibility = developerVisibility
        saveDebugScreenshotsCheckBox.visibility = developerVisibility
        recognizedTextConsoleCheckBox.visibility = developerVisibility
        highlightReadingLineCheckBox.visibility = developerVisibility
        pauseResumeReadingCheckBox.visibility = developerVisibility
        autoScrollCaptureCheckBox.visibility = developerVisibility
        autoScrollMaxCapturesInput.visibility = developerVisibility
        ocrModeTitle.visibility = developerVisibility
        ocrModeGroup.visibility = developerVisibility
        lastRunTimingText.visibility = developerVisibility
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
        languageSwitchButton.visibility = developerVisibility
        overlayPermissionButton.visibility = developerVisibility
        accessibilityButton.visibility = developerVisibility
        batteryButton.visibility = developerVisibility
        testReadButton.visibility = developerVisibility
        testReadNoResetButton.visibility = developerVisibility
        stopSpeechButton.visibility = developerVisibility
        developerPasswordInput.visibility = if (developerMode) android.view.View.GONE else android.view.View.VISIBLE
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

    private fun updateOcrModeSelection() {
        val checkedId = when (AppPreferences.getOcrMode(this)) {
            OcrMode.FAST -> R.id.ocrFastModeButton
            OcrMode.ACCURATE -> R.id.ocrAccurateModeButton
            OcrMode.AI_BOOST -> R.id.ocrAiBoostModeButton
        }
        if (ocrModeGroup.checkedRadioButtonId != checkedId) {
            ocrModeGroup.check(checkedId)
        }
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
        private const val DEVELOPER_PASSWORD = "12345678"
    }
}
