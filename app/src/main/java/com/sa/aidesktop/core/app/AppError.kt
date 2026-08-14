package com.sa.aidesktop.core.app

sealed interface AppError {
    val userMessage: String
    val debugMessage: String
    data class Validation(override val userMessage: String, override val debugMessage: String = userMessage) : AppError
    data class Permission(override val userMessage: String, override val debugMessage: String = userMessage) : AppError
    data class Storage(override val userMessage: String, override val debugMessage: String = userMessage) : AppError
    data class Runtime(override val userMessage: String, override val debugMessage: String = userMessage) : AppError
    data class Security(override val userMessage: String, override val debugMessage: String = userMessage) : AppError
    data class Unknown(override val userMessage: String, override val debugMessage: String = userMessage) : AppError
}
