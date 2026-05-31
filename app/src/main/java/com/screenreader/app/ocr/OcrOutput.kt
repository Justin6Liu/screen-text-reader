package com.screenreader.app.ocr

data class OcrOutput(
    val text: String,
    val debugSnapshot: OcrDebugSnapshot?
)
