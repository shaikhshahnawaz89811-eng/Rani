package com.sa.aidesktop.core.ai.tools

import com.sa.aidesktop.core.ai.*
import com.sa.aidesktop.core.files.*

class ReadFileTool(private val files:FileService):AITool{
    override val id="read_file";override val description="Read a file from the controlled project workspace.";override val risk=ToolRisk.READ_ONLY
    override suspend fun execute(input:Map<String,String>):AIResult<ToolResult>{val path=input["path"]?.trim().orEmpty();if(path.isBlank())return AIResult.Failure(AIError.InvalidRequest("path is required"));val r=files.read(path); return if(r.isSuccess) AIResult.Success(ToolResult(r.value.orEmpty())) else AIResult.Failure(AIError.Execution("Read failed: ${r.error}"))}
}
class SearchFileTool(private val files:FileService):AITool{
    override val id="search_files";override val description="Search project files by name.";override val risk=ToolRisk.READ_ONLY
    override suspend fun execute(input:Map<String,String>):AIResult<ToolResult>{val q=input["query"]?.trim().orEmpty();if(q.isBlank())return AIResult.Failure(AIError.InvalidRequest("query is required"));val r=files.search(q);return if(r.isSuccess)AIResult.Success(ToolResult(r.value.orEmpty().joinToString("\n"){it.path}))else AIResult.Failure(AIError.Execution("Search failed: ${r.error}"))}
}
class WriteFileTool(private val files:FileService):AITool{
    override val id="write_file";override val description="Write a controlled workspace file; approval is enforced by ToolExecutionGateway.";override val risk=ToolRisk.WRITE
    override suspend fun execute(input:Map<String,String>):AIResult<ToolResult>{val path=input["path"]?.trim().orEmpty();val content=input["content"]?:return AIResult.Failure(AIError.InvalidRequest("content is required"));if(path.isBlank())return AIResult.Failure(AIError.InvalidRequest("path is required"));val r=files.write(path,content);return if(r.isSuccess)AIResult.Success(ToolResult("Updated $path",true))else AIResult.Failure(AIError.Execution("Write failed: ${r.error}"))}
}


class WindowControlTool(private val manager: com.sa.aidesktop.core.window.WindowManager): AITool {
    override val id = "control_window"
    override val description = "Move, resize, minimize, maximize, restore, focus or close a desktop window."
    override val risk = ToolRisk.WRITE
    override suspend fun execute(input: Map<String, String>): AIResult<ToolResult> {
        val id = input["id"]?.trim().orEmpty()
        val action = input["action"]?.trim()?.lowercase().orEmpty()
        if (id.isBlank() || action.isBlank()) return AIResult.Failure(AIError.InvalidRequest("id and action are required"))
        when (action) {
            "move" -> manager.move(id, input["dx"]?.toFloatOrNull() ?: 0f, input["dy"]?.toFloatOrNull() ?: 0f)
            "resize" -> manager.resize(id, com.sa.aidesktop.core.window.ResizeEdge.BOTTOM_RIGHT, input["dw"]?.toFloatOrNull() ?: 0f, input["dh"]?.toFloatOrNull() ?: 0f)
            "minimize" -> manager.minimize(id)
            "maximize" -> manager.maximize(id)
            "restore" -> manager.restore(id)
            "focus" -> manager.focus(id)
            "close" -> manager.close(id)
            else -> return AIResult.Failure(AIError.InvalidRequest("Unsupported window action: $action"))
        }
        return AIResult.Success(ToolResult("Window $id: $action", changed = true))
    }
}
