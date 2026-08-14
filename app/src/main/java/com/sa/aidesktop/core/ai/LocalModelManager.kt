package com.sa.aidesktop.core.ai

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File

/** Owns the optional user-supplied GGUF model file. It never downloads a model. */
class LocalModelManager(context: Context) {
    enum class Status { NOT_INSTALLED, INSTALLED, INVALID }

    private val modelsDir = File(context.filesDir, "SA-AIDesktop/models")
    private val modelFile = File(modelsDir, DEFAULT_MODEL_FILE)

    init { modelsDir.mkdirs() }

    fun status(): Status = when {
        !modelFile.exists() -> Status.NOT_INSTALLED
        modelFile.isFile && modelFile.length() > MIN_MODEL_BYTES && looksLikeGguf(modelFile) -> Status.INSTALLED
        else -> Status.INVALID
    }

    fun modelFile(): File? = modelFile.takeIf { status() == Status.INSTALLED }

    /** Copies a user-selected GGUF into app-private storage and verifies the resulting file. */
    fun installFromUri(resolver: ContentResolver, uri: Uri): Result<File> = runCatching {
        val temp = File(modelsDir, "$DEFAULT_MODEL_FILE.part")
        temp.delete()
        resolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER) }
        } ?: error("Unable to open the selected model file.")
        if (temp.length() <= MIN_MODEL_BYTES) error("Selected file is empty or too small to be a GGUF model.")
        if (temp.length() > MAX_MODEL_BYTES) error("Selected model exceeds the supported local model size limit.")
        if (!looksLikeGguf(temp)) error("Selected file is not a GGUF model.")
        if (!temp.renameTo(modelFile)) {
            modelFile.delete()
            if (!temp.renameTo(modelFile)) error("Unable to install the local model.")
        }
        modelFile
    }.onFailure { File(modelsDir, "$DEFAULT_MODEL_FILE.part").delete() }

    fun removeModel(): Boolean = !modelFile.exists() || modelFile.delete()

    private fun looksLikeGguf(file: File): Boolean {
        file.inputStream().use { input ->
            val header = ByteArray(4)
            if (input.read(header) != 4) return false
            return header.contentEquals(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
        }
    }

    companion object {
        const val DEFAULT_MODEL_FILE = "SmolLM2-135M-Instruct-Q4_K_M.gguf"
        private const val MIN_MODEL_BYTES = 1024L * 1024L
        private const val MAX_MODEL_BYTES = 2L * 1024L * 1024L * 1024L
        private const val DEFAULT_BUFFER = 1024 * 1024
    }
}
