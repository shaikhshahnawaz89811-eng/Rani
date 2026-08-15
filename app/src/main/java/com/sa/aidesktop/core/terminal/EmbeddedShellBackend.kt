package com.sa.aidesktop.core.terminal

import com.sa.aidesktop.core.python.EmbeddedPythonEngine
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Restricted embedded shell. It never invokes `sh -c`; command text is tokenized and executable allowlisted. */
class EmbeddedShellBackend(private val workspace: File) : ShellBackend {
    private val processRef = AtomicReference<Process?>(null)
    private val allowed = setOf("pwd","ls","echo","cat","head","tail","grep","find","mkdir","touch","rm","cp","mv","git","python","python3","java","./gradlew","gradlew","./gradlew.bat","gradlew.bat","npm","npx","pytest","javac","kotlinc","node","clang","clang++")

    override fun execute(command: String, workingDirectory: String, timeoutMs: Long): TerminalResult {
        val tokens = tokenize(command.trim())
        if (tokens.isEmpty()) return TerminalResult("", 0)
        val executable = tokens.first()
        if (executable !in allowed) return TerminalResult("Command blocked by embedded shell policy: $executable", 126)
        if (tokens.any { it.contains("&&") || it.contains("||") || it.contains(';') || it.contains('|') || it.contains('>') || it.contains('<') }) {
            return TerminalResult("Shell operators are disabled by security policy.", 126)
        }
        if (unsafePathArguments(executable, tokens.drop(1))) {
            return TerminalResult("Command blocked by workspace security policy: path escapes the workspace.", 126)
        }
        val cwd = resolveDirectory(workingDirectory) ?: return TerminalResult("Invalid working directory.", 1)
        // There is no real system `python`/`python3` binary on Android — the embedded Chaquopy
        // engine is the ONLY thing that can ever run a script here. Handling these two executables
        // fully in-branch (instead of falling through to the raw ProcessBuilder below on a miss)
        // means every failure mode gets a real, specific message instead of the raw-process path
        // always dying with a confusing native "Cannot run program python" OS error (Rule 17:
        // an endpoint that exists but is never correct is still a bug).
        if (executable == "python" || executable == "python3") {
            return runEmbeddedPython(tokens.drop(1), cwd)
        }
        return try {
            val p = ProcessBuilder(tokens).directory(cwd).redirectErrorStream(true).start()
            processRef.set(p)
            val output = AtomicReference("")
            val reader = Thread {
                runCatching { p.inputStream.bufferedReader().use { output.set(it.readTextWithLimit(512 * 1024)) } }
            }.apply { isDaemon = true; start() }
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroyForcibly()
                reader.join(250)
                TerminalResult("Process timed out after ${timeoutMs}ms\n${output.get()}".trim(), 124)
            } else {
                reader.join(1000)
                TerminalResult(output.get().trimEnd(), p.exitValue())
            }
        } catch (e: Exception) {
            TerminalResult("Process failed: ${e.message ?: "unknown error"}", 127)
        } finally { processRef.set(null) }
    }

    override fun cancel() { processRef.getAndSet(null)?.destroyForcibly() }

    private fun unsafePathArguments(executable: String, args: List<String>): Boolean {
        val pathAware = setOf("rm", "cp", "mv", "mkdir", "touch", "cat", "head", "tail", "find")
        if (executable !in pathAware) return false
        return args.filterNot { it == "--" || it.startsWith("-") }.any { token ->
            token.startsWith("/") || token.startsWith("\\") || token.split('/', '\\').any { it == ".." }
        }
    }

    /** Sub-helper (Rule 15): the ONLY path that ever runs `python`/`python3` — every case below
     *  returns a specific, honest [TerminalResult] instead of silently falling through to a raw
     *  native process exec that can never succeed on this platform. */
    private fun runEmbeddedPython(args: List<String>, cwd: File): TerminalResult {
        val scriptArg = args.firstOrNull { !it.startsWith("-") }
            ?: return TerminalResult(
                "Embedded Python does not support the interactive REPL. Run it as: python <file>.py",
                1
            )
        if (!scriptArg.endsWith(".py")) {
            return TerminalResult("Embedded Python can only run .py files, got: $scriptArg", 1)
        }
        val candidate = File(scriptArg).let { if (it.isAbsolute) it else File(cwd, scriptArg) }.canonicalFile
        val inside = candidate.path == workspace.canonicalPath || candidate.path.startsWith(workspace.canonicalPath + File.separator)
        if (!inside) {
            return TerminalResult("Command blocked by workspace security policy: path escapes the workspace.", 126)
        }
        if (!candidate.isFile) {
            // This is the exact case the user hit: they saved the file under a different name/
            // extension (e.g. new_file.txt) than what they ran (python new_file.py). Say so
            // clearly instead of letting it fall through to a confusing native process error.
            return TerminalResult(
                "python: can't open file '$scriptArg': [Errno 2] No such file or directory. " +
                    "Check the file was saved with this exact name (including .py) in the workspace.",
                2
            )
        }
        return if (EmbeddedPythonEngine.isAvailable) {
            EmbeddedPythonEngine.runFile(candidate.path, cwd.path)
        } else {
            TerminalResult("Embedded Python engine is still starting up. Try again in a moment.", 1)
        }
    }

    private fun resolveDirectory(path: String): File? {
        val candidate = File(path).let { if (it.isAbsolute) it else File(workspace, path) }.canonicalFile
        return if (candidate.path == workspace.canonicalPath || candidate.path.startsWith(workspace.canonicalPath + File.separator)) candidate.takeIf { it.isDirectory }
        else null
    }

    private fun tokenize(s: String): List<String> = Regex("\\\"([^\\\"]*)\\\"|'([^']*)'|(\\S+)").findAll(s).map { it.groups[1]?.value ?: it.groups[2]?.value ?: it.groups[3]!!.value }.toList()
    private fun java.io.BufferedReader.readTextWithLimit(limit: Int): String { val sb=StringBuilder(); val buf=CharArray(4096); var total=0; while(true){val n=read(buf); if(n<0) break; val take=minOf(n, limit-total); if(take>0) sb.append(buf,0,take); total+=take; if(total>=limit){sb.append("\\n[output truncated]"); break}}; return sb.toString() }
}
