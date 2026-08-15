package com.sa.aidesktop.core.runtime

import com.sa.aidesktop.core.python.EmbeddedPythonEngine
import java.io.File

/**
 * Real Python [CodeRunner] backed by the embedded Chaquopy interpreter (see
 * [EmbeddedPythonEngine]) instead of shelling out to a `python3` binary — Android has no such
 * binary on-device, so the previous `CommandCodeRunner("Python","python3")` could never
 * actually succeed. This runner is used by RuntimeRegistry in its place.
 */
class EmbeddedPythonCodeRunner : CodeRunner {
    override val language = "Python"

    override fun prepare(): Boolean = EmbeddedPythonEngine.isAvailable

    override fun build(sourcePath: String): ExecutionResult = run(sourcePath)

    override fun run(sourcePath: String): ExecutionResult {
        val start = System.currentTimeMillis()
        if (!EmbeddedPythonEngine.isAvailable) {
            return ExecutionResult("Embedded Python engine is still starting up. Try again in a moment.", 1, System.currentTimeMillis() - start)
        }
        val workingDirectory = File(sourcePath).parentFile?.path ?: "."
        val result = EmbeddedPythonEngine.runFile(sourcePath, workingDirectory)
        return ExecutionResult(result.output, result.exitCode, System.currentTimeMillis() - start)
    }

    // The embedded interpreter runs synchronously on the caller's thread for a single script;
    // there is no separate OS process to forcibly kill the way CommandCodeRunner's stop() does.
    override fun stop() {}

    override fun status(): RuntimeStatus = if (EmbeddedPythonEngine.isAvailable) RuntimeStatus.AVAILABLE else RuntimeStatus.UNAVAILABLE
}
