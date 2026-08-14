package com.sa.aidesktop.core.ai

import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Real on-device GGUF inference adapter.
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
    private var model: LlamaModel? = null
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
        if (model != null && loadedPath == file.canonicalPath) return setStatus(LocalModelState.READY, null)
        if (model != null && loadedPath != file.canonicalPath) {
            releaseLocked()
        }

        setStatus(LocalModelState.LOADING, null)
        return try {
            model = Llama.loadModel(modelPath = file.canonicalPath, config = configProvider().toLlamaConfig())
            loadedPath = file.canonicalPath
            setStatus(LocalModelState.READY, null)
        } catch (e: CancellationException) {
            setStatus(LocalModelState.UNAVAILABLE, "Local model loading was cancelled.")
            throw e
        } catch (t: Throwable) {
            model = null
            loadedPath = null
            setStatus(LocalModelState.FAILED, t.message ?: t.javaClass.simpleName)
        }
    }

    suspend fun unload() = mutex.withLock {
        releaseLocked()
        setStatus(
            if (modelPathProvider()?.trim().isNullOrBlank()) LocalModelState.NOT_CONFIGURED else LocalModelState.UNLOADED,
            null
        )
    }

    private fun releaseLocked() {
        model?.let { runCatching { Llama.releaseModel(it) } }
        model = null
        loadedPath = null
    }

    override suspend fun generate(request: AIRequest): AIResult<AIResponse> = mutex.withLock {
        val loaded = loadLocked()
        if (loaded.state != LocalModelState.READY) {
            return@withLock AIResult.Failure(AIError.ModelUnavailable(loaded.error ?: "Local model is unavailable."))
        }
        val active = model ?: return@withLock AIResult.Failure(AIError.ModelUnavailable("Local model is not loaded."))
        try {
            val config = configProvider()
            val result = Llama.complete(
                model = active,
                prompt = buildPrompt(request),
                systemPrompt = LOCAL_SYSTEM_PROMPT,
                maxTokens = config.maxOutputTokens
            )
            val text = result.text.trim()
            if (text.isBlank()) AIResult.Failure(AIError.Execution("Local model returned an empty response."))
            else AIResult.Success(AIResponse(text))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            setStatus(LocalModelState.FAILED, t.message ?: t.javaClass.simpleName)
            AIResult.Failure(AIError.Execution("Local inference failed: ${t.message ?: t.javaClass.simpleName}"))
        }
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
        append(request.prompt.trim())
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
            "You are Sara's small offline local model. Work only from the provided user request and real local context. " +
            "Never invent files, tool results, builds, browser pages, GitHub state, or external information. " +
            "You cannot browse the web or access remote services while offline. Be concise and honest about uncertainty."
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
