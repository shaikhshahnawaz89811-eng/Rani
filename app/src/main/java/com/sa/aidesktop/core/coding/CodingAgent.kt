package com.sa.aidesktop.core.coding

import com.sa.aidesktop.core.ai.AIService
import com.sa.aidesktop.core.ai.AIRequest
import com.sa.aidesktop.core.ai.AIResult
import com.sa.aidesktop.core.ai.ProjectContext
import com.sa.aidesktop.core.ai.ToolExecutionGateway
import com.sa.aidesktop.core.ai.ToolRequest
import com.sa.aidesktop.core.ai.ToolRisk
import com.sa.aidesktop.core.files.FileService
import com.sa.aidesktop.core.terminal.TerminalService
import com.sa.aidesktop.core.workspace.ProjectDiscovery
import com.sa.aidesktop.core.workspace.ProjectWorkspaceManager
import com.sa.aidesktop.core.workspace.WorkspaceResult
import java.io.File
import java.util.UUID

/** Phase 4 orchestration primitives. It never fabricates build/test output. Every mutation is
 * routed through the existing tool gateway; callers choose approval explicitly. */
class CodingAgent(
    private val ai: AIService,
    private val files: FileService,
    private val terminal: TerminalService,
    private val gateway: ToolExecutionGateway,
    private val workspaceManager: ProjectWorkspaceManager,
    private val maxIterations:Int = 5
) {
    suspend fun inspectProject(root:File): Result<ProjectDiscovery> = when(val r=workspaceManager.discover(root)){ is WorkspaceResult.Success->Result.success(r.value); is WorkspaceResult.Failure->Result.failure(IllegalStateException(r.error.message)) }

    suspend fun plan(request:String, discovery:ProjectDiscovery, tree:String): Result<List<String>> {
        val context=ProjectContext(projectStructure=tree)
        return when(val r=ai.chat(AIRequest("Create a concise implementation plan for this real project. Do not modify files. Request: $request\nDetected: $discovery",context))){
            is AIResult.Success->Result.success(r.value.text.lines().filter{it.isNotBlank()}.take(12))
            is AIResult.Failure->Result.failure(IllegalStateException("AI planning failed: ${r.error}"))
        }
    }

    suspend fun executeTool(request:ToolRequest, approved:Boolean):AIResult<com.sa.aidesktop.core.ai.ToolResult> = gateway.execute(request,approved)

    suspend fun build(command:String):Result<com.sa.aidesktop.core.terminal.TerminalResult> {
        val request=ToolRequest("run_terminal",mapOf("command" to command),ToolRisk.EXECUTION)
        return when(val r=gateway.execute(request,approved=true)){ is AIResult.Success->Result.success(com.sa.aidesktop.core.terminal.TerminalResult(r.value.output,0)); is AIResult.Failure->Result.failure(IllegalStateException(r.error.toString())) }
    }

    fun buildCommand(discovery:ProjectDiscovery):String? = when(discovery.buildSystem){
        "Gradle" -> when { File("gradlew").exists() -> "./gradlew build"; File("gradlew.bat").exists() -> "gradlew.bat build"; else -> "gradle build" }
        "npm/package scripts" -> "npm test"
        "Python project", "Python setup.py" -> "python -m pytest"
        else -> null
    }

    fun taskState(request:String,root:File,state:CodingTaskState,iteration:Int=0,plan:List<String> = emptyList())=CodingTaskStateRecord(UUID.randomUUID().toString(),request,root.canonicalPath,state,plan,iteration=iteration)
}
