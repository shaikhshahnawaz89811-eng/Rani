package com.sa.aidesktop.core.python

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sa.aidesktop.core.terminal.EmbeddedShellBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs on a real device/emulator (unlike app/src/test, which runs on the local JVM and cannot
 * start the embedded interpreter). This is the genuine end-to-end proof that `python main.py`
 * executes for real inside the app, with no Termux and no on-device python binary involved.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddedPythonEngineInstrumentedTest {

    @Test
    fun runsARealMainPyScriptAndCapturesItsOutput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        EmbeddedPythonEngine.ensureStarted(context)
        assertTrue(EmbeddedPythonEngine.isAvailable)

        val workspace = context.filesDir.resolve("embedded-python-test").apply { mkdirs() }
        File(workspace, "main.py").writeText(
            """
            import sys
            if __name__ == "__main__":
                print("hello from embedded python")
                print("argv0=" + sys.argv[0])
            """.trimIndent()
        )

        val result = EmbeddedPythonEngine.runFile(File(workspace, "main.py").path, workspace.path)

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("hello from embedded python"))
        assertTrue(result.output.contains("argv0=main.py"))
    }

    @Test
    fun terminalRunsPythonMainPyThroughTheSameAllowlistedCommandPath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        EmbeddedPythonEngine.ensureStarted(context)

        val workspace = context.filesDir.resolve("embedded-python-terminal-test").apply { mkdirs() }
        File(workspace, "main.py").writeText("print(2 + 2)")

        val shell = EmbeddedShellBackend(workspace)
        val result = shell.execute("python main.py", workspace.path)

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("4"))
    }

    @Test
    fun uncaughtPythonExceptionIsReportedWithNonZeroExitCode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        EmbeddedPythonEngine.ensureStarted(context)

        val workspace = context.filesDir.resolve("embedded-python-error-test").apply { mkdirs() }
        File(workspace, "broken.py").writeText("raise ValueError('boom')")

        val result = EmbeddedPythonEngine.runFile(File(workspace, "broken.py").path, workspace.path)

        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("ValueError"))
    }

    @Test
    fun scriptInASubdirectoryRunsWithCorrectArgv0() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        EmbeddedPythonEngine.ensureStarted(context)

        val workspace = context.filesDir.resolve("embedded-python-nested-test").apply { mkdirs() }
        val sub = File(workspace, "sub").apply { mkdirs() }
        File(sub, "nested.py").writeText("import sys\nprint('nested argv0=' + sys.argv[0])")

        // Working directory is the workspace root, matching `python sub/nested.py` typed from
        // there — not the script's own parent directory.
        val result = EmbeddedPythonEngine.runFile(File(sub, "nested.py").path, workspace.path)

        assertEquals(0, result.exitCode)
        assertEquals("nested argv0=sub/nested.py", result.output)
    }
}
