// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import java.util.Locale

data class PostMessage(val id:Long,val itemId:String,val role:String,val text:String,val mode:String,val language:String,
    val state:String="pending",val replyTo:Long?=null,val audioPath:String="",val contextLimited:Boolean=false,val createdAt:Long=0)

object ConversationText {
    fun render(messages:List<PostMessage>):String = messages.filter {it.text.isNotBlank()}.joinToString("\n\n") {
        (if(it.role=="user") "User" else if(it.mode=="translate") "Translation (${it.language})" else "Assistant")+":\n"+it.text
    }
    // UTF-8 bytes are a deliberately conservative upper bound for token budgeting.
    // Reserve the remainder of the 4096-token context for instructions and output.
    const val inputBudget=2400
    fun bytes(text:String)=text.toByteArray(Charsets.UTF_8).size
    fun chunks(text:String,budget:Int=1200):List<String> {
        require(budget>=4)
        if(text.isEmpty()) return emptyList()
        val result=mutableListOf<String>();var start=0
        while(start<text.length) {
            var end=start;var size=0;var boundary=-1
            while(end<text.length) {
                val count=Character.charCount(text.codePointAt(end))
                val length=bytes(text.substring(end,end+count))
                if(size+length>budget) break
                size+=length;end+=count
                if(text[end-1].isWhitespace()) boundary=end
            }
            if(end<text.length && boundary>start+(end-start)/2) end=boundary
            result+=text.substring(start,end);start=end
        }
        return result
    }
    fun recent(messages:List<PostMessage>,budget:Int):List<PostMessage> {
        var remaining=budget
        val result=mutableListOf<PostMessage>()
        // Keep whole user/assistant pairs, never an orphaned model answer.
        val completed=messages.filter {it.role=="assistant" && it.state=="done"}
        for(reply in completed.asReversed()) {
            val user=messages.firstOrNull {it.id==reply.replyTo && it.state=="done"} ?: continue
            val cost=bytes(user.text)+bytes(reply.text)+40
            if(cost>remaining) break
            result.add(0,reply);result.add(0,user);remaining-=cost
        }
        return result
    }
}

object TranslationLanguages {
    val tags=listOf("it","en","fr","de","es","pt","nl","pl","ru","uk","ar","he","hi","bn","zh-Hans","zh-Hant","ja","ko","vi","tr")
    fun name(value:String,display:Locale=Locale.getDefault()):String {
        val locale=Locale.forLanguageTag(value)
        return if(locale.language.isNotBlank() && (value in tags || value.matches(Regex("[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})*")))) locale.getDisplayName(display) else value
    }
}
