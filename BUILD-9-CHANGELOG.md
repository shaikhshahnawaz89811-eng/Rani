# Build 9 — Video Bug Audit Fixes

Source: 60s screen recording of the running APK on a real device (720x1600,
Android status/nav bars visible, gesture navigation).

## Bugs fixed
1. **Status bar overlap (video 00:05-00:25)** — `SADesktopApp` measured its
   workspace from the full physical screen even though `MainActivity` calls
   `enableEdgeToEdge()`. Window title bars (including AI Assistant's close
   button) rendered under the status bar and were unreachable/overlapping the
   clock and network icons.
   Root cause: `BoxWithConstraints` had no inset consumption.
   Fix: `Modifier.windowInsetsPadding(WindowInsets.systemBars).imePadding()`
   added to the root `BoxWithConstraints` in
   `app/src/main/java/com/sa/aidesktop/ui/SADesktopApp.kt`, so `maxWidth`/
   `maxHeight` (and therefore `clampToWorkspace`, `windowDefaults`,
   `resizeWithinWorkspace`) already reflect the real usable area.
2. **Unreachable top window controls (video 00:05-00:25)** — direct
   consequence of (1); fixed by the same change.
3. **Rotation corruption (video ~00:35-00:45)** — rotating the device
   produced a corrupted/rotated render (sideways taskbar text, sideways
   keyboard) because the desktop/window-manager layout is designed and hand
   tuned for a single portrait canvas; nothing recalculated a landscape
   layout for it.
   Fix: `MainActivity` is locked to `android:screenOrientation="portrait"`
   in `AndroidManifest.xml`. This is a deliberate scope decision (see
   `docs/development/missing-features-audit.md`), not a silent removal —
   full landscape support (re-flowing every open window, taskbar, and the
   AI/terminal panels) is tracked as future work.

## Verification
Static code review only. `./gradlew` could not download its distribution in
this environment (no network egress), so no local build/instrumented test
run is claimed for this pass — consistent with prior builds (see
`docs/development/roadmap.md`, Build 2/8 validation note). The JVM unit
tests under `app/src/test` were not modified by this pass and should be run
with `./gradlew test` in an environment with network access before release.

## Preserved
No files were deleted. No existing feature, screen, service, or test was
removed. `OfflineDemoAI`, `EmbeddedTerminalService`, and all other modules
are unchanged in this pass — see
`docs/development/missing-features-audit.md` for what remains intentionally
deferred (real offline LLM, bundled runtimes, offline STT, etc.).
