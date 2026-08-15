package com.sa.aidesktop.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets

/** Real implementation of [HttpTransport] using the JDK/Android built-in HttpURLConnection —
 *  no new HTTP library dependency was added for this. */
class JavaHttpTransport : HttpTransport {
    override fun post(url: String, headers: Map<String, String>, body: String, connectTimeoutMs: Int, readTimeoutMs: Int): TransportResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            return TransportResponse(code, text)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Real Groq client against the official OpenAI-compatible endpoint:
 * https://api.groq.com/openai/v1/chat/completions
 *
 * - The API key is supplied via [apiKeyProvider], read fresh on every call — never stored as a
 *   loggable field on this class, never placed in the prompt, never written to any log.
 * - Usage is only reported when Groq's response actually contains a "usage" object.
 * - Retries only happen for genuinely retryable outcomes (429 / 5xx / timeout), bounded by
 *   [GroqSettings.retryLimit], with a short backoff — never silently swallowed as "success".
 */
class GroqClient(
    private val apiKeyProvider: () -> String?,
    private val transport: HttpTransport = JavaHttpTransport(),
    private val endpoint: String = ENDPOINT
) : GroqChatEngine {

    override suspend fun chat(
        prompt: String,
        settings: GroqSettings,
        systemPrompt: String?,
        tools: List<ToolDescriptor>
    ): GroqResult = chatConversation(
        messages = listOf(GroqMessage("user", prompt)),
        settings = settings,
        systemPrompt = systemPrompt,
        tools = tools
    )

    override suspend fun chatConversation(
        messages: List<GroqMessage>,
        settings: GroqSettings,
        systemPrompt: String?,
        tools: List<ToolDescriptor>
    ): GroqResult = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@withContext GroqResult.Failure(
                GroqError.MissingApiKey("Groq API key is not configured. Add it in Settings.")
            )

        val clamped = settings.clamped()
        var attempt = 0
        var lastFailure: GroqResult.Failure =
            GroqResult.Failure(GroqError.Network("No attempt was made."))
        while (attempt <= clamped.retryLimit) {
            when (val outcome = attemptOnce(apiKey, messages, systemPrompt, tools, clamped)) {
                is GroqResult.Success -> return@withContext outcome
                is GroqResult.Failure -> {
                    lastFailure = outcome
                    if (!isRetryable(outcome.error) || attempt == clamped.retryLimit) {
                        return@withContext outcome
                    }
                    delay(RETRY_BACKOFF_MS * (attempt + 1))
                }
            }
            attempt++
        }
        lastFailure
    }

    private fun isRetryable(error: GroqError): Boolean = when (error) {
        is GroqError.RateLimited, is GroqError.ServiceUnavailable, is GroqError.Timeout -> true
        else -> false
    }

    private fun attemptOnce(
        apiKey: String,
        messages: List<GroqMessage>,
        systemPrompt: String?,
        tools: List<ToolDescriptor>,
        settings: GroqSettings
    ): GroqResult {
        val payload = buildRequestBody(messages, systemPrompt, tools, settings)
        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json"
        )
        val response = try {
            transport.post(endpoint, headers, payload, settings.timeoutMs, settings.timeoutMs)
        } catch (e: SocketTimeoutException) {
            return GroqResult.Failure(
                GroqError.Timeout("Groq request timed out after ${settings.timeoutMs}ms")
            )
        } catch (e: IOException) {
            return GroqResult.Failure(
                GroqError.Network(e.message ?: "Network error contacting Groq")
            )
        }
        return parseResponse(response)
    }

    private fun buildRequestBody(
        messages: List<GroqMessage>,
        systemPrompt: String?,
        tools: List<ToolDescriptor>,
        settings: GroqSettings
    ): String {
        val messageArray = JSONArray()
        if (!systemPrompt.isNullOrBlank()) {
            messageArray.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        messages.forEach { message ->
            val json = JSONObject()
                .put("role", message.role)
                .put("content", message.content)
            if (!message.name.isNullOrBlank()) json.put("name", message.name)
            if (!message.toolCallId.isNullOrBlank()) json.put("tool_call_id", message.toolCallId)
            if (message.toolCalls.isNotEmpty()) {
                val calls = JSONArray()
                message.toolCalls.forEach { call ->
                    val arguments = JSONObject()
                    call.arguments.forEach { (key, value) -> arguments.put(key, value) }
                    calls.put(
                        JSONObject()
                            .put("id", call.id)
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", call.name)
                                    .put("arguments", arguments.toString())
                            )
                    )
                }
                json.put("tool_calls", calls)
            }
            messageArray.put(json)
        }

        val body = JSONObject()
            .put("model", settings.model)
            .put("messages", messageArray)
        settings.maxOutputTokens?.let { body.put("max_tokens", it) }

        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            tools.forEach { descriptor ->
                val properties = JSONObject()
                descriptor.parameters.forEach { (name, description) ->
                    properties.put(
                        name,
                        JSONObject()
                            .put("type", "string")
                            .put("description", description)
                    )
                }
                val parametersSchema = JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", JSONArray(descriptor.requiredParameters.toList()))
                    .put("additionalProperties", false)
                val function = JSONObject()
                    .put("name", descriptor.name)
                    .put("description", descriptor.description)
                    .put("parameters", parametersSchema)
                toolsArray.put(JSONObject().put("type", "function").put("function", function))
            }
            body.put("tools", toolsArray)
        }
        return body.toString()
    }

    private fun parseResponse(response: TransportResponse): GroqResult {
        when (response.statusCode) {
            401, 403 -> return GroqResult.Failure(
                GroqError.InvalidApiKey(
                    extractErrorMessage(response.body)
                        ?: "Groq rejected the API key (HTTP ${response.statusCode})."
                )
            )
            429 -> return GroqResult.Failure(
                GroqError.RateLimited(
                    extractErrorMessage(response.body)
                        ?: "Groq rate limit reached (HTTP 429).",
                    null
                )
            )
        }
        if (response.statusCode in 500..599) {
            return GroqResult.Failure(
                GroqError.ServiceUnavailable(
                    extractErrorMessage(response.body) ?: "Groq service unavailable.",
                    response.statusCode
                )
            )
        }
        if (response.statusCode !in 200..299) {
            return GroqResult.Failure(
                GroqError.Http(
                    response.statusCode,
                    extractErrorMessage(response.body)
                        ?: "Groq request failed (HTTP ${response.statusCode})."
                )
            )
        }
        return try {
            val json = JSONObject(response.body)
            val choice = json.optJSONArray("choices")?.optJSONObject(0)
                ?: return GroqResult.Failure(
                    GroqError.MalformedResponse("Groq response had no choices[0].")
                )
            val message = choice.optJSONObject("message")
                ?: return GroqResult.Failure(
                    GroqError.MalformedResponse("Groq response had no message.")
                )
            val text = if (message.isNull("content")) "" else message.optString("content", "")
            val toolCalls = parseToolCalls(message.optJSONArray("tool_calls"))
            if (text.isBlank() && toolCalls.isEmpty()) {
                return GroqResult.Failure(
                    GroqError.MalformedResponse(
                        "Groq response contained neither text nor a tool call."
                    )
                )
            }
            val finishReason = choice.optString("finish_reason").ifBlank { null }
            val usage = json.optJSONObject("usage")?.let {
                GroqUsage(
                    promptTokens = it.optIntOrNull("prompt_tokens"),
                    completionTokens = it.optIntOrNull("completion_tokens"),
                    totalTokens = it.optIntOrNull("total_tokens")
                )
            }
            GroqResult.Success(GroqChatResult(text, toolCalls, usage, finishReason))
        } catch (e: JSONException) {
            GroqResult.Failure(
                GroqError.MalformedResponse(e.message ?: "Could not parse Groq response JSON.")
            )
        }
    }

    private fun parseToolCalls(array: JSONArray?): List<GroqToolCall> {
        if (array == null) return emptyList()
        val result = mutableListOf<GroqToolCall>()
        for (i in 0 until array.length()) {
            val call = array.optJSONObject(i)
            val function = call?.optJSONObject("function")
            val name = function?.optString("name", "").orEmpty()
            if (call == null || function == null || name.isBlank()) continue
            val id = call.optString("id", "call_$i")
            val argumentsRaw = function.optString("arguments", "{}")
            val arguments = try {
                val obj = JSONObject(argumentsRaw)
                obj.keys().asSequence().associateWith { key ->
                    obj.optString(key, "")
                }
            } catch (e: JSONException) {
                emptyMap()
            }
            result.add(GroqToolCall(id, name, arguments))
        }
        return result
    }

    private fun extractErrorMessage(body: String): String? = try {
        JSONObject(body).optJSONObject("error")?.optString("message")
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    companion object {
        const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        private const val RETRY_BACKOFF_MS = 400L
    }
}
