package com.sa.aidesktop.core.window

enum class WindowType { DEVELOPER, AI, TERMINAL, GIT, FILES, SETTINGS, BROWSER }
enum class WindowState { NORMAL, MINIMIZED, MAXIMIZED }
enum class ResizeEdge { LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

data class WindowBounds(
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f
)

data class DesktopWindow(
    val id: String,
    val type: WindowType,
    val title: String,
    val z: Int,
    val state: WindowState = WindowState.NORMAL,
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
    val focused: Boolean = false,
    val restoreBounds: WindowBounds? = null,
    val protectedByTaskId: String? = null,
    val protectionReason: String? = null
)

interface WindowManager {
    val windows: List<DesktopWindow>
    fun open(type: WindowType)
    fun openNew(type: WindowType): String
    fun clampToWorkspace(width: Float, height: Float)
    fun close(id: String)
    fun minimize(id: String)
    fun maximize(id: String)
    fun restore(id: String)
    fun focus(id: String)
    fun move(id: String, dx: Float, dy: Float)
    fun resize(id: String, dw: Float, dh: Float)
    fun resize(id: String, edge: ResizeEdge, dx: Float, dy: Float)
    fun initializeBounds(id: String, bounds: WindowBounds)
}
