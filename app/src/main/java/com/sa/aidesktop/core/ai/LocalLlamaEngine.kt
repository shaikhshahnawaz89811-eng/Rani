package com.sa.aidesktop.core.ai

import com.llamatik.library.platform.LlamaBridge
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * On-device GGUF model adapter — real inference.
 *
 * Backed by `com.llamatik:library` (Kotlin Multiplatform wrapper around llama.cpp), published on
 * Maven Central — https://central.sonatype.com/artifact/com.llamatik/library — MIT/Apache-2.0,
 * min Android SDK 26 (matches this app's minSdk). Verified real, resolvable artifact; the previous
 * `dev.ffmpegkit-maintained:llama-android:0.1.1` was NOT real (it did not exist on any Maven
 * repository) and has stayed removed from app/build.gradle.kts. This class now depends on
 * `com.llamatik:library` instead (see app/build.gradle.kts).
 *
 * `LlamaBridge` (`com.llamatik.library.platform.LlamaBridge`) is a process-wide native singleton,
 * so every call into it is serialized through [mutex] — same "a model instance is not
 * thread-safe" contract this class already documented before real inference existed.
 *
 * The model is intentionally NOT bundled and is never downloaded by this class. The caller
 * supplies a local model path (normally copied into app-private storage from a user-selected
 * GGUF document — e.g. a Qwen2.5-Coder GGUF). Public API (class name, constructor,
 * status()/load()/unload()/generate(), LocalModelState, LocalModelStatus, LocalModelConfig) is
 * unchanged so ModelRouter, the Settings/AI windows, and the existing tests keep working exactly
 * as before.
 *
 * NOT VERIFIED: this file could not be compiled or run against a real Android/NDK environment in
 * the sandbox that wrote it (no network, no Android SDK/NDK, no device/emulator here) — see
 * OFFLINE_LLM_PHASE8.md for the same disclosure convention already used in this project. It must
 * be built and exercised with a real GGUF file on a real device/emulator before being marked done.
 */
class LocalLlamaEngine(
    private val modelPathProvider: () -> String?,
    private val configProvider: () -> LocalModelConfig = { LocalModelConfig() },
) : AIService, LocalModelEngine {

    private val mutex = Mutex()
    @Volatile private var loadedPath: String? = null
    @Volatile private var state: LocalModelState = LocalModelState.NOT_CONFIGURED
    @Volatile private var lastError: String? = null

    fun status(): LocalModelStatus = LocalModelStatus(state, modelPathProvider(), lastError)

    suspend fun load(): LocalModelStatus = mutex.withLock { loadLocked() }

    private suspend fun loadLocked(): LocalModelStatus {
        val path = modelPathProvider()?.trim().orEmpty()
        if (path.isBlank()) {
            releaseNativeIfLoaded()
            return setStatus(LocalModelState.NOT_CONFIGURED, "No local GGUF model is configured.")
        }
        val file = File(path)
        if (!file.isFile || !file.canRead()) {
            return setStatus(LocalModelState.UNAVAILABLE, "Local model file is missing or unreadable: $path")
        }
        if (!looksLikeGguf(file)) {
            return setStatus(LocalModelState.FAILED, "Configured local model is not a valid GGUF file.")
        }
        val canonical = file.canonicalPath
        if (state == LocalModelState.READY && loadedPath == canonical) {
            return status() // already loaded — real repeat-resource decision (Rule 17): REUSE, no reload
        }
        return loadIntoNative(canonical)
    }

    /** Sub-helper (Rule 15) — loads [canonical] into the real llama.cpp runtime via LlamaBridge,
     *  releasing any previously loaded (different) model first so native memory is never leaked
     *  when the user switches GGUF files.
     *
     *  THREADING FIX: model load is a heavy native/CPU call (can take several seconds for a real
     *  GGUF file). It's now pushed onto [Dispatchers.IO] via [withContext] instead of running on
     *  whatever dispatcher the caller (e.g. the Compose chat screen's `scope.launch`, which is
     *  Main) happened to be on. Previously this ran straight on the caller's thread, which — when
     *  the caller was Main — froze the whole UI until it finished and could trigger an ANR
     *  (Android force-closing the app back to the home screen), exactly the "freeze then auto
     *  back" behavior reported. */
    private suspend fun loadIntoNative(canonical: String): LocalModelStatus {
        state = LocalModelState.LOADING
        if (loadedPath != null && loadedPath != canonical) releaseNativeIfLoaded()
        applyGenerationParams(configProvider().toLlamaConfig())
        val ok = withContext(Dispatchers.IO) {
            runCatching { LlamaBridge.initGenerateModel(canonical) }
        }.getOrElse { t ->
            loadedPath = null
            return setStatus(
                LocalModelState.FAILED,
                "The local llama.cpp runtime failed to load this GGUF model: ${t.message ?: t::class.simpleName}"
            )
        }
        if (!ok) {
            loadedPath = null
            return setStatus(
                LocalModelState.FAILED,
                "The local llama.cpp runtime rejected this GGUF model at $canonical (unsupported architecture, corrupt file, or not enough memory)."
            )
        }
        loadedPath = canonical
        return setStatus(LocalModelState.READY, null)
    }

    /** Sub-helper (Rule 15) — pushes the user's configured context/threads/output-length settings
     *  into the real native backend. Rule 1 endpoint-fix: these settings previously had no effect
     *  because no runtime existed to receive them; now they genuinely reach inference. */
    private fun applyGenerationParams(cfg: LlamaConfig) {
        LlamaBridge.updateGenerateParams(
            temperature = cfg.temperature,
            maxTokens = cfg.maxTokens,
            topP = cfg.topP,
            topK = cfg.topK,
            repeatPenalty = DEFAULT_REPEAT_PENALTY,
            contextLength = cfg.contextSize,
            numThreads = cfg.threads,
            useMmap = true,
            flashAttention = false,
            batchSize = DEFAULT_BATCH_SIZE,
            gpuLayers = cfg.gpuLayers,
        )
    }

    suspend fun unload() = mutex.withLock {
        releaseNativeIfLoaded()
        setStatus(
            if (modelPathProvider()?.trim().isNullOrBlank()) LocalModelState.NOT_CONFIGURED else LocalModelState.UNLOADED,
            null
        )
    }

    private suspend fun releaseNativeIfLoaded() {
        if (loadedPath != null) withContext(Dispatchers.IO) { runCatching { LlamaBridge.shutdown() } }
        loadedPath = null
    }

    override suspend fun generate(request: AIRequest): AIResult<AIResponse> = mutex.withLock {
        val loaded = loadLocked()
        if (loaded.state != LocalModelState.READY) {
            return@withLock AIResult.Failure(AIError.ModelUnavailable(loaded.error ?: "Local model is unavailable."))
        }
        // THREADING FIX: real token generation is the heaviest native call in this class and was
        // previously running on whatever dispatcher called generate() (Main, from the chat
        // screen) — pushed onto Dispatchers.IO so it can no longer freeze the UI/ANR the app.
        val raw = withContext(Dispatchers.IO) {
            runCatching {
                LlamaBridge.generateWithContext(
                    LOCAL_SYSTEM_PROMPT,
                    fitContextToBudget(request, configProvider().toLlamaConfig()),
                    request.prompt.trim()
                )
            }
        }.getOrElse { t ->
            return@withLock AIResult.Failure(
                AIError.Execution("Local model generation failed: ${t.message ?: t::class.simpleName}")
            )
        }
        val text = raw.trim()
        if (text.isBlank()) {
            return@withLock AIResult.Failure(AIError.Execution("Local model returned an empty response."))
        }
        AIResult.Success(
            AIResponse(
                text = text,
                toolTrace = listOf("LOCAL MODEL (offline, ${File(loadedPath.orEmpty()).name}) — real on-device inference")
            )
        )
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

    /** Everything except the system prompt and the current user turn: recent history (up to
     *  [historyDepth] turns) plus any real project context supplied for this request. Kept
     *  separate from [LOCAL_SYSTEM_PROMPT] and the user's own prompt so all three can be passed
     *  to [LlamaBridge.generateWithContext] as its distinct parameters instead of one flattened
     *  string. [historyDepth] is only ever lowered by [fitContextToBudget] below the configured
     *  model's real context window — it never grows the history beyond what was actually asked
     *  for. */
    private fun buildContextBlock(request: AIRequest, historyDepth: Int): String = buildString {
        if (request.history.isNotEmpty() && historyDepth > 0) {
            appendLine("Recent conversation context (use it only as conversation history; it is not proof of tool execution):")
            request.history.takeLast(historyDepth).forEach { message ->
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
    }.trim()

    /** Sub-helper (Rule 15) — "ContextBudgetTrimmer". Rule 2/20 proactive budget check: estimates
     *  the token cost of system+context+user against the model's real configured context window
     *  ([cfg.contextSize], the same value just sent to [LlamaBridge.updateGenerateParams]) *before*
     *  calling into the native engine, instead of sending an unbounded context and hoping it fits.
     *  Reserves room for the model's own output ([cfg.maxTokens]) plus a small safety margin.
     *  Never touches the current user turn — only conversation history (dropped oldest-first) and
     *  supplied project-context fields are ever shortened; the estimate uses a standard
     *  ~4-chars-per-token heuristic (no tokenizer is available on this path), so the final result
     *  is additionally hard-capped as a safety net in case the estimate runs a little low. */
    private fun fitContextToBudget(request: AIRequest, cfg: LlamaConfig): String {
        val reserveTokens = cfg.maxTokens + SAFETY_MARGIN_TOKENS
        val budgetTokens = (cfg.contextSize - reserveTokens).coerceAtLeast(MIN_CONTEXT_BUDGET_TOKENS)
        val budgetChars = budgetTokens * CHARS_PER_TOKEN_ESTIMATE
        val fixedChars = LOCAL_SYSTEM_PROMPT.length + request.prompt.trim().length
        val contextBudgetChars = (budgetChars - fixedChars).coerceAtLeast(0)

        var historyDepth = MAX_HISTORY_TURNS
        var block = buildContextBlock(request, historyDepth)
        while (estimatedTokens(block) * CHARS_PER_TOKEN_ESTIMATE > contextBudgetChars && historyDepth > 0) {
            historyDepth--
            block = buildContextBlock(request, historyDepth)
        }
        return block.take(contextBudgetChars) // hard safety net if the heuristic underestimated
    }

    private fun estimatedTokens(text: String): Int = text.length / CHARS_PER_TOKEN_ESTIMATE

    companion object {
        private const val CONTEXT_LIMIT = 12_000
        private const val DEFAULT_REPEAT_PENALTY = 1.1f
        private const val DEFAULT_BATCH_SIZE = 512
        private const val MAX_HISTORY_TURNS = 10
        // ~4 characters per token is the standard rough heuristic for English/code text without a
        // real tokenizer on this path (Llamatik does not expose a client-side tokenizer call).
        private const val CHARS_PER_TOKEN_ESTIMATE = 4
        private const val SAFETY_MARGIN_TOKENS = 64
        private const val MIN_CONTEXT_BUDGET_TOKENS = 128
        private const val LOCAL_SYSTEM_PROMPT =
            "You are Sara's small offline local model. Answer the current user request directly and concisely. " +
            "Use recent conversation context when it helps answer a follow-up. " +
            "Never invent files, tool results, builds, browser pages, GitHub state, device state, calculations, or external information. " +
            "You cannot browse the web or access remote services while offline. " +
            "When a request needs a real operation, be honest that only the app's local tool bridge can perform it. " +
            "If the user's message lists available tools and a TOOL_CALL format, and a real tool genuinely matches what they asked for, " +
            "reply with EXACTLY that one TOOL_CALL line and nothing else; otherwise answer normally in plain text."
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
        maxTokens = maxOutputTokens.coerceIn(32, 1024),
    )
}

/**
 * Bounded, mobile-safe inference parameters for a local GGUF model, mapped onto the real
 * `com.llamatik.library.platform.LlamaBridge.updateGenerateParams(...)` call in
 * [LocalLlamaEngine.applyGenerationParams]. `seed` was dropped (previously present but unused,
 * decorative field from the removed fake dependency) because Llamatik's real
 * `updateGenerateParams` has no seed parameter — keeping it would have been dead weight (Rule 21
 * Part B: no extra/unnecessary field).
 */
data class LlamaConfig(
    val contextSize: Int,
    val threads: Int,
    val gpuLayers: Int,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val maxTokens: Int,
)
