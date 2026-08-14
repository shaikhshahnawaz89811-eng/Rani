package com.sa.aidesktop.core

import com.sa.aidesktop.core.terminal.SafeTerminalService
import org.junit.Assert.*
import org.junit.Test

class TerminalServiceTest {
    @Test fun safeCommandsWorkAndUnknownCommandsAreBlocked() {
        val t = SafeTerminalService()
        assertEquals("/workspace/MyProject", t.execute("pwd").output)
        assertTrue(t.execute("ls").output.contains("src"))
        assertTrue(t.execute("rm -rf /").output.contains("blocked"))
        assertEquals(0, t.execute("cd src").exitCode)
        assertTrue(t.state().workingDirectory.endsWith("sa-workspace/src"))
        assertEquals(1, t.execute("cd ../../").exitCode)
    }
}
