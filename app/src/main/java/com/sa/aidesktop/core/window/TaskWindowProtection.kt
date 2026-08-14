package com.sa.aidesktop.core.window

import com.sa.aidesktop.core.coding.AgentTaskRecord
import com.sa.aidesktop.core.coding.AgentTaskState

/** Pure policy: maps real task state/operation to only the windows involved in that operation. */
object TaskWindowProtection {
    fun protectedWindowTypes(task: AgentTaskRecord): Set<WindowType> {
        if (!task.requiresProtectedInteraction) return emptySet()
        if (task.state in setOf(
                AgentTaskState.WAITING_FOR_USER,
                AgentTaskState.WAITING_FOR_AUTH,
                AgentTaskState.WAITING_FOR_APPROVAL,
                AgentTaskState.WAITING_FOR_NETWORK,
                AgentTaskState.WAITING_FOR_RESOURCE,
                AgentTaskState.PAUSED,
                AgentTaskState.COMPLETED,
                AgentTaskState.FAILED,
                AgentTaskState.CANCELLED
            )) return emptySet()
        val op = task.currentOperation.lowercase()
        return when {
            op.contains("browser") || op.contains("ai_web") || op.contains("upload") || op.contains("download") -> setOf(WindowType.BROWSER, WindowType.AI)
            op.contains("git") || op.contains("github") || op.contains("push") || op.contains("commit") || op.contains("pull") -> setOf(WindowType.GIT)
            op.contains("terminal") || op.contains("build") || op.contains("test") || op.contains("command") -> setOf(WindowType.TERMINAL, WindowType.DEVELOPER)
            op.contains("file") || op.contains("write") || op.contains("edit") || op.contains("project") -> setOf(WindowType.DEVELOPER, WindowType.FILES)
            else -> setOf(WindowType.DEVELOPER)
        }
    }

    fun reason(task: AgentTaskRecord): String = "Protected while task ${task.taskId.take(8)} is performing ${task.currentOperation.ifBlank { "an important operation" }}."
}
