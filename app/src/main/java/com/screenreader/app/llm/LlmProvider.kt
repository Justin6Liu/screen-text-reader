package com.screenreader.app.llm

interface LlmProvider {
    suspend fun correctOcrText(text: String): String
}
