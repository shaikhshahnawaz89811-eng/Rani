package com.sa.aidesktop.core.coding

import java.io.File
import java.util.Properties

/** Small durable Phase-4 state record. It stores task metadata only, never secrets or full source. */
class CodingTaskStore(private val file: File) {
    /**
     * Phase-4 and Phase-6 share this store instance in the current app.  Keep their records
     * namespaced inside the same Properties file so saving one record cannot erase the other.
     */
    fun save(state: CodingTaskStateRecord) {
        val p = loadProperties()
        p["phase4.taskId"] = state.taskId
        p["phase4.request"] = state.request.take(4000)
        p["phase4.workspaceRoot"] = state.workspaceRoot
        p["phase4.state"] = state.state.name
        p["phase4.iteration"] = state.iteration.toString()
        p["phase4.plan"] = state.plan.joinToString("\u001f").take(12000)
        p["phase4.inspectedFiles"] = state.inspectedFiles.joinToString("\u001f").take(12000)
        p["phase4.commands"] = state.commands.joinToString("\u001f").take(12000)
        p["phase4.errors"] = state.unresolvedErrors.joinToString("\u001f").take(12000)
        p["phase4.waitingReason"] = state.waitingReason.orEmpty().take(1000)
        writeProperties(p, "SA task state")
    }

    fun load(): Map<String, String>? = if (!file.isFile) null else loadProperties()
        .entries.associate { it.key.toString() to it.value.toString() }

    fun clear() {
        if (file.exists()) file.delete()
    }

    /** Phase-6 extension: persist resumable agent metadata in the same physical store. */
    fun saveAgent(state: AgentTaskRecord) {
        val p = loadProperties()
        p["agent.taskId"] = state.taskId
        p["agent.request"] = state.request.take(6000)
        p["agent.state"] = state.state.name
        p["agent.currentStep"] = state.currentStep.toString()
        p["agent.workspaceRoot"] = state.workspaceRoot.take(1000)
        p["agent.currentOperation"] = state.currentOperation.take(1000)
        p["agent.pendingOperation"] = state.pendingOperation.take(1000)
        p["agent.relevantContext"] = state.relevantContext.take(14000)
        p["agent.lastToolResult"] = state.lastToolResult.take(10000)
        p["agent.waitingReason"] = state.waitingReason.orEmpty().take(2000)
        p["agent.retryCount"] = state.retryCount.toString()
        p["agent.iteration"] = state.iteration.toString()
        p["agent.protected"] = state.requiresProtectedInteraction.toString()
        p["agent.finalResult"] = state.finalResult.take(10000)
        p["agent.failureReason"] = state.failureReason.orEmpty().take(3000)
        p["agent.updatedAt"] = state.updatedAt.toString()
        p["agent.completed"] = state.completedOperations.joinToString("\u001f").take(10000)
        p["agent.changedFiles"] = state.changedFiles.joinToString("\u001f").take(10000)
        p["agent.plan"] = state.plan.joinToString("\u001e") {
            listOf(it.id, it.description, it.capability, it.inputSummary, it.expectedResult, it.actualResult, it.status)
                .joinToString("\u001f")
        }.take(20000)
        writeProperties(p, "SA Phase-6 resumable task state")
    }

    fun loadAgent(): AgentTaskRecord? {
        if (!file.isFile) return null
        val p = loadProperties()
        // Read the namespaced format. Do not infer a successful task from legacy Phase-4 keys.
        // Migrate the pre-audit format once, but only when its state is actually an AgentTaskState.
        // Phase-4 and Phase-6 previously shared unprefixed keys, so blindly interpreting them here
        // could resurrect a Phase-4 task as a Phase-6 task after an app restart.
        if (p.getProperty("agent.taskId").isNullOrBlank()) {
            val legacyState = runCatching { AgentTaskState.valueOf(p.getProperty("state", "__NONE__")) }.getOrNull()
            if (legacyState != null && !p.getProperty("taskId").isNullOrBlank() && !p.getProperty("request").isNullOrBlank()) {
                migrateLegacyAgent(p, legacyState)
            }
        }

        val taskId = p.getProperty("agent.taskId") ?: return null
        val request = p.getProperty("agent.request") ?: return null
        if (taskId.isBlank() || request.isBlank()) return null

        val state = runCatching { AgentTaskState.valueOf(p.getProperty("agent.state", "FAILED")) }
            .getOrElse { AgentTaskState.FAILED }
        val plan = p.getProperty("agent.plan", "").split("\u001e").filter { it.isNotBlank() }.mapNotNull { row ->
            val x = row.split("\u001f", limit = 7)
            if (x.size == 7) AgentStep(x[0], x[1], x[2], x[3], x[4], x[5], x[6]) else null
        }
        fun list(key: String) = p.getProperty(key, "").split("\u001f").filter { it.isNotBlank() }

        return AgentTaskRecord(
            taskId = taskId,
            request = request,
            state = state,
            currentStep = p.getProperty("agent.currentStep", "0").toIntOrNull() ?: 0,
            plan = plan,
            workspaceRoot = p.getProperty("agent.workspaceRoot", ""),
            currentOperation = p.getProperty("agent.currentOperation", ""),
            completedOperations = list("agent.completed"),
            pendingOperation = p.getProperty("agent.pendingOperation", ""),
            relevantContext = p.getProperty("agent.relevantContext", ""),
            changedFiles = list("agent.changedFiles"),
            lastToolResult = p.getProperty("agent.lastToolResult", ""),
            waitingReason = p.getProperty("agent.waitingReason").takeUnless { it.isNullOrBlank() },
            retryCount = p.getProperty("agent.retryCount", "0").toIntOrNull() ?: 0,
            iteration = p.getProperty("agent.iteration", "0").toIntOrNull() ?: 0,
            requiresProtectedInteraction = p.getProperty("agent.protected", "false").toBoolean(),
            finalResult = p.getProperty("agent.finalResult", ""),
            failureReason = p.getProperty("agent.failureReason").takeUnless { it.isNullOrBlank() },
            updatedAt = p.getProperty("agent.updatedAt", System.currentTimeMillis().toString()).toLongOrNull()
                ?: System.currentTimeMillis()
        )
    }


    private fun migrateLegacyAgent(p: Properties, state: AgentTaskState) {
        val legacyKeys = listOf(
            "taskId" to "agent.taskId", "request" to "agent.request", "state" to "agent.state",
            "currentStep" to "agent.currentStep", "workspaceRoot" to "agent.workspaceRoot",
            "currentOperation" to "agent.currentOperation", "pendingOperation" to "agent.pendingOperation",
            "relevantContext" to "agent.relevantContext", "lastToolResult" to "agent.lastToolResult",
            "waitingReason" to "agent.waitingReason", "retryCount" to "agent.retryCount",
            "iteration" to "agent.iteration", "protected" to "agent.protected",
            "finalResult" to "agent.finalResult", "failureReason" to "agent.failureReason",
            "updatedAt" to "agent.updatedAt", "completed" to "agent.completed",
            "changedFiles" to "agent.changedFiles", "plan" to "agent.plan"
        )
        legacyKeys.forEach { (oldKey, newKey) -> p.getProperty(oldKey)?.let { p[newKey] = it } }
        p["agent.state"] = state.name
        writeProperties(p, "SA task state migration")
    }

    private fun loadProperties(): Properties = Properties().also { properties ->
        if (file.isFile) file.inputStream().use(properties::load)
    }

    private fun writeProperties(properties: Properties, comment: String) {
        file.parentFile?.mkdirs()
        file.outputStream().use { properties.store(it, comment) }
    }
}
