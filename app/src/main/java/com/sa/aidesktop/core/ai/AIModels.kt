package com.sa.aidesktop.core.ai

data class AIMessage(val text: String, val fromUser: Boolean, val time: String)
data class AIProfile(val id:String,val name:String,val personality:String,val voiceId:String?,val language:String,val avatar:String?)
data class ProjectContext(val relevantFiles:List<String> = emptyList(),val projectStructure:String = "",val selectedCode:String = "",val compilerErrors:List<String> = emptyList(),val testResults:List<String> = emptyList(),val gitChanges:List<String> = emptyList())
data class AIConversationMessage(val role:String,val text:String)
data class AIRequest(
    val prompt:String,
    val context:ProjectContext = ProjectContext(),
    val history:List<AIConversationMessage> = emptyList()
)
data class AIResponse(
    val text:String,
    val toolRequests:List<ToolRequest> = emptyList(),
    val toolTrace:List<String> = emptyList()
)
sealed interface AIError { data class InvalidRequest(val message:String):AIError; data class ModelUnavailable(val message:String):AIError; data class ToolDenied(val message:String):AIError; data class Execution(val message:String):AIError }

/** Human-readable text for an [AIError]. The real message text is preserved verbatim — this only
 *  strips the raw `ModelUnavailable(message=...)` Kotlin data-class wrapper so chat UI never shows
 *  it to the user. No error detail is invented or hidden. */
fun AIError.toDisplayMessage(): String = when (this) {
    is AIError.InvalidRequest -> message
    is AIError.ModelUnavailable -> message
    is AIError.ToolDenied -> message
    is AIError.Execution -> message
}
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
interface AITool { val id:String; val description:String; val risk:ToolRisk; val parameterHints:Map<String,String> get()=emptyMap(); val requiredParameters:Set<String> get()=parameterHints.keys; suspend fun execute(input:Map<String,String>):AIResult<ToolResult> }
class ToolRegistry(initialTools:List<AITool>) {
    private val tools = initialTools.toMutableList()

    @Synchronized
    fun register(tool:AITool) {
        if (tools.none { it.id == tool.id }) tools.add(tool)
    }

    @Synchronized
    fun find(id:String)=tools.firstOrNull{it.id==id}

    @Synchronized
    fun all()=tools.toList()
}
class PermissionGate { fun requiresApproval(risk:ToolRisk)=risk!=ToolRisk.READ_ONLY }

class ToolExecutionGateway(private val registry:ToolRegistry,private val gate:PermissionGate=PermissionGate()) {
    suspend fun execute(request:ToolRequest,approved:Boolean=false):AIResult<ToolResult>{
        val tool=registry.find(request.toolId)?:return AIResult.Failure(AIError.ToolDenied("Unknown tool: ${request.toolId}"))
        if(tool.risk!=request.risk)return AIResult.Failure(AIError.ToolDenied("Tool risk mismatch"))
        if(gate.requiresApproval(tool.risk)&&!approved)return AIResult.Failure(AIError.ToolDenied("Approval required before ${tool.id}"))
        return tool.execute(request.input)
    }
}

/** Test/architecture adapter that deliberately reports offline AI as unavailable.
 * Production uses LocalLlamaEngine when a genuine GGUF model is configured.
 */
class UnavailableOfflineAI : AIService, LocalModelEngine {
    private fun unavailable(): AIResult<AIResponse> = AIResult.Failure(
        AIError.ModelUnavailable("No real offline model/runtime is packaged or configured in this build.")
    )
    override suspend fun chat(request: AIRequest) = unavailable()
    override suspend fun explainCode(code:String,context:ProjectContext)=unavailable()
    override suspend fun generateCode(prompt:String,context:ProjectContext)=unavailable()
    override suspend fun analyzeError(error:String,context:ProjectContext)=unavailable()
    override suspend fun suggestFix(error:String,context:ProjectContext)=unavailable()
    override suspend fun modifyFile(path:String,instruction:String,context:ProjectContext)=unavailable()
    override suspend fun understandProject(context:ProjectContext)=unavailable()
    override suspend fun runDeveloperTask(task:String,context:ProjectContext)=unavailable()
    override suspend fun generate(request:AIRequest)=unavailable()
}
