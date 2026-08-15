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
        pythonScriptFileOrNull(executable, tokens.drop(1), cwd)?.let { scriptFile ->
            return if (EmbeddedPythonEngine.isAvailable) {
                EmbeddedPythonEngine.runFile(scriptFile.path, cwd.path)
            } else {
                TerminalResult("Embedded Python engine is still starting up. Try again in a moment.", 1)
            }
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

    /** Returns the .py file to run with the embedded engine, or null if this isn't a plain
     *  `python`/`python3 <file>.py` invocation (e.g. no args, flags only, non-.py target). */
    private fun pythonScriptFileOrNull(executable: String, args: List<String>, cwd: File): File? {
        if (executable != "python" && executable != "python3") return null
        val scriptArg = args.firstOrNull { !it.startsWith("-") } ?: return null
        if (!scriptArg.endsWith(".py")) return null
        val candidate = File(scriptArg).let { if (it.isAbsolute) it else File(cwd, scriptArg) }.canonicalFile
        val inside = candidate.path == workspace.canonicalPath || candidate.path.startsWith(workspace.canonicalPath + File.separator)
        return candidate.takeIf { inside && it.isFile }
    }

    private fun resolveDirectory(path: String): File? {
        val candidate = File(path).let { if (it.isAbsolute) it else File(workspace, path) }.canonicalFile
        return if (candidate.path == workspace.canonicalPath || candidate.path.startsWith(workspace.canonicalPath + File.separator)) candidate.takeIf { it.isDirectory }
        else null
    }

    private fun tokenize(s: String): List<String> = Regex("\\\"([^\\\"]*)\\\"|'([^']*)'|(\\S+)").findAll(s).map { it.groups[1]?.value ?: it.groups[2]?.value ?: it.groups[3]!!.value }.toList()
    private fun java.io.BufferedReader.readTextWithLimit(limit: Int): String { val sb=StringBuilder(); val buf=CharArray(4096); var total=0; while(true){val n=read(buf); if(n<0) break; val take=minOf(n, limit-total); if(take>0) sb.append(buf,0,take); total+=take; if(total>=limit){sb.append("\\n[output truncated]"); break}}; return sb.toString() }
}
