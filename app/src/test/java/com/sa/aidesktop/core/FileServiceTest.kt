package com.sa.aidesktop.core

import com.sa.aidesktop.core.files.*
import org.junit.Assert.*
import org.junit.Test

class FileServiceTest {
    @Test fun readWriteFile() {
        val fs = InMemoryProjectFileService()
        fs.write("src/main.py", "print('changed')")
        assertEquals("print('changed')", fs.read("src/main.py").value)
        assertTrue(fs.projectTree().children.any { it.name == "src" })
    }

    @Test fun createDeleteCopyAndMove() {
        val fs = InMemoryProjectFileService()
        fs.createFile("src/a.txt", "hello")
        assertTrue(fs.search("a.txt").value!!.any { it.path == "src/a.txt" })
        assertTrue(fs.copy("src/a.txt", "src/b.txt").isSuccess)
        assertEquals("hello", fs.read("src/b.txt").value)
        assertTrue(fs.move("src/b.txt", "src/c.txt").isSuccess)
        assertEquals("hello", fs.read("src/c.txt").value)
        assertTrue(fs.delete("src/c.txt").isSuccess)
        assertTrue(fs.read("src/c.txt").value == null)
    }

    @Test fun directoryListingIsScoped() {
        val fs = InMemoryProjectFileService()
        val result = fs.listDirectory("src")
        assertTrue(result.isSuccess)
        assertTrue(result.value!!.any { it.name == "main.py" })
        assertFalse(result.value!!.any { it.name == "README.md" })
    }
}
