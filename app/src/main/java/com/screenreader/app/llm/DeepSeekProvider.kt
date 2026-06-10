package com.screenreader.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class DeepSeekProvider(
    private val config: LlmConfig
) : LlmProvider {

    override suspend fun correctOcrText(text: String): String = withContext(Dispatchers.IO) {
        require(config.apiKey.isNotBlank()) { "DeepSeek API key is missing." }
        if (text.isBlank()) return@withContext text

        val connection = openConnection()
        try {
            val body = buildRequestBody(text)
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }

            val responseCode = connection.responseCode
            val responseText = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
            } else {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { reader -> reader.readText() }.orEmpty()
            }
            if (responseCode !in 200..299) {
                throw IllegalStateException("DeepSeek request failed: HTTP $responseCode $responseText")
            }

            parseCorrectedText(responseText).ifBlank { text }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(): HttpURLConnection {
        val baseUrl = config.baseUrl.trimEnd('/')
        val url = URL("$baseUrl/chat/completions")
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = REQUEST_TIMEOUT_MS
            readTimeout = REQUEST_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        }
    }

    private fun buildRequestBody(text: String): JSONObject {
        return JSONObject()
            .put("model", config.model.ifBlank { DEFAULT_DEEPSEEK_MODEL })
            .put("temperature", 0)
            .put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", OCR_CORRECTION_PROMPT))
                put(JSONObject().put("role", "user").put("content", text))
            })
    }

    private fun parseCorrectedText(responseText: String): String {
        val root = JSONObject(responseText)
        val choices = root.optJSONArray("choices") ?: return ""
        val first = choices.optJSONObject(0) ?: return ""
        val message = first.optJSONObject("message") ?: return ""
        return message.optString("content").trim()
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 12_000

        private const val OCR_CORRECTION_PROMPT = """
You are an OCR correction engine.

Correct only obvious OCR character recognition errors in the Chinese text below.

Rules:
* Preserve original sentence structure.
* Preserve punctuation.
* Preserve line breaks whenever possible.
* Do not summarize.
* Do not rewrite for style.
* Do not add information.
* Do not remove information.
* Do not change names unless the correction is very likely.
* If uncertain, keep the original text.
* Return only the corrected text.
"""
    }
}
