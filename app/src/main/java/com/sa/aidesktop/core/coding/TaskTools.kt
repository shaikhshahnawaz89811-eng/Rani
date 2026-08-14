package com.sa.aidesktop.core.coding

import com.sa.aidesktop.core.ai.*

class TaskStatusTool(private val engine: TaskEngine): AITool {
    override val id="task.status"
    override val description="Inspect the real current/resumable autonomous task state."
    override val risk=ToolRisk.READ_ONLY
    override suspend fun execute(input:Map<String,String>):AIResult<ToolResult>{
        val r=engine.current() ?: return AIResult.Success(ToolResult("No active or persisted task."))
        return AIResult.Success(ToolResult(buildString {
            appendLine("taskId=${r.taskId}"); appendLine("state=${r.state}"); appendLine("iteration=${r.iteration}")
            appendLine("currentOperation=${r.currentOperation}"); appendLine("waitingReason=${r.waitingReason ?: "none"}")
            appendLine("protectedInteraction=${r.requiresProtectedInteraction}"); appendLine("lastResult=${r.lastToolResult.take(6000)}")
        }))
    }
}

class TaskCancelTool(private val engine: TaskEngine): AITool {
    override val id="task.cancel"
    override val description="Cancel the current autonomous task without deleting its workspace."
    override val risk=ToolRisk.WRITE
    override suspend fun execute(input:Map<String,String>):AIResult<ToolResult>{
        val r=engine.cancel() ?: return AIResult.Failure(AIError.InvalidRequest("No active task."))
        return AIResult.Success(ToolResult("Task ${r.taskId} cancelled. Workspace was not deleted.",true))
    }
}

class TaskPauseTool(private val engine:TaskEngine):AITool{
    override val id="task.pause"; override val description="Pause the autonomous task before future side effects."; override val risk=ToolRisk.WRITE
    override suspend fun execute(input:Map<String,String>):AIResult<ToolResult>{
        val r=engine.pause() ?: return AIResult.Failure(AIError.InvalidRequest("No active task."))
        return AIResult.Success(ToolResult("Task ${r.taskId} paused.",true))
    }
}
