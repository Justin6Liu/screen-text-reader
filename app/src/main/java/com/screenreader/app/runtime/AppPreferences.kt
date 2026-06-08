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
    private const val KEY_CHINESE_UI = "chinese_ui"
    private const val KEY_AUTO_SCROLL_CAPTURE = "auto_scroll_capture"
    private const val KEY_AUTO_SCROLL_MAX_CAPTURES = "auto_scroll_max_captures"

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
        return prefs(context).getBoolean(KEY_DEVELOPER_MODE, false)
    }

    fun setDeveloperModeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DEVELOPER_MODE, enabled).apply()
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

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    const val DEFAULT_AUTO_SCROLL_MAX_CAPTURES = 12
    const val MIN_AUTO_SCROLL_MAX_CAPTURES = 1
    const val MAX_AUTO_SCROLL_MAX_CAPTURES = 15
}
