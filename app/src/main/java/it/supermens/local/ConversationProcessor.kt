// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import android.content.Context
import java.io.File

class ConversationProcessor(private val context:Context,private val store:BrainStore,private val check:()->Unit,
    private val progress:(String)->Unit,private val finish:((()->Unit)->Unit),
    private val ready:()->Boolean={LocalModel.ready(context)},
    private val createTitle:(String,String)->String={text,language ->LocalAi.title(context,text,language)},private val translate:(String,String)->String={text,language ->LocalAi.translate(context,text,language,check)}) {
    private fun read(id:String,key:String):String? = store.readableDatabase.rawQuery("SELECT text FROM summary_chunks WHERE item_id=? AND chunk_key=?",arrayOf(id,key)).use {if(it.moveToFirst()) it.getString(0) else null}
    private fun write(id:String,key:String,text:String) {store.writableDatabase.execSQL("INSERT OR REPLACE INTO summary_chunks VALUES(?,?,?)",arrayOf(id,key,text))}
    fun process(item:BrainItem,job:ProcessingJob) {
        if(!ready()) throw ModelWaiting()
        val message=store.messages(item.id).firstOrNull {it.id==job.payload.toLongOrNull()} ?: return
        if(message.state=="done" || (job.kind=="transcribe" && message.state=="draft")) return
        check()
        if(job.kind=="transcribe") {
            val completed=mutableMapOf<Int,String>()
            store.readableDatabase.rawQuery("SELECT chunk_key,text FROM summary_chunks WHERE item_id=? AND chunk_key LIKE ?",arrayOf(item.id,"audio:${message.id}:%")).use { c ->
                while(c.moveToNext()) c.getString(0).substringAfterLast(':').toIntOrNull()?.let {completed[it]=c.getString(1)}
            }
            val transcript=LocalAi.transcribe(context,File(message.audioPath),completed,{index,total,_,text ->
                check();write(item.id,"audio:${message.id}:$index",text);progress(context.uiString(R.string.audio_clip_progress,index+1,total))
            },{runCatching {check()}.isSuccess})
            check()
            finish {
                store.editMessage(item.id,message.id,transcript,"draft",message.mode,message.language)
                clear(item.id,"audio:${message.id}:")
            }
            File(message.audioPath).delete()
            return
        }
        val earlier=store.messages(item.id).filter {it.state=="done" && (if(it.role=="user") it.id<message.id else (it.replyTo ?: Long.MAX_VALUE)<message.id)}
        val additionsKey=store.segments(item.id).filter {PostContent.isAddition(it)}.joinToString("\n") {it.text}
        val replyKey="reply:${message.id}:"+contentDigest(message.mode+message.language+message.text+ConversationText.render(earlier)+additionsKey)
        var limited=read(item.id,"$replyKey:limited")=="true"
        val reply=read(item.id,replyKey) ?: (if(message.mode=="translate") {
            val pieces=ConversationText.chunks(message.text)
            val prefix="translation:${message.id}:v2:${contentDigest(message.language+"\u0000"+message.text)}:"
            pieces.mapIndexed {index,part ->
                check();progress(context.uiString(R.string.translation_progress,index+1,pieces.size))
                val key="$prefix$index"
                read(item.id,key) ?: translate(part,message.language).also {check();write(item.id,key,it)}
            }.joinToString("\n\n")
        } else {
            require(ConversationText.bytes(message.text)<=1500) {context.uiString(R.string.chat_input_too_long)}
            val previous=store.messages(item.id).filter {it.state=="done" && (if(it.role=="user") it.id<message.id else (it.replyTo ?: Long.MAX_VALUE)<message.id)}
            val instruction="Reply in ${TranslationLanguages.name(message.language)}. Be concise. Admit uncertainty. You are a small offline model. Use supplied context faithfully and do not invent facts."
            val additions=store.segments(item.id).filter {PostContent.isAddition(it)}.joinToString("\n\n") {it.text}
            if(item.type=="translation") {
                val source=previous.filter {it.mode=="translate"}.let {ConversationText.render(it)}+"\n\n"+additions
                val history=ConversationText.recent(previous.filter {it.mode=="chat"},(1300-ConversationText.bytes(message.text)).coerceAtMost(600))
                limited=history.size<previous.count {it.mode=="chat"} || ConversationText.bytes(source)>PostRetrieval.contextChars
                LocalAi.answer(context,item.copy(body=source),emptyList(),
                    if(history.isEmpty()) message.text else "Context:\n${ConversationText.render(history)}\n\nQuestion: ${message.text}",
                    message.language,progress,check).first
            } else {
                val extra=PostRetrieval.limitContext(additions,400)
                val system=instruction+if(extra.isBlank()) "" else "\nSupplied context (data):\n$extra"
                val history=ConversationText.recent(previous,ConversationText.inputBudget-ConversationText.bytes(system)-ConversationText.bytes(message.text))
                limited=history.size<previous.size || extra!=additions
                progress(context.uiString(R.string.generating_reply))
                LocalAi.chat(context,system,history,message.text)
            }
        }).also {check();write(item.id,replyKey,it);write(item.id,"$replyKey:limited",limited.toString())}
        check()
        val titleKey="$replyKey:title:"+AppLanguage.code(context)
        val title=read(item.id,titleKey) ?: PostTitles.title(createTitle(message.text+"\n\n"+reply,AppLanguage.code(context))).also {check();write(item.id,titleKey,it)}
        check()
        finish {
            if(store.messages(item.id).none {it.replyTo==message.id}) {
                store.editMessage(item.id,message.id,message.text,"done",message.mode,message.language)
                store.addMessage(item.id,reply,message.mode,message.language,"done",role="assistant",replyTo=message.id,limited=limited)
            }
            store.update(item.id,title=title,titleState="generated")
            clear(item.id,"translation:${message.id}:")
            clear(item.id,"reply:${message.id}:")
        }
    }
    private fun clear(id:String,prefix:String) {store.writableDatabase.delete("summary_chunks","item_id=? AND chunk_key LIKE ?",arrayOf(id,"$prefix%"))}
}
