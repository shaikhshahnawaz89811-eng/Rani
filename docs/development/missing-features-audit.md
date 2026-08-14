# Missing / Deferred Capability Audit

## Fixed in this audit
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

## Still intentionally deferred
1. A real packaged on-device LLM. `OfflineDemoAI` is a deterministic adapter used for architecture/testing. A real local model engine must be supplied for actual generative offline AI.
2. Fully packaged language runtimes. Python/Node/Java/Clang availability is detected/used only when a compatible executable/runtime exists. The APK does not secretly contain all toolchains.
3. A bundled Git executable. Git UI/backend is real when Git is available; Android does not provide `git` by default.
4. Persistent Room-backed project metadata/window state/memory. Current project and memory foundations are modular, but long-term persistence is not yet wired into the UI.
5. Full offline STT. Android SpeechRecognizer may depend on device speech services. The interface is replaceable so an offline engine can be added later.
6. Full browser feature set such as downloads, file upload mediation, multiple-page tab strip, bookmarks, history database, and content permissions.
7. Full edge-resize cursor feedback and snap-to-edge behavior. Geometry behavior is implemented; visual cursor affordances can be added later.

These are explicit capability boundaries, not hidden broken endpoints.
