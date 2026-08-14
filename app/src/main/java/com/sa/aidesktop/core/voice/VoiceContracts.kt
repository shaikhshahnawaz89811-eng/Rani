package com.sa.aidesktop.core.voice

import kotlinx.coroutines.flow.StateFlow

sealed interface VoiceResult {
    data class Success(val text: String) : VoiceResult
    data class Failure(val message: String) : VoiceResult
}

data class VoiceState(val listening: Boolean = false, val speaking: Boolean = false, val available: Boolean = true)

interface SpeechToText {
    val state: StateFlow<VoiceState>
    val transcript: StateFlow<String>
    fun start()
    fun stop()
    fun release()
}

interface TextToSpeechEngine {
    suspend fun speak(text: String): VoiceResult
    fun stop()
    fun release()
}
