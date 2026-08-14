package com.sa.aidesktop.core.files

import java.io.File
import java.io.IOException

/** App-private project workspace. All filesystem operations are scoped to root. */
class AndroidProjectFileService(workspaceRoot: File) : FileService {
    private val root = workspaceRoot.canonicalFile

    init { seedProjectIfNeeded() }

    private fun safe(path: String): FileResult<File> {
        return try {
        val normalized = path.trim().trimStart('/')
        if (normalized.isBlank()) return FileResult.ok(root)
        if (normalized.split('/').any { it == ".." }) return FileResult.fail(FileError.InvalidPath)
        val target = File(root, normalized).canonicalFile
        if (target.path != root.path && !target.path.startsWith(root.path + File.separator)) FileResult.fail(FileError.InvalidPath)
        else FileResult.ok(target)
    } catch (e: IOException) { FileResult.fail(FileError.Access(e.message ?: "Invalid path")) }
    }

    private fun validName(name: String): Boolean = name.isNotBlank() && name !in setOf(".", "..") && !name.contains('/') && !name.contains('\\')

    override fun projectTree(): ProjectFile = tree(root, "")

    override fun read(path: String): FileResult<String> {
        val file = safe(path).value ?: return FileResult.fail(FileError.InvalidPath)
        if (!file.exists()) return FileResult.fail(FileError.NotFound)
        if (file.isDirectory) return FileResult.fail(FileError.IsDirectory)
        return runCatching { FileResult.ok(file.readText()) }.getOrElse { FileResult.fail(FileError.Access(it.message ?: "Read failed")) }
    }

    override fun write(path: String, content: String): FileResult<Unit> {
        val file = safe(path).value ?: return FileResult.fail(FileError.InvalidPath)
        if (file.exists() && file.isDirectory) return FileResult.fail(FileError.IsDirectory)
        return runCatching { file.parentFile?.mkdirs(); file.writeText(content); FileResult.ok(Unit) }
            .getOrElse { FileResult.fail(FileError.Access(it.message ?: "Write failed")) }
    }

    override fun createFile(path: String, content: String): FileResult<Unit> {
        val file = safe(path).value ?: return FileResult.fail(FileError.InvalidPath)
        if (file.exists()) return FileResult.fail(FileError.AlreadyExists)
        return write(path, content)
    }

    override fun createFolder(path: String): FileResult<Unit> {
        val file = safe(path).value ?: return FileResult.fail(FileError.InvalidPath)
        if (file.exists()) return FileResult.fail(FileError.AlreadyExists)
        return if (file.mkdirs()) FileResult.ok(Unit) else FileResult.fail(FileError.Access("Folder creation failed"))
    }

    override fun rename(path: String, newName: String): FileResult<String> {
        if (!validName(newName)) return FileResult.fail(FileError.InvalidName("Invalid file name"))
        val source = safe(path).value ?: return FileResult.fail(FileError.InvalidPath)
        if (!source.exists() || source == root) return FileResult.fail(FileError.NotFound)
        val target = File(source.parentFile, newName).canonicalFile
        if (target.exists()) return FileResult.fail(FileError.AlreadyExists)
        return if (source.renameTo(target)) FileResult.ok(relative(target)) else FileResult.fail(FileError.Access("Rename failed"))
    }

    override fun delete(path: String): FileResult<Unit> {
        val file = safe(path).value ?: return FileResult.fail(FileError.InvalidPath)
        if (!file.exists() || file == root) return FileResult.fail(FileError.NotFound)
        if (file.isDirectory && file.listFiles()?.isNotEmpty() == true) return FileResult.fail(FileError.DirectoryNotEmpty)
        return if (file.delete()) FileResult.ok(Unit) else FileResult.fail(FileError.Access("Delete failed"))
    }

    override fun copy(source: String, destination: String): FileResult<Unit> = transfer(source, destination, false)
    override fun move(source: String, destination: String): FileResult<Unit> = transfer(source, destination, true)

    private fun transfer(source: String, destination: String, move: Boolean): FileResult<Unit> {
        val src = safe(source).value ?: return FileResult.fail(FileError.InvalidPath)
        val dst = safe(destination).value ?: return FileResult.fail(FileError.InvalidPath)
        if (!src.exists() || src == root) return FileResult.fail(FileError.NotFound)
        if (dst.exists()) return FileResult.fail(FileError.AlreadyExists)
        if (src.isDirectory && (dst.path == src.path || dst.path.startsWith(src.path + File.separator))) return FileResult.fail(FileError.InvalidPath)
        return try {
            dst.parentFile?.mkdirs()
            if (src.isDirectory) src.copyRecursively(dst, overwrite = false) else src.copyTo(dst, overwrite = false)
            if (move && !src.deleteRecursively()) return FileResult.fail(FileError.Access("Move cleanup failed"))
            FileResult.ok(Unit)
        } catch (e: Exception) { FileResult.fail(FileError.Access(e.message ?: "File operation failed")) }
    }

    override fun exists(path: String): FileResult<Boolean> = safe(path).let { if (!it.isSuccess) FileResult.fail(it.error!!) else FileResult.ok(it.value!!.exists()) }

    override fun listDirectory(path: String): FileResult<List<ProjectFile>> {
        val dir = safe(path).value ?: return FileResult.fail(FileError.InvalidPath)
        if (!dir.exists()) return FileResult.fail(FileError.NotFound)
        if (!dir.isDirectory) return FileResult.fail(FileError.NotDirectory)
        return FileResult.ok(dir.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))?.map { tree(it, relative(it)) }.orEmpty())
    }

    override fun search(query: String): FileResult<List<ProjectFile>> {
        val q = query.trim()
        if (q.isEmpty()) return FileResult.ok(emptyList())
        return runCatching {
            FileResult.ok(root.walkTopDown().filter { it != root && it.name.contains(q, true) }.take(200).map { tree(it, relative(it)) }.toList())
        }.getOrElse { FileResult.fail(FileError.Access(it.message ?: "Search failed")) }
    }

    private fun tree(file: File, relativePath: String): ProjectFile {
        if (file.isDirectory) {
            val children = file.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))?.map { tree(it, relative(it)) }.orEmpty()
            return ProjectFile(relativePath, file.name.ifBlank { "MyProject" }, FileKind.FOLDER, children, children.sumOf { it.sizeBytes })
        }
        return ProjectFile(relativePath, file.name, kind(file.name), sizeBytes = file.length())
    }

    private fun relative(file: File): String = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
    private fun kind(name: String) = when {
        name.endsWith(".py", true) -> FileKind.PYTHON
        name.endsWith(".kt", true) -> FileKind.KOTLIN
        name.endsWith(".java", true) -> FileKind.JAVA
        name.endsWith(".js", true) -> FileKind.JAVASCRIPT
        name.endsWith(".c", true) -> FileKind.C
        name.endsWith(".cpp", true) -> FileKind.CPP
        name.endsWith(".json", true) -> FileKind.JSON
        name.endsWith(".xml", true) -> FileKind.XML
        name.endsWith(".md", true) -> FileKind.MARKDOWN
        name.endsWith(".txt", true) -> FileKind.TEXT
        else -> FileKind.OTHER
    }

    private fun seedProjectIfNeeded() {
        root.mkdirs()
        if (File(root, "src/main.py").exists()) return
        write("src/main.py", "import math\n\n\ndef add(a, b):\n    return a + b\n\n\ndef main():\n    print(\"Hello, SA Assistant!\")\n    print(\"2 + 3 =\", add(2, 3))\n\n\nif __name__ == \"__main__\":\n    main()\n")
        write("src/utils.py", "def clamp(value, low, high):\n    return max(low, min(value, high))\n")
        write("tests/test_main.py", "def test_add():\n    assert 2 + 3 == 5\n")
        write("README.md", "# MyProject\n\nSA AI Desktop project workspace.\n")
        write("requirements.txt", "# runtime dependencies\n")
        createFolder("assets")
    }
}
