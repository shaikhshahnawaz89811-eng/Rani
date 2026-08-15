package com.sa.aidesktop.core.ai

/**
 * Phase 1 — real Groq (OpenAI-compatible) provider models.
 *
 * These types describe the actual shape of requests/responses exchanged with
 * https://api.groq.com/openai/v1/chat/completions. Nothing here invents fields the API
 * doesn't provide: usage is nullable because Groq only returns it when the response includes
 * a "usage" object, and errors are modeled after Groq's real HTTP status codes.
 */

/** User-configurable, non-secret Groq request settings. The API key is never part of this
 *  class so that logging/printing a GroqSettings instance can never leak it. */
data class GroqSettings(
    val model: String = DEFAULT_MODEL,
    val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    val retryLimit: Int = DEFAULT_RETRY_LIMIT,
    val maxOutputTokens: Int? = null
) {
    /** Clamp user-entered values into safe, sane ranges. We do not know Groq's exact per-model
     *  context limit (it is not exposed by a settings-time API call), so this only bounds our
     *  own request/timeout/retry behavior — never a fabricated provider limit. */
    fun clamped(): GroqSettings = copy(
        model = model.ifBlank { DEFAULT_MODEL },
        timeoutMs = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS),
        retryLimit = retryLimit.coerceIn(0, MAX_RETRY_LIMIT),
        maxOutputTokens = maxOutputTokens?.takeIf { it > 0 }?.coerceAtMost(MAX_OUTPUT_TOKENS_CEILING)
    )

    companion object {
        const val DEFAULT_MODEL = "llama-3.3-70b-versatile"
        const val DEFAULT_TIMEOUT_MS = 30_000
        const val DEFAULT_RETRY_LIMIT = 1
        const val MIN_TIMEOUT_MS = 1_000
        const val MAX_TIMEOUT_MS = 120_000
        const val MAX_RETRY_LIMIT = 5
        const val MAX_OUTPUT_TOKENS_CEILING = 32_000
    }
}

/** Token usage as actually reported by Groq's response "usage" object. Any field Groq omits
 *  stays null — we never estimate or invent a token count. */
data class GroqUsage(val promptTokens: Int?, val completionTokens: Int?, val totalTokens: Int?)

/** A tool call the model asked to make, decoded from choices[0].message.tool_calls. */
data class GroqToolCall(val id: String, val name: String, val arguments: Map<String, String>)

data class GroqMessage(
    val role:String,
    val content:String,
    val toolCalls:List<GroqToolCall> = emptyList(),
    val toolCallId:String? = null,
    val name:String? = null
)

data class GroqChatResult(
    val text: String,
    val toolCalls: List<GroqToolCall> = emptyList(),
    val usage: GroqUsage? = null,
    val finishReason: String? = null
)

/** Real error taxonomy mapped from actual Groq/HTTP failure modes. No field here is guessed:
 *  RateLimited/Http carry whatever Groq's error body actually said, and Network/Timeout are
 *  produced only from real IOException/SocketTimeoutException outcomes. */
sealed interface GroqError {
    data class MissingApiKey(val message: String) : GroqError
    data class InvalidApiKey(val message: String) : GroqError
    data class RateLimited(val message: String, val retryAfterSeconds: Int?) : GroqError
    data class ServiceUnavailable(val message: String, val code: Int) : GroqError
    data class Http(val code: Int, val message: String) : GroqError
    data class Network(val message: String) : GroqError
    data class Timeout(val message: String) : GroqError
    data class MalformedResponse(val message: String) : GroqError
}

sealed interface GroqResult {
    data class Success(val value: GroqChatResult) : GroqResult
    data class Failure(val error: GroqError) : GroqResult
}

/** Describes a Phase-1 tool for Groq's OpenAI-compatible function-calling schema. Parameter
 *  names/descriptions come from the real [AITool.parameterHints] of the tool being exposed —
 *  this file never invents a tool or a parameter that isn't actually implemented. */
data class ToolDescriptor(val name: String, val description: String, val parameters: Map<String, String>, val requiredParameters: Set<String> = parameters.keys)

/** Minimal HTTP abstraction so GroqClient's request/response handling can be exercised against
 *  a real loopback HTTP server in unit tests, without touching the network in production code
 *  paths differently than at runtime. */
interface HttpTransport {
    /** May throw java.net.SocketTimeoutException or java.io.IOException on real network failure —
     *  callers are expected to catch those, not this interface. */
    fun post(url: String, headers: Map<String, String>, body: String, connectTimeoutMs: Int, readTimeoutMs: Int): TransportResponse
}

data class TransportResponse(val statusCode: Int, val body: String)

/** The engine abstraction ModelRouter depends on, so it can be unit tested with a fake instead
 *  of a real network call. GroqClient is the only real implementation. */
interface GroqChatEngine {
    suspend fun chat(
        prompt: String,
        settings: GroqSettings,
        systemPrompt: String?,
        tools: List<ToolDescriptor>
    ): GroqResult

    /**
     * Conversation-aware variant used by the real router. The default implementation preserves
     * compatibility with simple test doubles by delegating to the single-prompt method.
     */
    suspend fun chatConversation(
        messages: List<GroqMessage>,
        settings: GroqSettings,
        systemPrompt: String?,
        tools: List<ToolDescriptor>
    ): GroqResult = chat(
        messages.lastOrNull { it.role == "user" }?.content.orEmpty(),
        settings,
        systemPrompt,
        tools
    )
}
