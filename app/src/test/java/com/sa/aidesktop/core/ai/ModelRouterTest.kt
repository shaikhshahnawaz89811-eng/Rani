package com.sa.aidesktop.core.ai

import com.sa.aidesktop.core.ai.tools.ReadFileTool
import com.sa.aidesktop.core.files.InMemoryProjectFileService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Fake engine — stands in for GroqClient so routing logic is tested without real network,
 *  using a deterministic test double so routing logic can be tested without network access. */
private class FakeEngine(private val result: GroqResult) : GroqChatEngine {
    var callCount = 0
    override suspend fun chat(prompt: String, settings: GroqSettings, systemPrompt: String?, tools: List<ToolDescriptor>): GroqResult {
        callCount++
        return result
    }
}

class ModelRouterTest {
    private fun registry() = ToolRegistry(listOf(ReadFileTool(InMemoryProjectFileService())))

    @Test fun blankRequestIsRejectedWithoutCallingEngine() = runBlocking {
        val engine = FakeEngine(GroqResult.Success(GroqChatResult("unused")))
        val router = ModelRouter(engine, UnavailableOfflineAI(), hasApiKey = { true }, settingsProvider = { GroqSettings() }, toolRegistry = registry())
        val result = router.chat(AIRequest("   "))
        assertTrue(result is AIResult.Failure)
        assertEquals(0, engine.callCount)
    }

    @Test fun noApiKeyUsesLocalPathAndReportsUnavailableWhenNoModelExists() = runBlocking {
        val engine = FakeEngine(GroqResult.Success(GroqChatResult("should not be used")))
        val offline = UnavailableOfflineAI()
        val router = ModelRouter(engine, offline, hasApiKey = { false }, settingsProvider = { GroqSettings() }, toolRegistry = registry())
        val result = router.chat(AIRequest("hello"))
        assertTrue(result is AIResult.Failure)
        assertEquals(0, engine.callCount)
        assertEquals(RouterTier.OFFLINE_LOCAL_UNAVAILABLE, router.currentStatus().lastTier)
    }

    @Test fun successfulGroqCallIsUsedAsIs() = runBlocking {
        val engine = FakeEngine(GroqResult.Success(GroqChatResult("real groq answer")))
        val router = ModelRouter(engine, UnavailableOfflineAI(), hasApiKey = { true }, settingsProvider = { GroqSettings() }, toolRegistry = registry())
        val result = router.chat(AIRequest("hello")) as AIResult.Success
        assertEquals("real groq answer", result.value.text)
        assertEquals(RouterTier.ONLINE_GROQ, router.currentStatus().lastTier)
        assertNull(router.currentStatus().lastError)
    }

    @Test fun groqFailureFallsBackToOfflineAndRecordsRealError() = runBlocking {
        val engine = FakeEngine(GroqResult.Failure(GroqError.Timeout("Groq request timed out after 30000ms")))
        val router = ModelRouter(engine, UnavailableOfflineAI(), hasApiKey = { true }, settingsProvider = { GroqSettings() }, toolRegistry = registry())
        val result = router.chat(AIRequest("hello"))
        assertTrue(result is AIResult.Failure)
        assertEquals(RouterTier.OFFLINE_LOCAL_UNAVAILABLE, router.currentStatus().lastTier)
        assertTrue(router.currentStatus().lastError!!.contains("timed out"))
        assertEquals(1, router.currentStatus().consecutiveOnlineFailures)
    }

    @Test fun toolCallFromGroqIsMappedToARealRegisteredToolRequest() = runBlocking {
        val engine = FakeEngine(GroqResult.Success(GroqChatResult(
            text = "",
            toolCalls = listOf(GroqToolCall("call_1", "read_file", mapOf("path" to "src/main.py")))
        )))
        val router = ModelRouter(engine, UnavailableOfflineAI(), hasApiKey = { true }, settingsProvider = { GroqSettings() }, toolRegistry = registry())
        val result = router.chat(AIRequest("read main.py")) as AIResult.Success
        val toolRequest = result.value.toolRequests.single()
        assertEquals("read_file", toolRequest.toolId)
        assertEquals("src/main.py", toolRequest.input["path"])
        assertEquals(ToolRisk.READ_ONLY, toolRequest.risk)
    }

    @Test fun unknownToolNameFromGroqIsDroppedNotFabricated() = runBlocking {
        val engine = FakeEngine(GroqResult.Success(GroqChatResult(
            text = "",
            toolCalls = listOf(GroqToolCall("call_1", "browser_click", mapOf("selector" to "#submit")))
        )))
        val router = ModelRouter(engine, UnavailableOfflineAI(), hasApiKey = { true }, settingsProvider = { GroqSettings() }, toolRegistry = registry())
        val result = router.chat(AIRequest("click submit")) as AIResult.Success
        assertTrue(result.value.toolRequests.isEmpty())
    }
}
