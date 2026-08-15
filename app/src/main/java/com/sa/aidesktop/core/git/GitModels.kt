package com.sa.aidesktop.core.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed interface GitResult<out T> {
    data class Success<T>(val value: T): GitResult<T>
    data class Failure(val error: GitError): GitResult<Nothing>
}

data class GitChange(
    val path: String,
    val state: String,
    val staged: Boolean = false,
    val originalPath: String? = null
)

data class GitStatus(
    val branch: String,
    val changes: List<GitChange>,
    val clean: Boolean = changes.isEmpty(),
    val ahead: Int = 0,
    val behind: Int = 0,
    val upstream: String? = null
)

data class GitRemote(val name: String, val fetchUrl: String, val pushUrl: String)
data class GitCommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok get() = exitCode == 0
}

sealed interface GitError {
    data class Validation(val message:String):GitError
    data class NotAvailable(val message:String):GitError
    data class Command(val code:Int,val message:String):GitError
    data class Permission(val message:String):GitError
    data class AuthenticationRequired(val message:String):GitError
    data class DirtyWorkspace(val message:String):GitError
}

interface GitService {
    suspend fun init(): GitResult<Unit>
    suspend fun clone(url:String, destination:String): GitResult<Unit>
    suspend fun status(): GitResult<GitStatus>
    suspend fun add(paths:List<String>): GitResult<Unit>
    suspend fun commit(message:String): GitResult<String>
    suspend fun push(remote:String? = null, branch:String? = null, confirmed:Boolean = false): GitResult<String>
    suspend fun pull(remote:String? = null, branch:String? = null, confirmed:Boolean = false): GitResult<String>
    suspend fun fetch(remote:String? = null): GitResult<String>
    suspend fun branch(name:String? = null): GitResult<List<String>>
    suspend fun checkout(name:String): GitResult<Unit>
    suspend fun merge(name:String): GitResult<String>
    suspend fun diff(cached:Boolean = false): GitResult<String>
    suspend fun log(limit:Int = 20): GitResult<String>
    suspend fun remote(): GitResult<List<GitRemote>>
}

interface GitBackend { suspend fun run(args:List<String>): GitCommandResult }

class ProcessGitBackend(private val root: File): GitBackend {
    override suspend fun run(args:List<String>): GitCommandResult = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(listOf("git") + args)
                .directory(root)
                .redirectErrorStream(false)
                .start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outReader = Thread {
                runCatching { process.inputStream.bufferedReader().use { reader -> reader.forEachLine { stdout.appendLine(it) } } }
            }.apply { isDaemon = true; start() }
            val errReader = Thread {
                runCatching { process.errorStream.bufferedReader().use { reader -> reader.forEachLine { stderr.appendLine(it) } } }
            }.apply { isDaemon = true; start() }
            val finished = process.waitFor(COMMAND_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                outReader.join(250)
                errReader.join(250)
                return@withContext GitCommandResult(124, stdout.toString(), stderr.toString() + "\nGit command timed out after ${COMMAND_TIMEOUT_MS}ms")
            }
            outReader.join(1000)
            errReader.join(1000)
            GitCommandResult(process.exitValue(), stdout.toString(), stderr.toString())
        } catch (e: Exception) {
            GitCommandResult(127, "", e.message ?: "git executable unavailable")
        }
    }

    private companion object { const val COMMAND_TIMEOUT_MS = 60_000L }
}

class CommandGitService(
    private val root: File,
    private val backend: GitBackend = ProcessGitBackend(root)
): GitService {
    private fun validRoot(): Boolean = root.isDirectory
    private fun validateRelativePath(path: String): Boolean =
        path.isNotBlank() &&
            !path.startsWith("/") &&
            !path.startsWith("\\") &&
            path != "." &&
            path != ".." &&
            !path.split('/', '\\').contains("..")

    private fun validateBranch(name: String): Boolean =
        name.isNotBlank() && name.length <= 255 && !name.startsWith("-") &&
            !name.contains("..") && !name.contains(' ') && !name.contains('~') &&
            !name.contains('^') && !name.contains(':') && !name.contains('?') &&
            !name.contains('*') && !name.contains('[') && !name.endsWith('.') &&
            !name.endsWith('/') && !name.contains("//")

    private fun mapFailure(r: GitCommandResult): GitResult<Nothing> {
        val detail = redactCredentials((r.stderr.ifBlank { r.stdout }).trim().take(8000))
        val lower = detail.lowercase()
        return when {
            r.exitCode == 127 -> GitResult.Failure(GitError.NotAvailable("Git executable is not available on this device."))
            lower.contains("authentication failed") ||
                lower.contains("could not read username") ||
                lower.contains("permission denied (publickey)") ||
                lower.contains("authentication required") ->
                GitResult.Failure(GitError.AuthenticationRequired(detail.ifBlank { "Git authentication is required." }))
            lower.contains("permission denied") ->
                GitResult.Failure(GitError.Permission(detail.ifBlank { "Git permission denied." }))
            else -> GitResult.Failure(GitError.Command(r.exitCode, detail.ifBlank { "Git command failed." }))
        }
    }

    private fun unit(r: GitCommandResult): GitResult<Unit> =
        if (r.ok) GitResult.Success(Unit) else mapFailure(r)

    private fun text(r: GitCommandResult): GitResult<String> =
        if (r.ok) GitResult.Success(r.stdout.trim()) else mapFailure(r)

    override suspend fun init(): GitResult<Unit> {
        if (!validRoot()) return GitResult.Failure(GitError.Validation("Workspace does not exist."))
        return unit(backend.run(listOf("init")))
    }

    override suspend fun clone(url: String, destination: String): GitResult<Unit> {
        if (url.length > 2048 || !isSupportedCloneUrl(url))
            return GitResult.Failure(GitError.Validation("Unsupported repository URL. Use HTTPS, SSH, or SCP-style git@host:path."))
        if (!validateRelativePath(destination))
            return GitResult.Failure(GitError.Validation("Invalid clone destination."))
        val dest = File(root, destination).canonicalFile
        if (dest.path != root.canonicalPath && !dest.path.startsWith(root.canonicalPath + File.separator))
            return GitResult.Failure(GitError.Validation("Clone destination escapes the workspace."))
        if (dest.exists() && dest.listFiles()?.isNotEmpty() == true)
            return GitResult.Failure(GitError.Validation("Clone destination is not empty."))
        return unit(backend.run(listOf("clone", "--", url, destination)))
    }

    private fun isSupportedCloneUrl(url: String): Boolean =
        url.startsWith("https://") || url.startsWith("ssh://") || url.startsWith("git@")

    override suspend fun status(): GitResult<GitStatus> {
        val r = backend.run(listOf("status", "--porcelain=v1", "-b"))
        if (!r.ok) return mapFailure(r)
        val lines = r.stdout.lines()
        val header = lines.firstOrNull { it.startsWith("## ") }?.removePrefix("## ").orEmpty()
        val branchPart = header.substringBefore("...")
        val upstream = header.substringAfter("...", "").substringBefore(' ').takeIf { it.isNotBlank() }
        val divergence = Regex("""\[(?:ahead (\d+))?(?:, )?(?:behind (\d+))?]""").find(header)
        val ahead = divergence?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val behind = divergence?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0

        val changes = lines.filter { it.length >= 3 && !it.startsWith("## ") }.mapNotNull { line ->
            val xy = line.substring(0, 2)
            val rawPath = line.substring(3).trim()
            if (rawPath.isBlank()) return@mapNotNull null
            val renamed = if ("->" in rawPath) rawPath.substringAfter("->").trim() else rawPath
            GitChange(
                path = renamed.removeSurrounding("\""),
                state = statusState(xy),
                staged = xy[0] != ' ' && xy[0] != '?',
                originalPath = rawPath.substringBefore(" -> ", "").takeIf { " -> " in rawPath }
            )
        }
        return GitResult.Success(GitStatus(branchPart.trim(), changes, changes.isEmpty(), ahead, behind, upstream))
    }

    private fun statusState(xy: String): String = when {
        xy == "??" -> "Untracked"
        xy.contains('R') -> "Renamed"
        xy.contains('A') -> "Added"
        xy.contains('D') -> "Deleted"
        xy.contains('M') -> "Modified"
        xy.contains('U') -> "Conflict"
        else -> xy.trim().ifBlank { "Changed" }
    }

    override suspend fun add(paths: List<String>): GitResult<Unit> {
        if (paths.isEmpty()) return GitResult.Failure(GitError.Validation("No files selected."))
        if (paths.any { !validateRelativePath(it) })
            return GitResult.Failure(GitError.Validation("Invalid repository-relative path."))
        return unit(backend.run(listOf("add", "--") + paths))
    }

    override suspend fun commit(message: String): GitResult<String> {
        val cleanMessage = message.trim()
        if (cleanMessage.isBlank() || cleanMessage.length > 200)
            return GitResult.Failure(GitError.Validation("Commit message must be 1-200 characters."))
        val staged = backend.run(listOf("diff", "--cached", "--quiet"))
        if (staged.exitCode == 0)
            return GitResult.Failure(GitError.Validation("There are no staged changes to commit."))
        if (staged.exitCode != 1) return mapFailure(staged)
        val r = backend.run(listOf("commit", "-m", cleanMessage))
        return if (r.ok) GitResult.Success(r.stdout.trim().ifBlank { r.stderr.trim() }) else mapFailure(r)
    }

    override suspend fun push(remote: String?, branch: String?, confirmed: Boolean): GitResult<String> {
        if (!confirmed) return GitResult.Failure(GitError.Validation("Push requires explicit confirmation."))
        val remoteName = remote?.trim().takeUnless { it.isNullOrBlank() } ?: currentRemoteName()
        if (remoteName == null) return GitResult.Failure(GitError.Validation("No Git remote is configured."))
        if (!validRemoteName(remoteName)) return GitResult.Failure(GitError.Validation("Invalid remote name."))
        val branchName = branch?.trim().takeUnless { it.isNullOrBlank() } ?: currentBranch()
        if (!validateBranch(branchName)) return GitResult.Failure(GitError.Validation("Invalid branch name."))
        val upstream = (status() as? GitResult.Success)?.value?.upstream
        val args = if (upstream.isNullOrBlank()) listOf("push", "--set-upstream", remoteName, branchName)
        else listOf("push", remoteName, branchName)
        return text(backend.run(args))
    }

    override suspend fun pull(remote: String?, branch: String?, confirmed: Boolean): GitResult<String> {
        if (!confirmed) return GitResult.Failure(GitError.Validation("Pull requires explicit confirmation because it can modify the workspace."))
        val status = status()
        if (status is GitResult.Success && status.value.changes.isNotEmpty())
            return GitResult.Failure(GitError.DirtyWorkspace("Workspace has uncommitted changes. Commit or stash them before pull."))
        val remoteName = remote?.trim().takeUnless { it.isNullOrBlank() } ?: currentRemoteName()
        val branchName = branch?.trim().takeUnless { it.isNullOrBlank() } ?: currentBranch()
        if (remoteName == null) return GitResult.Failure(GitError.Validation("No Git remote is configured."))
        if (!validRemoteName(remoteName) || !validateBranch(branchName))
            return GitResult.Failure(GitError.Validation("Invalid remote or branch."))
        return text(backend.run(listOf("pull", "--ff-only", remoteName, branchName)))
    }

    override suspend fun fetch(remote: String?): GitResult<String> {
        val remoteName = remote?.trim().takeUnless { it.isNullOrBlank() }
        return if (remoteName == null) text(backend.run(listOf("fetch", "--all", "--prune")))
        else if (validRemoteName(remoteName)) text(backend.run(listOf("fetch", remoteName, "--prune")))
        else GitResult.Failure(GitError.Validation("Invalid remote name."))
    }

    override suspend fun branch(name: String?): GitResult<List<String>> {
        if (name == null) {
            val r = backend.run(listOf("branch", "--list", "--format=%(refname:short)"))
            return if (r.ok) GitResult.Success(r.stdout.lines().filter(String::isNotBlank)) else mapFailure(r)
        }
        if (!validateBranch(name)) return GitResult.Failure(GitError.Validation("Invalid branch name."))
        val switched = backend.run(listOf("switch", "-c", "--", name))
        if (!switched.ok) return mapFailure(switched)
        // BUG FIX (Rule 17 endpoint-correctness): this used to report GitResult.Success(emptyList())
        // on branch creation — the chain "completed" (Rule 1) but the reported result was always
        // blank, so a caller could never tell the branch genuinely got created vs. silently no-op'd.
        // Re-read the real branch list after the switch so the endpoint reflects verified state
        // (the new branch actually present), not an assumed/empty placeholder.
        val list = backend.run(listOf("branch", "--list", "--format=%(refname:short)"))
        return if (list.ok) GitResult.Success(list.stdout.lines().filter(String::isNotBlank))
        else GitResult.Success(listOf(name)) // switch already succeeded; don't fail the op over a secondary read
    }

    override suspend fun checkout(name: String): GitResult<Unit> {
        if (!validateBranch(name)) return GitResult.Failure(GitError.Validation("Invalid branch name."))
        return unit(backend.run(listOf("switch", "--", name)))
    }

    override suspend fun merge(name: String): GitResult<String> {
        if (!validateBranch(name)) return GitResult.Failure(GitError.Validation("Invalid branch name."))
        return text(backend.run(listOf("merge", "--", name)))
    }

    override suspend fun diff(cached: Boolean): GitResult<String> =
        text(backend.run(if (cached) listOf("diff", "--cached") else listOf("diff"))).limit(40_000)

    override suspend fun log(limit: Int): GitResult<String> =
        text(backend.run(listOf("log", "-n", limit.coerceIn(1, 100).toString(), "--oneline", "--decorate"))).limit(20_000)

    override suspend fun remote(): GitResult<List<GitRemote>> {
        val r = backend.run(listOf("remote", "-v"))
        if (!r.ok) return mapFailure(r)
        val byName = linkedMapOf<String, MutableList<String>>()
        r.stdout.lines().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 3) byName.getOrPut(parts[0]) { mutableListOf() }.add(parts[1])
        }
        return GitResult.Success(byName.map { (name, urls) ->
            GitRemote(name, redactCredentials(urls.firstOrNull().orEmpty()), redactCredentials(urls.getOrElse(1) { urls.firstOrNull().orEmpty() }))
        })
    }

    private suspend fun currentBranch(): String =
        (status() as? GitResult.Success)?.value?.branch.orEmpty()

    private suspend fun currentRemoteName(): String? =
        (remote() as? GitResult.Success)?.value?.firstOrNull()?.name

    private fun validRemoteName(name: String) =
        name.isNotBlank() && name.length <= 100 && name.none { it.isWhitespace() || it == '/' || it == '\\' }

    private fun redactCredentials(value: String): String =
        value.replace(Regex("""(?i)(https?://)([^/@\s:]+):([^/@\s]+)@"""), "$1[REDACTED]@")
            .replace(Regex("""(?i)(https?://)([^/@\s]+)@"""), "$1[REDACTED]@")

    private fun GitResult<String>.limit(max: Int): GitResult<String> =
        when (this) {
            is GitResult.Success -> GitResult.Success(value.take(max) + if (value.length > max) "\n[OUTPUT TRUNCATED]" else "")
            is GitResult.Failure -> this
        }
}

private fun <T> GitResult<T>.map(block: (T) -> T): GitResult<T> =
    when (this) { is GitResult.Success -> GitResult.Success(block(value)); is GitResult.Failure -> this }
