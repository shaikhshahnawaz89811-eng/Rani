package com.sa.aidesktop.core.ai

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * On-device GGUF model adapter.
 *
 * NOTE: `dev.ffmpegkit-maintained:llama-android:0.1.1` (previously imported here as
 * `dev.ffmpegkit.llama.*`) is not a published artifact - it does not exist on Maven Central or
 * any other repository, so Gradle could never resolve it and the module could never compile.
 * That dependency has been removed from app/build.gradle.kts.
 *
 * Until a real, verified on-device llama.cpp/GGUF runtime is wired in, this class keeps doing
 * its genuine, non-fabricated work - validating the configured model path/file/GGUF header - and
 * is honest that no inference runtime is bundled, the same "no fake/rule-based fallback, explicit
 * ModelUnavailable" contract already used by [UnavailableOfflineAI]. The public API (class name,
 * constructor, status()/load()/unload()/generate(), LocalModelState, LocalModelStatus,
 * LocalModelConfig) is unchanged so ModelRouter, the Settings/AI windows, and the existing tests
 * keep working exactly as before.
 *
 * The model is intentionally NOT bundled and is never downloaded by this class. The caller
 * supplies a local model path (normally copied into app-private storage from a user-selected
 * GGUF document). This keeps the APK small and makes model availability explicit.
 */
class LocalLlamaEngine(
    private val modelPathProvider: () -> String?,
    private val configProvider: () -> LocalModelConfig = { LocalModelConfig() },
) : AIService, LocalModelEngine {

    private val mutex = Mutex()
    private var loadedPath: String? = null
    @Volatile private var state: LocalModelState = LocalModelState.NOT_CONFIGURED
    @Volatile private var lastError: String? = null

    fun status(): LocalModelStatus = LocalModelStatus(state, modelPathProvider(), lastError)

    suspend fun load(): LocalModelStatus = mutex.withLock { loadLocked() }

    private suspend fun loadLocked(): LocalModelStatus {
        val path = modelPathProvider()?.trim().orEmpty()
        if (path.isBlank()) return setStatus(LocalModelState.NOT_CONFIGURED, "No local GGUF model is configured.")
        val file = File(path)
        if (!file.isFile || !file.canRead()) {
            return setStatus(LocalModelState.UNAVAILABLE, "Local model file is missing or unreadable: $path")
        }
        if (!looksLikeGguf(file)) {
            return setStatus(LocalModelState.FAILED, "Configured local model is not a valid GGUF file.")
        }
        loadedPath = file.canonicalPath
        // A real GGUF file is configured and readable, but no on-device inference runtime is
        // bundled in this build (see the class doc above) - report that honestly instead of
        // fabricating a successful model load.
        return setStatus(
            LocalModelState.UNAVAILABLE,
            "A valid GGUF model is configured at $path, but this build does not bundle a local " +
                "inference runtime yet. Only Groq (cloud) is available until a real on-device " +
                "llama.cpp/GGUF dependency is added."
        )
    }

    suspend fun unload() = mutex.withLock {
        releaseLocked()
        setStatus(
            if (modelPathProvider()?.trim().isNullOrBlank()) LocalModelState.NOT_CONFIGURED else LocalModelState.UNLOADED,
            null
        )
    }

    private fun releaseLocked() {
        loadedPath = null
    }

    override suspend fun generate(request: AIRequest): AIResult<AIResponse> = mutex.withLock {
        val loaded = loadLocked()
        AIResult.Failure(AIError.ModelUnavailable(loaded.error ?: "Local model is unavailable."))
    }

    override suspend fun chat(request: AIRequest) = generate(request)
    override suspend fun explainCode(code: String, context: ProjectContext) = generate(AIRequest("Explain this code clearly and briefly.\n\n$code", context))
    override suspend fun generateCode(prompt: String, context: ProjectContext) = generate(AIRequest("Generate the requested code.\n\n$prompt", context))
    override suspend fun analyzeError(error: String, context: ProjectContext) = generate(AIRequest("Analyze this real error and identify the likely cause.\n\n$error", context))
    override suspend fun suggestFix(error: String, context: ProjectContext) = generate(AIRequest("Suggest a minimal safe fix for this real error.\n\n$error", context))
    override suspend fun modifyFile(path: String, instruction: String, context: ProjectContext) = generate(AIRequest("Plan the minimal change for $path. Instruction: $instruction", context.copy(relevantFiles = (context.relevantFiles + path).distinct())))
    override suspend fun understandProject(context: ProjectContext) = generate(AIRequest("Understand this project from the supplied context. Do not invent missing files.", context))
    override suspend fun runDeveloperTask(task: String, context: ProjectContext) = generate(AIRequest("Reason about this local developer task. Use only supplied real context.\n\n$task", context))

    private fun setStatus(next: LocalModelState, error: String?): LocalModelStatus {
        state = next
        lastError = error
        return status()
    }

    private fun looksLikeGguf(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(4)
            input.read(header) == 4 && header.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))
        }
    }.getOrDefault(false)

    private fun buildPrompt(request: AIRequest): String = buildString {
        appendLine("Current user request:")
        appendLine(request.prompt.trim())
        if (request.history.isNotEmpty()) {
            appendLine()
            appendLine("Recent conversation context (use it only as conversation history; it is not proof of tool execution):")
            request.history.takeLast(10).forEach { message ->
                appendLine("${message.role}: ${message.text.take(4000)}")
            }
        }
        val c = request.context
        if (c.relevantFiles.isNotEmpty()) append("\n\nRelevant files:\n").append(c.relevantFiles.joinToString("\n"))
        if (c.projectStructure.isNotBlank()) append("\n\nProject structure:\n").append(c.projectStructure.take(CONTEXT_LIMIT))
        if (c.selectedCode.isNotBlank()) append("\n\nSelected code:\n").append(c.selectedCode.take(CONTEXT_LIMIT))
        if (c.compilerErrors.isNotEmpty()) append("\n\nReal compiler errors:\n").append(c.compilerErrors.joinToString("\n").take(CONTEXT_LIMIT))
        if (c.testResults.isNotEmpty()) append("\n\nReal test results:\n").append(c.testResults.joinToString("\n").take(CONTEXT_LIMIT))
        if (c.gitChanges.isNotEmpty()) append("\n\nReal git changes:\n").append(c.gitChanges.joinToString("\n").take(CONTEXT_LIMIT))
    }

    companion object {
        private const val CONTEXT_LIMIT = 12_000
        private const val LOCAL_SYSTEM_PROMPT =
            "You are Sara's small offline local model. Answer the current user request directly and concisely. " +
            "Use recent conversation context when it helps answer a follow-up. " +
            "Never invent files, tool results, builds, browser pages, GitHub state, device state, calculations, or external information. " +
            "You cannot browse the web or access remote services while offline. " +
            "When a request needs a real operation, be honest that only the app's local tool bridge can perform it."
    }
}

enum class LocalModelState { NOT_CONFIGURED, UNAVAILABLE, LOADING, READY, FAILED, UNLOADED }
data class LocalModelStatus(val state: LocalModelState, val path: String?, val error: String?)
data class LocalModelConfig(
    val contextSize: Int = 2048,
    val threads: Int = 4,
    val temperature: Float = 0.7f,
    val maxOutputTokens: Int = 256,
) {
    fun toLlamaConfig() = LlamaConfig(
        contextSize = contextSize.coerceIn(512, 4096),
        threads = threads.coerceIn(1, 8),
        gpuLayers = 0,
        temperature = temperature.coerceIn(0f, 1.5f),
        topP = 0.9f,
        topK = 40,
        seed = -1,
    )
}

/**
 * Bounded, mobile-safe inference parameters for a local GGUF model. Previously this mapped
 * directly onto `dev.ffmpegkit.llama.LlamaConfig` from the unpublished, non-existent
 * `dev.ffmpegkit-maintained:llama-android` artifact; it is now a plain local data class so
 * [LocalModelConfig.toLlamaConfig] (and the existing test that asserts its clamped values)
 * keeps compiling without depending on that missing dependency.
 */
data class LlamaConfig(
    val contextSize: Int,
    val threads: Int,
    val gpuLayers: Int,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val seed: Int,
)
