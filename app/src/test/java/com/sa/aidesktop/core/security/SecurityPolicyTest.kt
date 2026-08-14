package com.sa.aidesktop.core.security

import com.sa.aidesktop.core.ai.ToolRisk
import org.junit.Assert.*
import org.junit.Test

class SecurityPolicyTest {
    private val policy = DefaultSecurityPolicy("/workspace/MyProject")
    @Test fun readOnlyDoesNotNeedApproval() = assertFalse(policy.requiresApproval(ToolRisk.READ_ONLY))
    @Test fun writeNeedsApproval() = assertTrue(policy.requiresApproval(ToolRisk.WRITE))
    @Test fun workspacePathIsAccepted() = assertTrue(policy.isWorkspacePath("/workspace/MyProject/src/main.py"))
    @Test fun traversalPathIsRejected() = assertFalse(policy.isWorkspacePath("/workspace/MyProject/../Other/file"))
    @Test fun destructiveCommandIsRejected() = assertFalse(policy.canExecuteCommand("rm -rf /workspace/MyProject"))
    @Test fun shellOperatorsAreRejected() = assertFalse(policy.canExecuteCommand("echo ok && rm file"))
}
