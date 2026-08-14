package com.sa.aidesktop.core

import com.sa.aidesktop.core.window.*
import org.junit.Assert.*
import org.junit.Test

class WindowManagerTest {

    @Test
    fun openFocusMinimizeRestoreClose() {
        val wm = DesktopWindowManager()

        wm.open(WindowType.DEVELOPER)
        assertEquals(1, wm.windows.size)
        assertTrue(wm.windows.first().focused)

        wm.minimize("developer")
        assertEquals(WindowState.MINIMIZED, wm.windows.first().state)

        wm.restore("developer")
        assertEquals(WindowState.NORMAL, wm.windows.first().state)

        wm.close("developer")
        assertTrue(wm.windows.isEmpty())
    }

    @Test
    fun maximizePreservesBoundsAndRestoreReturnsToThem() {
        val wm = DesktopWindowManager()

        wm.open(WindowType.AI)
        wm.initializeBounds("ai", WindowBounds(40f, 60f, 360f, 400f))
        wm.maximize("ai")

        assertEquals(WindowState.MAXIMIZED, wm.windows.single().state)

        wm.restore("ai")

        val w = wm.windows.single()
        assertEquals(WindowState.NORMAL, w.state)
        assertEquals(40f, w.x, 0.01f)
        assertEquals(60f, w.y, 0.01f)
        assertEquals(360f, w.width, 0.01f)
        assertEquals(400f, w.height, 0.01f)
    }

    @Test
    fun resizeNeverGoesBelowMinimum() {
        val wm = DesktopWindowManager()

        wm.open(WindowType.TERMINAL)
        wm.initializeBounds("terminal", WindowBounds(0f, 0f, 300f, 200f))
        wm.resize("terminal", ResizeEdge.BOTTOM_RIGHT, -1000f, -1000f)

        val w = wm.windows.single()
        assertEquals(260f, w.width, 0.01f)
        assertEquals(180f, w.height, 0.01f)
    }

    @Test
    fun openNewCreatesIndependentBrowserWindows() {
        val manager = DesktopWindowManager()

        val first = manager.openNew(WindowType.BROWSER)
        val second = manager.openNew(WindowType.BROWSER)

        assertNotEquals(first, second)
        assertEquals(2, manager.windows.count { it.type == WindowType.BROWSER })
    }

    @Test
    fun resizeSupportsAllEdgesAndClampsAfterRotation() {
        val wm = DesktopWindowManager()

        wm.open(WindowType.BROWSER)
        wm.initializeBounds("browser", WindowBounds(500f, 500f, 600f, 500f))

        wm.resize("browser", ResizeEdge.LEFT, 100f, 0f)
        wm.resize("browser", ResizeEdge.TOP, 0f, 100f)
        wm.resize("browser", ResizeEdge.TOP_LEFT, -50f, -50f)
        wm.clampToWorkspace(360f, 640f)

        val w = wm.windows.single()

        assertTrue(w.width <= 360f)
        assertTrue(w.height <= 640f)
        assertTrue(w.x >= 4f)
        assertTrue(w.y >= 4f)
    }

    @Test
    fun resizeIsClampedToWorkspace() {
        val manager = DesktopWindowManager()

        manager.open(WindowType.BROWSER)
        val id = manager.windows.single().id

        manager.initializeBounds(
            id,
            WindowBounds(10f, 10f, 300f, 200f)
        )

        manager.resizeWithinWorkspace(
            id,
            ResizeEdge.RIGHT,
            1000f,
            0f,
            500f,
            400f
        )

        val w = manager.windows.single()

        assertTrue(w.x >= 4f)
        assertTrue(w.x + w.width <= 500f)
    }
}
