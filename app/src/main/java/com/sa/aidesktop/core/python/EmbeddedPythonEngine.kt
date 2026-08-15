package com.sa.aidesktop.core.python

import android.content.Context
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.sa.aidesktop.core.terminal.TerminalResult
import java.io.File

/**
 * Real, embedded CPython 3.11 runtime (Chaquopy), bundled inside this app's own APK.
 *
 * This is NOT a wrapper around an on-device `python`/`python3` binary (Android does not ship
 * one) and it does NOT shell out to Termux or any other external app. The interpreter and
 * standard library are packaged as native libraries + assets inside this app, so `python
 * main.py` runs fully in-process.
 *
 * [ensureStarted] must be called once, early (MainActivity.onCreate), before any terminal or
 * runner code tries to execute Python. Calling it more than once is safe (no-op after the
 * first successful start).
 */
object EmbeddedPythonEngine {
    @Volatile private var started = false

    @Synchronized
    fun ensureStarted(context: Context) {
        if (started) return
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        started = true
    }

    /** True once the embedded interpreter has been started and is ready to execute code. */
    val isAvailable: Boolean get() = started

    /**
     * Runs a real .py file with the embedded interpreter.
     *
     * @param scriptPath absolute path to the .py file (already validated by the caller as
     *   inside the sandboxed workspace — this function does not re-check that).
     * @param workingDirectory directory the script should see as its cwd (so relative file
     *   access inside the user's script behaves the way it would in a normal terminal).
     *
     * Returns a [TerminalResult] so existing Terminal/Runner callers don't need a new result
     * shape: `output` is combined real stdout+stderr text, `exitCode` is 0 on success, 1 on an
     * uncaught Python exception, or the code passed to `sys.exit(n)`.
     */
    fun runFile(scriptPath: String, workingDirectory: String): TerminalResult {
        if (!started) return TerminalResult("Embedded Python engine not started yet.", 1)
        val file = File(scriptPath)
        if (!file.isFile) return TerminalResult("$scriptPath: No such file", 1)

        val py = Python.getInstance()
        val sys = py.getModule("sys")
        val io = py.getModule("io")
        val runpy = py.getModule("runpy")
        val os = py.getModule("os")

        val originalStdout = sys.get("stdout")
        val originalStderr = sys.get("stderr")
        val originalCwd = runCatching { os.callAttr("getcwd").toString() }.getOrNull()
        val captured = io.callAttr("StringIO")

        return try {
            sys.put("stdout", captured)
            sys.put("stderr", captured)
            val relativePath = File(workingDirectory).toPath().relativize(file.toPath()).toString()
            sys.put("argv", listOf(relativePath))
            runCatching { os.callAttr("chdir", workingDirectory) }

            var exitCode = 0
            try {
                // run_name="__main__" so `if __name__ == "__main__":` in the user's script
                // behaves exactly like running `python main.py` on a normal machine.
                // IMPORTANT: run_path() itself overwrites sys.argv[0] with whatever path string
                // it's given (verified by direct testing of CPython's runpy module), so it must
                // be called with relativePath — not the absolute scriptPath — now that cwd has
                // already been switched to workingDirectory above. This also correctly handles
                // scripts in a subdirectory of the workspace (e.g. `python sub/main.py`), not
                // just ones sitting directly in workingDirectory.
                runpy.callAttr("run_path", relativePath, null, "__main__")
            } catch (e: PyException) {
                val message = e.message.orEmpty()
                exitCode = if (message.contains("SystemExit")) {
                    Regex("SystemExit:\\s*(-?\\d+)").find(message)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                } else {
                    captured.callAttr("write", "\n$message\n")
                    1
                }
            }
            TerminalResult(captured.callAttr("getvalue").toString().trimEnd(), exitCode)
        } finally {
            sys.put("stdout", originalStdout)
            sys.put("stderr", originalStderr)
            originalCwd?.let { runCatching { os.callAttr("chdir", it) } }
        }
    }
}
