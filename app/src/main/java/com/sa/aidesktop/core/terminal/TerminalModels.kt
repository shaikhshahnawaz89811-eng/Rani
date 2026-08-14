package com.sa.aidesktop.core.terminal

data class TerminalResult(val output: String, val exitCode: Int, val cancelled: Boolean = false)
data class TerminalSessionState(val workingDirectory: String, val history: List<String>, val running: Boolean, val lastExitCode: Int?)

enum class TerminalError { INVALID_COMMAND, BLOCKED_COMMAND, TIMEOUT, PROCESS_FAILED, CANCELLED }

interface ShellBackend {
    fun execute(command: String, workingDirectory: String, timeoutMs: Long = 10_000L): TerminalResult
    fun cancel()
}
