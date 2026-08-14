package com.sa.aidesktop.core.website

import com.sa.aidesktop.core.ai.*
import com.sa.aidesktop.core.browser.BrowserResult

private fun <T> BrowserResult<T>.err(action:String):AIResult<ToolResult> = when(this){ is BrowserResult.Success->AIResult.Success(ToolResult("$action succeeded: ${value.toString().take(18_000)}")); is BrowserResult.Failure->AIResult.Failure(AIError.Execution("$action failed: $message")) }

abstract class AIWebTool(protected val service:AIWebService):AITool {
    protected fun id(input:Map<String,String>)=input["window_id"]?.takeIf{it.isNotBlank()}
}

class AIWebDetectTool(s:AIWebService):AIWebTool(s){
    override val id="ai_web.detect"; override val description="Inspect the live page and detect an AI-web composer, send/upload controls, login and generation state."; override val risk=ToolRisk.READ_ONLY
    override val parameterHints=mapOf("window_id" to "Browser window id")
    override suspend fun execute(input:Map<String,String>)=service.detect(id(input)?:return AIResult.Failure(AIError.InvalidRequest("window_id is required"))).let{r-> when(r){is BrowserResult.Failure->AIResult.Failure(AIError.Execution(r.message));is BrowserResult.Success->AIResult.Success(ToolResult("STATE=${r.value.state}\nURL=${r.value.page.url}\nTITLE=${r.value.page.title}\nCOMPOSER=${r.value.composer?.ref}\nSEND=${r.value.sendControl?.ref}\nUPLOAD=${r.value.uploadControl?.ref}\nRESPONSE=${r.value.responseText.take(8_000)}"))}}
}
class AIWebInspectTool(s:AIWebService):AIWebTool(s){
    override val id="ai_web.inspect"; override val description="Inspect the real current AI website page without performing an action."; override val risk=ToolRisk.READ_ONLY
    override val parameterHints=mapOf("window_id" to "Browser window id")
    override suspend fun execute(input:Map<String,String>)=service.inspect(id(input)?:return AIResult.Failure(AIError.InvalidRequest("window_id is required"))).let{r->when(r){is BrowserResult.Failure->AIResult.Failure(AIError.Execution(r.message));is BrowserResult.Success->AIResult.Success(ToolResult("STATE=${r.value.state}\nCOMPOSER=${r.value.composer?.ref}\nSEND=${r.value.sendControl?.ref}\nUPLOAD=${r.value.uploadControl?.ref}\nURL=${r.value.page.url}\nTITLE=${r.value.page.title}\nTEXT=${r.value.page.visibleText.take(12_000)}"))}}}
}
class AIWebTypeTool(s:AIWebService):AIWebTool(s){
    override val id="ai_web.type_message"; override val description="Type a real message into the currently inspected AI website composer."; override val risk=ToolRisk.WRITE
    override val parameterHints=mapOf("window_id" to "Browser window id","ref" to "Current composer ref from ai_web.inspect","text" to "Message text")
    override suspend fun execute(input:Map<String,String>):AIResult<ToolResult>{val id=id(input)?:return AIResult.Failure(AIError.InvalidRequest("window_id is required"));val ref=input["ref"]?.trim().orEmpty();if(ref.isBlank())return AIResult.Failure(AIError.InvalidRequest("ref is required"));return service.typeMessage(id,ref,input["text"].orEmpty()).err("ai_web.type_message")}
}
class AIWebSendTool(s:AIWebService):AIWebTool(s){
    override val id="ai_web.send_message"; override val description="Click the real inspected AI website send control."; override val risk=ToolRisk.WRITE
    override val parameterHints=mapOf("window_id" to "Browser window id","ref" to "Current send control ref from ai_web.inspect")
    override suspend fun execute(input:Map<String,String>):AIResult<ToolResult>{val id=id(input)?:return AIResult.Failure(AIError.InvalidRequest("window_id is required"));val ref=input["ref"]?.trim().orEmpty();if(ref.isBlank())return AIResult.Failure(AIError.InvalidRequest("ref is required"));return service.sendMessage(id,ref).err("ai_web.send_message")}
}
class AIWebWaitTool(s:AIWebService):AIWebTool(s){
    override val id="ai_web.wait_response"; override val description="Wait for a real AI-web generation to finish and return the observed page state."; override val risk=ToolRisk.READ_ONLY
    override val parameterHints=mapOf("window_id" to "Browser window id","timeout_ms" to "Wait timeout")
    override suspend fun execute(input:Map<String,String>):AIResult<ToolResult>{val id=id(input)?:return AIResult.Failure(AIError.InvalidRequest("window_id is required"));return service.waitForResponse(id,input["timeout_ms"]?.toLongOrNull()?:60_000L).let{r->when(r){is BrowserResult.Failure->AIResult.Failure(AIError.Execution(r.message));is BrowserResult.Success->AIResult.Success(ToolResult("STATE=${r.value.state}\nRESPONSE=${r.value.responseText.take(16_000)}"))}}}
}
class AIWebReadTool(s:AIWebService):AIWebTool(s){
    override val id="ai_web.read_response"; override val description="Read the real visible AI-web response from the current page."; override val risk=ToolRisk.READ_ONLY
    override val parameterHints=mapOf("window_id" to "Browser window id")
    override suspend fun execute(input:Map<String,String>):AIResult<ToolResult>{val id=id(input)?:return AIResult.Failure(AIError.InvalidRequest("window_id is required"));return service.inspect(id).let{r->when(r){is BrowserResult.Failure->AIResult.Failure(AIError.Execution(r.message));is BrowserResult.Success->if(r.value.state==AIWebState.GENERATING)AIResult.Failure(AIError.Execution("AI is still generating; response is not complete."))else AIResult.Success(ToolResult(r.value.responseText))}}}
}
class AIWebUploadTool(s:AIWebService):AIWebTool(s){
    override val id="ai_web.upload_file"; override val description="Upload a real local file through the current inspected AI website file input."; override val risk=ToolRisk.WRITE
    override val parameterHints=mapOf("window_id" to "Browser window id","ref" to "Current upload input ref","file_path" to "Readable local file path")
    override suspend fun execute(input:Map<String,String>):AIResult<ToolResult>{val id=id(input)?:return AIResult.Failure(AIError.InvalidRequest("window_id is required"));val ref=input["ref"].orEmpty();val path=input["file_path"].orEmpty();if(ref.isBlank()||path.isBlank())return AIResult.Failure(AIError.InvalidRequest("ref and file_path are required"));val f=java.io.File(path);if(!f.isFile||!f.canRead())return AIResult.Failure(AIError.Execution("File is not readable: $path"));return service.upload(id,ref,path).err("ai_web.upload_file")}
}
