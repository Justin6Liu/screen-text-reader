package com.screenreader.app.runtime

import android.content.Context

object AppPreferences {

    private const val PREFS_NAME = "screen_reader_prefs"
    private const val KEY_OCR_DEBUG_MODE = "ocr_debug_mode"
    private const val KEY_SAVE_DEBUG_SCREENSHOTS = "save_debug_screenshots"
    private const val KEY_SHOW_RECOGNIZED_TEXT_CONSOLE = "show_recognized_text_console"
    private const val KEY_HIGHLIGHT_READING_LINE = "highlight_reading_line"
    private const val KEY_PAUSE_RESUME_READING = "pause_resume_reading"
    private const val KEY_DEVELOPER_MODE = "developer_mode"
    private const val KEY_DEVELOPER_ACCESS_LEVEL = "developer_access_level"
    private const val KEY_CHINESE_UI = "chinese_ui"
    private const val KEY_AUTO_SCROLL_CAPTURE = "auto_scroll_capture"
    private const val KEY_AUTO_SCROLL_MAX_CAPTURES = "auto_scroll_max_captures"
    private const val KEY_AUTO_SCROLL_MIN_SETTLE_DELAY_MS = "auto_scroll_min_settle_delay_ms"
    private const val KEY_AUTO_SCROLL_LAST_STOP_REASON = "auto_scroll_last_stop_reason"
    private const val KEY_OCR_MODE = "ocr_mode"
    private const val KEY_SPEECH_RATE = "speech_rate"

    fun isOcrDebugModeEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_OCR_DEBUG_MODE, false)
    }

    fun setOcrDebugModeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_OCR_DEBUG_MODE, enabled).apply()
    }

    fun isSaveDebugScreenshotsEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SAVE_DEBUG_SCREENSHOTS, false)
    }

    fun setSaveDebugScreenshotsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SAVE_DEBUG_SCREENSHOTS, enabled).apply()
    }

    fun isRecognizedTextConsoleEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SHOW_RECOGNIZED_TEXT_CONSOLE, false)
    }

    fun setRecognizedTextConsoleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_RECOGNIZED_TEXT_CONSOLE, enabled).apply()
    }

    fun isHighlightReadingLineEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HIGHLIGHT_READING_LINE, false)
    }

    fun setHighlightReadingLineEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIGHLIGHT_READING_LINE, enabled).apply()
    }

    fun isPauseResumeReadingEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_PAUSE_RESUME_READING, true)
    }

    fun setPauseResumeReadingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PAUSE_RESUME_READING, enabled).apply()
    }

    fun isDeveloperModeEnabled(context: Context): Boolean {
        return getDeveloperAccessLevel(context) != DeveloperAccessLevel.NONE
    }

    fun setDeveloperModeEnabled(context: Context, enabled: Boolean) {
        setDeveloperAccessLevel(
            context,
            if (enabled) DeveloperAccessLevel.FULL else DeveloperAccessLevel.NONE
        )
    }

    fun getDeveloperAccessLevel(context: Context): DeveloperAccessLevel {
        val prefs = prefs(context)
        val stored = prefs.getString(KEY_DEVELOPER_ACCESS_LEVEL, null)
        if (stored != null) {
            return DeveloperAccessLevel.values().firstOrNull { it.name == stored }
                ?: DeveloperAccessLevel.NONE
        }
        return if (prefs.getBoolean(KEY_DEVELOPER_MODE, false)) {
            DeveloperAccessLevel.FULL
        } else {
            DeveloperAccessLevel.NONE
        }
    }

    fun setDeveloperAccessLevel(context: Context, level: DeveloperAccessLevel) {
        prefs(context).edit()
            .putString(KEY_DEVELOPER_ACCESS_LEVEL, level.name)
            .putBoolean(KEY_DEVELOPER_MODE, level != DeveloperAccessLevel.NONE)
            .apply()
    }

    fun isChineseUiEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CHINESE_UI, true)
    }

    fun setChineseUiEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CHINESE_UI, enabled).apply()
    }

    fun isAutoScrollCaptureEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTO_SCROLL_CAPTURE, false)
    }

    fun setAutoScrollCaptureEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SCROLL_CAPTURE, enabled).apply()
    }

    fun getAutoScrollMaxCaptures(context: Context): Int {
        return prefs(context)
            .getInt(KEY_AUTO_SCROLL_MAX_CAPTURES, DEFAULT_AUTO_SCROLL_MAX_CAPTURES)
            .coerceIn(MIN_AUTO_SCROLL_MAX_CAPTURES, MAX_AUTO_SCROLL_MAX_CAPTURES)
    }

    fun setAutoScrollMaxCaptures(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(
                KEY_AUTO_SCROLL_MAX_CAPTURES,
                value.coerceIn(MIN_AUTO_SCROLL_MAX_CAPTURES, MAX_AUTO_SCROLL_MAX_CAPTURES)
            )
            .apply()
    }

    fun getAutoScrollMinSettleDelayMs(context: Context): Int {
        return prefs(context)
            .getInt(KEY_AUTO_SCROLL_MIN_SETTLE_DELAY_MS, DEFAULT_AUTO_SCROLL_MIN_SETTLE_DELAY_MS)
            .coerceIn(MIN_AUTO_SCROLL_SETTLE_DELAY_MS, MAX_AUTO_SCROLL_SETTLE_DELAY_MS)
    }

    fun setAutoScrollMinSettleDelayMs(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(
                KEY_AUTO_SCROLL_MIN_SETTLE_DELAY_MS,
                value.coerceIn(
                    MIN_AUTO_SCROLL_SETTLE_DELAY_MS,
                    MAX_AUTO_SCROLL_SETTLE_DELAY_MS
                )
            )
            .apply()
    }

    fun getAutoScrollLastStopReason(context: Context): String {
        return prefs(context).getString(KEY_AUTO_SCROLL_LAST_STOP_REASON, null).orEmpty()
    }

    fun setAutoScrollLastStopReason(context: Context, reason: String) {
        prefs(context).edit()
            .putString(KEY_AUTO_SCROLL_LAST_STOP_REASON, reason)
            .apply()
    }

    fun getOcrMode(context: Context): OcrMode {
        val name = prefs(context).getString(KEY_OCR_MODE, OcrMode.ACCURATE.name)
        return OcrMode.values().firstOrNull { it.name == name } ?: OcrMode.ACCURATE
    }

    fun setOcrMode(context: Context, mode: OcrMode) {
        prefs(context).edit().putString(KEY_OCR_MODE, mode.name).apply()
    }

    fun getSpeechRate(context: Context): Float {
        return prefs(context)
            .getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE)
            .coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
    }

    fun setSpeechRate(context: Context, rate: Float) {
        prefs(context).edit()
            .putFloat(KEY_SPEECH_RATE, rate.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE))
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    const val DEFAULT_AUTO_SCROLL_MAX_CAPTURES = 12
    const val MIN_AUTO_SCROLL_MAX_CAPTURES = 1
    const val MAX_AUTO_SCROLL_MAX_CAPTURES = 15
    const val DEFAULT_AUTO_SCROLL_MIN_SETTLE_DELAY_MS = 300
    const val MIN_AUTO_SCROLL_SETTLE_DELAY_MS = 300
    const val MAX_AUTO_SCROLL_SETTLE_DELAY_MS = 1000
    const val DEFAULT_SPEECH_RATE = 1.0f
    const val MIN_SPEECH_RATE = 0.5f
    const val MAX_SPEECH_RATE = 3.0f
}

enum class OcrMode {
    FAST,
    ACCURATE,
    AI_BOOST
}

enum class DeveloperAccessLevel {
    NONE,
    CONFIGURATION,
    FULL
}
