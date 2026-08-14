# Phase 2 — Real Universal Browser

Phase 2 adds a real Android WebView browser foundation to the existing SA Desktop architecture.

## Reused Phase-1 architecture

- `DesktopWindowManager` / `WindowManager`
- `AITool`, `ToolRegistry`
- `ToolExecutionGateway` / `PermissionGate`
- `ModelRouter` / real Groq function-calling
- `AndroidProjectFileService` workspace boundary
- existing desktop browser window type

## Real browser implementation

`AndroidBrowserService` owns registered WebViews and reports real browser state.

Implemented operations:

- open/navigate with a real HTTP(S) WebView load and a bounded 30-second navigation wait
- back / forward / reload / stop
- loading progress, URL, title and main-frame errors
- DOM/page inspection through the actual loaded document
- semantic element references generated from the current DOM
- click, type, clear, select, check, focus and scroll
- page-text search
- DownloadManager-backed downloads with session cookies where available
- WebView file chooser / FileProvider-backed upload selection
- stale-reference rejection by invalidating inspection references on each fresh inspection/navigation

## AI integration

Browser tools are registered in the existing `ToolRegistry` and exposed to Groq through `ModelRouter`.

Read-only tools:
- `browser.inspect`
- `browser.search`

State-changing/browser interaction tools require the existing approval path:
- `browser.open`
- `browser.back`
- `browser.forward`
- `browser.reload`
- `browser.stop`
- `browser.click`
- `browser.type`
- `browser.clear`
- `browser.select`
- `browser.check`
- `browser.scroll`
- `browser.focus`
- `browser.download`
- `browser.upload`

Read-only tool calls are executed through `ToolExecutionGateway` and their real results are fed back to Groq for a bounded reasoning loop.

## Explicit limitations

- CAPTCHA, OTP, 2FA, biometric confirmation and login are not bypassed.
- Password values are not included in inspection output.
- The browser does not claim server-side form/upload completion when only the WebView file selection has completed.
- Download completion is reported from Android `DownloadManager`.
- No website-specific automation is implemented.
- No coordinate-based browser automation is used.
- No fake DOM, fake page content or simulated success is used.
- No Phase 3+ account, credential, task-state, window-locking or site-specific systems are added.

## Validation status

The source tree was inspected and a Gradle test command was attempted. The local environment could not download Gradle 9.6.1 because outbound DNS/network access was unavailable, so Android compilation and device-level browser interaction could not be truthfully marked as passed. No claim of a successful APK build or real device interaction is made.
