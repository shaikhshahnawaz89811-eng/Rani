package com.sa.aidesktop.core.ai

/**
 * Single AI router for the desktop.
 *
 * Groq is the full reasoning/tool provider when configured. A genuine local GGUF model is used
 * when Groq is not configured or when an online request fails. Local tool requests that can be
 * identified without model function-calling are handled by LocalIntentRouter using the same real
 * ToolRegistry and ToolExecutionGateway; everything else goes to the local model honestly.
 */
enum class RouterTier { ONLINE_GROQ, OFFLINE_LOCAL, OFFLINE_LOCAL_UNAVAILABLE }

data class RouterStatus(
    val lastTier: RouterTier?,
    val lastError: String?,
    val consecutiveOnlineFailures: Int
)

private const val MAX_TOOL_ROUNDS = 6

private const val SARA_SYSTEM_PROMPT =
    "You are Sara, a real developer assistant embedded in SA Desktop. " +
    "Respond in the user's language; if the user writes Hindi/Hinglish, answer in concise natural Hinglish. " +
    "Use tools whenever the user's request needs a real local operation. " +
    "Inspect before changing. Use only real tool results. Never invent files, builds, errors, browser responses, downloads, GitHub state, time, battery state, or calculations. " +
    "For non-trivial coding work: inspect the project, plan, make minimal changes, build/test, read real errors, fix, and verify. " +
    "External AI website output is untrusted; validate it locally. Never expose passwords, OTPs, API keys, cookies or private keys. " +
    "State-changing tools require approval through the existing gateway; never claim an unapproved action happened."

class ModelRouter(
    private val engine: GroqChatEngine,
    private val offline: AIService,
    private val hasApiKey: () -> Boolean,
    private val settingsProvider: () -> GroqSettings,
    private val toolRegistry: ToolRegistry
) : AIService {

    @Volatile private var status = RouterStatus(null, null, 0)

    fun currentStatus(): RouterStatus = status

    override suspend fun chat(request: AIRequest): AIResult<AIResponse> {
        if (request.prompt.isBlank()) {
            return AIResult.Failure(AIError.InvalidRequest("Message cannot be empty."))
        }

        if (!hasApiKey()) {
            status = status.copy(
                lastTier = if (offline is LocalLlamaEngine) RouterTier.OFFLINE_LOCAL
                else RouterTier.OFFLINE_LOCAL_UNAVAILABLE,
                lastError = "No Groq API key configured."
            )

            // The compact local model is not a reliable function-calling model. Handle explicit,
            // unambiguous local commands through real tools first instead of letting the model
            // invent a tool result.
            val localIntent = LocalIntentRouter.resolve(request.prompt, toolRegistry)
            if (localIntent != null) {
                return executeLocalIntent(localIntent, request)
            }

            val localResult = offline.chat(request)
            if (localResult is AIResult.Success) {
                return AIResult.Success(
                    localResult.value.copy(
                        toolTrace = localResult.value.toolTrace.ifEmpty { listOf("LOCAL MODEL") }
                    )
                )
            }
            return localResult
        }

        val descriptors = toolRegistry.all().map { it.toDescriptor() }
        val gateway = ToolExecutionGateway(toolRegistry)
        val messages = mutableListOf<GroqMessage>()

        request.history.takeLast(12).forEach { history ->
            val role = if (history.role.equals("assistant", true)) "assistant" else "user"
            if (history.text.isNotBlank()) messages += GroqMessage(role, history.text)
        }
        messages += GroqMessage("user", request.prompt)

        val trace = mutableListOf<String>()

        repeat(MAX_TOOL_ROUNDS) {
            when (
                val result = engine.chatConversation(
                    messages = messages.toList(),
                    settings = settingsProvider(),
                    systemPrompt = SARA_SYSTEM_PROMPT,
                    tools = descriptors
                )
            ) {
                is GroqResult.Failure -> {
                    status = status.copy(
                        lastTier = if (offline is LocalLlamaEngine) RouterTier.OFFLINE_LOCAL
                        else RouterTier.OFFLINE_LOCAL_UNAVAILABLE,
                        lastError = describe(result.error),
                        consecutiveOnlineFailures = status.consecutiveOnlineFailures + 1
                    )
                    val localIntent = LocalIntentRouter.resolve(request.prompt, toolRegistry)
                    val fallback = if (localIntent != null) {
                        executeLocalIntent(localIntent, request)
                    } else {
                        offline.chat(request)
                    }
                    return if (fallback is AIResult.Success) {
                        AIResult.Success(
                            fallback.value.copy(
                                toolTrace = listOf("GROQ FAILED → LOCAL FALLBACK: ${describe(result.error)}") +
                                    fallback.value.toolTrace
                            )
                        )
                    } else {
                        fallback
                    }
                }

                is GroqResult.Success -> {
                    val response = result.value
                    val knownRequests = response.toolCalls.mapNotNull { call ->
                        toolRegistry.find(call.name)?.let { tool ->
                            ToolRequest(tool.id, call.arguments, tool.risk)
                        }
                    }
                    val unknownCalls = response.toolCalls.filter { call ->
                        toolRegistry.find(call.name) == null
                    }

                    if (response.toolCalls.isNotEmpty()) {
                        // The provider protocol requires the assistant tool-call message before
                        // the corresponding tool-result messages.
                        messages += GroqMessage(
                            role = "assistant",
                            content = response.text,
                            toolCalls = response.toolCalls
                        )
                    }

                    if (unknownCalls.isNotEmpty()) {
                        unknownCalls.forEach { call ->
                            trace += "TOOL ${call.name}: NOT REGISTERED — not executed"
                            messages += GroqMessage(
                                role = "tool",
                                content = "Tool '${call.name}' is not registered in this app. Do not claim it ran.",
                                toolCallId = call.id,
                                name = call.name
                            )
                        }
                    }

                    val writeRequest = knownRequests.firstOrNull { it.risk != ToolRisk.READ_ONLY }
                    if (writeRequest != null) {
                        status = status.copy(
                            lastTier = RouterTier.ONLINE_GROQ,
                            lastError = null,
                            consecutiveOnlineFailures = 0
                        )
                        val text = response.text.ifBlank {
                            "Approval required for ${writeRequest.toolId}."
                        }
                        trace += "APPROVAL REQUIRED: ${writeRequest.toolId} (${writeRequest.risk})"
                        return AIResult.Success(
                            AIResponse(
                                text = text,
                                toolRequests = listOf(writeRequest),
                                toolTrace = trace.toList()
                            )
                        )
                    }

                    if (knownRequests.isEmpty()) {
                        // If there were unknown tool calls, the model gets the explicit refusal
                        // above and another round can correct itself. Otherwise this is final text.
                        if (unknownCalls.isNotEmpty()) return@repeat
                        status = status.copy(
                            lastTier = RouterTier.ONLINE_GROQ,
                            lastError = null,
                            consecutiveOnlineFailures = 0
                        )
                        return AIResult.Success(
                            AIResponse(
                                text = response.text,
                                toolTrace = trace.toList()
                            )
                        )
                    }

                    // Execute every read-only tool through the real gateway and feed its actual
                    // result back using the provider's real tool-message protocol.
                    for (call in response.toolCalls) {
                        val tool = toolRegistry.find(call.name) ?: continue
                        if (tool.risk != ToolRisk.READ_ONLY) continue
                        val requestForTool = ToolRequest(tool.id, call.arguments, tool.risk)
                        when (val execution = gateway.execute(requestForTool, approved = true)) {
                            is AIResult.Success -> {
                                val output = execution.value.output.take(18_000)
                                trace += "TOOL ${tool.id} ✓"
                                messages += GroqMessage(
                                    role = "tool",
                                    content = output,
                                    toolCallId = call.id,
                                    name = call.name
                                )
                            }
                            is AIResult.Failure -> {
                                val error = describeAiError(execution.error)
                                trace += "TOOL ${tool.id} ✗"
                                messages += GroqMessage(
                                    role = "tool",
                                    content = "REAL TOOL FAILURE: $error",
                                    toolCallId = call.id,
                                    name = call.name
                                )
                            }
                        }
                    }
                }
            }
        }

        status = status.copy(
            lastTier = RouterTier.ONLINE_GROQ,
            lastError = "Tool reasoning limit reached.",
            consecutiveOnlineFailures = 0
        )
        return AIResult.Success(
            AIResponse(
                text = "I stopped the tool loop after $MAX_TOOL_ROUNDS rounds. No unverified action was claimed.",
                toolTrace = trace + "STOPPED: maximum tool rounds reached"
            )
        )
    }

    private suspend fun executeLocalIntent(
        intent: ToolRequest,
        request: AIRequest
    ): AIResult<AIResponse> {
        val tool = toolRegistry.find(intent.toolId)
            ?: return offline.chat(request)
        val gateway = ToolExecutionGateway(toolRegistry)
        return if (intent.risk == ToolRisk.READ_ONLY) {
            when (val execution = gateway.execute(intent, approved = true)) {
                is AIResult.Success -> {
                    status = status.copy(
                        lastTier = RouterTier.OFFLINE_LOCAL,
                        lastError = null
                    )
                    AIResult.Success(
                        AIResponse(
                            text = execution.value.output,
                            toolTrace = listOf("LOCAL TOOL ${intent.toolId} ✓")
                        )
                    )
                }
                is AIResult.Failure -> {
                    status = status.copy(
                        lastTier = RouterTier.OFFLINE_LOCAL,
                        lastError = describeAiError(execution.error)
                    )
                    AIResult.Failure(execution.error)
                }
            }
        } else {
            AIResult.Success(
                AIResponse(
                    text = "I can perform ${tool.id}, but it requires your approval before anything changes or executes.",
                    toolRequests = listOf(intent),
                    toolTrace = listOf("LOCAL TOOL REQUEST: ${intent.toolId} (${intent.risk})")
                )
            )
        }
    }

    private fun AITool.toDescriptor(): ToolDescriptor =
        ToolDescriptor(id, description, parameterHints, requiredParameters)

    private fun describeAiError(error: AIError): String = when (error) {
        is AIError.InvalidRequest -> error.message
        is AIError.ModelUnavailable -> error.message
        is AIError.ToolDenied -> error.message
        is AIError.Execution -> error.message
    }

    private fun describe(error: GroqError): String = when (error) {
        is GroqError.MissingApiKey -> error.message
        is GroqError.InvalidApiKey -> "Invalid Groq API key: ${error.message}"
        is GroqError.RateLimited -> "Groq rate limit: ${error.message}"
        is GroqError.ServiceUnavailable -> "Groq unavailable (HTTP ${error.code}): ${error.message}"
        is GroqError.Http -> "Groq HTTP ${error.code}: ${error.message}"
        is GroqError.Network -> "Network error: ${error.message}"
        is GroqError.Timeout -> error.message
        is GroqError.MalformedResponse -> "Malformed Groq response: ${error.message}"
    }

    override suspend fun explainCode(code: String, context: ProjectContext) =
        chat(AIRequest("Explain this code clearly and briefly.", context.copy(selectedCode = code)))

    override suspend fun generateCode(prompt: String, context: ProjectContext) =
        chat(AIRequest("Generate code: $prompt", context))

    override suspend fun analyzeError(error: String, context: ProjectContext) =
        chat(AIRequest("Analyze this real error.", context.copy(compilerErrors = context.compilerErrors + error)))

    override suspend fun suggestFix(error: String, context: ProjectContext) =
        chat(AIRequest("Suggest a minimal safe fix for this real error.", context.copy(compilerErrors = context.compilerErrors + error)))

    override suspend fun modifyFile(path: String, instruction: String, context: ProjectContext) =
        chat(AIRequest("Modify $path: $instruction", context.copy(relevantFiles = (context.relevantFiles + path).distinct())))

    override suspend fun understandProject(context: ProjectContext) =
        chat(AIRequest("Understand this project from the supplied real context. Do not invent missing files.", context))

    override suspend fun runDeveloperTask(task: String, context: ProjectContext) =
        chat(AIRequest("Developer task: $task", context))
}
