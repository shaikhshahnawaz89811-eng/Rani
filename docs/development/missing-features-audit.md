# Missing / Deferred Capability Audit

## Fixed in this audit
- `browser.open`/back/forward/reload/stop/scroll/focus no longer require manual approval —
  they only view/navigate a page, so they run like any other read-only tool. `upload` and
  `download` still require approval since those move a real file.
  **CORRECTION (later change, not part of this audit pass):** `BrowserTools.kt`'s
  `riskForElementAction` subsequently downgraded `click`, `type`, `select`, and `check` to
  `ToolRisk.READ_ONLY` as well — the code comment there marks this "TEMPORARY (per explicit
  user request)" so simple "play this" style requests wouldn't stop for approval. This line was
  never updated to match, so it no longer describes what the app actually does: the AI can now
  click any button, type into any field, and toggle any checkbox/radio on any open webpage
  without a human approval step. `ai_web.type_message`/`ai_web.send_message` (the AI-website
  composer/send tools) are unaffected and still require approval.
- AI/Terminal/Git windows can no longer be resized below the minimum height their own header +
  toolbar + input row need, which previously made input fields/suggestions overlap when a
  window was shrunk a lot.
- Raw `AIError`/exception objects (e.g. `ModelUnavailable(message=...)`) are no longer shown
  verbatim in AI chat; the real message text is preserved but the Kotlin wrapper is stripped.
- One AI reply can now ask for more than one action needing approval (e.g. "message someone and
  play a song" in one prompt). Previously the router surfaced only the first write-tool request
  from a turn and silently dropped the rest; now every requested action is queued, shown one at
  a time as "Approval required (1 of N)", and — once every step in that batch is approved and
  actually executed — the model gets all the real results back together so it can finish the
  reply. Cancelling, or a step failing, stops the rest of that batch instead of skipping ahead.
- Window resizing now supports all four edges and all four corners.
- Window geometry is clamped after portrait/landscape size changes.
- Browser instances are independent desktop windows rather than tabs.
- Browser has address/search, back, forward, refresh, and native page scrolling.
- Browser can create additional browser windows.
- Long Git/File Manager content can scroll.
- AI can request window movement/resizing/state actions through a protected tool and the UI provides an explicit Apply approval step.
- Android voice input now exposes recognition text and requests RECORD_AUDIO permission before listening.
- Taskbar network/volume/battery indicators open the corresponding Android settings when tapped.
- Terminal timeout no longer waits indefinitely on a blocked output reader.
- Missing ModelAdapter and ProjectManager concrete foundation implementations were added.

## Fixed in Build 9 (video bug audit)
- Root Compose layout now consumes `WindowInsets.systemBars` + IME insets, so
  the workspace, window default positions, and clamping logic are computed
  from the real usable screen area instead of the full physical screen. This
  removes the status-bar overlap and unreachable top window controls seen in
  the test recording.
- `MainActivity` is locked to portrait (see below) to stop the rotation
  render corruption seen in the test recording, rather than leaving rotation
  unhandled.

## Still intentionally deferred
1. A real packaged on-device LLM. `OfflineDemoAI` is a deterministic adapter used for architecture/testing. A real local model engine must be supplied for actual generative offline AI.
2. Fully packaged language runtimes. Python/Node/Java/Clang availability is detected/used only when a compatible executable/runtime exists. The APK does not secretly contain all toolchains.
3. A bundled Git executable. Git UI/backend is real when Git is available; Android does not provide `git` by default.
4. Persistent Room-backed project metadata/window state/memory. Current project and memory foundations are modular, but long-term persistence is not yet wired into the UI.
5. Full offline STT. Android SpeechRecognizer may depend on device speech services. The interface is replaceable so an offline engine can be added later.
6. Full browser feature set such as downloads, file upload mediation, multiple-page tab strip, bookmarks, history database, and content permissions.
7. Full edge-resize cursor feedback and snap-to-edge behavior. Geometry behavior is implemented; visual cursor affordances can be added later.
8. **Landscape/multi-orientation desktop support.** Build 9 locks the app to
   portrait (`android:screenOrientation="portrait"`) instead of implementing
   full landscape re-flow, because the window manager's default positions,
   taskbar, and panel sizing are hand-tuned for one canvas shape and
   correctly re-flowing every open window, the taskbar, and IME handling for
   a second orientation is a distinct feature, not a one-line fix. Tracked as
   future work; `DesktopWindowManager.resizeWithinWorkspace`/
   `clampToWorkspace` already accept arbitrary workspace dimensions, so a
   landscape layout can build on top of them later.
9. **Rani Mini Assistant (offline small-model conversational/coding
   assistant), Rani-specific package manager (`rani install/remove/...`),
   sandboxed Android userspace runtime, and offline STT/TTS voice pipeline**
   described in later product proposals are architecture-only proposals at
   this point. None of this exists in the codebase yet. Building any of it
   for real requires: selecting and licensing an actual quantized on-device
   model + inference runtime sized for a phone, an actual offline STT/TTS
   engine, and a real Android-compatible userspace/package-manager design —
   each a multi-week effort with real testing on a device, not something to
   assert as working without that verification.

These are explicit capability boundaries, not hidden broken endpoints.
