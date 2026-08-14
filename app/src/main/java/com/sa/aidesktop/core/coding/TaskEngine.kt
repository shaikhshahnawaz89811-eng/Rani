package com.sa.aidesktop.core.coding

import com.sa.aidesktop.core.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.math.min

/**
 * Phase-6 persistent coordinator. It does not implement another browser/file/Git backend.
 * ModelRouter remains the reasoning layer and ToolExecutionGateway remains the side-effect gate.
 */
class TaskEngine(
    private val ai: AIService,
    private val gateway: ToolExecutionGateway,
    private val store: CodingTaskStore,
    private val maxIterations: Int = 12,
    private val maxRetries: Int = 2
) {
    @Volatile private var active: AgentTaskRecord? = null
    @Volatile private var cancelled = false
    @Volatile private var paused = false

    fun current(): AgentTaskRecord? = active ?: store.loadAgent()

    suspend fun start(request: String, context: ProjectContext = ProjectContext()): TaskRunResult {
        require(request.isNotBlank())
        val existing = store.loadAgent()
        if (existing != null && existing.state in setOf(
                AgentTaskState.PLANNING, AgentTaskState.INSPECTING, AgentTaskState.ANALYZING,
                AgentTaskState.EXECUTING, AgentTaskState.VERIFYING, AgentTaskState.WAITING_FOR_APPROVAL,
                AgentTaskState.WAITING_FOR_AUTH, AgentTaskState.WAITING_FOR_USER,
                AgentTaskState.RETRYING, AgentTaskState.PAUSED
            )) return reconcileAndResume(existing, context)

        cancelled = false; paused = false
        val record = AgentTaskRecord(
            taskId = UUID.randomUUID().toString(), request = request,
            state = AgentTaskState.PLANNING,
            relevantContext = bounded(context.projectStructure, 6000),
            requiresProtectedInteraction = requiresProtection(request)
        )
        active = record; persist(record)
        return runLoop(record, context)
    }

    suspend fun resume(context: ProjectContext = ProjectContext()): TaskRunResult {
        val record = store.loadAgent() ?: return TaskRunResult(
            AgentTaskRecord("none", "", AgentTaskState.FAILED, failureReason = "No resumable task exists.")
        )
        return reconcileAndResume(record, context)
    }

    fun pause(): AgentTaskRecord? {
        val r = active ?: store.loadAgent() ?: return null
        paused = true
        val next = r.copy(state = AgentTaskState.PAUSED, updatedAt = System.currentTimeMillis())
        active = next; persist(next); return next
    }

    fun cancel(): AgentTaskRecord? {
        val r = active ?: store.loadAgent() ?: return null
        cancelled = true; paused = false
        val next = r.copy(state = AgentTaskState.CANCELLED, currentOperation = "", pendingOperation = "", updatedAt = System.currentTimeMillis())
        active = next; persist(next); return next
    }

    /** Called only after the UI/PermissionGate actually approved and executed the pending tool. */
    suspend fun continueAfterApprovedTool(
        taskId: String,
        request: ToolRequest,
        result: AIResult<ToolResult>,
        context: ProjectContext = ProjectContext()
    ): TaskRunResult {
        val r = current() ?: return TaskRunResult(AgentTaskRecord(taskId, "", AgentTaskState.FAILED, failureReason = "Task not found."))
        if (r.taskId != taskId) return TaskRunResult(r.copy(state = AgentTaskState.FAILED, failureReason = "Task ID mismatch."))
        if (r.state !in setOf(AgentTaskState.WAITING_FOR_APPROVAL, AgentTaskState.WAITING_FOR_AUTH) ||
            r.pendingOperation != request.toolId) {
            return TaskRunResult(r.copy(
                state = AgentTaskState.FAILED,
                failureReason = "Pending operation is stale or no longer matches the requested tool: ${request.toolId}."
            ))
        }
        val resultText = when (result) {
            is AIResult.Success -> "${request.toolId}: ${bounded(result.value.output, 8000)}"
            is AIResult.Failure -> "${request.toolId} FAILED: ${bounded(describe(result.error), 8000)}"
        }
        val authenticationRequired = result is AIResult.Failure &&
            describe(result.error).contains(Regex("authentication|login|credential|unauthorized|401|403", RegexOption.IGNORE_CASE))
        val nextState = when {
            result is AIResult.Success -> AgentTaskState.EXECUTING
            authenticationRequired -> AgentTaskState.WAITING_FOR_AUTH
            else -> AgentTaskState.FAILED
        }
        val next = r.copy(
            state = nextState,
            pendingOperation = if (nextState == AgentTaskState.WAITING_FOR_AUTH) request.toolId else "",
            lastToolResult = resultText,
            completedOperations = if (result is AIResult.Success) (r.completedOperations + request.toolId).takeLast(100) else r.completedOperations,
            iteration = r.iteration + 1,
            updatedAt = System.currentTimeMillis(),
            waitingReason = if (authenticationRequired) "Authentication is required before ${request.toolId} can continue." else null
        )
        active = next; persist(next)
        return if (nextState != AgentTaskState.EXECUTING) TaskRunResult(next)
        else runLoop(next, context)
    }

    private suspend fun reconcileAndResume(record: AgentTaskRecord, context: ProjectContext): TaskRunResult {
        // The saved state is evidence of what was requested, not proof that it completed.
        // Ask the same reasoning layer to reconcile the live project/browser/Git state first.
        cancelled = false; paused = false
        val reconciled = record.copy(
            state = AgentTaskState.INSPECTING,
            currentOperation = "Reconcile live state before resuming",
            updatedAt = System.currentTimeMillis()
        )
        active = reconciled; persist(reconciled)
        return runLoop(reconciled, context, reconciliation = true)
    }

    private suspend fun runLoop(initial: AgentTaskRecord, context: ProjectContext, reconciliation: Boolean = false): TaskRunResult {
        var record = initial
        try {
            while (record.iteration < maxIterations) {
                if (cancelled) return TaskRunResult(save(record.copy(state = AgentTaskState.CANCELLED)))
                if (paused) return TaskRunResult(save(record.copy(state = AgentTaskState.PAUSED)))

                val prompt = buildPrompt(record, context, reconciliation)
                when (val response = ai.chat(AIRequest(prompt, context.copy(
                    projectStructure = bounded(context.projectStructure, 8000),
                    compilerErrors = context.compilerErrors.takeLast(12),
                    gitChanges = context.gitChanges.takeLast(100)
                )))) {
                    is AIResult.Failure -> {
                        val classified = classify(response.error)
                        val retryable = classified == FailureClass.NETWORK_RETRYABLE && record.retryCount < maxRetries
                        record = if (retryable) {
                            save(record.copy(state = AgentTaskState.RETRYING, retryCount = record.retryCount + 1, iteration = record.iteration + 1, failureReason = describe(response.error)))
                        } else {
                            save(record.copy(state = if (classified == FailureClass.USER_INPUT_REQUIRED) AgentTaskState.WAITING_FOR_USER else AgentTaskState.FAILED, failureReason = describe(response.error), iteration = record.iteration + 1))
                        }
                        if (!retryable) return TaskRunResult(record)
                        delay(300L * (1L shl min(record.retryCount, 3)))
                    }
                    is AIResult.Success -> {
                        val pending = response.value.toolRequests.firstOrNull { it.risk != ToolRisk.READ_ONLY }
                        val text = bounded(response.value.text, 10_000)
                        if (record.state == AgentTaskState.PLANNING && record.plan.isEmpty()) {
                            val parsed = parsePlan(text)
                            if (parsed.isNotEmpty()) record = save(record.copy(plan = parsed, state = AgentTaskState.INSPECTING, lastToolResult = text))
                        }
                        if (pending != null) {
                            val waitingState = classifyWaiting()
                            record = save(record.copy(
                                state = waitingState,
                                currentOperation = pending.toolId,
                                pendingOperation = pending.toolId,
                                lastToolResult = text,
                                iteration = record.iteration + 1,
                                waitingReason = "Approval required for ${pending.toolId}",
                                requiresProtectedInteraction = true
                            ))
                            return TaskRunResult(record, pending)
                        }

                        if (isComplete(text)) {
                            record = save(record.copy(state = AgentTaskState.COMPLETED, finalResult = text, currentOperation = "", iteration = record.iteration + 1))
                            return TaskRunResult(record)
                        }

                        val state = inferState(text)
                        record = save(record.copy(
                            state = state,
                            currentOperation = "Reasoning / verification",
                            lastToolResult = text,
                            iteration = record.iteration + 1,
                            relevantContext = bounded((record.relevantContext + "\n" + text).takeLast(14_000), 14_000),
                            waitingReason = if (state == AgentTaskState.WAITING_FOR_USER) text.take(1200) else null
                        ))
                        if (state == AgentTaskState.WAITING_FOR_USER) return TaskRunResult(record)
                    }
                }
            }
            record = save(record.copy(state = AgentTaskState.FAILED, failureReason = "Maximum safe task iterations reached.", iteration = record.iteration))
            return TaskRunResult(record)
        } catch (e: CancellationException) {
            save(record.copy(state = AgentTaskState.PAUSED, failureReason = "Task coroutine cancelled; live state must be reconciled before resume."))
            throw e
        } catch (e: Exception) {
            return TaskRunResult(save(record.copy(state = AgentTaskState.FAILED, failureReason = e.message ?: "Task engine failure.")))
        }
    }

    private fun buildPrompt(record: AgentTaskRecord, context: ProjectContext, reconciliation: Boolean): String = buildString {
        appendLine("You are the Phase-6 task coordinator for Sara.")
        appendLine("Never claim a side effect succeeded unless a real tool result proves it.")
        appendLine("Use the existing tools only. Inspect live state before retrying an operation.")
        appendLine("Original request: ${record.request}")
        appendLine("Task ID: ${record.taskId}")
        appendLine("Current state: ${record.state}")
        appendLine("Iteration: ${record.iteration}/$maxIterations")
        appendLine("Workspace: ${record.workspaceRoot.ifBlank { "not selected" }}")
        appendLine("Last real result: ${bounded(record.lastToolResult, 7000)}")
        appendLine("Completed operations: ${record.completedOperations.takeLast(40).joinToString()}")
        appendLine("Reconciliation required: $reconciliation")
        appendLine("If user input/auth/approval is required, stop and state exactly what is needed.")
        appendLine("For completion, end your response with [TASK_COMPLETE] only after real verification.")
        appendLine("For a user question, end with [WAITING_FOR_USER].")
        appendLine("Project context: ${bounded(context.projectStructure, 6000)}")
    }

    private fun parsePlan(text: String): List<AgentStep> = text.lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .filter { Regex("^(?:[-*] |\\d+[.)] )").containsMatchIn(it) }
        .take(12)
        .mapIndexed { index, line -> AgentStep("step-${index + 1}", line.replace(Regex("^(?:[-*] |\\d+[.)] )"), ""), "model", status = "PENDING") }

    private fun isComplete(text: String) = text.contains("[TASK_COMPLETE]", true)
    private fun inferState(text: String): AgentTaskState = when {
        text.contains("[WAITING_FOR_USER]", true) || text.contains("login required", true) || text.contains("captcha", true) || text.contains("otp", true) || text.contains("2fa", true) -> AgentTaskState.WAITING_FOR_USER
        text.contains("build", true) || text.contains("test", true) -> AgentTaskState.VERIFYING
        text.contains("inspect", true) || text.contains("read", true) -> AgentTaskState.INSPECTING
        else -> AgentTaskState.ANALYZING
    }
    private fun classifyWaiting(): AgentTaskState = AgentTaskState.WAITING_FOR_APPROVAL
    private enum class FailureClass { USER_INPUT_REQUIRED, AUTH_REQUIRED, NETWORK_RETRYABLE, TOOL_FAILURE, UNRECOVERABLE_ERROR }
    private fun classify(e: AIError): FailureClass = when (e) {
        is AIError.ToolDenied -> FailureClass.USER_INPUT_REQUIRED
        is AIError.Execution -> if (e.message.contains("network", true) || e.message.contains("timeout", true)) FailureClass.NETWORK_RETRYABLE else FailureClass.TOOL_FAILURE
        else -> FailureClass.UNRECOVERABLE_ERROR
    }
    private fun requiresProtection(request: String) = Regex("(?i)(fix|build|modify|change|commit|push|upload|download|github|claude|chatgpt|gemini|project|zip|code|app)").containsMatchIn(request)
    private fun describe(e: AIError) = when(e){ is AIError.InvalidRequest->e.message; is AIError.ModelUnavailable->e.message; is AIError.ToolDenied->e.message; is AIError.Execution->e.message }
    private fun bounded(s:String,max:Int)=if(s.length<=max)s else s.take(max)+"\n[TRUNCATED]"
    private fun persist(r:AgentTaskRecord):AgentTaskRecord { store.saveAgent(r); return r }
    private fun save(r:AgentTaskRecord)=persist(r.copy(updatedAt=System.currentTimeMillis()))
}

