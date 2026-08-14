# Build 7/8 — Offline AI + Tool System + Project Context

- Added AI profile abstraction for Sara.
- Added AI request/response/error contracts.
- Added interchangeable LocalModelEngine and ModelAdapter contracts.
- Added need-based ProjectContext.
- Added AITool, ToolRegistry and PermissionGate.
- Added read_file, search_files and protected write_file tools.
- Added approval-aware Sara AI workspace UI.
- Added deterministic offline AI test implementation.
- Added AI service unit tests.
- No Termux dependency introduced.
- Destructive/write/sensitive actions are not auto-applied by the demo AI.
