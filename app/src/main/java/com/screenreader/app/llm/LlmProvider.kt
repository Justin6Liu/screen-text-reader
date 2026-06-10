package com.screenreader.app.llm

interface LlmProvider {
    suspend fun correctOcrText(text: String): String

    suspend fun correctOcrTextWithDiagnostics(text: String): LlmProviderResponse {
        val correctedText = correctOcrText(text)
        return LlmProviderResponse(
            correctedText = correctedText,
            rawResponseBody = correctedText
        )
    }
}

data class LlmProviderResponse(
    val correctedText: String,
    val rawResponseBody: String
)

class LlmProviderException(
    message: String,
    val rawResponseBody: String = "",
    cause: Throwable? = null
) : Exception(message, cause)
