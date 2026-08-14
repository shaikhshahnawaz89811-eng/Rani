package com.sa.aidesktop.core.editor

import org.junit.Assert.*
import org.junit.Test

class EditorModelsTest {
    @Test fun dirtyStateTracksChanges() {
        val doc = EditorDocument("main.py", EditorLanguage.PYTHON, "print(1)")
        assertFalse(doc.isDirty)
        assertTrue(doc.copy(text = "print(2)").isDirty)
    }

    @Test fun undoRedoRoundTrip() {
        val history = UndoRedoHistory("a")
        history.push("ab")
        history.push("abc")
        assertEquals("ab", history.undo())
        assertEquals("abc", history.redo())
    }

    @Test fun replaceAllIsCaseInsensitiveByDefault() {
        assertEquals("x x", EditorTextOperations.replaceAll("A a", "a", "x"))
    }

    @Test fun lineCountHandlesSingleLineAndNewlines() {
        assertEquals(1, EditorTextOperations.lineCount("abc"))
        assertEquals(3, EditorTextOperations.lineCount("a\nb\n"))
    }
}
