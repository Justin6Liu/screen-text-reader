package com.screenreader.app.llm

import android.content.Context
import kotlinx.coroutines.runBlocking

object LlmCorrectionEngine {

    fun correctIfEnabled(context: Context, text: String): String {
        if (!LlmPreferences.isEnabled(context) || text.isBlank()) return text
        return runCatching {
            runBlocking {
                providerFor(LlmPreferences.getConfig(context)).correctOcrText(text)
            }
        }.getOrElse {
            text
        }.ifBlank {
            text
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

    private const val TEST_TEXT = "朝鲜完法2026年3月"
}
