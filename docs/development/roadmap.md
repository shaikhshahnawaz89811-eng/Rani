# Development Roadmap

1. Core + Desktop — current
2. Window Manager — current foundation
3. Persistent File System
4. Professional Editor
5. Real Terminal/Runtime adapters
6. Test runner
7. Real Git/JGit + GitHub authentication
8. Offline local model adapter
9. AI tool/permission system
10. Security hardening
11. Voice adapters
12. Extensible future applications

Each phase: design -> contract -> implementation -> unit tests -> integration tests -> UI -> manual test -> Git commit.

## Build 2/8 — Window Manager
- Added persistent window bounds and responsive initial placement.
- Added focused-window z ordering.
- Added minimize -> restore behavior with top-window focus recovery.
- Added maximize -> restore with saved bounds.
- Added draggable title bars constrained to the desktop viewport.
- Added bottom-right window resizing with minimum dimensions.
- Added taskbar running-window indicators.
- Added unit coverage for maximize/restore and resize limits.

Validation note: source archive was checked with `git diff --check` and ZIP integrity testing. Android/Gradle execution was not available in the current build environment, so no local APK/test result is claimed.
