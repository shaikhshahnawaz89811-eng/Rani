package com.sa.aidesktop.core.github

import com.sa.aidesktop.core.ai.*

class GitStatusTool(private val git: com.sa.aidesktop.core.git.GitService): AITool {
    override val id = "git.status"
    override val description = "Inspect the real Git repository status, branch, staged/unstaged changes and ahead/behind state."
    override val risk = ToolRisk.READ_ONLY
    override val parameterHints = emptyMap<String, String>()
    override suspend fun execute(input: Map<String, String>): AIResult<ToolResult> =
        git.status().toAi("git.status") { s ->
            buildString {
                appendLine("branch=${s.branch}")
                appendLine("upstream=${s.upstream ?: "(none)"}")
                appendLine("ahead=${s.ahead} behind=${s.behind}")
                if (s.changes.isEmpty()) append("clean")
                else s.changes.take(500).forEach { appendLine("${it.state} ${if (it.staged) "[staged] " else ""}${it.path}") }
                if (s.changes.size > 500) appendLine("[STATUS TRUNCATED]")
            }
        }
}

class GitDiffTool(private val git: com.sa.aidesktop.core.git.GitService): AITool {
    override val id = "git.diff"
    override val description = "Inspect the real Git diff; use cached=true for staged changes."
    override val risk = ToolRisk.READ_ONLY
    override val parameterHints = mapOf("cached" to "true to inspect staged diff, false for unstaged diff")
    override suspend fun execute(input: Map<String, String>): AIResult<ToolResult> =
        git.diff(input["cached"]?.toBooleanStrictOrNull() == true).toAi("git.diff") { it }
}

class GitLogTool(private val git: com.sa.aidesktop.core.git.GitService): AITool {
    override val id = "git.log"
    override val description = "Read the real recent Git history."
    override val risk = ToolRisk.READ_ONLY
    override val parameterHints = mapOf("limit" to "Number of commits, bounded to 1-100")
    override suspend fun execute(input: Map<String, String>): AIResult<ToolResult> =
        git.log(input["limit"]?.toIntOrNull()?.coerceIn(1,100) ?: 20).toAi("git.log") { it }
}

class GitRemoteTool(private val git: com.sa.aidesktop.core.git.GitService): AITool {
    override val id = "git.remote"
    override val description = "Inspect the real configured Git remotes."
    override val risk = ToolRisk.READ_ONLY
    override val parameterHints = emptyMap<String, String>()
    override suspend fun execute(input: Map<String, String>): AIResult<ToolResult> =
        git.remote().toAi("git.remote") { remotes ->
            remotes.joinToString("\n") { "${it.name}\tfetch=${it.fetchUrl}\tpush=${it.pushUrl}" }.ifBlank { "(no remotes)" }
        }
}

class GitInitTool(private val git: com.sa.aidesktop.core.git.GitService): AITool {
    override val id = "git.init"
    override val description = "Initialize a real Git repository in the controlled project workspace."
    override val risk = ToolRisk.WRITE
    override val parameterHints = emptyMap<String, String>()
    override suspend fun execute(input: Map<String, String>) = git.init().toAi("git.init") { "Git repository initialized." }
}

class GitAddTool(private val git: com.sa.aidesktop.core.git.GitService): AITool {
    override val id = "git.add"
    override val description = "Stage explicitly selected real repository-relative files."
    override val risk = ToolRisk.WRITE
    override val parameterHints = mapOf("paths" to "Comma-separated repository-relative paths to stage")
    override suspend fun execute(input: Map<String, String>): AIResult<ToolResult> {
        val paths = input["paths"].orEmpty().split(',').map(String::trim).filter(String::isNotBlank)
        return git.add(paths).toAi("git.add") { "Staged ${paths.size} selected path(s)." }
    }
}

class GitCommitTool(private val git: com.sa.aidesktop.core.git.GitService): AITool {
    override val id = "git.commit"
    override val description = "Create a real local Git commit from currently staged changes."
    override val risk = ToolRisk.WRITE
    override val parameterHints = mapOf("message" to "Meaningful commit message based on actual staged changes")
    override suspend fun execute(input: Map<String, String>) =
        git.commit(input["message"].orEmpty()).toAi("git.commit") { it }
}

class GitFetchTool(private val git: com.sa.aidesktop.core.git.GitService): AITool {
    override val id = "git.fetch"
    override val description = "Fetch real Git remote updates without changing tracked working files."
    override val risk = ToolRisk.EXECUTION
    override val parameterHints = mapOf("remote" to "Optional remote name")
    override suspend fun execute(input: Map<String, String>) =
        git.fetch(input["remote"]).toAi("git.fetch") { it }
}

class GitPushTool(private val git: com.sa.aidesktop.core.git.GitService): AITool {
    override val id = "git.push"
    override val description = "Push real commits to an explicitly selected remote/branch after confirmation."
    override val risk = ToolRisk.WRITE
    override val parameterHints = mapOf(
        "remote" to "Explicit remote name, usually origin",
        "branch" to "Explicit branch name",
        "confirmed" to "Must be true after the existing approval flow confirms the push"
    )
    override suspend fun execute(input: Map<String, String>) =
        git.push(input["remote"], input["branch"], input["confirmed"]?.toBooleanStrictOrNull() == true)
            .toAi("git.push") { it }
}

class GitPullTool(private val git: com.sa.aidesktop.core.git.GitService): AITool {
    override val id = "git.pull"
    override val description = "Fast-forward-only pull into the real workspace after confirmation and a clean working tree."
    override val risk = ToolRisk.WRITE
    override val parameterHints = mapOf(
        "remote" to "Explicit remote name",
        "branch" to "Explicit branch name",
        "confirmed" to "Must be true after approval"
    )
    override suspend fun execute(input: Map<String, String>) =
        git.pull(input["remote"], input["branch"], input["confirmed"]?.toBooleanStrictOrNull() == true)
            .toAi("git.pull") { it }
}

class GitCloneTool(private val git: com.sa.aidesktop.core.git.GitService): AITool {
    override val id = "git.clone"
    override val description = "Clone a real Git repository into a controlled workspace-relative destination."
    override val risk = ToolRisk.WRITE
    override val parameterHints = mapOf("url" to "HTTPS/SSH Git URL", "destination" to "Workspace-relative empty destination directory")
    override suspend fun execute(input: Map<String, String>) =
        git.clone(input["url"].orEmpty(), input["destination"].orEmpty()).toAi("git.clone") { "Repository cloned successfully." }
}

class GitBranchTool(private val git: com.sa.aidesktop.core.git.GitService): AITool {
    override val id = "git.branch"
    override val description = "List real branches or create a new branch."
    override val risk = ToolRisk.WRITE
    override val parameterHints = mapOf("name" to "Omit to list branches; provide a valid name to create a branch")
    override suspend fun execute(input: Map<String, String>) =
        git.branch(input["name"]?.trim()?.takeIf(String::isNotBlank)).toAi("git.branch") { it.joinToString("\n") }
}

class GitCheckoutTool(private val git: com.sa.aidesktop.core.git.GitService): AITool {
    override val id = "git.checkout"
    override val description = "Switch the real repository to an existing branch."
    override val risk = ToolRisk.WRITE
    override val parameterHints = mapOf("name" to "Existing branch name")
    override suspend fun execute(input: Map<String, String>) =
        git.checkout(input["name"].orEmpty()).toAi("git.checkout") { "Checked out ${input["name"]}." }
}

class GitMergeTool(private val git: com.sa.aidesktop.core.git.GitService): AITool {
    override val id = "git.merge"
    override val description = "Merge an existing branch into the current real Git branch."
    override val risk = ToolRisk.WRITE
    override val parameterHints = mapOf("name" to "Existing branch to merge")
    override suspend fun execute(input: Map<String, String>) =
        git.merge(input["name"].orEmpty()).toAi("git.merge") { it }
}

class GitHubAccountStatusTool(private val accounts: GitHubAccountStore): AITool {
    override val id = "github.account_status"
    override val description = "Show the configured GitHub account login without exposing credentials."
    override val risk = ToolRisk.READ_ONLY
    override val parameterHints = emptyMap<String, String>()
    override suspend fun execute(input: Map<String, String>) =
        AIResult.Success(ToolResult(accounts.active()?.let { "GitHub account: ${it.login}" } ?: "No GitHub account is configured."))
}

class GitHubListRepositoriesTool(private val api: GitHubApiClient): AITool {
    override val id = "github.list_repositories"
    override val description = "List real repositories available to the authenticated GitHub account."
    override val risk = ToolRisk.READ_ONLY
    override val parameterHints = emptyMap<String, String>()
    override suspend fun execute(input: Map<String, String>) =
        api.listRepositories().toAi("github.list_repositories") { repos ->
            repos.take(100).joinToString("\n") { "${it.fullName} ${if (it.private) "[private]" else "[public]"} clone=${it.cloneUrl}" }
        }
}

class GitHubCreateRepositoryTool(private val api: GitHubApiClient): AITool {
    override val id = "github.create_repository"
    override val description = "Create a real GitHub repository for the authenticated user."
    override val risk = ToolRisk.WRITE
    override val parameterHints = mapOf("name" to "Repository name", "description" to "Optional description", "private" to "true/false")
    override suspend fun execute(input: Map<String, String>) =
        api.createRepository(input["name"].orEmpty(), input["description"].orEmpty(), input["private"]?.toBooleanStrictOrNull() ?: true)
            .toAi("github.create_repository") { "${it.fullName}\nclone=${it.cloneUrl}\nurl=${it.htmlUrl}" }
}

private inline fun <T,R> com.sa.aidesktop.core.git.GitResult<T>.toAi(
    action: String,
    transform: (T) -> R,
    changed: Boolean = false
): AIResult<ToolResult> = when (this) {
    is com.sa.aidesktop.core.git.GitResult.Success -> AIResult.Success(ToolResult("$action success:\n${transform(value)}".take(32_000), changed = changed))
    is com.sa.aidesktop.core.git.GitResult.Failure -> AIResult.Failure(AIError.Execution("$action failed: ${error.message()}"))
}

private inline fun <T,R> GitHubResult<T>.toAi(
    action: String,
    transform: (T) -> R,
    changed: Boolean = false
): AIResult<ToolResult> = when (this) {
    is GitHubResult.Success -> AIResult.Success(ToolResult("$action success:\n${transform(value)}".take(32_000), changed = changed))
    is GitHubResult.Failure -> AIResult.Failure(AIError.Execution("$action failed: $message"))
}

private fun com.sa.aidesktop.core.git.GitError.message(): String = when (this) {
    is com.sa.aidesktop.core.git.GitError.Validation -> message
    is com.sa.aidesktop.core.git.GitError.NotAvailable -> message
    is com.sa.aidesktop.core.git.GitError.Command -> "exit $code: $message"
    is com.sa.aidesktop.core.git.GitError.Permission -> message
    is com.sa.aidesktop.core.git.GitError.AuthenticationRequired -> message
    is com.sa.aidesktop.core.git.GitError.DirtyWorkspace -> message
}
