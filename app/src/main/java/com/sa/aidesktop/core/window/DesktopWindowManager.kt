package com.sa.aidesktop.core.window

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

class DesktopWindowManager : WindowManager {
    private val _windows = MutableStateFlow<List<DesktopWindow>>(emptyList())
    override val windows: List<DesktopWindow> get() = _windows.value
    val state: StateFlow<List<DesktopWindow>> = _windows.asStateFlow()
    private var z = 0

    private fun title(type: WindowType) = when (type) {
        WindowType.DEVELOPER -> "Developer Workspace"
        WindowType.AI -> "AI Assistant - Sara"
        WindowType.TERMINAL -> "Terminal"
        WindowType.GIT -> "Git"
        WindowType.FILES -> "File Manager"
        WindowType.SETTINGS -> "Settings"
        WindowType.BROWSER -> "Browser"
    }

    override fun open(type: WindowType) {
        val existing = _windows.value.firstOrNull { it.type == type }
        if (existing != null) {
            if (existing.state == WindowState.MINIMIZED) restore(existing.id) else focus(existing.id)
            return
        }
        z += 1
        val id = type.name.lowercase()
        val next = DesktopWindow(id = id, type = type, title = title(type), z = z, focused = true)
        _windows.value = _windows.value.map { it.copy(focused = false) } + next
    }

    override fun openNew(type: WindowType): String {
        val base = type.name.lowercase()
        var index = 1
        var id = base
        while (_windows.value.any { it.id == id }) { index++; id = "$base-$index" }
        z += 1
        val displayTitle = if (type == WindowType.BROWSER) "Browser $index" else title(type) + " $index"
        val next = DesktopWindow(id = id, type = type, title = displayTitle, z = z, focused = true)
        _windows.value = _windows.value.map { it.copy(focused = false) } + next
        return id
    }

    override fun clampToWorkspace(width: Float, height: Float) {
        val maxWidth = width.coerceAtLeast(1f)
        val maxHeight = height.coerceAtLeast(1f)
        _windows.value = _windows.value.map { w ->
            if (w.state == WindowState.MAXIMIZED || w.state == WindowState.MINIMIZED) w
            else {
                val newWidth = w.width.coerceIn(220f.coerceAtMost(maxWidth), maxWidth)
                val newHeight = w.height.coerceIn(160f.coerceAtMost(maxHeight), maxHeight)
                val maxX = (maxWidth - newWidth - 4f).coerceAtLeast(4f)
                val maxY = (maxHeight - newHeight - 4f).coerceAtLeast(4f)
                w.copy(width = newWidth, height = newHeight, x = w.x.coerceIn(4f, maxX), y = w.y.coerceIn(4f, maxY))
            }
        }
    }

    /** Minimum usable size per window type. AI/Terminal/Git windows carry a fixed header, a
     *  quick-command bar and an input row that don't shrink below their own content, so letting
     *  those windows resize down to the generic 260x180 minimum squeezed/overlapped that chrome
     *  instead of shrinking it. Types without that extra chrome keep the original minimum. */
    private fun minSizeFor(type: WindowType): Pair<Float, Float> = when (type) {
        WindowType.AI -> 300f to 340f
        WindowType.TERMINAL, WindowType.GIT -> 280f to 220f
        else -> 260f to 180f
    }


        if (_windows.value.firstOrNull { it.id == id }?.protectedByTaskId != null) return
        val remaining = _windows.value.filterNot { it.id == id }
        _windows.value = focusTop(remaining)
    }

    override fun minimize(id: String) {
        if (_windows.value.firstOrNull { it.id == id }?.protectedByTaskId != null) return
        val updated = _windows.value.map { if (it.id == id) it.copy(state = WindowState.MINIMIZED, focused = false) else it.copy(focused = false) }
        _windows.value = focusTop(updated)
    }

    override fun maximize(id: String) {
        if (_windows.value.firstOrNull { it.id == id }?.protectedByTaskId != null) return
        z += 1
        _windows.value = _windows.value.map {
            when {
                it.id != id -> it.copy(focused = false)
                it.state == WindowState.MAXIMIZED -> it.copy(focused = true, z = z)
                else -> it.copy(state = WindowState.MAXIMIZED, focused = true, z = z, restoreBounds = WindowBounds(it.x, it.y, it.width, it.height))
            }
        }
    }

    override fun restore(id: String) {
        if (_windows.value.firstOrNull { it.id == id }?.protectedByTaskId != null) return
        z += 1
        _windows.value = _windows.value.map {
            if (it.id != id) it.copy(focused = false)
            else {
                val b = it.restoreBounds
                it.copy(
                    state = WindowState.NORMAL,
                    focused = true,
                    z = z,
                    x = b?.x ?: it.x,
                    y = b?.y ?: it.y,
                    width = b?.width ?: it.width,
                    height = b?.height ?: it.height,
                    restoreBounds = null
                )
            }
        }
    }

    override fun focus(id: String) {
        val target = _windows.value.firstOrNull { it.id == id } ?: return
        if (target.state == WindowState.MINIMIZED) return
        z += 1
        _windows.value = _windows.value.map { it.copy(focused = it.id == id, z = if (it.id == id) z else it.z) }
    }

    override fun move(id: String, dx: Float, dy: Float) {
        if (_windows.value.firstOrNull { it.id == id }?.protectedByTaskId != null) return
        update(id) { it.copy(x = it.x + dx, y = it.y + dy) }
    }

    override fun resize(id: String, dw: Float, dh: Float) {
        if (_windows.value.firstOrNull { it.id == id }?.protectedByTaskId != null) return
        resize(id, ResizeEdge.BOTTOM_RIGHT, dw, dh)
    }

    override fun initializeBounds(id: String, bounds: WindowBounds) {
        _windows.value = _windows.value.map {
            if (it.id == id && it.width <= 0f && it.height <= 0f) it.copy(x = bounds.x, y = bounds.y, width = bounds.width, height = bounds.height) else it
        }
    }

    override fun resize(id: String, edge: ResizeEdge, dx: Float, dy: Float) {
        if (_windows.value.firstOrNull { it.id == id }?.protectedByTaskId != null) return
        update(id) { w ->
            if (w.state != WindowState.NORMAL) return@update w
            val (minW, minH) = minSizeFor(w.type)
            val leftEdge = edge == ResizeEdge.LEFT || edge == ResizeEdge.TOP_LEFT || edge == ResizeEdge.BOTTOM_LEFT
            val topEdge = edge == ResizeEdge.TOP || edge == ResizeEdge.TOP_LEFT || edge == ResizeEdge.TOP_RIGHT
            val rawW = if (leftEdge) w.width - dx else if (edge == ResizeEdge.RIGHT || edge == ResizeEdge.TOP_RIGHT || edge == ResizeEdge.BOTTOM_RIGHT) w.width + dx else w.width
            val rawH = if (topEdge) w.height - dy else if (edge == ResizeEdge.BOTTOM || edge == ResizeEdge.BOTTOM_LEFT || edge == ResizeEdge.BOTTOM_RIGHT) w.height + dy else w.height
            val newW = max(minW, rawW)
            val newH = max(minH, rawH)
            val newX = if (leftEdge) w.x + (w.width - newW) else w.x
            val newY = if (topEdge) w.y + (w.height - newH) else w.y
            w.copy(x = newX, y = newY, width = newW, height = newH)
        }
    }

    /** Keeps a window inside the current desktop work area after a resize/rotation. */
    fun resizeWithinWorkspace(
        id: String,
        edge: ResizeEdge,
        dx: Float,
        dy: Float,
        workspaceWidth: Float,
        workspaceHeight: Float
    ) {
        val maxWidth = workspaceWidth.coerceAtLeast(1f)
        val maxHeight = workspaceHeight.coerceAtLeast(1f)

        _windows.value = _windows.value.map { w ->
            if (w.id != id || w.state != WindowState.NORMAL || w.protectedByTaskId != null) {
                w
            } else {
                val (baseMinW, baseMinH) = minSizeFor(w.type)
                val minW = baseMinW.coerceAtMost(maxWidth)
                val minH = baseMinH.coerceAtMost(maxHeight)

                val leftEdge = edge == ResizeEdge.LEFT ||
                    edge == ResizeEdge.TOP_LEFT ||
                    edge == ResizeEdge.BOTTOM_LEFT

                val rightEdge = edge == ResizeEdge.RIGHT ||
                    edge == ResizeEdge.TOP_RIGHT ||
                    edge == ResizeEdge.BOTTOM_RIGHT

                val topEdge = edge == ResizeEdge.TOP ||
                    edge == ResizeEdge.TOP_LEFT ||
                    edge == ResizeEdge.TOP_RIGHT

                val bottomEdge = edge == ResizeEdge.BOTTOM ||
                    edge == ResizeEdge.BOTTOM_LEFT ||
                    edge == ResizeEdge.BOTTOM_RIGHT

                var newWidth = when {
                    leftEdge -> w.width - dx
                    rightEdge -> w.width + dx
                    else -> w.width
                }

                var newHeight = when {
                    topEdge -> w.height - dy
                    bottomEdge -> w.height + dy
                    else -> w.height
                }

                // Bound the new size by whichever edge stays anchored, so the
                // opposite side can never be pushed past the workspace margin.
                val maxWidthAllowed = if (leftEdge) {
                    (w.x + w.width - 4f).coerceAtLeast(minW)
                } else {
                    (maxWidth - w.x - 4f).coerceAtLeast(minW)
                }
                val maxHeightAllowed = if (topEdge) {
                    (w.y + w.height - 4f).coerceAtLeast(minH)
                } else {
                    (maxHeight - w.y - 4f).coerceAtLeast(minH)
                }

                newWidth = newWidth.coerceIn(minW, maxWidthAllowed.coerceAtMost(maxWidth))
                newHeight = newHeight.coerceIn(minH, maxHeightAllowed.coerceAtMost(maxHeight))

                var newX = if (leftEdge) {
                    w.x + (w.width - newWidth)
                } else {
                    w.x
                }

                var newY = if (topEdge) {
                    w.y + (w.height - newHeight)
                } else {
                    w.y
                }

                newX = newX.coerceIn(4f, (maxWidth - newWidth - 4f).coerceAtLeast(4f))
                newY = newY.coerceIn(4f, (maxHeight - newHeight - 4f).coerceAtLeast(4f))

                w.copy(
                    x = newX,
                    y = newY,
                    width = newWidth,
                    height = newHeight
                )
            }
        }
    }

    /** Applies task-scoped protection without globally freezing unrelated windows. */
    fun setTaskProtection(windowIds: Set<String>, taskId: String?, reason: String?) {
        _windows.value = _windows.value.map { w ->
            when {
                taskId == null -> w.copy(protectedByTaskId = null, protectionReason = null)
                w.protectedByTaskId == taskId && w.id !in windowIds -> w.copy(protectedByTaskId = null, protectionReason = null)
                taskId != null && w.id in windowIds -> w.copy(protectedByTaskId = taskId, protectionReason = reason)
                else -> w
            }
        }
    }

    fun clearTaskProtection(taskId: String) {
        _windows.value = _windows.value.map { w ->
            if (w.protectedByTaskId == taskId) w.copy(protectedByTaskId = null, protectionReason = null) else w
        }
    }

    fun isProtected(id: String): Boolean = _windows.value.firstOrNull { it.id == id }?.protectedByTaskId != null

    private fun update(id: String, transform: (DesktopWindow) -> DesktopWindow) {
        _windows.value = _windows.value.map { if (it.id == id) transform(it) else it }
    }

    private fun focusTop(list: List<DesktopWindow>): List<DesktopWindow> {
        val top = list.filter { it.state != WindowState.MINIMIZED }.maxByOrNull { it.z }?.id ?: return list.map { it.copy(focused = false) }
        return list.map { it.copy(focused = it.id == top) }
    }
}
