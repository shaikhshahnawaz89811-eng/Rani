# Phase 7 + 8 audit

## Phase 7
- Reused `DesktopWindowManager`, `DesktopWindow`, and Phase-6 `AgentTaskRecord.requiresProtectedInteraction`.
- Added task-scoped protection fields and a pure policy mapping task operation/state to relevant window types.
- Protection is released for user/auth/approval/network waits and terminal task states, so the user can perform required interactions.
- Protected windows reject close/minimize/move/resize through the existing window manager; unrelated windows remain usable.
- Rotation continues to use the existing Compose workspace clamping and Android configuration behavior; no fixed portrait/landscape coordinates were introduced.

## Phase 8
- The current project contains no real local inference runtime or packaged model. The prior `OfflineDemoAI` was rule-based and was not a model.
- It was replaced with `UnavailableOfflineAI`, which returns a real `ModelUnavailable` error rather than fabricating an offline response.
- `ModelRouter` now labels the offline tier as unavailable and preserves the same AI/tool/permission architecture.
- No fake model, fake inference, or runtime model download was introduced.

## Regression limitations
- Full Android Gradle build remains environment-dependent. If Gradle cannot resolve/download its distribution/dependencies, build/test is reported as NOT VERIFIED rather than PASS.
