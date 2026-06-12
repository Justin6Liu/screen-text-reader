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

    override suspend fun correctOcrText(text: String): String {
        return correctOcrTextWithDiagnostics(text).correctedText
    }

    override suspend fun correctOcrTextWithDiagnostics(text: String): LlmProviderResponse =
        withContext(Dispatchers.IO) {
            require(config.apiKey.isNotBlank()) { "DeepSeek API key is missing." }
            if (text.isBlank()) {
                return@withContext LlmProviderResponse(
                    correctedText = text,
                    rawResponseBody = ""
                )
            }

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
                    throw LlmProviderException(
                        message = "DeepSeek request failed: HTTP $responseCode",
                        rawResponseBody = responseText
                    )
                }

                LlmProviderResponse(
                    correctedText = parseCorrectedText(responseText).ifBlank { text },
                    rawResponseBody = responseText
                )
            } catch (error: LlmProviderException) {
                throw error
            } catch (error: Exception) {
                throw LlmProviderException(
                    message = "${error.javaClass.simpleName}: ${error.message ?: "unknown network error"}",
                    cause = error
                )
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
            .put("thinking", JSONObject().put("type", "disabled"))
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
        private const val REQUEST_TIMEOUT_MS = 120_000

        private const val OCR_CORRECTION_PROMPT = """
        You are an OCR correction engine.

        Your task is to restore the original text as accurately as possible from OCR output.
        
        Correct obvious OCR recognition errors while preserving the original meaning and content.
        
        Rules:
        
        * Correct obvious character recognition errors.
        * Preserve the original meaning.
        * Preserve all meaningful information present in the input.
        * Remove only content that is highly likely to be duplicated by OCR or screenshot overlap.
        * If a short text fragment is clearly an OCR artifact and breaks the semantic flow of surrounding text, remove or repair it only when the intended content is highly certain.
        * Correct common OCR confusions involving visually similar Chinese characters, letters, and digits when the intended text is obvious from context.
        * Do not summarize.
        * Do not rewrite for style.
        * Do not add new information.
        * Do not remove meaningful information unless it is highly likely to be an OCR artifact or duplicated screenshot overlap.

        
        Structure handling:
        
        * Preserve the overall document structure whenever possible.
        * You may repair sentence boundaries that were broken by OCR or screenshot stitching.
        * You may merge adjacent lines or paragraphs when they clearly belong to the same sentence or paragraph.
        * You may adjust punctuation when necessary to restore fluent and grammatically correct sentences.
        * You may remove duplicated words, phrases, sentences, or paragraphs when they are clearly OCR or screenshot-overlap artifacts.
        * You may repair obviously corrupted text segments when the intended meaning is highly confident from surrounding context.
        * If a sentence is clearly incomplete due to a line break or screenshot boundary, merge it with the following text.
        * If two consecutive sections clearly form a single paragraph, combine them into one paragraph.
        * If a passage contains obvious OCR-generated gibberish that duplicates nearby content, remove only the duplicated artifact.
        
        Conservative behavior:
        
        * Do not invent missing content.
        * Do not fill in gaps unless the correction is highly certain from context.
        * Do not change names, numbers, dates, quotations, or technical terms unless the OCR error is obvious.
        * If uncertain, keep the original text.
        
        Output only the corrected text.
        """
    }
}
