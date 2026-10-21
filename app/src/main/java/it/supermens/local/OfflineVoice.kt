// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import java.util.Locale

class OfflineVoice(context:Context,private val error:()->Unit,private val changed:(Boolean)->Unit) {
    private val handler=Handler(Looper.getMainLooper())
    private var ready=false
    private var closed=false
    private var utterance=""
    private var engine:TextToSpeech?=null
    init {
        engine=TextToSpeech(context.applicationContext) {status ->ready=status==TextToSpeech.SUCCESS && !closed}
        engine?.setOnUtteranceProgressListener(object:UtteranceProgressListener() {
            override fun onStart(id:String?) {handler.post {if(!closed && id?.startsWith(utterance)==true && utterance.isNotBlank()) changed(true)}}
            override fun onDone(id:String?) {if(id?.endsWith(":last")==true) handler.post {if(!closed && utterance.isNotBlank() && id.startsWith(utterance)) changed(false)}}
            @Deprecated("Legacy callback") override fun onError(id:String?) {handler.post {if(!closed && utterance.isNotBlank() && id?.startsWith(utterance)==true) {changed(false);error()}}}
            override fun onError(id:String?,code:Int) {handler.post {if(!closed && utterance.isNotBlank() && id?.startsWith(utterance)==true) {changed(false);error()}}}
        })
    }
    fun speak(text:String,language:String) {
        stop()
        val tts=engine
        val locale=Locale.forLanguageTag(language)
        val installed=if(ready) tts?.voices?.filter {!it.isNetworkConnectionRequired && it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)!=true}.orEmpty() else emptyList()
        val voice=installed.firstOrNull {it.locale.toLanguageTag().equals(language,true)} ?: installed.firstOrNull {it.locale.language==locale.language}
        if(tts==null || voice==null) {error();return}
        if(tts.setVoice(voice)==TextToSpeech.ERROR) {error();return}
        utterance=java.util.UUID.randomUUID().toString()
        val parts=ConversationText.chunks(text,3000)
        parts.forEachIndexed {index,part ->
            if(tts.speak(part,TextToSpeech.QUEUE_ADD,null,"$utterance:$index"+if(index==parts.lastIndex) ":last" else "") == TextToSpeech.ERROR) {stop();error();return}
        }
    }
    fun stop() {utterance="";engine?.stop();changed(false)}
    fun close() {closed=true;engine?.stop();engine?.shutdown();engine=null}
}
