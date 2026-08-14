# AI Boundaries

The AI layer is intentionally separated from Android APIs. The model can only request typed `AITool` operations. Each tool validates input and returns `AIResult`; raw exceptions do not cross into UI code.

`PermissionGate` treats read-only tools as safe and requires approval for writes, command execution and sensitive Git operations. The current Build 7 UI exposes the approval state and does not automatically apply a write request.

`ProjectContext` carries only relevant files, selected code, compiler errors, test results and Git changes. The whole workspace is not loaded for every request.

`OfflineDemoAI` is a deterministic implementation used to validate boundaries. It is not presented as a bundled language model. A real local model must implement `LocalModelEngine` or `ModelAdapter`.
