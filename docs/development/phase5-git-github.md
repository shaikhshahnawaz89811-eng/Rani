# Phase 5 — Git / GitHub / Secure Authentication

Phase 5 extends the existing Phase 1–4 architecture. It reuses the existing terminal, SecureStore, ToolRegistry, ToolExecutionGateway and PermissionGate.

## Real Git

`CommandGitService` performs real Git commands in the controlled project workspace. It provides:

- status with branch, staged/unstaged state and ahead/behind information
- diff and staged diff
- log
- remotes
- init
- clone
- add
- commit
- branch/create branch
- checkout
- merge
- fetch
- pull with explicit confirmation and `--ff-only`
- push with explicit confirmation and no force-push

Git command failures, authentication errors and dirty-workspace pull protection are surfaced as real failures.

## GitHub authentication

`GitHubAccountStore` stores account metadata in normal app preferences and the GitHub credential only through the existing Android-Keystore-backed `SecureStore`.

The GitHub token is:

- entered through the Git window's password field
- sent directly to GitHub's HTTPS API for verification
- cleared from the UI after verification
- never returned by AI tools
- never written to task history or logs

`GitHubApiClient` currently provides authenticated user verification, repository listing and repository creation.

The stored GitHub API credential does not get injected into arbitrary Git command lines. Git push/clone/pull therefore still depend on the legitimate Git HTTPS credential helper or SSH authentication available on the device. If Git reports that authentication is required, Sara reports that state instead of exposing the stored API token or fabricating success.

## Permission boundary

All Git/GitHub tools are registered in the existing ToolRegistry and continue through the existing ToolExecutionGateway and PermissionGate.

Network/write operations remain approval-controlled. Force push is not implemented.

## Validation

The project was statically checked for balanced Kotlin delimiters in all Phase-5 changed Kotlin files.

Gradle test execution was attempted. The wrapper could not download Gradle 9.6.1 because this environment cannot resolve `services.gradle.org`; therefore build/tests are **NOT VERIFIED**.
