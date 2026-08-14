package com.sa.aidesktop.core.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalModelManagerTest {
    @Test fun defaultModelNameIsStable() {
        assertEquals("SmolLM2-135M-Instruct-Q4_K_M.gguf", LocalModelManager.DEFAULT_MODEL_FILE)
    }
}
