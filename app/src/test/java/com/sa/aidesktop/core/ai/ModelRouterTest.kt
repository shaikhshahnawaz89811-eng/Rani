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


private class FakeEngineSequence(private val results: List<GroqResult>) : GroqChatEngine {
    private var index = 0
    override suspend fun chat(prompt: String, settings: GroqSettings, systemPrompt: String?, tools: List<ToolDescriptor>): GroqResult {
        return results[(index++).coerceAtMost(results.lastIndex)]
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
        val files = InMemoryProjectFileService()
        files.write("src/main.py", "print('hello')")
        val registry = ToolRegistry(listOf(ReadFileTool(files)))
        val engine = FakeEngineSequence(listOf(
            GroqResult.Success(GroqChatResult(
                text = "",
                toolCalls = listOf(GroqToolCall("call_1", "read_file", mapOf("path" to "src/main.py")))
            )),
            GroqResult.Success(GroqChatResult("The file contains print('hello')."))
        ))
        val router = ModelRouter(engine, UnavailableOfflineAI(), hasApiKey = { true }, settingsProvider = { GroqSettings() }, toolRegistry = registry)
        val result = router.chat(AIRequest("read main.py")) as AIResult.Success
        assertEquals("The file contains print('hello').", result.value.text)
        assertTrue(result.value.toolRequests.isEmpty())
        assertEquals(RouterTier.ONLINE_GROQ, router.currentStatus().lastTier)
    }

    @Test fun unknownToolNameIsNotExecutedAndProviderCanRecover() = runBlocking {
        val engine = FakeEngineSequence(
            listOf(
                GroqResult.Success(
                    GroqChatResult(
                        text = "",
                        toolCalls = listOf(
                            GroqToolCall("call_1", "browser_click", mapOf("selector" to "#submit"))
                        )
                    )
                ),
                GroqResult.Success(GroqChatResult("I cannot execute that unregistered tool."))
            )
        )
        val router = ModelRouter(
            engine,
            UnavailableOfflineAI(),
            hasApiKey = { true },
            settingsProvider = { GroqSettings() },
            toolRegistry = registry()
        )
        val result = router.chat(AIRequest("click submit")) as AIResult.Success
        assertTrue(result.value.toolRequests.isEmpty())
        assertTrue(result.value.toolTrace.any { it.contains("NOT REGISTERED") })
    }
    @Test fun registeredReadOnlyToolResultIsFedBackThroughConversationLoop() = runBlocking {
        val files = InMemoryProjectFileService()
        files.write("hello.txt", "Rahul")
        val captured = mutableListOf<List<GroqMessage>>()
        val engine = object : GroqChatEngine {
            private var round = 0
            override suspend fun chat(
                prompt: String,
                settings: GroqSettings,
                systemPrompt: String?,
                tools: List<ToolDescriptor>
            ) = GroqResult.Success(GroqChatResult("unused"))

            override suspend fun chatConversation(
                messages: List<GroqMessage>,
                settings: GroqSettings,
                systemPrompt: String?,
                tools: List<ToolDescriptor>
            ): GroqResult {
                captured += messages
                return if (round++ == 0) {
                    GroqResult.Success(
                        GroqChatResult(
                            text = "",
                            toolCalls = listOf(
                                GroqToolCall("call_1", "read_file", mapOf("path" to "hello.txt"))
                            )
                        )
                    )
                } else {
                    GroqResult.Success(GroqChatResult("The file says Rahul."))
                }
            }
        }
        val router = ModelRouter(
            engine,
            UnavailableOfflineAI(),
            hasApiKey = { true },
            settingsProvider = { GroqSettings() },
            toolRegistry = ToolRegistry(listOf(ReadFileTool(files)))
        )
        val result = router.chat(AIRequest("read hello.txt")) as AIResult.Success
        assertEquals("The file says Rahul.", result.value.text)
        assertTrue(captured[1].any { it.role == "tool" && it.toolCallId == "call_1" && it.content.contains("Rahul") })
    }

}
