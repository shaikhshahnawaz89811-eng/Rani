package com.sa.aidesktop.core.ai

data class AIMessage(val text: String, val fromUser: Boolean, val time: String)
data class AIProfile(val id:String,val name:String,val personality:String,val voiceId:String?,val language:String,val avatar:String?)
data class ProjectContext(val relevantFiles:List<String> = emptyList(),val projectStructure:String = "",val selectedCode:String = "",val compilerErrors:List<String> = emptyList(),val testResults:List<String> = emptyList(),val gitChanges:List<String> = emptyList())
data class AIRequest(val prompt:String,val context:ProjectContext = ProjectContext())
data class AIResponse(val text:String,val toolRequests:List<ToolRequest> = emptyList())
sealed interface AIError { data class InvalidRequest(val message:String):AIError; data class ModelUnavailable(val message:String):AIError; data class ToolDenied(val message:String):AIError; data class Execution(val message:String):AIError }
sealed interface AIResult<out T> { data class Success<T>(val value:T):AIResult<T>; data class Failure(val error:AIError):AIResult<Nothing> }
interface LocalModelEngine { suspend fun generate(request:AIRequest):AIResult<AIResponse> }
interface ModelAdapter { suspend fun generate(request:AIRequest):AIResult<AIResponse> }
interface AIService {
    suspend fun chat(request:AIRequest):AIResult<AIResponse>
    suspend fun explainCode(code:String,context:ProjectContext=ProjectContext()):AIResult<AIResponse>
    suspend fun generateCode(prompt:String,context:ProjectContext=ProjectContext()):AIResult<AIResponse>
    suspend fun analyzeError(error:String,context:ProjectContext=ProjectContext()):AIResult<AIResponse>
    suspend fun suggestFix(error:String,context:ProjectContext=ProjectContext()):AIResult<AIResponse>
    suspend fun modifyFile(path:String,instruction:String,context:ProjectContext=ProjectContext()):AIResult<AIResponse>
    suspend fun understandProject(context:ProjectContext):AIResult<AIResponse>
    suspend fun runDeveloperTask(task:String,context:ProjectContext=ProjectContext()):AIResult<AIResponse>
}
enum class ToolRisk { READ_ONLY, WRITE, EXECUTION, GIT_SENSITIVE }
data class ToolRequest(val toolId:String,val input:Map<String,String>,val risk:ToolRisk)
data class ToolResult(val output:String,val changed:Boolean=false)
interface AITool { val id:String; val description:String; val risk:ToolRisk; suspend fun execute(input:Map<String,String>):AIResult<ToolResult> }
class ToolRegistry(private val tools:List<AITool>) { fun find(id:String)=tools.firstOrNull{it.id==id}; fun all()=tools.toList() }
class PermissionGate { fun requiresApproval(risk:ToolRisk)=risk!=ToolRisk.READ_ONLY }

class ToolExecutionGateway(private val registry:ToolRegistry,private val gate:PermissionGate=PermissionGate()) {
    suspend fun execute(request:ToolRequest,approved:Boolean=false):AIResult<ToolResult>{
        val tool=registry.find(request.toolId)?:return AIResult.Failure(AIError.ToolDenied("Unknown tool: ${request.toolId}"))
        if(tool.risk!=request.risk)return AIResult.Failure(AIError.ToolDenied("Tool risk mismatch"))
        if(gate.requiresApproval(tool.risk)&&!approved)return AIResult.Failure(AIError.ToolDenied("Approval required before ${tool.id}"))
        return tool.execute(request.input)
    }
}

class DemoModelAdapter(private val engine: LocalModelEngine) : ModelAdapter {
    override suspend fun generate(request: AIRequest): AIResult<AIResponse> = engine.generate(request)
}

class OfflineDemoAI : AIService, LocalModelEngine {
    override suspend fun chat(request:AIRequest):AIResult<AIResponse>{
        val p=request.prompt.trim(); if(p.isBlank())return AIResult.Failure(AIError.InvalidRequest("Message cannot be empty."))
        val lower=p.lowercase()
        val windowMatch=Regex("(?:move|resize|maximize|minimize|restore|focus|close)\\s+(browser(?:-\\d+)?|developer|terminal|git|files|ai|settings)", RegexOption.IGNORE_CASE).find(lower)
        val windowRequests=windowMatch?.let { m ->
            val action=m.groupValues[0].substringBefore(' ').lowercase()
            val target=m.groupValues[1].lowercase().let { if (it=="ai") "ai" else it }
            val dx=Regex("(?:right|left)\\s+(\\d+)").find(lower)?.groupValues?.get(1)?.toFloatOrNull()?.let { if (lower.contains("left")) -it else it } ?: 0f
            val dy=Regex("(?:down|up)\\s+(\\d+)").find(lower)?.groupValues?.get(1)?.toFloatOrNull()?.let { if (lower.contains("up")) -it else it } ?: 0f
            val dw=Regex("(?:width|w)\\s*([+-]\\d+)").find(lower)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            val dh=Regex("(?:height|h)\\s*([+-]\\d+)").find(lower)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            ToolRequest("control_window", mapOf("id" to target, "action" to action, "dx" to dx.toString(), "dy" to dy.toString(), "dw" to dw.toString(), "dh" to dh.toString()), ToolRisk.WRITE)
        }
        val answer=when{ windowRequests!=null->"Window action samajh liya. Apply karne se pehle approval required hai."; "error" in lower->"Error analysis ready hai. Build, test aur selected-code context ko need-based analyze kiya ja sakta hai."; "explain" in lower||"code" in lower->"Code explanation ke liye selected code aur relevant project context use kiya jayega."; "create file" in lower||"modify file" in lower||"delete" in lower->"Ye protected file action hai. Proposed change review aur approval ke baad hi apply hoga."; else->"Main Sara hoon. Offline model adapter architecture ready hai; authorized developer tools approval boundaries ke andar use kiye ja sakte hain."}
        return AIResult.Success(AIResponse(answer, windowRequests?.let { listOf(it) }.orEmpty()))
    }
    override suspend fun explainCode(code:String,context:ProjectContext)=chat(AIRequest("Explain code",context.copy(selectedCode=code)))
    override suspend fun generateCode(prompt:String,context:ProjectContext)=chat(AIRequest("Generate code: $prompt",context))
    override suspend fun analyzeError(error:String,context:ProjectContext)=chat(AIRequest("Analyze error",context.copy(compilerErrors=context.compilerErrors+error)))
    override suspend fun suggestFix(error:String,context:ProjectContext)=chat(AIRequest("Suggest fix for: $error",context.copy(compilerErrors=context.compilerErrors+error)))
    override suspend fun modifyFile(path:String,instruction:String,context:ProjectContext)=chat(AIRequest("Modify $path: $instruction",context.copy(relevantFiles=(context.relevantFiles+path).distinct())))
    override suspend fun understandProject(context:ProjectContext)=chat(AIRequest("Understand project",context))
    override suspend fun runDeveloperTask(task:String,context:ProjectContext)=chat(AIRequest("Developer task: $task",context))
    override suspend fun generate(request:AIRequest)=chat(request)
}
