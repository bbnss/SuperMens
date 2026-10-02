// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.UUID

object PdfTextExtractor {
    fun extract(context: Context, store: BrainStore, item: BrainItem, shouldContinue: ()->Boolean = { true }, ocr: ((Bitmap)->String)? = null, readPage:(Int)->String? = {null}, savePage:(Int,String)->Unit = {_,_->}) {
        DocumentStorage.open(context,item).use { source ->
            val renderer=source.renderer
            require(renderer.pageCount>0) { context.uiString(R.string.pdf_empty) }
            val results=mutableListOf<Segment>()
            var recognizer: TextRecognizer?=null
            try { for(index in 0 until renderer.pageCount) {
                if(!shouldContinue()) throw InterruptedException(context.uiString(R.string.pdf_interrupted))
                store.update(item.id,status="processing_pdf ${index+1}/${renderer.pageCount}")
                renderer.openPage(index).use { page->
                val native=page.textContents.joinToString("\n") { it.text }
                val content=readPage(index) ?: (if(native.isNotBlank()) native else {
                    val scale=(2000f/maxOf(page.width,page.height)).coerceAtMost(2f)
                    val bmp=Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),(page.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                    try { bmp.eraseColor(Color.WHITE);page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); ocr?.invoke(bmp) ?: Tasks.await((recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer=it }).process(InputImage.fromBitmap(bmp,0))).text } finally { bmp.recycle() }
                }).also {savePage(index,it)}
                // Keep blank pages too, so a fully extracted document has a record for every page.
                results+=Segment(0,item.id,content,-1,index+1,if(native.isNotBlank()) "pdf" else "ocr")
            } } } finally { recognizer?.close() }
            store.addSegments(item.id,store.segments(item.id).filter { it.source=="testo condiviso" }+results)
            store.update(item.id,pdfPages=renderer.pageCount)
            val first=renderer.openPage(0)
            first.use { page->
                val scale=720f/maxOf(page.width,page.height)
                val bmp=Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),(page.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                try {
                    bmp.eraseColor(Color.WHITE);page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val thumb=File(File(context.filesDir,"previews").apply { mkdirs() },"${item.id}-${UUID.randomUUID()}.webp")
                    try {
                        thumb.outputStream().use { require(bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY,75,it)) }
                        store.update(item.id,thumbnail=thumb.absolutePath)
                        if(item.thumbnail.isNotBlank() && item.thumbnail!=item.attachment) File(item.thumbnail).delete()
                    } catch(e: Exception) { thumb.delete();throw e }
                } finally { bmp.recycle() }
            }
        }
        // A relinked old copy is removed only after all pages have been extracted successfully.
        if(item.documentUri.isNotBlank() && item.attachment.isNotBlank() && DocumentStorage.hasPermission(context,item.documentUri)) {
            store.update(item.id,attachment="")
            File(item.attachment).delete()
        }
    }
}
