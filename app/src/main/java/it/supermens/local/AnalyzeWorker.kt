package it.supermens.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import androidx.work.*
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

/** Old persisted requests delegate; no legacy worker can run inference in parallel. */
class AnalyzeWorker(context:Context,params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result {
        inputData.getString("id")?.let { ProcessingQueue.enqueue(applicationContext,it,enrichOnly=inputData.getBoolean("enrich_only",false)) }
        return Result.success()
    }
}
object ProcessingNotifications {
    fun foreground(context:Context,item:BrainItem):ForegroundInfo {
        val channel="supermens_processing"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel,context.uiString(R.string.notification_channel),NotificationManager.IMPORTANCE_LOW))
        val notification=Notification.Builder(context,channel).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle(context.uiString(R.string.notification_title,typeLabel(context,item.type))).setContentText(item.title).setOngoing(true).build()
        val kind=if(item.type in setOf("audio","video","image")) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return ForegroundInfo(item.id.hashCode().and(0x7fffffff).coerceAtLeast(1),notification,kind)
    }
}
class PostProcessor(private val context:Context,private val store:BrainStore,private val check:()->Unit) {
    fun process(item:BrainItem) {
        check()
        when(item.type) {
            "pdf" -> if(item.pdfPages==0) {
                PdfTextExtractor.extract(context,store,if(item.pendingMedia.isNotBlank()) item.copy(attachment=item.pendingMedia,documentUri="") else item,{ runCatching { check() }.isSuccess },readPage={ page ->store.readableDatabase.rawQuery("SELECT text FROM summary_chunks WHERE item_id=? AND chunk_key=?",arrayOf(item.id,"pdf-page-$page")).use {if(it.moveToFirst()) it.getString(0) else null} },savePage={page,text ->store.writableDatabase.execSQL("INSERT OR REPLACE INTO summary_chunks VALUES(?,?,?)",arrayOf(item.id,"pdf-page-$page",text))})
                if(item.pendingMedia.isNotBlank()) {
                    File(item.pendingMedia).delete();store.update(item.id,pendingMedia="")
                    if(item.attachment.isNotBlank() && item.documentUri.isNotBlank() && DocumentStorage.hasPermission(context,item.documentUri)) {File(item.attachment).delete();store.update(item.id,attachment="")}
                }
            }
            "image" -> if(store.segments(item.id).none { it.source=="ocr" }) image(item)
            "transcript" -> if(store.segments(item.id).isEmpty()) { val raw=File(item.attachment).readText();store.addSegments(item.id,YoutubeCaptions.parse(raw).ifEmpty { listOf(Segment(0,item.id,raw,-1,-1,"import")) }.map { it.copy(itemId=item.id) }) }
        }
        check()
        if(item.type in setOf("audio","video")) {
            val source=if(item.type=="video" && item.attachment.isBlank()) item.pendingMedia else item.attachment
            if(source.isNotBlank()) {
                if(!LocalModel.ready(context)) throw ModelWaiting()
                store.update(item.id,status="processing_audio")
                val completed=store.segments(item.id).filter { it.source=="gemma audio" }.associate { it.page to it.text }
                val transcript=LocalAi.transcribe(context,File(source),completed,{ index,total,startMs,text ->store.upsertAudioClip(item.id,index,startMs,text);store.update(item.id,status="processing_audio ${index+1}/$total") },{runCatching {check()}.isSuccess})
                val shared=store.segments(item.id).filter { it.source=="testo condiviso" }.joinToString("\n") { it.text }
                store.update(item.id,body=listOf(shared,transcript).filter { it.isNotBlank() }.joinToString("\n\n"),title=if(item.type=="audio") transcript.lineSequence().first().take(76) else null)
                if(item.source=="dettatura Gemma") store.changeType(item.id,"note")
                if(item.type=="video" && item.attachment.isBlank()) { File(source).delete();store.update(item.id,pendingMedia="") }
            }
        }
        check()
        val current=store.get(item.id) ?: return
        if(current.type=="image") {
            if(!LocalModel.ready(context)) throw ModelWaiting()
            store.update(item.id,status="processing_vision")
            ImageAnalysis.enrich(store,current,true,AppLanguage.code(context)) { LocalAi.describeImage(context,it,AppLanguage.code(context)) }
        } else if(current.type in setOf("x","instagram","facebook","tiktok") && current.body.replace(Regex("https?://\\S+"),"").trim().isBlank()) {
            store.update(item.id,status="source_limited",summary=context.uiString(R.string.social_unavailable))
        } else if(current.body.isNotBlank() && (current.type!="youtube" || store.segments(item.id).isNotEmpty())) summarize(current)
        else store.update(item.id,status=if(current.type=="youtube") "youtube_no_captions" else "ready")
    }
    fun summarize(item:BrainItem) {
        if(!LocalModel.ready(context)) throw ModelWaiting()
        check();store.update(item.id,status="processing_summary")
        val language=AppLanguage.code(context)
        val input=if(item.type in setOf("x","instagram","facebook","tiktok") && !item.sourceQuality.startsWith("complete")) "Riassumi solo il testo disponibile, la fonte potrebbe essere incompleta.\n\n${item.body}" else item.body
        val summary=LocalAi.summarize(context,input,language,check,{ key -> store.readableDatabase.rawQuery("SELECT text FROM summary_chunks WHERE item_id=? AND chunk_key=?",arrayOf(item.id,key)).use { if(it.moveToFirst()) it.getString(0) else null } },{key,text ->store.writableDatabase.execSQL("INSERT OR REPLACE INTO summary_chunks VALUES(?,?,?)",arrayOf(item.id,key,text))})
        store.update(item.id,summary=summary,status="ready")
        store.writableDatabase.delete("summary_chunks","item_id=?",arrayOf(item.id))
    }
    private fun image(item:BrainItem) {
        var file=File(item.attachment)
        if(!ImageStorage.isOptimized(file)) { val compressed=ImageStorage.importFile(context,file,item.title);store.update(item.id,attachment=compressed.absolutePath,thumbnail=compressed.absolutePath);file.delete();file=compressed }
        val bmp=BitmapFactory.decodeFile(file.absolutePath) ?: error(context.uiString(R.string.image_unreadable))
        store.update(item.id,status="processing_ocr")
        val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try { val text=Tasks.await(recognizer.process(InputImage.fromBitmap(bmp,0))).text
            val shared=store.segments(item.id).filter { it.source=="testo condiviso" }
            store.addSegments(item.id,shared+listOf(Segment(0,item.id,text,-1,-1,"ocr")))
        } finally { bmp.recycle();recognizer.close() }
    }
}
