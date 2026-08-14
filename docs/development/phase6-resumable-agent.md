# Phase 6 — Resumable Autonomous Task Engine

Implemented on top of the existing Phase 1–5 services.

## Reused
- `ModelRouter` / `GroqClient`
- `ToolRegistry` / `ToolExecutionGateway` / `PermissionGate`
- `CodingTaskStore` (extended; no second task database)
- Phase-2 browser services
- Phase-3 AI website service
- Phase-4 workspace/coding/terminal services
- Phase-5 Git/GitHub services and `SecureStore`

## Added
- `AgentTaskState`, `AgentStep`, `AgentTaskRecord`
- `TaskEngine`
- Phase-6 task status/pause/cancel tool definitions
- persistent reconciliation before resume
- bounded retry handling
- approval/auth/user waiting states
- task protection signal for later window-locking work
- bounded context and tool-result storage

## Safety
The engine never treats a saved `started` state as proof of completion. Resume first reconciles live state. Side effects still pass through the existing gateway and approval boundary. Secrets are not persisted in task state.

## Verification
Full Android Gradle compilation could not be run in the current environment because Gradle 9.6.1 could not be downloaded from `services.gradle.org` due DNS/network restrictions. No build/test PASS is claimed.
