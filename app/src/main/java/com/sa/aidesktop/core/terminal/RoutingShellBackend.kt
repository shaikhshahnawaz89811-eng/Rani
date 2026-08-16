package com.sa.aidesktop.core.terminal

import java.io.File

/**
 * Rule 8 counterpart-dispatch: routes every command to the REAL Linux backend once
 * [bootstrap] reports ready, otherwise to the restricted [EmbeddedShellBackend] — this never
 * deletes or replaces either implementation, it only decides which one handles this call.
 * [TerminalService] and the rest of the app never need to know or care which mode is active.
 *
 * Also owns the two built-in `bootstrap status` / `bootstrap install` commands (Rule 1: a real,
 * discoverable entry point for the one-time setup) so installing the real environment needs no
 * new screen — it happens from the same Terminal window the user already has.
 *
 * [onLiveOutput] is a single shared sink for anything that happens WHILE a command is still
 * running — `bootstrap install`'s download/extract progress lines, and (once real mode is
 * active) each line of a real command's output as it arrives — so the UI can show it live
 * instead of only the final [TerminalResult] once everything is already finished.
 */
class RoutingShellBackend(
    private val workspace: File,
    private val bootstrap: LinuxBootstrapManager,
    private val onLiveOutput: (String) -> Unit = {},
    private val embedded: ShellBackend = EmbeddedShellBackend(workspace)
) : ShellBackend {
    private val real: RealLinuxShellBackend by lazy { RealLinuxShellBackend(workspace, bootstrap.prefix, onLiveOutput) }

    override fun execute(command: String, workingDirectory: String, timeoutMs: Long): TerminalResult {
        when (command.trim()) {
            "bootstrap status" -> return TerminalResult(
                if (bootstrap.isReady())
                    "Real Linux environment: READY (${bootstrap.prefix}). Commands now run through real bash/busybox/apt."
                else
                    "Real Linux environment: NOT installed (restricted sandbox mode active). Run 'bootstrap install' — needs internet, one-time, then works offline.",
                0
            )
            "bootstrap install" -> {
                return when (val result = bootstrap.install(onProgress = onLiveOutput)) {
                    is BootstrapStatus.Ready -> TerminalResult(
                        "Real Linux environment installed. Every command from here now runs through the real bootstrap (bash/busybox/apt) instead of the restricted sandbox. Try: apt install python",
                        0
                    )
                    is BootstrapStatus.Failed -> TerminalResult("Install failed: ${result.reason}", 1)
                    BootstrapStatus.NotInstalled -> TerminalResult("Install did not complete.", 1)
                }
            }
        }
        return if (bootstrap.isReady()) {
            real.execute(command, workingDirectory, timeoutMs)
        } else {
            embedded.execute(command, workingDirectory, timeoutMs)
        }
    }

    override fun cancel() {
        if (bootstrap.isReady()) real.cancel() else embedded.cancel()
    }
}
