package com.sa.aidesktop.core.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AIServiceTest {
    @Test fun blankRequestIsRejected() = runBlocking {
        val result = UnavailableOfflineAI().chat(AIRequest("   "))
        assertTrue(result is AIResult.Failure)
    }

    @Test fun unavailableOfflinePathNeverFabricatesAnAnswer() = runBlocking {
        val result = UnavailableOfflineAI().chat(AIRequest("delete this file"))
        assertTrue(result is AIResult.Failure)
        assertTrue((result as AIResult.Failure).error is AIError.ModelUnavailable)
    }

    @Test fun permissionGateProtectsWrites() {
        val gate = PermissionGate()
        assertFalse(gate.requiresApproval(ToolRisk.READ_ONLY))
        assertTrue(gate.requiresApproval(ToolRisk.WRITE))
        assertTrue(gate.requiresApproval(ToolRisk.GIT_SENSITIVE))
    }
}
