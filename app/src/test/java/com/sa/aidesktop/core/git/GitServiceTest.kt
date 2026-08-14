package com.sa.aidesktop.core.git

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GitServiceTest {
    private class FakeBackend(private val responses: MutableMap<String, GitCommandResult> = mutableMapOf()) : GitBackend {
        val calls = mutableListOf<List<String>>()
        override suspend fun run(args: List<String>): GitCommandResult {
            calls += args
            return responses[args.joinToString(" ")] ?: GitCommandResult(0, "", "")
        }
    }

    @Test fun blankCommitRejected() = runBlocking {
        val service = CommandGitService(File("."), FakeBackend())
        assertTrue(service.commit("").isFailure())
    }

    @Test fun statusParsesStagedAndUntrackedChanges() = runBlocking {
        val backend = FakeBackend(mutableMapOf(
            "git status --porcelain=v1 -b" to GitCommandResult(
                0,
                "## main...origin/main [ahead 2, behind 1]\nM  app/src/Main.kt\n?? notes.txt\nR  old.txt -> new.txt\n",
                ""
            )
        ))
        val status = (CommandGitService(File("."), backend).status() as GitResult.Success).value
        assertEquals("main", status.branch)
        assertEquals(2, status.ahead)
        assertEquals(1, status.behind)
        assertEquals(3, status.changes.size)
        assertTrue(status.changes.first().staged)
        assertEquals("Untracked", status.changes[1].state)
        assertEquals("Renamed", status.changes[2].state)
    }

    @Test fun addRejectsTraversal() = runBlocking {
        val service = CommandGitService(File("."), FakeBackend())
        assertTrue(service.add(listOf("../outside.txt")).isFailure())
    }

    @Test fun pushRequiresExplicitConfirmation() = runBlocking {
        val backend = FakeBackend(mutableMapOf(
            "git status --porcelain=v1 -b" to GitCommandResult(0, "## main\n", ""),
            "git remote -v" to GitCommandResult(0, "origin\thttps://github.com/example/repo.git\t(fetch)\norigin\thttps://github.com/example/repo.git\t(push)\n", ""),
            "git push --set-upstream origin main" to GitCommandResult(0, "Everything up-to-date\n", "")
        ))
        val service = CommandGitService(File("."), backend)
        assertTrue(service.push(confirmed = false).isFailure())
        assertTrue(service.push(confirmed = true).isSuccess())
    }

    @Test fun pullRefusesDirtyWorkspace() = runBlocking {
        val backend = FakeBackend(mutableMapOf(
            "git status --porcelain=v1 -b" to GitCommandResult(0, "## main\nM  file.txt\n", "")
        ))
        val service = CommandGitService(File("."), backend)
        val result = service.pull(confirmed = true)
        assertTrue(result is GitResult.Failure)
        assertTrue((result as GitResult.Failure).error is GitError.DirtyWorkspace)
    }

    private fun <T> GitResult<T>.isFailure() = this is GitResult.Failure
    private fun <T> GitResult<T>.isSuccess() = this is GitResult.Success
}
