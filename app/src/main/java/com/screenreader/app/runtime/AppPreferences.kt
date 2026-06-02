package com.screenreader.app.runtime

import android.content.Context

object AppPreferences {

    private const val PREFS_NAME = "screen_reader_prefs"
    private const val KEY_OCR_DEBUG_MODE = "ocr_debug_mode"
    private const val KEY_SAVE_DEBUG_SCREENSHOTS = "save_debug_screenshots"
    private const val KEY_SHOW_RECOGNIZED_TEXT_CONSOLE = "show_recognized_text_console"

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

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
