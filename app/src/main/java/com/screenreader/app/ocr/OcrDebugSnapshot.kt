package com.screenreader.app.ocr

import android.graphics.Rect

data class OcrDebugSnapshot(
    val imageWidth: Int,
    val imageHeight: Int,
    val lineBounds: List<Rect>,
    val paragraphBounds: List<Rect>
)
