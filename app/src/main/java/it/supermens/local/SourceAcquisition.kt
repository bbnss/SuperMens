// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

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
    fun host(url:String)=runCatching {URL(url).host.lowercase().removePrefix("www.")}.getOrDefault("")
    fun supportedHost(url:String):Boolean {
        val h=host(url)
        return h=="reddit.com" || h.endsWith(".reddit.com") || h=="redd.it" || h=="linkedin.com" || h.endsWith(".linkedin.com") || h=="amzn.eu" || h=="a.co" || Regex("(?:.*\\.)?amazon\\.[a-z.]+$").matches(h)
    }
    fun socialHost(url:String)=host(url).let {it=="reddit.com" || it.endsWith(".reddit.com") || it=="redd.it" || it=="linkedin.com" || it.endsWith(".linkedin.com")}
    fun parse(html:String,base:String,social:Boolean):Content {
        val document=Jsoup.parse(html,base)
        val h=host(base)
        val reddit=h=="reddit.com" || h.endsWith(".reddit.com")
        val linkedin=h=="linkedin.com" || h.endsWith(".linkedin.com")
        val amazon=Regex("(?:.*\\.)?amazon\\.[a-z.]+$").matches(h)
        fun meta(key:String)=document.select("meta").firstOrNull {it.attr("property").equals(key,true) || it.attr("name").equals(key,true)}?.attr("content").orEmpty()
        fun absolute(value:String)=runCatching {URL(URL(base),value).toString()}.getOrNull()?.takeIf {it.startsWith("https://")}
        val images=mutableListOf<String>()
        listOf("og:image:secure_url","og:image","twitter:image","twitter:image:src").map(::meta).filter {it.isNotBlank()}.mapNotNull(::absolute).forEach {images+=it}
        var title=meta("og:title").ifBlank {document.title()}
        val description=listOf("og:description","twitter:description","description").map(::meta).firstOrNull {it.isNotBlank()}.orEmpty()
        var structuredText=""
        var structuredComplete=false
        fun image(value:Any?) {
            when(value) {
                is String -> absolute(value)?.let {images+=it}
                is org.json.JSONArray -> for(i in 0 until value.length()) image(value.opt(i))
                is JSONObject -> image(value.opt("contentUrl") ?: value.opt("url"))
            }
        }
        fun structured(value:Any?) {
            when(value) {
                is org.json.JSONArray -> for(i in 0 until value.length()) structured(value.opt(i))
                is JSONObject -> {
                    val type=value.optString("@type")
                    if(type.contains("Product") || type.contains("Article") || type.contains("SocialMediaPosting")) {
                        title=value.optString("headline").ifBlank {value.optString("name")}.ifBlank {title}
                        structuredText=value.optString("articleBody").ifBlank {value.optString("text")}.ifBlank {value.optString("description")}.ifBlank {structuredText}
                        structuredComplete=structuredComplete || value.optString("articleBody").isNotBlank() || value.optString("text").isNotBlank()
                        image(value.opt("image"))
                    }
                    structured(value.opt("@graph"));structured(value.opt("mainEntity"))
                }
            }
        }
        document.select("script[type=application/ld+json]").forEach {script -> runCatching {structured(org.json.JSONTokener(script.data()).nextValue())}}
        val post=when {
            reddit -> document.selectFirst("shreddit-post [slot=text-body], .thing.link .usertext-body .md, [data-test-id=post-content]")
            linkedin -> document.selectFirst(".feed-shared-update-v2__description, .attributed-text-segment-list__content, .share-update-card__update-text")
            amazon -> document.selectFirst("#feature-bullets, #productDescription")
            else -> null
        }
        if(reddit) {
            document.selectFirst("shreddit-post")?.attr("post-title")?.takeIf {it.isNotBlank()}?.let {title=it}
            document.selectFirst(".thing.link a.title")?.text()?.takeIf {it.isNotBlank()}?.let {title=it}
        }
        if(amazon) {
            document.selectFirst("#productTitle")?.text()?.takeIf {it.isNotBlank()}?.let {title=it}
            document.select("#landingImage, #imgBlkFront, #ebooksImgBlkFront").forEach {element ->
                listOf("src","data-old-hires").map {element.attr(it)}.filter {it.isNotBlank()}.mapNotNull(::absolute).forEach {images.add(0,it)}
                runCatching {val dynamic=JSONObject(element.attr("data-a-dynamic-image"));dynamic.keys().forEach {absolute(it)?.let {url ->images+=url}}}
            }
        }
        if(linkedin || reddit) document.select("shreddit-post img, .thing.link .thumbnail img, .thing.link .expando img, .share-update-card img, .feed-shared-image img").forEach {element ->absolute(element.attr("src"))?.let {images+=it}}
        val explicit=post?.wholeText()?.trim().orEmpty().ifBlank {structuredText}
        document.select("script,style,nav,footer,header,noscript").remove()
        val limited=social || linkedin || reddit || amazon
        val blocked=Regex("(?i)^(?:log[ -]?in|sign[ -]?in|sign[ -]?up|accedi|registrati|welcome to reddit|robot check|access denied|just a moment|blocked|you(?:'ve| have) been blocked)\\b").containsMatchIn(title+" "+description) || document.select("#captchacharacters, form[action*=validateCaptcha], .authwall").isNotEmpty() || Regex("(?i)blocked by network security|verify you are human").containsMatchIn(document.body()?.text().orEmpty())
        val text=if(blocked) "" else explicit.ifBlank {if(limited || amazon) description else document.selectFirst("article,main")?.wholeText()?.trim().orEmpty().ifBlank {document.body()?.wholeText()?.trim().orEmpty()}}
        val usable=images.distinct().filterNot {Regex("(?i)(?:logo|avatar|default[_-]?image|placeholder|redditstatic.*icon)").containsMatchIn(it)}
        return Content(if(blocked) "" else title,text,if(blocked) emptyList() else usable,limited && post?.wholeText()?.trim().isNullOrBlank() && !structuredComplete)
    }
    fun redditJson(raw:String):Content {
        val root=org.json.JSONTokener(raw).nextValue()
        val listing=if(root is org.json.JSONArray) root.optJSONObject(0) else root as? JSONObject
        val data=listing?.optJSONObject("data")?.optJSONArray("children")?.optJSONObject(0)?.optJSONObject("data") ?: error("No public Reddit post")
        val title=data.optString("title")
        val body=data.optString("selftext").takeUnless {it in setOf("[removed]","[deleted]")}.orEmpty()
        val images=mutableListOf<String>()
        fun add(value:String) {val decoded=org.jsoup.parser.Parser.unescapeEntities(value,false);if(decoded.startsWith("https://")) images+=decoded}
        val preview=data.optJSONObject("preview")?.optJSONArray("images")
        if(preview!=null) for(i in 0 until preview.length()) add(preview.optJSONObject(i)?.optJSONObject("source")?.optString("url").orEmpty())
        val media=data.optJSONObject("media_metadata")
        media?.keys()?.forEach {key ->media.optJSONObject(key)?.optJSONObject("s")?.let {add(it.optString("u").ifBlank {it.optString("gif")})}}
        add(data.optString("thumbnail"))
        val text=listOf(title,body).filter {it.isNotBlank()}.joinToString("\n\n")
        require(text.isNotBlank())
        return Content(title,text,images.distinct(),body.isBlank())
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
        val social=item.type in setOf("x","instagram","facebook","tiktok") || PublicPage.socialHost(item.sourceUrl)
        var page:PublicPage.Content?=null
        var quality="unknown"
        if(item.type=="x") {
            val id=XPost.id(item.sourceUrl)
            if(id!=null) runCatching {
                val (content,sourceQuality)=XPost.parse(get("https://cdn.syndication.twimg.com/tweet-result?id=$id&lang=${AppLanguage.code(context)}&token=0").first,item.title)
                page=content;quality=sourceQuality
            }.onFailure { android.util.Log.w("SuperMensSource", "Public X syndication unavailable",it) }
            if(page==null) runCatching {
                val obj=JSONObject(get("https://publish.x.com/oembed?url=${URLEncoder.encode(item.sourceUrl,"UTF-8")}&omit_script=true&dnt=true").first)
                val text=Jsoup.parse(obj.getString("html")).selectFirst("blockquote p")?.text().orEmpty()
                require(text.isNotBlank());page=PublicPage.Content(obj.optString("author_name",item.title),text,emptyList(),true);quality="limited"
            }
        }
        if(page==null) {
            var resolved=item.sourceUrl
            val html=runCatching {get(item.sourceUrl)}.onSuccess {(raw,finalUrl) ->
                resolved=finalUrl;page=PublicPage.parse(raw,finalUrl,social)
            }
            if(PublicPage.host(resolved).let {it=="reddit.com" || it.endsWith(".reddit.com")}) runCatching {
                val jsonUrl=URL(resolved).let {"https://www.reddit.com"+it.path.trimEnd('/')+".json?raw_json=1&limit=1"}
                val structured=PublicPage.redditJson(get(jsonUrl).first)
                page=structured.copy(images=(structured.images+page?.images.orEmpty()).distinct())
            }
            val reddit=PublicPage.host(resolved).let {it=="reddit.com" || it.endsWith(".reddit.com") || it=="redd.it"}
            if(reddit && (page?.text.isNullOrBlank() || page?.limited==true)) runCatching {
                val (raw,finalUrl)=get("https://old.reddit.com"+URL(resolved).path)
                val old=PublicPage.parse(raw,finalUrl,true)
                if(old.text.isNotBlank()) page=old.copy(images=(old.images+page?.images.orEmpty()).distinct())
            }
            if(reddit && page?.images.isNullOrEmpty()) runCatching {
                val embed=JSONObject(get("https://www.reddit.com/oembed?url="+URLEncoder.encode(resolved,"UTF-8")).first)
                val image=embed.optString("thumbnail_url").takeIf {it.startsWith("https://")}
                val current=page ?: PublicPage.Content("","",emptyList(),true)
                page=current.copy(title=current.title.ifBlank {embed.optString("title")},images=if(image!=null) listOf(image) else current.images)
            }
            if(page==null) {
                val available=store.segments(item.id).filter {it.source=="testo condiviso"}.joinToString("\n\n") {it.text}.ifBlank {item.body}
                if(available.replace(Regex("https?://\\S+"),"").trim().isBlank()) throw html.exceptionOrNull() ?: IllegalStateException("Public source unavailable")
                page=PublicPage.Content("","",emptyList(),true)
            }
            quality=if(page!!.limited) "limited" else "complete"
        }
        var content=page!!
        // oEmbed returns text without images. HTML metadata may still supply the preview.
        if(item.type=="x" && content.images.isEmpty()) runCatching {
            val (html,finalUrl)=get(item.sourceUrl)
            content=content.copy(images=PublicPage.parse(html,finalUrl,true).images)
        }
        val shared=store.segments(item.id).filter { it.source=="testo condiviso" }.ifEmpty { if(item.body.isNotBlank()) listOf(Segment(0,item.id,item.body,-1,-1,"testo condiviso")) else emptyList() }
        if(content.text.isNotBlank()) store.addSegments(item.id,shared+Segment(0,item.id,content.text,-1,-1,if(content.limited) "metadati pubblici" else "web"))
        if(content.title.isNotBlank()) store.update(item.id,title=content.title.take(150))
        store.update(item.id,sourceQuality=quality)
        preview(context,store,item,content.images)
    }
    private fun preview(context:Context,store:BrainStore,item:BrainItem,candidates:List<String>) {
        val tweetPhoto=item.type=="x" && candidates.any {it.contains("pbs.twimg.com/media/") || it.contains("pbs.twimg.com/card_img/") || it.contains("video_thumb")}
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
        if(usable.isNotEmpty() || ((item.type=="x" || PublicPage.supportedHost(item.sourceUrl)) && item.thumbnail.isBlank())) store.update(item.id,sourceQuality=store.get(item.id)?.sourceQuality.orEmpty()+"_preview_failed")
    }
}
