package com.sa.aidesktop.core.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

class AndroidTextToSpeechEngine(context: Context, language: String = "en-IN") : TextToSpeechEngine, TextToSpeech.OnInitListener {
    private var ready = false
    private val locale = Locale.forLanguageTag(language)
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.language = locale
    }

    override suspend fun speak(text: String): VoiceResult = suspendCancellableCoroutine { cont ->
        if (!ready || text.isBlank()) { cont.resume(VoiceResult.Failure("Text-to-speech is unavailable.")); return@suspendCancellableCoroutine }
        val utteranceId = "sa-${System.nanoTime()}"
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { if (id == utteranceId && cont.isActive) cont.resume(VoiceResult.Success(text)) }
            override fun onError(id: String?) { if (id == utteranceId && cont.isActive) cont.resume(VoiceResult.Failure("Speech synthesis failed.")) }
        })
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        cont.invokeOnCancellation { tts.stop() }
    }

    override fun stop() { tts.stop() }
    override fun release() { tts.shutdown() }
}
