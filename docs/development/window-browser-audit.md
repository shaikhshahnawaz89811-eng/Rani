# Window / Browser Audit

## Window geometry
- Window bounds are stored independently from UI.
- Portrait and landscape changes trigger workspace clamping.
- Normal windows can move without leaving the desktop work area.
- Normal windows can resize from left, top, right, bottom and all four corners.
- Minimum dimensions are enforced by the window manager.
- Maximize stores restore bounds and restore returns to them.
- Minimize removes the window from the visible desktop while preserving state.
- Close removes the window and refocuses the top remaining normal window.

## Scroll policy
- Code editor: vertical editor scrolling and horizontally scrollable tabs.
- Explorer: vertical scrolling.
- AI: vertical message history and horizontal tool strip.
- Terminal: vertical output scrolling.
- Git: vertical scrolling for long change/log content.
- File Manager: vertical item scrolling.
- Settings: vertical scrolling.
- Browser: WebView-native page scrolling; the outer desktop window does not compete with page scrolling.

## Browser windows
Browser instances are independent desktop windows, not browser tabs. Each instance owns a separate WebView state and can be moved, resized, minimized, maximized and closed independently. Desktop, taskbar, Start menu and Browser "New" control can create another browser window.

## AI window control
The AI tool registry includes `control_window`. It can request move/resize/minimize/maximize/restore/focus/close actions. These actions are classified as WRITE risk and require explicit approval before execution.
