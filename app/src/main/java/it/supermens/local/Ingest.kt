package it.supermens.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.net.URI
import java.util.UUID

object Ingest {
    private val url=Regex("https?://[^\\s<>]+")
    fun intake(context:Context,store:BrainStore,intent:Intent):List<String> {
        val ids=mutableListOf<String>()
        val text=intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val uris=mutableListOf<Uri>()
        if(intent.action==Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM,Uri::class.java)?.let { uris.addAll(it) }
        } else intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java)?.let { uris.add(it) }
        intent.clipData?.let { clip-> for(i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { if(it !in uris) uris.add(it) } }
        if(intent.action==Intent.ACTION_SEND && text.isNotBlank()) {
            val sharedLink=url.find(text)!=null
            val explicit=uris.firstOrNull { uri ->
                if(!sharedLink) true else {
                    val mime=context.contentResolver.getType(uri).orEmpty()
                    val name=runCatching { context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else "" }.orEmpty() }.getOrDefault("")
                    mime.startsWith("audio/") || mime.startsWith("video/") || mime=="application/pdf" || listOf(".pdf",".m4a",".mp3",".wav",".ogg",".flac",".mp4",".mov",".mkv",".webm").any { name.endsWith(it,true) }
                }
            }
            if(explicit!=null) return listOf(file(context,store,explicit,intent.type,text))
            val id=text(context,store,text)
            ids+=id
            // Browsers often attach an image preview to a shared URL. It belongs to this item.
            uris.firstOrNull { context.contentResolver.getType(it)?.startsWith("image/")==true }?.takeIf { store.get(id)?.thumbnail.isNullOrBlank() }?.let { preview ->
                runCatching { ImageStorage.import(context,preview,"preview",textSensitive=false) }.onSuccess { store.update(id,thumbnail=it.absolutePath) }
            }
            return ids
        }
        if(text.isNotBlank()) ids+=text(context,store,text)
        uris.distinct().forEach { ids+=file(context,store,it,intent.type) }
        return ids
    }
    fun text(context:Context,store:BrainStore,raw:String):String {
        val link=url.find(raw)?.value?.trimEnd('.',',',')',';')
        if(link!=null) store.findByUrl(link)?.let { return it.id }
        val host=link?.let { runCatching { URI(it).host?.removePrefix("www.") }.getOrNull() }.orEmpty()
        val type=when { host=="youtube.com" || host.endsWith(".youtube.com") || host=="youtu.be" -> "youtube"; host=="instagram.com" || host.endsWith(".instagram.com") -> "instagram"; host=="x.com" || host.endsWith(".x.com") || host=="twitter.com" -> "x"; host=="facebook.com" || host.endsWith(".facebook.com") || host=="fb.watch" -> "facebook"; host=="tiktok.com" || host.endsWith(".tiktok.com") -> "tiktok"; link!=null -> "web"; else -> "note" }
        val title=if(link==null) raw.lineSequence().first().take(90) else when(type) { "youtube"->context.uiString(R.string.youtube_title); "note"->context.uiString(R.string.type_note); else ->context.uiString(R.string.content_from,host) }
        val id=store.add(type,title,link.orEmpty(),host,raw)
        enqueue(context,id)
        return id
    }
    fun file(context:Context,store:BrainStore,uri:Uri,mimeHint:String?=null,sharedText:String=""):String {
        val resolver=context.contentResolver
        val mime=resolver.getType(uri).takeUnless { it.isNullOrBlank() || it=="application/octet-stream" } ?: mimeHint.orEmpty()
        val name=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null } ?: context.uiString(R.string.attachment_label)
        val type=when { mime=="application/pdf" || name.endsWith(".pdf",true)->"pdf"; mime.startsWith("audio/") || listOf(".m4a",".mp3",".wav",".ogg",".flac").any { name.endsWith(it,true) }->"audio"; mime.startsWith("video/") || listOf(".mp4",".mov",".mkv",".webm").any { name.endsWith(it,true) }->"video"; mime.startsWith("image/") || listOf(".jpg",".jpeg",".png",".webp",".heic").any { name.endsWith(it,true) }->"image"; name.endsWith(".vtt",true)||name.endsWith(".srt",true)->"transcript"; else->"file" }
        val persistentPdf=type in setOf("pdf","video") && DocumentStorage.persist(context,uri)
        val stagedVideo=if(type=="video" || (type=="pdf" && !persistentPdf)) VideoStorage.stage(context,uri) else null
        val target=when {
            type=="video" -> null
            type=="pdf" && !persistentPdf -> null
            persistentPdf -> null
            type=="image" -> ImageStorage.import(context,uri,name)
            else -> copyAttachment(context,uri,name)
        }
        val link=url.find(sharedText)?.value?.trimEnd('.',',',')',';').orEmpty()
        val id=try { store.add(type,name,sourceUrl=link,body=sharedText,attachment=target?.absolutePath.orEmpty(),thumbnail=if(type=="image") target?.absolutePath.orEmpty() else "",documentUri=if(persistentPdf) uri.toString() else "") }
            catch(e: Exception) { target?.delete();stagedVideo?.delete();throw e }
        if(stagedVideo!=null) store.update(id,pendingMedia=stagedVideo.absolutePath)
        if(sharedText.isNotBlank()) store.addSegments(id,listOf(Segment(0,id,sharedText,-1,-1,"testo condiviso")))
        enqueue(context,id)
        return id
    }
    fun relinkVideo(context:Context,store:BrainStore,id:String,uri:Uri) {
        val item=store.get(id) ?: return
        require(item.type=="video")
        val persisted=DocumentStorage.persist(context,uri)
        val staged=VideoStorage.stage(context,uri)
        ProcessingGuard.exclusive {
            if(item.pendingMedia.isNotBlank()) File(item.pendingMedia).delete()
            store.update(id,documentUri=if(persisted) uri.toString() else "",pendingMedia=staged.absolutePath,status="saved")
            store.clearAudioClips(id)
        }
        enqueue(context,id,manual=true)
    }
    fun relinkPdf(context: Context, store: BrainStore, id: String, uri: Uri) {
        val item=store.get(id) ?: error(context.uiString(R.string.item_missing))
        store.writableDatabase.delete("summary_chunks","item_id=?",arrayOf(id))
        require(item.type=="pdf") { context.uiString(R.string.not_pdf) }
        val persistent=DocumentStorage.persist(context,uri)
        val copy=if(persistent) null else copyAttachment(context,uri,"document.pdf")
        try { store.update(id,documentUri=if(persistent) uri.toString() else "",attachment=copy?.absolutePath ?: item.attachment,pdfPages=0,status="saved") }
        catch(e: Exception) { copy?.delete();throw e }
        if(copy!=null && item.attachment.isNotBlank()) File(item.attachment).delete()
        if(item.documentUri.isNotBlank() && item.documentUri!=uri.toString() && store.list().none { it.documentUri==item.documentUri }) runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(item.documentUri),Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        enqueue(context,id,manual=true)
    }
    private fun copyAttachment(context:Context,uri:Uri,name:String):File {
        val dir=File(context.filesDir,"attachments").apply { mkdirs() }
        val ext=name.substringAfterLast('.',"bin").take(8).filter { it.isLetterOrDigit() }.ifBlank { "bin" }
        val target=File(dir,"${UUID.randomUUID()}.$ext")
        try { context.contentResolver.openInputStream(uri)?.use { input->target.outputStream().use { input.copyTo(it) } } ?: error(context.uiString(R.string.file_open_failed)) }
        catch(e:Exception) { target.delete();throw e }
        return target
    }
    fun enqueue(context:Context,id:String,enrichOnly:Boolean=false,manual:Boolean=false) = ProcessingQueue.enqueue(context,id,manual,enrichOnly)
    fun reschedulePending(context:Context,store:BrainStore) { ProcessingQueue.reconcile(context,store);ProcessingQueue.wake(context) }
}
