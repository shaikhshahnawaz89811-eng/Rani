package com.sa.aidesktop.core.editor

enum class EditorLanguage { PYTHON, KOTLIN, JAVA, JAVASCRIPT, C, CPP, JSON, MARKDOWN, XML, TEXT }

data class EditorDocument(
    val path: String,
    val language: EditorLanguage,
    val text: String,
    val savedText: String = text
) {
    val isDirty: Boolean get() = text != savedText
}

class UndoRedoHistory(initial: String, private val maxEntries: Int = 50) {
    private val entries = mutableListOf(initial)
    private var index = 0

    val canUndo: Boolean get() = index > 0
    val canRedo: Boolean get() = index < entries.lastIndex

    fun push(value: String) {
        if (entries[index] == value) return
        while (entries.lastIndex > index) entries.removeAt(entries.lastIndex)
        entries += value
        if (entries.size > maxEntries) entries.removeAt(0)
        index = entries.lastIndex
    }

    fun undo(): String? = if (canUndo) { index--; entries[index] } else null
    fun redo(): String? = if (canRedo) { index++; entries[index] } else null
}

object EditorTextOperations {
    fun replaceAll(text: String, find: String, replacement: String, ignoreCase: Boolean = true): String =
        if (find.isEmpty()) text else text.replace(find, replacement, ignoreCase = ignoreCase)

    fun lineCount(text: String): Int = text.split('\n').size
}
