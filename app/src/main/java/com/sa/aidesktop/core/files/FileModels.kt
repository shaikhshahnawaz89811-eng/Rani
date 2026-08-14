package com.sa.aidesktop.core.files

enum class FileKind { FOLDER, PYTHON, KOTLIN, JAVA, JAVASCRIPT, C, CPP, JSON, XML, MARKDOWN, TEXT, OTHER }

data class ProjectFile(
    val path: String,
    val name: String,
    val kind: FileKind,
    val children: List<ProjectFile> = emptyList(),
    val sizeBytes: Long = 0L
)

sealed interface FileError {
    data object InvalidPath : FileError
    data object NotFound : FileError
    data object AlreadyExists : FileError
    data object NotDirectory : FileError
    data object DirectoryNotEmpty : FileError
    data object IsDirectory : FileError
    data class Access(val message: String) : FileError
    data class InvalidName(val message: String) : FileError
}

data class FileResult<T>(val value: T? = null, val error: FileError? = null) {
    val isSuccess: Boolean get() = error == null
    companion object {
        fun <T> ok(value: T): FileResult<T> = FileResult(value = value)
        fun <T> fail(error: FileError): FileResult<T> = FileResult(error = error)
    }
}

interface FileService {
    fun projectTree(): ProjectFile
    fun read(path: String): FileResult<String>
    fun write(path: String, content: String): FileResult<Unit>
    fun createFile(path: String, content: String = ""): FileResult<Unit>
    fun createFolder(path: String): FileResult<Unit>
    fun rename(path: String, newName: String): FileResult<String>
    fun delete(path: String): FileResult<Unit>
    fun copy(source: String, destination: String): FileResult<Unit>
    fun move(source: String, destination: String): FileResult<Unit>
    fun exists(path: String): FileResult<Boolean>
    fun listDirectory(path: String = ""): FileResult<List<ProjectFile>>
    fun search(query: String): FileResult<List<ProjectFile>>
}
