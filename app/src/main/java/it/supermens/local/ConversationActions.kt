// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import android.content.Context
import java.io.File

object ConversationActions {
    fun submit(context:Context,store:BrainStore,id:String?,type:String,text:String,mode:String,language:String,draftId:Long?=null,audio:File?=null):String {
        require(language.isNotBlank() && language.length<=80) {context.uiString(R.string.language_invalid)}
        if(audio==null) {
            require(text.isNotBlank())
            require(mode=="translate" || ConversationText.bytes(text)<=1500) {context.uiString(R.string.chat_input_too_long)}
        }
        val db=store.writableDatabase;db.beginTransaction()
        var itemId=id.orEmpty();var messageId=0L
        try {
            if(itemId.isBlank()) {
                itemId=store.add(type,text.trim().lineSequence().firstOrNull()?.take(76)?.ifBlank {null} ?: context.uiString(if(type=="translation") R.string.translation else R.string.free_chat))
            }
            require(store.get(itemId)!=null) {context.uiString(R.string.item_missing)}
            require(store.messages(itemId).none {it.state in setOf("pending","transcribing")}) {context.uiString(R.string.conversation_pending)}
            if(type=="translation") store.setTargetLanguage(itemId,language)
            val editable=draftId ?: store.messages(itemId).firstOrNull {it.state=="draft"}?.id
            if(editable!=null && audio==null) {
                require(store.messages(itemId).any {it.id==editable && it.state=="draft"})
                store.editMessage(itemId,editable,text,"pending",mode,language);messageId=editable
            } else messageId=store.addMessage(itemId,text,mode,language,if(audio!=null) "transcribing" else "pending",audio?.absolutePath.orEmpty())
            // Persist the job in the same database transaction as the user message.
            db.insertOrThrow("processing_jobs",null,android.content.ContentValues().apply {
                put("item_id",itemId);put("kind",if(audio!=null) "transcribe" else "conversation");put("manual",1);put("payload",messageId.toString());put("created_at",System.currentTimeMillis())
            })
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
        ProcessingQueue.wake(context)
        return itemId
    }
}
