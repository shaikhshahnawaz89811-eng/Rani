package com.sa.aidesktop.core.ai.tools

import com.sa.aidesktop.core.ai.AIResult
import com.sa.aidesktop.core.files.InMemoryProjectFileService
import com.sa.aidesktop.core.terminal.EmbeddedTerminalService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CoreAIToolsTest {

    @Test fun listFilesReturnsRealDirectoryContents() = runBlocking {
        val tool = ListFilesTool(InMemoryProjectFileService())
        val result = tool.execute(mapOf("path" to "src")) as AIResult.Success
        assertTrue(result.value.output.contains("main.py"))
        assertTrue(result.value.output.contains("utils.py"))
        assertFalse(result.value.output.contains("README.md"))
    }

    @Test fun listFilesReportsNotFoundAsFailureNotEmptySuccess() = runBlocking {
        val tool = ListFilesTool(InMemoryProjectFileService())
        val result = tool.execute(mapOf("path" to "does/not/exist"))
        assertTrue(result is AIResult.Failure)
    }

    @Test fun terminalRunToolReturnsRealSuccessfulCommandOutput() = runBlocking {
        val workspace = Files.createTempDirectory("sa-test-ws").toFile()
        val terminal = EmbeddedTerminalService(workspace)
        val tool = TerminalRunTool(terminal)
        val result = tool.execute(mapOf("command" to "pwd")) as AIResult.Success
        assertTrue(result.value.output.contains("Exit code: 0"))
        assertTrue(result.value.changed)
    }

    @Test fun terminalRunToolTreatsFailedCommandAsFailureNotSuccess() = runBlocking {
        val workspace = Files.createTempDirectory("sa-test-ws2").toFile()
        val terminal = EmbeddedTerminalService(workspace)
        val tool = TerminalRunTool(terminal)
        // "cat" a file that does not exist -> nonzero real exit code from the real `cat` process.
        val result = tool.execute(mapOf("command" to "cat does-not-exist.txt"))
        assertTrue(result is AIResult.Failure)
    }

    @Test fun terminalRunToolBlocksDisallowedExecutable() = runBlocking {
        val workspace = Files.createTempDirectory("sa-test-ws3").toFile()
        val terminal = EmbeddedTerminalService(workspace)
        val tool = TerminalRunTool(terminal)
        val result = tool.execute(mapOf("command" to "curl http://example.com"))
        assertTrue(result is AIResult.Failure)
        assertTrue((result as AIResult.Failure).error.toString().contains("blocked", ignoreCase = true))
    }

    @Test fun terminalRunToolRejectsBlankCommand() = runBlocking {
        val workspace = Files.createTempDirectory("sa-test-ws4").toFile()
        val tool = TerminalRunTool(EmbeddedTerminalService(workspace))
        assertTrue(tool.execute(mapOf("command" to "  ")) is AIResult.Failure)
    }
    @Test fun calculatorUsesRealArithmeticWithoutCodeExecution() = runBlocking {
        val tool = CalculatorTool()
        val result = tool.execute(mapOf("expression" to "(25*4)+10/2")) as AIResult.Success
        assertEquals("(25*4)+10/2 = 105", result.value.output)
    }

    @Test fun calculatorRejectsDivisionByZero() = runBlocking {
        val result = CalculatorTool().execute(mapOf("expression" to "10/0"))
        assertTrue(result is AIResult.Failure)
    }

}
