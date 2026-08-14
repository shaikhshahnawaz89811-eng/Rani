# SA AI Desktop Android Workstation

Fresh Android/Jetpack Compose foundation built from the supplied desktop reference image and master architecture prompt.

## Visual target
The desktop shell is intentionally modeled on the reference: dark mountain/galaxy wallpaper, left vertical icons, floating rounded windows, neon purple/blue accents, bottom taskbar, desktop-style title bars and compact system tray.

## Current build
This is the first real build foundation, not a screenshot or static mockup. Window lifecycle is interactive; the Developer Workspace contains a project tree and editable code; Terminal has a safe embedded command backend; Git has status/commit/push confirmation flow; File Manager displays the project; Sara uses an offline-ready service contract.

## Important runtime boundary
Android does not provide a general desktop shell or unrestricted process execution to an ordinary app. The initial build therefore uses explicit safe adapters rather than pretending that arbitrary shell/runtime/GitHub execution is already available. Real runtimes, JGit/GitHub auth, persistent project storage and a local LLM are next adapters behind the same contracts.

## Build
Use JDK 17 and Gradle 8.11.1 or run the included GitHub Actions workflow. The supplied environment for this artifact did not contain an Android SDK/Gradle installation, so an APK was not falsely claimed as locally compiled.

## Project structure
app/src/main/java/com/sa/aidesktop/
- core/window — window contract and manager
- core/files — file contract and project adapter
- core/ai — AI contract and offline adapter
- core/git — Git contract and confirmation-aware adapter
- core/terminal — safe terminal contract
- ui — desktop shell and windows
- ui/theme — centralized theme

docs/ contains architecture, contracts, testing, security and roadmap documentation.

## Current Build
Build 2/8 — Desktop Window Manager.

The desktop now has a persistent window state model, responsive initial window placement, focus/z-order handling, minimize/restore, maximize/restore bounds, constrained title-bar dragging, and bottom-right resizing. The taskbar shows which registered applications are running.

The GitHub Actions workflow remains the intended build/test path. The current generation environment did not have a Gradle executable or Android SDK available, so this archive does not claim a locally built APK.

## Build 7 status
Offline AI contracts, Sara profile abstraction, project-context model, protected AI tools and approval boundaries are implemented. A deterministic offline AI implementation is included for testing; a real on-device LLM can be plugged in through `LocalModelEngine`/`ModelAdapter`.
