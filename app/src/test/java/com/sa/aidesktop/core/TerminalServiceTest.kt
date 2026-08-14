package com.sa.aidesktop.core

import com.sa.aidesktop.core.terminal.EmbeddedTerminalService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TerminalServiceTest {
    @Test fun realEmbeddedShellExecutesAllowedCommandsAndBlocksShellOperators() {
        val root = Files.createTempDirectory("sa-terminal-test").toFile()
        root.resolve("src").mkdirs()
        try {
            val t = EmbeddedTerminalService(root)
            assertEquals(root.canonicalPath, t.execute("pwd").output)
            assertEquals(0, t.execute("cd src").exitCode)
            assertTrue(t.execute("rm -rf /").output.contains("blocked", ignoreCase = true))
            assertEquals(126, t.execute("echo ok && echo bad").exitCode)
            assertEquals(1, t.execute("cd ../../").exitCode)
        } finally {
            root.deleteRecursively()
        }
    }
}
