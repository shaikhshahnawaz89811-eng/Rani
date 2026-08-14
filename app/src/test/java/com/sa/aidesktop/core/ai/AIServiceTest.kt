package com.sa.aidesktop.core.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AIServiceTest {
    @Test fun blankRequestIsRejected() = runBlocking {
        val result = OfflineDemoAI().chat(AIRequest("   "))
        assertTrue(result is AIResult.Failure)
    }

    @Test fun protectedActionsAreNotAutomaticallyExecuted() = runBlocking {
        val result = OfflineDemoAI().chat(AIRequest("delete this file")) as AIResult.Success
        assertTrue(result.value.text.contains("approval", ignoreCase = true))
        assertTrue(result.value.toolRequests.isEmpty())
    }

    @Test fun permissionGateProtectsWrites() {
        val gate = PermissionGate()
        assertFalse(gate.requiresApproval(ToolRisk.READ_ONLY))
        assertTrue(gate.requiresApproval(ToolRisk.WRITE))
        assertTrue(gate.requiresApproval(ToolRisk.GIT_SENSITIVE))
    }
}
