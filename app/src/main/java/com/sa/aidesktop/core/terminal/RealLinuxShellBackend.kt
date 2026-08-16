package com.sa.aidesktop.core.terminal

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Real counterpart (Rule 8) to [EmbeddedShellBackend]: where that class is a restricted allowlist
 * sandbox that can never run arbitrary commands, this one runs a genuine Linux userland
 * (busybox/bash/coreutils/apt, plus anything the user `apt install`s afterwards) that
 * [LinuxBootstrapManager] downloaded — the same real binaries Termux itself runs, via the same
 * real system-linker execution technique ([SystemLinkerExec]). Only ever constructed/used once
 * [LinuxBootstrapManager.isReady] is true; [RoutingShellBackend] falls back to
 * [EmbeddedShellBackend] otherwise, so this class never has to handle a "not installed yet" case.
 *
 * Unlike [EmbeddedShellBackend], shell operators (&&, |, ;, >, <) are NOT reimplemented here —
 * the whole command line is handed to the real `bash -c`, exactly like a real terminal, which is
 * both more correct and avoids duplicating a parser Rule 21 would flag as unnecessary.
 */
class RealLinuxShellBackend(
    private val workspace: File,
    private val prefix: File,
    private val onLiveOutput: (String) -> Unit = {}
) : ShellBackend {
    private val processRef = AtomicReference<Process?>(null)
    private val home: File = File(prefix, "home").apply { mkdirs() }
    private val bash: File = File(prefix, "bin/bash")

    override fun execute(command: String, workingDirectory: String, timeoutMs: Long): TerminalResult {
        if (!bash.isFile) {
            // Endpoint-correctness guard (Rule 17): RoutingShellBackend only checks for
            // bash+busybox existing before routing here; if bash is somehow gone afterwards
            // (partial/corrupted state), say so honestly instead of a confusing native-exec error.
            return TerminalResult(
                "Real Linux environment is incomplete (bash missing). Run 'bootstrap install' again.",
                1
            )
        }
        val cwd = resolveDirectory(workingDirectory)
            ?: return TerminalResult("Invalid working directory.", 1)
        val argv = SystemLinkerExec.buildCommand(bash, listOf("-c", command))
        return try {
            val processBuilder = ProcessBuilder(argv).directory(cwd).redirectErrorStream(true)
            processBuilder.environment().apply {
                clear()
                putAll(buildEnv(cwd))
            }
            val process = processBuilder.start()
            processRef.set(process)
            // Rule 14 follow-up: this used to buffer the ENTIRE output silently and only reveal
            // it once the whole process finished — for a slow `apt install` or `gradle build`
            // that could be minutes of a terminal showing nothing. Now reads line-by-line and
            // pushes each real line to [onLiveOutput] as it arrives, same as watching a real
            // terminal; the full text is still collected too, for the final [TerminalResult].
            val collected = StringBuilder()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (collected.length < 1024 * 1024) collected.append(line).append('\n')
                            onLiveOutput(line)
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                reader.join(250)
                TerminalResult("Process timed out after ${timeoutMs}ms\n$collected".trim(), 124)
            } else {
                reader.join(1000)
                TerminalResult(collected.toString().trimEnd(), process.exitValue())
            }
        } catch (e: Exception) {
            TerminalResult("Process failed: ${e.message ?: "unknown error"}", 127)
        } finally {
            processRef.set(null)
        }
    }

    override fun cancel() {
        processRef.getAndSet(null)?.destroyForcibly()
    }

    private fun buildEnv(cwd: File): Map<String, String> = mapOf(
        "PREFIX" to prefix.absolutePath,
        "HOME" to home.absolutePath,
        "PATH" to "${prefix.absolutePath}/bin",
        "LD_LIBRARY_PATH" to "${prefix.absolutePath}/lib",
        "TMPDIR" to File(prefix, "tmp").apply { mkdirs() }.absolutePath,
        // termux-exec's own preload library, shipped inside the bootstrap zip (not built by this
        // app) — needed so bash's OWN internal execve() calls (running `ls`, `git`, `python`, a
        // script's shebang) also go through the system-linker trick, not just this top-level bash
        // invocation.
        "LD_PRELOAD" to "${prefix.absolutePath}/lib/libtermux-exec.so",
        "PWD" to cwd.absolutePath
    )

    private fun resolveDirectory(path: String): File? {
        val candidate = File(path).let { if (it.isAbsolute) it else File(workspace, path) }.canonicalFile
        val inside = candidate.path == workspace.canonicalPath ||
            candidate.path.startsWith(workspace.canonicalPath + File.separator)
        return if (inside) candidate.takeIf { it.isDirectory } else null
    }
}
