package com.sa.aidesktop.core.ai

import org.json.JSONObject

/**
 * Rule 1 fix (missing endpoint): before this file existed, the offline/local-model path in
 * [ModelRouter.runOfflineFirst] had no way to reach [ToolExecutionGateway] beyond the handful of
 * deterministic patterns in [LocalIntentRouter]. A request like "calculator app banao" fell
 * through to a single [LocalLlamaEngine.generate] call, which can only return plain text — it
 * could never call write_file/run_terminal the way the Groq path in [ModelRouter.chat] can. This
 * class gives the offline tier a bounded, text-based tool-call loop over the SAME real
 * [ToolExecutionGateway]/[ToolRegistry] the Groq path already uses. It does not add a second
 * gateway, a second approval system, or a second terminal/file backend (Rule 4: one chain), and it
 * does not touch [ModelRouter.chat]'s Groq path at all (Rule 7 risk-check: isolated change).
 *
 * The local GGUF runtime wired through [LocalLlamaEngine] only exposes raw text completion — no
 * native function-calling API. So tool calls are requested through a plain-text convention this
 * class defines and parses itself: the model is asked to answer with a single line
 * `TOOL_CALL: {"tool":"<id>","args":{...}}` only when the user's request needs a real action;
 * anything else is treated as its final answer. This is honest about being a convention, not a
 * guarantee (Rule 10) — a small model can and sometimes will just answer in plain text instead of
 * emitting a tool call. When that happens this loop returns that plain text truthfully; it never
 * invents a tool result.
 *
 * NOT VERIFIED (same disclosure convention already used in LocalLlamaEngine.kt): this file was
 * written and hand-traced but could not be compiled or exercised against a real device/emulator +
 * real GGUF model in the sandbox that wrote it (no Android SDK/NDK, no network here). It must be
 * built and tested for real (Rule 10, Rule 19) before being marked "Done".
 */
class LocalToolCallLoop(
    private val offline: AIService,
    private val toolRegistry: ToolRegistry,
    private val maxRounds: Int = 6
) {
    private val gateway = ToolExecutionGateway(toolRegistry)

    suspend fun run(request: AIRequest): AIResult<AIResponse> {
        val menu = buildToolMenu(toolRegistry) // Rule 20: compact tool list, not full descriptions
        val history = request.history.toMutableList()
        var round = 0
        var currentPrompt = withToolInstructions(request.prompt, menu)

        while (round < maxRounds) { // Rule 21 Part B.3: bounded loop, verified exit condition
            round++
            val turn = AIRequest(prompt = currentPrompt, context = request.context, history = history.toList())
            val result = offline.chat(turn)
            if (result !is AIResult.Success) return result

            val raw = result.value.text
            val call = LocalToolCallParser.parse(raw) ?: return AIResult.Success(result.value) // plain text = final answer

            val tool = toolRegistry.find(call.toolId)
            if (tool == null) {
                history += AIConversationMessage("assistant", raw)
                history += AIConversationMessage(
                    "user",
                    "REAL TOOL RESULT: '${call.toolId}' is not a registered tool. Do not claim it ran. " +
                        "Available tools:\n$menu"
                )
                currentPrompt = "Continue with the original request."
                continue
            }

            val toolRequest = ToolRequest(tool.id, call.args, tool.risk)

            if (tool.risk != ToolRisk.READ_ONLY) {
                // Same approval gate the Groq path uses: WRITE/EXECUTION/GIT_SENSITIVE tools are
                // never auto-executed here. The existing UI (SADesktopApp.kt) and TaskEngine
                // already read AIResponse.toolRequests regardless of which tier produced it, so
                // this reuses that one real approval flow instead of building a second.
                return AIResult.Success(
                    AIResponse(
                        text = raw.ifBlank { "Approval required for ${tool.id}." },
                        toolRequests = listOf(toolRequest),
                        toolTrace = listOf("LOCAL TOOL REQUEST: ${tool.id} (${tool.risk})")
                    )
                )
            }

            when (val execution = gateway.execute(toolRequest, approved = true)) {
                is AIResult.Success -> {
                    history += AIConversationMessage("assistant", raw)
                    history += AIConversationMessage(
                        "user",
                        "REAL TOOL RESULT (already executed, not from the human) for ${tool.id}:\n" +
                            execution.value.output.take(TOOL_OUTPUT_CHAR_LIMIT)
                    )
                }
                is AIResult.Failure -> {
                    history += AIConversationMessage("assistant", raw)
                    history += AIConversationMessage(
                        "user",
                        "REAL TOOL FAILURE for ${tool.id}: ${execution.error.toDisplayMessage()}"
                    )
                }
            }
            currentPrompt = "Continue with the original request using the real tool result above."
        }

        return AIResult.Success(
            AIResponse(
                text = "Stopped after $maxRounds local tool rounds. No unverified action was claimed.",
                toolTrace = listOf("STOPPED: maximum local tool rounds reached")
            )
        )
    }

    private fun withToolInstructions(userPrompt: String, menu: String): String = buildString {
        appendLine("You may call ONE real tool if this request needs a real action. Available tools:")
        appendLine(menu)
        appendLine("To call a tool, reply with EXACTLY one line: TOOL_CALL: {\"tool\":\"<id>\",\"args\":{...}}")
        appendLine("Otherwise answer normally in plain text. Never claim a tool ran unless its real result is shown to you.")
        appendLine()
        append(userPrompt)
    }

    companion object {
        private const val TOOL_OUTPUT_CHAR_LIMIT = 4_000 // Rule 20: local model's context window is small
    }
}

/** Sub-helper (Rule 15) — single job: extract a tool-call id+args from raw local-model text, or
 *  null if the text is a plain-text final answer. Never guesses at malformed JSON. */
object LocalToolCallParser {
    private val marker = Regex("""TOOL_CALL:\s*(\{.*)""", RegexOption.DOT_MATCHES_ALL)

    data class Call(val toolId: String, val args: Map<String, String>)

    fun parse(text: String): Call? {
        val match = marker.find(text) ?: return null
        val jsonText = extractFirstJsonObject(match.groupValues[1]) ?: return null
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val id = obj.optString("tool").trim()
        if (id.isBlank()) return null
        val argsObj = obj.optJSONObject("args") ?: JSONObject()
        val args = mutableMapOf<String, String>()
        argsObj.keys().forEach { key -> args[key] = argsObj.optString(key) }
        return Call(id, args)
    }

    /** Bracket-counting scan so any trailing text after the JSON object (if the model adds any)
     *  doesn't break org.json parsing. */
    private fun extractFirstJsonObject(text: String): String? {
        var depth = 0
        var start = -1
        for ((i, c) in text.withIndex()) {
            when (c) {
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> { depth--; if (depth == 0 && start >= 0) return text.substring(start, i + 1) }
            }
        }
        return null
    }
}

/** Rule 20: only id + param names + risk — not each tool's full description — to keep this menu
 *  cheap inside a ~2-4K token local context window. */
private fun buildToolMenu(registry: ToolRegistry): String =
    registry.all().joinToString("\n") { tool ->
        "- ${tool.id}(${tool.parameterHints.keys.joinToString(",")}) [${tool.risk}]"
    }
