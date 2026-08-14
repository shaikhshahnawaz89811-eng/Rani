package com.sa.aidesktop.core.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlamaEngineTest {
    @Test fun missingModelIsReportedWithoutFabricatingInference() = runBlocking {
        val engine = LocalLlamaEngine(modelPathProvider = { "/definitely/missing/model.gguf" })
        val result = engine.chat(AIRequest("hello"))
        assertTrue(result is AIResult.Failure)
        assertTrue((result as AIResult.Failure).error is AIError.ModelUnavailable)
        assertEquals(LocalModelState.UNAVAILABLE, engine.status().state)
    }

    @Test fun blankModelPathIsNotReady() = runBlocking {
        val engine = LocalLlamaEngine(modelPathProvider = { "" })
        val status = engine.load()
        assertEquals(LocalModelState.NOT_CONFIGURED, status.state)
    }

    @Test fun configIsBoundedForMobileSafety() {
        val config = LocalModelConfig(contextSize = 99999, threads = 99, maxOutputTokens = 99999)
        assertEquals(4096, config.toLlamaConfig().contextSize)
        assertEquals(8, config.toLlamaConfig().threads)
    }
}
