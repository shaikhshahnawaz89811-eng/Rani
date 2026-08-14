# Phase 7 — Offline AI, Tools and Project Context

Build 7 introduces stable AI contracts without coupling the desktop UI to a model implementation.

## Contracts
- `AIService` — chat, code explanation and error analysis.
- `LocalModelEngine` / `ModelAdapter` — interchangeable local inference layer.
- `ProjectContext` — need-based project context instead of loading the whole project.
- `AITool` / `ToolRegistry` — controlled developer actions.
- `PermissionGate` — read-only actions may run without approval; write, execution and sensitive Git actions require approval.

## Built-in tools
- `read_file`
- `search_files`
- `write_file`

The write tool is never automatically invoked by the demo AI. A future model adapter may request it, but the UI must present the proposed change before applying it.

## Local model integration
`OfflineDemoAI` is a deterministic test implementation, not a bundled LLM. A real on-device model can be connected through `LocalModelEngine`/`ModelAdapter` without rewriting the UI or tool contracts.
