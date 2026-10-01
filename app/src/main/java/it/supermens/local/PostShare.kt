package it.supermens.local

import android.content.*
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

object PostShare {
    fun text(context:Context,item:BrainItem,segments:List<Segment>,answers:List<SavedAnswer>):String = buildString {
        append(item.title);append("\n\n")
        if(item.sourceUrl.isNotBlank()) append(item.sourceUrl+"\n\n")
        if(item.documentUri.isNotBlank()) append(context.uiString(R.string.original_reference)+": "+item.documentUri+"\n\n")
        val summary=if(item.type=="image") PostContent.description(segments) else item.summary
        if(summary.isNotBlank()) append(context.uiString(if(item.type=="image") R.string.description else R.string.summary)+"\n"+summary+"\n\n")
        if(answers.isNotEmpty()) {
            append(context.uiString(R.string.questions_answers)+"\n\n")
            answers.sortedBy {it.createdAt}.forEach { append(it.question+"\n"+it.answer+"\n\n") }
        }
        val body=PostContent.copyText(item,segments)
        val additional=segments.filter { it.source!="visione" && it !in PostContent.textSegments(item.type,segments) && it.text.isNotBlank() }
        if(additional.isNotEmpty()) append(additional.joinToString("\n\n") {it.text}+"\n\n")
        if(body.isNotBlank()) append(context.uiString(if(item.type=="image") R.string.detected_text else if(item.type in setOf("audio","video","youtube")) R.string.full_transcript else R.string.extracted_text)+"\n"+body)
    }
    /** Snapshot every stored segment, irrespective of the detail screen's pagination. */
    fun prepare(context:Context,store:BrainStore,id:String,pdf:Boolean):Intent = ProcessingGuard.exclusive {
        val item=store.get(id) ?: error(context.uiString(R.string.item_missing))
        val body=text(context,item,store.segments(id),store.answers(id))
        val dir=File(context.cacheDir,"shares").apply {mkdirs()}
        val cutoff=System.currentTimeMillis()-24*60*60*1000
        dir.listFiles()?.filter {it.lastModified()<cutoff}?.forEach {it.delete()}
        val photo=if(item.thumbnail.isNotBlank()) BitmapFactory.decodeFile(item.thumbnail) else null
        try {
            var file:File?=null
            if(pdf) {
                file=File(dir,"SuperMens-${UUID.randomUUID()}.pdf")
                val document=PdfDocument()
                try {
                    val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {textSize=12f;color=Color.BLACK}
                    val layout=StaticLayout.Builder.obtain(body,0,body.length,paint,523).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(3f,1f).build()
                    var line=0;var number=1
                    do {
                        val page=document.startPage(PdfDocument.PageInfo.Builder(595,842,number).create())
                        var top=36f
                        if(number==1 && photo!=null) {
                            val height=(523f*photo.height/photo.width).coerceAtMost(230f)
                            val width=height*photo.width/photo.height
                            page.canvas.drawBitmap(photo,null,RectF(36f,top,36f+width,top+height),null);top+=height+20
                        }
                        val start=line
                        val offset=layout.getLineTop(start)
                        while(line<layout.lineCount && layout.getLineBottom(line)-offset<=790-top) line++
                        if(line==start) line++
                        page.canvas.save();page.canvas.clipRect(36f,top,559f,790f)
                        page.canvas.translate(36f,top-offset)
                        // Clip precisely at the final complete line; the next page resumes there.
                        page.canvas.clipRect(0,offset,523,layout.getLineBottom(line-1))
                        layout.draw(page.canvas);page.canvas.restore()
                        page.canvas.drawText(number.toString(),550f,817f,paint)
                        document.finishPage(page);number++
                    } while(line<layout.lineCount)
                    file.outputStream().use {document.writeTo(it)}
                } finally {document.close()}
            } else if(photo!=null) {
                file=File(dir,"SuperMens-${UUID.randomUUID()}.jpg")
                file.outputStream().use { require(photo.compress(Bitmap.CompressFormat.JPEG,88,it)) }
            }
            Intent(Intent.ACTION_SEND).apply {
                type=if(pdf) "application/pdf" else if(file!=null) "image/jpeg" else "text/plain"
                putExtra(Intent.EXTRA_SUBJECT,item.title)
                if(!pdf) putExtra(Intent.EXTRA_TEXT,body)
                if(file!=null) {
                    val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
                    putExtra(Intent.EXTRA_STREAM,uri);clipData=ClipData.newUri(context.contentResolver,item.title,uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        } finally {photo?.recycle()}
    }
}
