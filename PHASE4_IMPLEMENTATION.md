# Phase 4 implementation notes

This build extends the existing Phase-1/2 architecture and restores the missing universal Phase-3 AI-web layer required by the Phase-4 request.

## Added
- `core/website/AIWebService.kt`: live-page AI website detection/interaction using Phase-2 WebView inspection.
- `core/website/AIWebTools.kt`: real AI-web detect/inspect/type/send/wait/read/upload tools.
- `core/workspace/ProjectWorkspace.kt`: safe ZIP extraction, bounded workspace creation, real project discovery.
- `core/coding/CodingTaskModels.kt`: minimal resumable coding state model.
- `core/coding/CodingTaskStore.kt`: durable task metadata only; no secrets/source dumps.
- `core/coding/CodingAgent.kt`: project inspection/planning/tool execution/build primitives.
- `core/coding/ProjectCodingTools.kt`: bounded tree inspection, project discovery, safe ZIP extraction, real build/test execution.

## Reused
- `FileService` / `AndroidProjectFileService`
- `EmbeddedTerminalService` / `EmbeddedShellBackend`
- `ToolRegistry` / `ToolExecutionGateway` / `PermissionGate`
- `GroqClient` / `ModelRouter`
- Phase-2 `AndroidBrowserService` and browser tools
- existing desktop window system

## Safety
- No fake build/test output.
- No fake browser/AI responses.
- ZIP extraction rejects traversal, duplicate entries and configured size/count limits.
- State-changing actions remain behind the existing permission gateway.
- No password/OTP/session-token handling was added.
- No remote Git push/account management was added.
