package com.screenreader.app.ocr

import android.graphics.Rect

data class OcrReadSegment(
    val text: String,
    val bounds: Rect,
    val startIndex: Int,
    val endIndex: Int
)
