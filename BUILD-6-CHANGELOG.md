# Build 6/8 — Git/GitHub + Build/Test foundation

- Replaced the minimal Git contract with a structured GitService covering init, clone, status, add, commit, push, pull, fetch, branch, checkout, merge, diff, log and remote.
- Added CommandGitService with a process backend and structured error mapping.
- Added validation for repository URLs and workspace-relative paths.
- Git executable absence is reported as a controlled NotAvailable error rather than crashing the app.
- Git UI now reads the project workspace, displays branch/changes, refreshes status, stages changed files, commits, push/pull/fetch/diff operations.
- Sensitive remote authentication remains outside UI and is not logged.
- Added generic BuildService and structured BuildResult/BuildLog models.
- Added generic TestRunner with discovery, execution and result models.
- Added unit tests for Git, build and test contracts.
- Existing Phase 1–5 files and workflows retained.
