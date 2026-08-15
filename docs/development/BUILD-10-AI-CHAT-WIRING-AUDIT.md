# Build 10 — AI Chat Wiring Audit

This pass reviewed the AI chat path against the existing module documentation and preserved every existing
production subsystem.

## Chat wiring

`SADesktopApp -> AIWindow -> ModelRouter -> GroqClient/LocalLlamaEngine -> ToolRegistry -> ToolExecutionGateway -> real service -> result -> chat UI`

There is one ToolRegistry and one PermissionGate. The router now exposes the actual registered tools instead
of maintaining a stale hard-coded subset.

## Groq

- Uses the official Groq OpenAI-compatible chat-completions endpoint already used by the project.
- Preserves the configured model, timeout, retry and secure API-key storage.
- Preserves real 401/403, 429, 5xx, timeout, network and malformed-response handling.
- Uses the provider's assistant `tool_calls` -> `tool` result message protocol for multi-step local-tool calling.
- Read-only tools execute automatically through the existing gateway.
- Writes, execution and sensitive actions return an explicit approval request to the UI.
- After an approved normal-chat action, the real tool result is sent back to the model so the chat turn can finish.
- Unknown tool names are never executed or reported as successful.

## Local GGUF

- No on-device inference runtime is bundled in this build (see LocalLlamaEngine's class doc):
  the previous `dev.ffmpegkit-maintained:llama-android` dependency never resolved, so the local
  path now honestly reports `ModelUnavailable` instead of claiming inference it cannot perform.
- Explicit, unambiguous commands such as arithmetic, time, date, battery, file listing/reading/search,
  project inspection and read-only Git status/diff/log/remote use the same real ToolRegistry through
  `LocalIntentRouter` regardless of local-model availability, and are unaffected by this.
- Ambiguous requests fall through to Groq (cloud); with no API key and no local runtime, they
  correctly report unavailable rather than fabricating a response.

## User-visible state

The Sara header now shows provider tier, tool count and current agent state. Tool execution traces are
shown in chat so the user can distinguish model text from a real tool result/approval.

## Conversation continuity

The chat UI now supplies recent conversation context, so follow-up questions can refer to messages from
the current session. This is session conversation context, not a claim of persistent long-term memory.

## Existing capabilities retained

The documented file, terminal, browser, AI-website, project/coding, Git/GitHub, window-control, voice,
approval and resumable-agent modules remain present. No existing production module or test was deleted.

## Honest boundaries

The app still does not claim capabilities that the existing documentation explicitly marks as deferred:
packaged language runtimes, a bundled Git executable, full offline STT, full browser feature parity, or
persistent Room-backed memory.
