package com.sa.aidesktop.core.ai

import com.sa.aidesktop.core.ai.tools.ReadFileTool
import com.sa.aidesktop.core.ai.tools.WriteFileTool
import com.sa.aidesktop.core.files.InMemoryProjectFileService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scripted fake standing in for the local GGUF model, same role FakeEngine plays for Groq in
 *  ModelRouterTest — returns queued responses in order so the loop's real branching logic can be
 *  tested without a real llama.cpp runtime. */
private class ScriptedOfflineAI(private val responses: List<String>) : AIService {
    var callCount = 0
    override suspend fun chat(request: AIRequest): AIResult<AIResponse> {
        val text = responses[callCount.coerceAtMost(responses.lastIndex)]
        callCount++
        return AIResult.Success(AIResponse(text = text))
    }
    override suspend fun explainCode(code: String, context: ProjectContext) = chat(AIRequest(code, context))
    override suspend fun generateCode(prompt: String, context: ProjectContext) = chat(AIRequest(prompt, context))
    override suspend fun analyzeError(error: String, context: ProjectContext) = chat(AIRequest(error, context))
    override suspend fun suggestFix(error: String, context: ProjectContext) = chat(AIRequest(error, context))
    override suspend fun modifyFile(path: String, instruction: String, context: ProjectContext) = chat(AIRequest(instruction, context))
    override suspend fun understandProject(context: ProjectContext) = chat(AIRequest("", context))
    override suspend fun runDeveloperTask(task: String, context: ProjectContext) = chat(AIRequest(task, context))
}

class LocalToolCallLoopTest {
    @Test fun plainTextAnswerNeedsNoToolAndReturnsAsIs() = runBlocking {
        val offline = ScriptedOfflineAI(listOf("2 + 2 is 4."))
        val registry = ToolRegistry(listOf(ReadFileTool(InMemoryProjectFileService())))
        val loop = LocalToolCallLoop(offline, registry)
        val result = loop.run(AIRequest("what is 2+2 conceptually")) as AIResult.Success
        assertEquals("2 + 2 is 4.", result.value.text)
        assertEquals(1, offline.callCount)
    }

    @Test fun readOnlyToolCallIsExecutedThenLoopedBackForAFinalAnswer() = runBlocking {
        val files = InMemoryProjectFileService()
        files.write("notes.txt", "hello from disk")
        val registry = ToolRegistry(listOf(ReadFileTool(files)))
        val offline = ScriptedOfflineAI(listOf(
            "TOOL_CALL: {\"tool\":\"read_file\",\"args\":{\"path\":\"notes.txt\"}}",
            "The file contains: hello from disk"
        ))
        val loop = LocalToolCallLoop(offline, registry)
        val result = loop.run(AIRequest("read notes.txt")) as AIResult.Success
        assertEquals("The file contains: hello from disk", result.value.text)
        assertEquals(2, offline.callCount) // real second round, not a fabricated single-shot answer
    }

    @Test fun writeToolCallStopsForRealApprovalInsteadOfAutoExecuting() = runBlocking {
        val files = InMemoryProjectFileService()
        val registry = ToolRegistry(listOf(WriteFileTool(files)))
        val offline = ScriptedOfflineAI(listOf(
            "TOOL_CALL: {\"tool\":\"write_file\",\"args\":{\"path\":\"calc.py\",\"content\":\"print(1+1)\"}}"
        ))
        val loop = LocalToolCallLoop(offline, registry)
        val result = loop.run(AIRequest("calculator banao")) as AIResult.Success
        assertEquals(1, result.value.toolRequests.size)
        assertEquals("write_file", result.value.toolRequests.first().toolId)
        assertEquals(ToolRisk.WRITE, result.value.toolRequests.first().risk)
        // Rule 10: the file must NOT exist yet — only an approved gateway call may create it.
        assertTrue(!files.read("calc.py").isSuccess)
    }

    @Test fun unknownToolNameDoesNotCrashAndReportsItHonestlyToTheModel() = runBlocking {
        val registry = ToolRegistry(listOf(ReadFileTool(InMemoryProjectFileService())))
        val offline = ScriptedOfflineAI(listOf(
            "TOOL_CALL: {\"tool\":\"delete_everything\",\"args\":{}}",
            "Sorry, I don't have that tool. Here is a plain-text answer instead."
        ))
        val loop = LocalToolCallLoop(offline, registry)
        val result = loop.run(AIRequest("do something odd")) as AIResult.Success
        assertEquals("Sorry, I don't have that tool. Here is a plain-text answer instead.", result.value.text)
    }

    @Test fun loopIsBoundedAndNeverRunsForever() = runBlocking {
        val registry = ToolRegistry(listOf(ReadFileTool(InMemoryProjectFileService())))
        // Always asks to call a tool that will never resolve to a final answer.
        val offline = ScriptedOfflineAI(listOf("TOOL_CALL: {\"tool\":\"read_file\",\"args\":{\"path\":\"missing.txt\"}}"))
        val loop = LocalToolCallLoop(offline, registry, maxRounds = 3)
        val result = loop.run(AIRequest("read a file that keeps failing")) as AIResult.Success
        assertTrue(result.value.text.contains("Stopped after 3"))
        assertEquals(3, offline.callCount)
    }
}
