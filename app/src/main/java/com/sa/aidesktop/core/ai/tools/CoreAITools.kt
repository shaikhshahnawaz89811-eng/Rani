package com.sa.aidesktop.core.ai.tools

import com.sa.aidesktop.core.ai.*
import com.sa.aidesktop.core.files.*
import com.sa.aidesktop.core.terminal.TerminalService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReadFileTool(private val files:FileService):AITool{
    override val id="read_file";override val description="Read a file from the controlled project workspace.";override val risk=ToolRisk.READ_ONLY
    override val parameterHints=mapOf("path" to "Workspace-relative path of the file to read")
    override suspend fun execute(input:Map<String,String>):AIResult<ToolResult>{val path=input["path"]?.trim().orEmpty();if(path.isBlank())return AIResult.Failure(AIError.InvalidRequest("path is required"));val r=files.read(path); return if(r.isSuccess) AIResult.Success(ToolResult(r.value.orEmpty().take(32_000) + if (r.value.orEmpty().length > 32_000) "\n[FILE CONTENT TRUNCATED]" else "")) else AIResult.Failure(AIError.Execution("Read failed: ${r.error}"))}
}
class SearchFileTool(private val files:FileService):AITool{
    override val id="search_files";override val description="Search project files by name.";override val risk=ToolRisk.READ_ONLY
    override val parameterHints=mapOf("query" to "Filename substring to search for")
    override suspend fun execute(input:Map<String,String>):AIResult<ToolResult>{val q=input["query"]?.trim().orEmpty();if(q.isBlank())return AIResult.Failure(AIError.InvalidRequest("query is required"));val r=files.search(q);return if(r.isSuccess)AIResult.Success(ToolResult(r.value.orEmpty().joinToString("\n"){it.path}))else AIResult.Failure(AIError.Execution("Search failed: ${r.error}"))}
}
class WriteFileTool(private val files:FileService):AITool{
    override val id="write_file";override val description="Write a controlled workspace file; approval is enforced by ToolExecutionGateway.";override val risk=ToolRisk.WRITE
    override val parameterHints=mapOf("path" to "Workspace-relative path of the file to write","content" to "Full new text content of the file")
    override suspend fun execute(input:Map<String,String>):AIResult<ToolResult>{
        val path=input["path"]?.trim().orEmpty();val content=input["content"]?:return AIResult.Failure(AIError.InvalidRequest("content is required"));if(path.isBlank())return AIResult.Failure(AIError.InvalidRequest("path is required"))
        // Rule 17 correctness / Rule 13 cleaner-viewer: capture the real previous content BEFORE
        // writing, so the result can carry a genuine before/after diff instead of only a bare
        // "Updated <path>" string. A read failure (e.g. this is a brand-new file) just means an
        // empty "before" — not an error, since write_file is allowed to create new files.
        val previousContent = files.read(path).let { if (it.isSuccess) it.value.orEmpty() else "" }
        val r=files.write(path,content)
        if(!r.isSuccess) return AIResult.Failure(AIError.Execution("Write failed: ${r.error}"))
        return AIResult.Success(ToolResult(buildWriteSummary(path, previousContent, content), true))
    }
}

/** Rule 15 sub-helper (single job: format WriteFileTool's real before/after content into the
 *  structured, delimiter-marked text the AI chat UI's diff card parses — see SADesktopApp's
 *  parseFileDiffMessage/FileDiffCard). The first line stays a plain human-readable sentence so any
 *  other consumer of this text (task status log, tool trace, model context) still reads sensibly
 *  even without the special-case UI parsing (Rule 4: one chain, not a UI-only side channel). */
private fun buildWriteSummary(path: String, oldContent: String, newContent: String): String {
    val diff = ChatDiffUtil.lineDiff(oldContent.lines(), newContent.lines())
        ?: return "Updated $path (file too large to diff on-device — content replaced)"
    val changed = diff.count { it.kind != ' ' }
    if (changed == 0) return "Updated $path (no line changes)"
    val body = ChatDiffUtil.collapseContext(diff)
    val sb = StringBuilder()
    sb.append("Updated $path ($changed line change${if (changed == 1) "" else "s"})\n")
    sb.append("\u00A7\u00A7FILE_DIFF\u00A7\u00A7path=$path\n")
    body.forEach { sb.append(it).append('\n') }
    sb.append("\u00A7\u00A7END_DIFF\u00A7\u00A7\n")
    // Full new content for the optional "View full code" toggle — capped so a huge file doesn't
    // blow up the message/tool-trace payload (Rule 20 minimal-necessary-payload); the diff above
    // is already the useful part when a file is this large.
    if (newContent.length <= 20_000) {
        sb.append("\u00A7\u00A7FULL_CONTENT\u00A7\u00A7\n").append(newContent).append("\n\u00A7\u00A7END_FULL\u00A7\u00A7")
    }
    return sb.toString()
}

/** Phase 1 addition: file.list — lists a directory of the controlled workspace via the existing
 *  FileService (no duplicate/new file backend). */
class ListFilesTool(private val files:FileService):AITool{
    override val id="list_files";override val description="List files and folders inside a directory of the controlled project workspace.";override val risk=ToolRisk.READ_ONLY
    override val parameterHints=mapOf("path" to "Optional workspace-relative directory path; empty lists the workspace root")
    override val requiredParameters = emptySet<String>()
    override suspend fun execute(input:Map<String,String>):AIResult<ToolResult>{
        val path=input["path"]?.trim().orEmpty()
        val r=files.listDirectory(path)
        if(!r.isSuccess) return AIResult.Failure(AIError.Execution("List failed: ${r.error}"))
        val entries=r.value.orEmpty()
        val listing=entries.joinToString("\n"){ f-> "${if(f.kind==FileKind.FOLDER)"[DIR] " else ""}${f.path} (${f.sizeBytes}b)" }
        return AIResult.Success(ToolResult(listing.ifBlank{"(empty directory)"}))
    }
}

/** Phase 1 addition: terminal.run — executes a command through the existing TerminalService /
 *  EmbeddedShellBackend (allowlisted executables, workspace-scoped cwd, real timeout/cancel).
 *  A non-zero exit code, timeout, or cancellation is always surfaced as AIResult.Failure so a
 *  failed command is never reported as a successful one. */
class TerminalRunTool(private val terminal:TerminalService):AITool{
    override val id="run_terminal";override val description="Run a shell command in the sandboxed project terminal workspace.";override val risk=ToolRisk.EXECUTION
    override val parameterHints=mapOf("command" to "Shell command to execute (workspace-scoped, allowlisted executables only)")
    override suspend fun execute(input:Map<String,String>):AIResult<ToolResult>{
        val command=input["command"]?.trim().orEmpty()
        if(command.isBlank()) return AIResult.Failure(AIError.InvalidRequest("command is required"))
        val result=withContext(Dispatchers.IO){ terminal.execute(command) }
        val summary="Exit code: ${result.exitCode}\n${result.output}".trim()
        return when{
            result.cancelled -> AIResult.Failure(AIError.Execution("Command cancelled.\n$summary"))
            result.exitCode==124 -> AIResult.Failure(AIError.Execution("Command timed out.\n$summary"))
            result.exitCode==126 -> AIResult.Failure(AIError.Execution("Command blocked by security policy.\n$summary"))
            result.exitCode==127 -> AIResult.Failure(AIError.Execution("Command not found or failed to start.\n$summary"))
            result.exitCode!=0 -> AIResult.Failure(AIError.Execution("Command failed.\n$summary"))
            else -> AIResult.Success(ToolResult(summary,changed=true))
        }
    }
}


class WindowControlTool(private val manager: com.sa.aidesktop.core.window.WindowManager): AITool {
    override val id = "control_window"
    override val description = "Move, resize, minimize, maximize, restore, focus or close a desktop window."
    override val risk = ToolRisk.WRITE
    override suspend fun execute(input: Map<String, String>): AIResult<ToolResult> {
        val id = input["id"]?.trim().orEmpty()
        val action = input["action"]?.trim()?.lowercase().orEmpty()
        if (id.isBlank() || action.isBlank()) return AIResult.Failure(AIError.InvalidRequest("id and action are required"))
        val before = manager.windows.firstOrNull { it.id == id }
            ?: return AIResult.Failure(AIError.Execution("Window not found: $id"))
        if (before.protectedByTaskId != null) {
            return AIResult.Failure(AIError.ToolDenied("Window $id is protected by task ${before.protectedByTaskId}."))
        }
        when (action) {
            "move" -> {
                val dx = input["dx"]?.toFloatOrNull() ?: return AIResult.Failure(AIError.InvalidRequest("dx must be a number"))
                val dy = input["dy"]?.toFloatOrNull() ?: return AIResult.Failure(AIError.InvalidRequest("dy must be a number"))
                manager.move(id, dx, dy)
            }
            "resize" -> {
                val dw = input["dw"]?.toFloatOrNull() ?: return AIResult.Failure(AIError.InvalidRequest("dw must be a number"))
                val dh = input["dh"]?.toFloatOrNull() ?: return AIResult.Failure(AIError.InvalidRequest("dh must be a number"))
                manager.resize(id, com.sa.aidesktop.core.window.ResizeEdge.BOTTOM_RIGHT, dw, dh)
            }
            "minimize" -> manager.minimize(id)
            "maximize" -> manager.maximize(id)
            "restore" -> manager.restore(id)
            "focus" -> manager.focus(id)
            "close" -> manager.close(id)
            else -> return AIResult.Failure(AIError.InvalidRequest("Unsupported window action: $action"))
        }
        val after = manager.windows.firstOrNull { it.id == id }
        val succeeded = when (action) {
            "close" -> after == null
            "minimize" -> after?.state == com.sa.aidesktop.core.window.WindowState.MINIMIZED
            "maximize" -> after?.state == com.sa.aidesktop.core.window.WindowState.MAXIMIZED
            "restore" -> after?.state == com.sa.aidesktop.core.window.WindowState.NORMAL
            "focus" -> after?.focused == true
            "move", "resize" -> after != null && after != before
            else -> false
        }
        return if (succeeded) AIResult.Success(ToolResult("Window $id: $action", changed = true))
        else AIResult.Failure(AIError.Execution("Window action did not change the requested window state: $action"))
    }
}
