// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.net.URL

object ImageStorage {
    private fun decode(source: ImageDecoder.Source, maxSide: Int): Bitmap = ImageDecoder.decodeBitmap(source) { decoder,info,_ ->
        val scale=(maxSide.toDouble()/maxOf(info.size.width,info.size.height)).coerceAtMost(1.0)
        decoder.setTargetSize((info.size.width*scale).toInt().coerceAtLeast(1),(info.size.height*scale).toInt().coerceAtLeast(1))
        decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        // ImageDecoder applies EXIF rotation before scaling. The compressed output needs no EXIF.
    }
    fun import(context: Context, uri: Uri, name: String, textSensitive: Boolean=true): File = ProcessingGuard.exclusive { compress(context,ImageDecoder.createSource(context.contentResolver,uri),name,textSensitive) }
    fun importFile(context: Context, file: File, name: String = file.name, textSensitive: Boolean=true): File = ProcessingGuard.exclusive { compress(context,ImageDecoder.createSource(file),name,textSensitive) }
    fun isOptimized(file: File): Boolean {
        if(!file.name.startsWith("sm-image-v1-") || file.extension!="webp") return false
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(file.absolutePath,bounds)
        return bounds.outWidth>0 && bounds.outHeight>0 && maxOf(bounds.outWidth,bounds.outHeight)<=2400
    }
    fun downloadPreview(context: Context, url: URL): File {
        val temporary=File.createTempFile("supermens-preview-",".image",context.cacheDir)
        try {
            url.openConnection().apply { connectTimeout=10000;readTimeout=15000 }.getInputStream().use { input -> temporary.outputStream().use { output ->
                val buffer=ByteArray(65536);var total=0
                while(true) { val size=input.read(buffer);if(size<0) break;total+=size;require(total<=20_000_000) { context.uiString(R.string.preview_too_large) };output.write(buffer,0,size) }
            } }
            return importFile(context,temporary,textSensitive=false)
        } finally { temporary.delete() }
    }
    private fun compress(context: Context, source: ImageDecoder.Source, name: String, textSensitive: Boolean): File {
        val large=decode(source,2400)
        var scaled: Bitmap?=null
        var pendingOcr: Task<Text>?=null
        val target=File(File(context.filesDir,"attachments").apply { mkdirs() },"sm-image-v1-${UUID.randomUUID()}.webp")
        try {
            // Keep small print legible; OCR runs later in the serialized processing queue.
            val detailed=textSensitive
            val side=if(detailed) 2400 else 1600
            val ratio=(side.toDouble()/maxOf(large.width,large.height)).coerceAtMost(1.0)
            val bitmap=if(ratio<1) Bitmap.createScaledBitmap(large,(large.width*ratio).toInt().coerceAtLeast(1),(large.height*ratio).toInt().coerceAtLeast(1),true).also { scaled=it } else large
            target.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY,if(detailed) 85 else 75,it)) { context.uiString(R.string.image_compression_failed) } }
            require(target.length()>0) { context.uiString(R.string.image_empty) }
            return target
        } catch(e: Exception) { target.delete();throw e }
        finally {
            scaled?.recycle()
            val pending=pendingOcr
            if(pending!=null && !pending.isComplete) pending.addOnCompleteListener { large.recycle() } else large.recycle()
        }
    }
    /** LiteRT-LM's image decoder accepts PNG/JPEG. PNG bytes preserve the stored WebP pixels. */
    fun inferenceBytes(file: File, language: String="it"): ByteArray {
        val bitmap=decode(ImageDecoder.createSource(file),2400)
        try { return ByteArrayOutputStream().use { output ->
            require(bitmap.compress(Bitmap.CompressFormat.PNG,100,output)) { LanguageChoice.text(language,"Impossibile preparare l’immagine per il modello","Could not prepare the image for the model") }
            output.toByteArray()
        } } finally { bitmap.recycle() }
    }
}
