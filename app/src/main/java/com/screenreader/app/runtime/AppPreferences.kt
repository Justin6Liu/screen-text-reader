package com.screenreader.app.runtime

import android.content.Context

object AppPreferences {

    private const val PREFS_NAME = "screen_reader_prefs"
    private const val KEY_OCR_DEBUG_MODE = "ocr_debug_mode"

    fun isOcrDebugModeEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_OCR_DEBUG_MODE, false)
    }

    fun setOcrDebugModeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_OCR_DEBUG_MODE, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
