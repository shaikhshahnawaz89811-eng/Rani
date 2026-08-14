# Build 5/8 — Terminal + Runtime Foundation

- Added structured TerminalResult and TerminalSessionState.
- Added restricted EmbeddedShellBackend without `sh -c` command execution.
- Added workspace-bound path validation, command allowlist, timeout, cancellation and output limits.
- Added persistent terminal working directory and command history.
- Added generic CodeRunner abstraction and runtime registry for Python, Java, JavaScript, C and C++.
- Runtime availability is capability-based; a runtime is only enabled when its executable exists on the device.
- Added unit coverage for runtime registry and preserved terminal safety tests.
