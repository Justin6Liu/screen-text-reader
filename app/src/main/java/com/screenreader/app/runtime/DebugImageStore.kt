package com.screenreader.app.runtime

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugImageStore {

    private val timestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    fun saveCapturedScreenshot(context: Context, bitmap: Bitmap): Result<File> {
        return runCatching {
            val directory = File(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "ocr-debug"
            ).apply { mkdirs() }

            val file = File(directory, "capture_${timestampFormat.format(Date())}.png")
            FileOutputStream(file).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Failed to compress debug screenshot."
                }
                stream.flush()
            }
            file
        }
    }
}
