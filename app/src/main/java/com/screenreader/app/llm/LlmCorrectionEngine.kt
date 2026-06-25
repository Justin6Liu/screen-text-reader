package com.screenreader.app.llm

import android.content.Context
import kotlinx.coroutines.runBlocking

object LlmCorrectionEngine {

    fun correctIfEnabled(context: Context, text: String): String {
        return correctWithDetails(context, text).outputText
    }

    fun correctWithDetails(context: Context, text: String): LlmCorrectionResult {
        if (text.isBlank()) {
            return LlmCorrectionResult(
                originalText = text,
                outputText = text,
                rawResponse = "",
                status = "AI correction was not run.",
                usedFallback = true
            )
        }
        return runCatching {
            runBlocking {
                providerFor(LlmPreferences.getConfig(context)).correctOcrTextWithDiagnostics(text)
            }
        }.fold(
            onSuccess = { response ->
                val corrected = response.correctedText.ifBlank { text }
                if (isUsableCorrection(text, corrected)) {
                    LlmCorrectionResult(
                        originalText = text,
                        outputText = corrected,
                        rawResponse = response.rawResponseBody,
                        status = "AI correction accepted.",
                        usedFallback = false
                    )
                } else {
                    LlmCorrectionResult(
                        originalText = text,
                        outputText = text,
                        rawResponse = response.rawResponseBody,
                        status = "AI response rejected; original OCR text was used.",
                        usedFallback = true
                    )
                }
            },
            onFailure = { error ->
                val rawResponseBody = (error as? LlmProviderException)?.rawResponseBody.orEmpty()
                val diagnostic = rawResponseBody.ifBlank {
                    "${error.javaClass.simpleName}: ${error.message ?: "unknown error"}"
                }
                LlmCorrectionResult(
                    originalText = text,
                    outputText = text,
                    rawResponse = diagnostic,
                    status = "AI correction failed; original OCR text was used.",
                    usedFallback = true
                )
            }
        )
    }

    private fun isUsableCorrection(original: String, corrected: String): Boolean {
        if (corrected.isBlank()) return false
        val normalizedOriginal = original.filterNot { it.isWhitespace() }
        val normalizedCorrected = corrected.filterNot { it.isWhitespace() }
        if (normalizedOriginal.length >= LONG_TEXT_THRESHOLD) {
            val minLength = (normalizedOriginal.length * MIN_CORRECTION_LENGTH_RATIO).toInt()
            if (normalizedCorrected.length < minLength) return false
        }
        return REFUSAL_MARKERS.none { marker ->
            corrected.contains(marker, ignoreCase = true)
        }
    }

    fun testConnection(context: Context): Result<String> {
        val config = LlmPreferences.getConfig(context)
        return runCatching {
            runBlocking {
                providerFor(config).correctOcrText(TEST_TEXT)
            }
        }
    }

    private fun providerFor(config: LlmConfig): LlmProvider {
        return when (config.provider) {
            Provider.DEEPSEEK -> DeepSeekProvider(config)
            Provider.OPENAI,
            Provider.GEMINI -> throw UnsupportedOperationException("${config.provider} provider is not implemented yet.")
        }
    }

    private const val TEST_TEXT = "你好，祝你今天生活愉快。"
    private const val LONG_TEXT_THRESHOLD = 80
    private const val MIN_CORRECTION_LENGTH_RATIO = 0.55
    private val REFUSAL_MARKERS = listOf(
        "sorry",
        "policy",
        "I can't",
        "I cannot",
        "can't assist",
        "cannot assist",
        "无法处理",
        "无法提供",
        "不能提供",
        "我不能",
        "政策限制",
        "敏感内容"
    )
}

data class LlmCorrectionResult(
    val originalText: String,
    val outputText: String,
    val rawResponse: String,
    val status: String,
    val usedFallback: Boolean
)
