# SA AI Desktop — Architecture

The project is a self-contained Android desktop/workstation foundation. UI modules call typed contracts; implementations can be replaced without rewriting the desktop shell.

Desktop UI -> WindowManager/ApplicationRegistry -> feature service -> backend/adapter.

Initial contracts included:
- WindowManager
- FileService
- AIService
- GitService
- TerminalService

The first build intentionally uses safe demo/local adapters for features that need privileged or external infrastructure. The UI is not coupled to those adapters.
