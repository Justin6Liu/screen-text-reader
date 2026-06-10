package com.screenreader.app.llm

data class LlmConfig(
    val provider: Provider = Provider.DEEPSEEK,
    val apiKey: String = "",
    val model: String = DEFAULT_DEEPSEEK_MODEL,
    val baseUrl: String = DEFAULT_DEEPSEEK_BASE_URL
)

enum class Provider {
    DEEPSEEK,
    OPENAI,
    GEMINI
}

const val DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com"
const val DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash"
const val DEEPSEEK_CHAT_MODEL = "deepseek-chat"
