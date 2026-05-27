package com.screenreader.app.ocr

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognition

class OcrEngine(context: Context) {

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    fun recognize(bitmap: Bitmap): Result<String> {
        return runCatching {
            val scaledBitmap = downscale(bitmap)
            val image = InputImage.fromBitmap(scaledBitmap, 0)
            val result = Tasks.await(recognizer.process(image))
            result.text.trim()
        }
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val maxDimension = 1600
        val width = bitmap.width
        val height = bitmap.height
        val longestSide = maxOf(width, height)
        if (longestSide <= maxDimension) return bitmap

        val scale = maxDimension.toFloat() / longestSide.toFloat()
        val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
    }
}
