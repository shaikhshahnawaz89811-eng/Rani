package com.sa.aidesktop.core.settings

import android.content.Context
import com.sa.aidesktop.core.ai.GroqClient
import com.sa.aidesktop.core.ai.GroqSettings
import com.sa.aidesktop.core.security.SecureStore

/**
 * Phase 1 settings for the Groq provider.
 *
 * The API key is the only secret here and is stored exclusively through the existing
 * [SecureStore] (AES/GCM, Android-Keystore-backed) — it is never written to SharedPreferences,
 * never logged, and never included in [GroqSettings] (which is what gets passed around and could
 * end up in a log line or a data-class toString somewhere).
 *
 * Everything else (model id, timeout, retry limit, output-token cap) is not secret and lives in
 * a plain SharedPreferences file, same as any other app preference.
 */
class AISettingsStore(
    context: Context,
    private val secureStore: SecureStore = SecureStore(context)
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Groq is opt-in. Default false: the app runs entirely on the offline on-device model until
     *  the user explicitly turns this on in Settings. */
    fun isOnlineModeEnabled(): Boolean = prefs.getBoolean(KEY_ONLINE_MODE, false)
    fun setOnlineModeEnabled(value: Boolean) { prefs.edit().putBoolean(KEY_ONLINE_MODE, value).apply() }

    fun getApiKey(): String? = secureStore.get(KEY_API_KEY)?.takeIf { it.isNotBlank() }
    fun hasApiKey(): Boolean = !getApiKey().isNullOrBlank()
    fun setApiKey(value: String) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) secureStore.remove(KEY_API_KEY) else secureStore.put(KEY_API_KEY, trimmed)
    }
    fun clearApiKey() = secureStore.remove(KEY_API_KEY)

    fun getModel(): String = prefs.getString(KEY_MODEL, GroqSettings.DEFAULT_MODEL) ?: GroqSettings.DEFAULT_MODEL
    fun setModel(value: String) { prefs.edit().putString(KEY_MODEL, value.trim().ifBlank { GroqSettings.DEFAULT_MODEL }).apply() }

    /** Chat-completions endpoint the online provider is called on. Defaults to Groq's own
     *  official endpoint (unchanged existing behavior) - a real, non-blank value is always
     *  returned so nothing else has to null-check this. Overriding it lets this same
     *  Groq-shaped client talk to any other OpenAI-compatible "/v1/chat/completions" server -
     *  e.g. a paired phone running Brain's Local API Server on the LAN - since the wire format
     *  (Bearer auth, messages[], tool_calls, streaming) is identical either way. */
    fun getEndpoint(): String = prefs.getString(KEY_ENDPOINT, GroqClient.ENDPOINT)?.takeIf { it.isNotBlank() } ?: GroqClient.ENDPOINT
    fun setEndpoint(value: String) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) prefs.edit().remove(KEY_ENDPOINT).apply()
        else prefs.edit().putString(KEY_ENDPOINT, trimmed).apply()
    }
    fun isUsingCustomEndpoint(): Boolean = getEndpoint() != GroqClient.ENDPOINT

    fun getTimeoutMs(): Int = prefs.getInt(KEY_TIMEOUT, GroqSettings.DEFAULT_TIMEOUT_MS)
        .coerceIn(GroqSettings.MIN_TIMEOUT_MS, GroqSettings.MAX_TIMEOUT_MS)
    fun setTimeoutMs(value: Int) { prefs.edit().putInt(KEY_TIMEOUT, value.coerceIn(GroqSettings.MIN_TIMEOUT_MS, GroqSettings.MAX_TIMEOUT_MS)).apply() }

    fun getRetryLimit(): Int = prefs.getInt(KEY_RETRY, GroqSettings.DEFAULT_RETRY_LIMIT).coerceIn(0, GroqSettings.MAX_RETRY_LIMIT)
    fun setRetryLimit(value: Int) { prefs.edit().putInt(KEY_RETRY, value.coerceIn(0, GroqSettings.MAX_RETRY_LIMIT)).apply() }

    /** 0 (or absent) means "no limit configured" -> null, matching Groq's own default behavior. */
    fun getMaxOutputTokens(): Int? = prefs.getInt(KEY_MAX_TOKENS, 0).let { if (it <= 0) null else it.coerceAtMost(GroqSettings.MAX_OUTPUT_TOKENS_CEILING) }
    fun setMaxOutputTokens(value: Int?) { prefs.edit().putInt(KEY_MAX_TOKENS, (value ?: 0).coerceIn(0, GroqSettings.MAX_OUTPUT_TOKENS_CEILING)).apply() }


    fun getLocalModelPath(): String? = prefs.getString(KEY_LOCAL_MODEL_PATH, null)?.takeIf { it.isNotBlank() }
    fun setLocalModelPath(path: String?) {
        val value = path?.trim().orEmpty()
        if (value.isBlank()) prefs.edit().remove(KEY_LOCAL_MODEL_PATH).apply()
        else prefs.edit().putString(KEY_LOCAL_MODEL_PATH, value).apply()
    }

    fun getLocalContextSize(): Int = prefs.getInt(KEY_LOCAL_CONTEXT, 2048).coerceIn(512, 4096)
    fun setLocalContextSize(value: Int) { prefs.edit().putInt(KEY_LOCAL_CONTEXT, value.coerceIn(512, 4096)).apply() }

    fun getLocalThreads(): Int = prefs.getInt(KEY_LOCAL_THREADS, 4).coerceIn(1, 8)
    fun setLocalThreads(value: Int) { prefs.edit().putInt(KEY_LOCAL_THREADS, value.coerceIn(1, 8)).apply() }

    fun getLocalMaxOutputTokens(): Int = prefs.getInt(KEY_LOCAL_MAX_TOKENS, 256).coerceIn(32, 1024)
    fun setLocalMaxOutputTokens(value: Int) { prefs.edit().putInt(KEY_LOCAL_MAX_TOKENS, value.coerceIn(32, 1024)).apply() }

    fun toGroqSettings(): GroqSettings = GroqSettings(
        model = getModel(),
        timeoutMs = getTimeoutMs(),
        retryLimit = getRetryLimit(),
        maxOutputTokens = getMaxOutputTokens()
    )

    private companion object {
        const val PREFS_NAME = "sa_ai_settings"
        const val KEY_ONLINE_MODE = "online_mode_enabled"
        const val KEY_API_KEY = "groq_api_key"
        const val KEY_MODEL = "groq_model"
        const val KEY_ENDPOINT = "groq_endpoint"
        const val KEY_TIMEOUT = "groq_timeout_ms"
        const val KEY_RETRY = "groq_retry_limit"
        const val KEY_MAX_TOKENS = "groq_max_output_tokens"
        const val KEY_LOCAL_MODEL_PATH = "local_model_path"
        const val KEY_LOCAL_CONTEXT = "local_model_context"
        const val KEY_LOCAL_THREADS = "local_model_threads"
        const val KEY_LOCAL_MAX_TOKENS = "local_model_max_output_tokens"
    }
}
