package com.sa.aidesktop.core.coding

import com.sa.aidesktop.core.ai.*
import com.sa.aidesktop.core.files.FileService
import com.sa.aidesktop.core.terminal.TerminalService
import com.sa.aidesktop.core.workspace.ProjectWorkspaceManager
import com.sa.aidesktop.core.workspace.WorkspaceResult
import java.io.File

class ProjectInspectTreeTool(private val files: FileService): AITool {
    override val id="project.inspect_tree"; override val description="Inspect the real project tree with bounded depth/output."; override val risk=ToolRisk.READ_ONLY
    override val parameterHints=mapOf("max_entries" to "Optional maximum entries to return, default 300")
    override val requiredParameters=emptySet<String>()
    override suspend fun execute(input:Map<String,String>):AIResult<ToolResult>{
        val max=input["max_entries"]?.toIntOrNull()?.coerceIn(1,1000)?:300
        val lines=mutableListOf<String>()
        fun walk(f:com.sa.aidesktop.core.files.ProjectFile, depth:Int){ if(lines.size>=max)return; val indent="  ".repeat(depth); lines+=indent+if(f.kind==com.sa.aidesktop.core.files.FileKind.FOLDER)"[DIR] ${f.path}" else "${f.path} (${f.sizeBytes}b)"; f.children.forEach{walk(it,depth+1)} }
        walk(files.projectTree(),0)
        val suffix=if(lines.size>=max)"\n[TREE TRUNCATED: configured entry limit reached]" else ""
        return AIResult.Success(ToolResult(lines.joinToString("\n")+suffix))
    }
}

class ProjectDiscoverTool(private val workspaceManager:ProjectWorkspaceManager, private val root:File): AITool {
    override val id="project.discover"; override val description="Discover the actual project type, build system, entry points, source/test/config/dependency files."; override val risk=ToolRisk.READ_ONLY
    override val parameterHints=emptyMap<String,String>()
    override suspend fun execute(input:Map<String,String>):AIResult<ToolResult>{
        return when(val r=workspaceManager.discover(root)){ is WorkspaceResult.Success->AIResult.Success(ToolResult(r.value.toString())); is WorkspaceResult.Failure->AIResult.Failure(AIError.Execution(r.error.message)) }
    }

}

class ZipWorkspaceTool(private val workspaceManager:ProjectWorkspaceManager):AITool {
    override val id="project.extract_zip"; override val description="Safely extract a real ZIP into a separate workspace with path-traversal and size limits."; override val risk=ToolRisk.WRITE
    override val parameterHints=mapOf("zip_path" to "Absolute or app-accessible path to the real ZIP", "workspace_name" to "Optional new workspace name")
    override val requiredParameters=setOf("zip_path")
    override suspend fun execute(input:Map<String,String>):AIResult<ToolResult>{
        val path=input["zip_path"]?.trim().orEmpty(); if(path.isBlank())return AIResult.Failure(AIError.InvalidRequest("zip_path is required"))
        val name=input["workspace_name"]?.trim().takeUnless{it.isNullOrBlank()}?:"task-${System.currentTimeMillis()}"
        return when(val r=workspaceManager.extractZip(File(path),name)){ is WorkspaceResult.Success->AIResult.Success(ToolResult("Extracted real ZIP to ${r.value.root.canonicalPath}",true)); is WorkspaceResult.Failure->AIResult.Failure(AIError.Execution(r.error.message)) }
    }
}

class BuildProjectTool(private val terminal:TerminalService):AITool {
    override val id="project.build"; override val description="Run a supplied project build/test command through the existing real terminal."; override val risk=ToolRisk.EXECUTION
    override val parameterHints=mapOf("command" to "Actual project build or test command discovered from project configuration")
    override suspend fun execute(input:Map<String,String>):AIResult<ToolResult>{
        val command=input["command"]?.trim().orEmpty(); if(command.isBlank())return AIResult.Failure(AIError.InvalidRequest("command is required"))
        // BUG FIX (Rule 4/10 correctness): terminal.execute() is a plain blocking call (it can
        // block for the shell backend's whole timeout on a real build/test run). Every other
        // caller of it (see CoreAITools.TerminalRunTool) dispatches onto Dispatchers.IO first;
        // this one previously ran it directly on whatever coroutine dispatcher the AI chat/task
        // loop is on, risking a UI stall on the one command most likely to be slow.
        // BUG FIX (Rule 17): a successful build/test run can genuinely write output files, so it
        // must report changed=true like TerminalRunTool does, not the silent default false.
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { terminal.execute(command) }
        val output=("$command\n${r.output}").takeLast(24_000)
        return if(r.exitCode==0) AIResult.Success(ToolResult("BUILD/TEST SUCCESS\n$output", changed = true)) else AIResult.Failure(AIError.Execution("BUILD/TEST FAILED (exit ${r.exitCode})\n$output"))
    }
}
