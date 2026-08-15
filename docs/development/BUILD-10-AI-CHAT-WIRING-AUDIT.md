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

- The existing real llama.cpp/Android GGUF engine remains unchanged as the inference runtime.
- Conversation history is supplied to the local model.
- The compact local model is not treated as a reliable function-calling model.
- Explicit, unambiguous commands such as arithmetic, time, date, battery, file listing/reading/search,
  project inspection and read-only Git status/diff/log/remote use the same real ToolRegistry through
  `LocalIntentRouter`.
- Ambiguous requests still go to the real local model instead of being guessed into a tool call.

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
