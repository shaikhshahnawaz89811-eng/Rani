package com.sa.aidesktop.core.coding

/** Persistent Phase-6 state. Only task metadata and bounded real results are stored. */
enum class AgentTaskState {
    NEW, PLANNING, INSPECTING, ANALYZING, EXECUTING, VERIFYING,
    WAITING_FOR_USER, WAITING_FOR_AUTH, WAITING_FOR_APPROVAL,
    WAITING_FOR_NETWORK, WAITING_FOR_RESOURCE, RETRYING, PAUSED,
    COMPLETED, FAILED, CANCELLED
}

data class AgentStep(
    val id: String,
    val description: String,
    val capability: String,
    val inputSummary: String = "",
    val expectedResult: String = "",
    val actualResult: String = "",
    val status: String = "PENDING"
)

data class AgentTaskRecord(
    val taskId: String,
    val request: String,
    val state: AgentTaskState,
    val currentStep: Int = 0,
    val plan: List<AgentStep> = emptyList(),
    val workspaceRoot: String = "",
    val currentOperation: String = "",
    val completedOperations: List<String> = emptyList(),
    val pendingOperation: String = "",
    val relevantContext: String = "",
    val changedFiles: List<String> = emptyList(),
    val lastToolResult: String = "",
    val waitingReason: String? = null,
    val retryCount: Int = 0,
    val iteration: Int = 0,
    val requiresProtectedInteraction: Boolean = false,
    val finalResult: String = "",
    val failureReason: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

data class TaskRunResult(
    val record: AgentTaskRecord,
    val pendingTool: com.sa.aidesktop.core.ai.ToolRequest? = null
)
