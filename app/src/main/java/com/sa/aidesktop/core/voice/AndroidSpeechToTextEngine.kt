package com.sa.aidesktop.core.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AndroidSpeechToTextEngine(context:Context,private val language:String="en-IN"):SpeechToText{
    private val _state=MutableStateFlow(VoiceState()); override val state: StateFlow<VoiceState> = _state
    private val _transcript=MutableStateFlow(""); override val transcript: StateFlow<String> = _transcript
    private val recognizer:SpeechRecognizer?=if(SpeechRecognizer.isRecognitionAvailable(context))SpeechRecognizer.createSpeechRecognizer(context.applicationContext)else null
    init{recognizer?.setRecognitionListener(object:RecognitionListener{
        override fun onReadyForSpeech(params:Bundle?) {_state.value=_state.value.copy(listening=true,available=true)}
        override fun onBeginningOfSpeech(){}
        override fun onRmsChanged(rmsdB:Float){}
        override fun onBufferReceived(buffer:ByteArray?){}
        override fun onEndOfSpeech(){_state.value=_state.value.copy(listening=false)}
        override fun onError(error:Int){_state.value=_state.value.copy(listening=false,available=true)}
        override fun onResults(results:Bundle?){
            val text=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            _transcript.value=text
            _state.value=_state.value.copy(listening=false,available=true)
        }
        override fun onPartialResults(partialResults:Bundle?){
            val text=partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if(text.isNotBlank()) _transcript.value=text
        }
        override fun onEvent(eventType:Int,params:Bundle?){}
    })}
    override fun start(){val r=recognizer?:run{_state.value=_state.value.copy(available=false);return};val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE,language);putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)};_state.value=_state.value.copy(listening=true);r.startListening(i)}
    override fun stop(){recognizer?.stopListening();_state.value=_state.value.copy(listening=false)}
    override fun release(){recognizer?.destroy()}
}
