package it.supermens.local

import android.content.Context
import org.jsoup.Jsoup
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Structured HTML extraction preserves quotes/apostrophes and resolves redirected relative URLs. */
object PublicPage {
    data class Content(val title:String,val text:String,val images:List<String>,val limited:Boolean)
    fun parse(html:String,base:String,social:Boolean):Content {
        val document=Jsoup.parse(html,base)
        fun meta(key:String)=document.select("meta").firstOrNull { it.attr("property").equals(key,true) || it.attr("name").equals(key,true) }?.attr("content").orEmpty()
        val images=listOf("og:image:secure_url","og:image","twitter:image","twitter:image:src").map { meta(it) }.filter { it.isNotBlank() }.mapNotNull { runCatching { URL(URL(base),it).toString() }.getOrNull() }.distinct()
        val title=meta("og:title").ifBlank { document.title() }
        val description=listOf("og:description","twitter:description","description").map { meta(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
        document.select("script,style,nav,footer,header,noscript").remove()
        val text=if(social) description else document.selectFirst("article,main")?.wholeText()?.trim().orEmpty().ifBlank { document.body()?.wholeText()?.trim().orEmpty() }
        val login=social && Regex("(?i)^(log[ -]?in|sign[ -]?in|sign[ -]?up|accedi|registrati)\\b").containsMatchIn(title+" "+description)
        return Content(if(login) "" else title,if(login) "" else text,images,social)
    }
}
object SourceAcquisition {
    private fun get(url:String):Pair<String,String> {
        require(URL(url).protocol=="https")
        val c=URL(url).openConnection() as HttpURLConnection
        c.connectTimeout=15000;c.readTimeout=20000;c.setRequestProperty("User-Agent","Mozilla/5.0 (compatible; SuperMens)")
        try { c.inputStream.bufferedReader().use { input -> val out=StringBuilder();val buffer=CharArray(8192);while(out.length<2_000_000) { val n=input.read(buffer);if(n<0) break;out.append(buffer,0,n) };return out.toString() to c.url.toString() } } finally { c.disconnect() }
    }
    fun acquire(context:Context,store:BrainStore,item:BrainItem) {
        when(item.type) {
            "video" -> ProcessingGuard.exclusive { VideoStorage.prepare(context,store,item) }
            "pdf" -> {
                if(item.pdfPages>0 || item.pendingMedia.isNotBlank() || (item.attachment.isNotBlank() && item.documentUri.isBlank())) return
                // Persist a temporary source snapshot now, including cloud-provider bytes.
                val file=File(File(context.filesDir,"pending").apply { mkdirs() },"${item.id}.pdf")
                try { context.contentResolver.openInputStream(android.net.Uri.parse(item.documentUri))?.use { input ->file.outputStream().use { input.copyTo(it) } } ?: error(context.uiString(R.string.pdf_missing_original));store.update(item.id,pendingMedia=file.absolutePath) } catch(e:Exception) {file.delete();throw e}
            }
            "youtube" -> {
                val result=YoutubeCaptions.fetchWithFallback(context,item.sourceUrl)
                if(result.title.isNotBlank()) store.update(item.id,title=result.title)
                if(result.segments.isNotEmpty()) store.addSegments(item.id,result.segments.map { it.copy(itemId=item.id) })
                else if(result.reason!="video senza sottotitoli") error(result.reason)
                val vid=Regex("(?:v=|youtu\\.be/|shorts/)([A-Za-z0-9_-]{11})").find(item.sourceUrl)?.groupValues?.get(1)
                if(vid!=null) preview(context,store,item,listOf("https://img.youtube.com/vi/$vid/hqdefault.jpg"))
            }
            else -> web(context,store,item)
        }
    }
    private fun web(context:Context,store:BrainStore,item:BrainItem) {
        val social=item.type in setOf("x","instagram","facebook","tiktok")
        var page:PublicPage.Content?=null
        var quality="unknown"
        if(item.type=="x") {
            val id=Regex("/status/(\\d+)").find(item.sourceUrl)?.groupValues?.get(1)
            if(id!=null) runCatching {
                val obj=JSONObject(get("https://cdn.syndication.twimg.com/tweet-result?id=$id&lang=${AppLanguage.code(context)}&token=0").first)
                val full=obj.optJSONObject("note_tweet")?.optString("text").orEmpty().ifBlank { obj.optString("full_text") }
                val text=full.ifBlank { obj.optString("text") }
                require(text.isNotBlank())
                val photos=obj.optJSONArray("photos")
                val images=buildList {
                    if(photos!=null) for(i in 0 until photos.length()) photos.optJSONObject(i)?.optString("url")?.takeIf {it.startsWith("https://")}?.let {add(it)}
                    val media=obj.optJSONArray("mediaDetails")
                    if(media!=null) for(i in 0 until media.length()) {
                        val entry=media.optJSONObject(i) ?: continue
                        listOf("media_url_https","thumbnail_url").forEach {key ->entry.optString(key).takeIf {it.startsWith("https://")}?.let {add(it)}}
                    }
                }.distinct()
                // Public syndication does not reliably certify long-post completeness.
                quality=if(full.isNotBlank()) "complete" else if(text.endsWith("…") || text.endsWith("...")) "limited" else "unknown"
                page=PublicPage.Content(obj.optJSONObject("user")?.optString("name").orEmpty().ifBlank {item.title},text,images,quality!="complete")
            }
            if(page==null) runCatching {
                val obj=JSONObject(get("https://publish.x.com/oembed?url=${URLEncoder.encode(item.sourceUrl,"UTF-8")}&omit_script=true&dnt=true").first)
                val text=Jsoup.parse(obj.getString("html")).selectFirst("blockquote p")?.text().orEmpty()
                require(text.isNotBlank());page=PublicPage.Content(obj.optString("author_name",item.title),text,emptyList(),true);quality="limited"
            }
        }
        if(page==null) {
            val (html,finalUrl)=get(item.sourceUrl)
            page=PublicPage.parse(html,finalUrl,social);quality=if(social) "limited" else "complete"
        }
        val content=page!!
        val shared=store.segments(item.id).filter { it.source=="testo condiviso" }.ifEmpty { if(item.body.isNotBlank()) listOf(Segment(0,item.id,item.body,-1,-1,"testo condiviso")) else emptyList() }
        if(content.text.isNotBlank()) store.addSegments(item.id,shared+Segment(0,item.id,content.text,-1,-1,if(social) "metadati pubblici" else "web"))
        if(content.title.isNotBlank()) store.update(item.id,title=content.title.take(150))
        store.update(item.id,sourceQuality=quality)
        preview(context,store,item,content.images)
    }
    private fun preview(context:Context,store:BrainStore,item:BrainItem,candidates:List<String>) {
        val tweetPhoto=item.type=="x" && candidates.any {it.contains("pbs.twimg.com/media/")}
        if(!tweetPhoto && item.thumbnail.isNotBlank() && File(item.thumbnail).isFile) return
        val usable=candidates.filter { it.startsWith("https://") && !(item.type=="x" && (it.contains("abs.twimg.com") || it.contains("twitter_logo") || it.contains("/icons/"))) }
        for(url in usable) try {
            val preview=ImageStorage.downloadPreview(context,URL(url))
            if(store.get(item.id)==null) { preview.delete();return }
            val previous=store.get(item.id)?.thumbnail.orEmpty()
            store.update(item.id,thumbnail=preview.absolutePath)
            if(previous.isNotBlank() && previous!=item.attachment) File(previous).delete()
            return
        } catch(e:Exception) { android.util.Log.w("SuperMensPreview", "Preview unavailable for ${item.id}",e) }
        if(usable.isNotEmpty()) store.update(item.id,sourceQuality=store.get(item.id)?.sourceQuality.orEmpty()+"_preview_failed")
    }
}
