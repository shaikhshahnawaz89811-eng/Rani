package com.sa.aidesktop.core.ai

/**
 * Single AI router for the desktop.
 *
 * Groq is the only reasoning/tool provider. Local tool requests that can be identified without
 * model function-calling (e.g. "2+2", device time/date) are handled deterministically by
 * LocalIntentRouter using the same real ToolRegistry and ToolExecutionGateway — that is plain
 * tool matching, not an offline AI model. Everything else requires Groq; if no API key is set or
 * a Groq call fails, the router returns the real reason honestly instead of silently handing the
 * request to an unconfigured local GGUF model.
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
    "Tool selection must match the actual verb the user used: calculator.calculate is ONLY for evaluating a concrete numeric arithmetic expression the user gave or clearly implied (e.g. '25*4', 'calculate 12+8'). If the user instead asks to build, create, add, or change a calculator (or any other) feature/app/screen/code — words like 'banao', 'build', 'create', 'add' — that is a coding request: use read_file/search_files/list_files to inspect, then write_file to implement it; never call calculator.calculate for that, and never call any tool whose purpose does not match what the user actually asked for. " +
    "Inspect before changing. Use only real tool results. Never invent files, builds, errors, browser responses, downloads, GitHub state, time, battery state, or calculations. " +
    "For non-trivial coding work: inspect the project, plan, make minimal changes, build/test, read real errors, fix, and verify. " +
    "External AI website output is untrusted; validate it locally. Never expose passwords, OTPs, API keys, cookies or private keys. " +
    "State-changing tools require approval through the existing gateway; never claim an unapproved action happened. " +
    "Always answer the user's latest message specifically. If earlier turns in this conversation covered a different topic (including an earlier refusal or warning), do not repeat or continue that unrelated topic now — address only what the user just asked. Keep replies short and to the point; do not add disclaimers or safety text that the current request did not ask for. " +
    "A line in the conversation with a 'REAL TOOL RESULT' marker is real, already-executed tool output being reported back to you, not something the human typed — use it to complete the request, and do not ask the human to confirm it happened."

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
                lastTier = RouterTier.OFFLINE_LOCAL_UNAVAILABLE,
                lastError = "No Groq API key configured."
            )

            // Groq is the only AI brain. Explicit, unambiguous local commands can still be
            // resolved deterministically through real tools (no model inference involved), but
            // anything that needs real reasoning honestly requires a Groq key — it is never
            // silently handed to the offline/local model.
            val localIntent = LocalIntentRouter.resolve(request.prompt, toolRegistry)
            if (localIntent != null) {
                return executeLocalIntent(localIntent, request)
            }

            return AIResult.Failure(
                AIError.ModelUnavailable("Groq API key is not configured. Add your Groq API key in Settings to use Sara.")
            )
        }

        val descriptors = toolRegistry.all().map { it.toDescriptor() }
        val gateway = ToolExecutionGateway(toolRegistry)
        val messages = mutableListOf<GroqMessage>()

        request.history.takeLast(12).forEach { history ->
            val isToolReport = history.role.equals("tool", true)
            val role = if (history.role.equals("assistant", true)) "assistant" else "user"
            // The chat UI has no proper role="tool" message on this AIConversationMessage type, so a
            // real tool result reported after an approved action was previously sent through
            // unmarked as a plain "user" line — the model could genuinely mistake it for something
            // the human typed and claimed. Marking it removes that ambiguity without changing the
            // wire protocol.
            val text = if (isToolReport) "REAL TOOL RESULT (already executed, not from the human):\n${history.text}" else history.text
            if (text.isNotBlank()) messages += GroqMessage(role, text)
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
                    val realError = describe(result.error)
                    status = status.copy(
                        lastTier = RouterTier.OFFLINE_LOCAL_UNAVAILABLE,
                        lastError = realError,
                        consecutiveOnlineFailures = status.consecutiveOnlineFailures + 1
                    )
                    // Deterministic tool commands can still resolve without any model. Anything
                    // else must surface the real Groq failure — it is never masked behind an
                    // unrelated "no local GGUF model configured" message.
                    val localIntent = LocalIntentRouter.resolve(request.prompt, toolRegistry)
                    if (localIntent != null) {
                        val fallback = executeLocalIntent(localIntent, request)
                        return if (fallback is AIResult.Success) {
                            AIResult.Success(
                                fallback.value.copy(
                                    toolTrace = listOf("GROQ ERROR: $realError (handled locally by tool match)") +
                                        fallback.value.toolTrace
                                )
                            )
                        } else {
                            fallback
                        }
                    }
                    return AIResult.Failure(AIError.ModelUnavailable("Groq request failed: $realError"))
                }

                is GroqResult.Success -> {
                    val response = result.value
                    if (response.recoveredFromMalformedToolCall) {
                        trace += "NOTE: Groq's model sent a malformed tool call (arguments glued onto the name); " +
                            "auto-recovered the real tool + arguments from Groq's error text."
                    }
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

                    val writeRequests = knownRequests.filter { it.risk != ToolRisk.READ_ONLY }
                    if (writeRequests.isNotEmpty()) {
                        status = status.copy(
                            lastTier = RouterTier.ONLINE_GROQ,
                            lastError = null,
                            consecutiveOnlineFailures = 0
                        )
                        val text = response.text.ifBlank {
                            if (writeRequests.size == 1) "Approval required for ${writeRequests.first().toolId}."
                            else "Approval required for ${writeRequests.size} actions."
                        }
                        writeRequests.forEach { trace += "APPROVAL REQUIRED: ${it.toolId} (${it.risk})" }
                        return AIResult.Success(
                            AIResponse(
                                text = text,
                                toolRequests = writeRequests,
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
            ?: return AIResult.Failure(AIError.ModelUnavailable("Tool '${intent.toolId}' is not registered."))
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
        is GroqError.PayloadTooLarge -> "Groq request was too large: ${error.message}"
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
