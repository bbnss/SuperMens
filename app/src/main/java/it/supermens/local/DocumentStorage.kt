// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.UUID
import androidx.work.*

object DocumentStorage {
    fun displayName(context: Context, uri: Uri): String = context.contentResolver.query(uri,
        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
        if(it.moveToFirst()) it.getString(0) else null
    } ?: "document.pdf"

    /** Stage and validate completely before exposing the new private copy. */
    fun copyPdf(context: Context, uri: Uri): File {
        val directory=File(context.filesDir,"attachments").apply {mkdirs()}
        val target=File(directory,"${UUID.randomUUID()}.pdf")
        val staging=File(directory,target.name+".part")
        try {
            context.contentResolver.openInputStream(uri)?.use {input -> staging.outputStream().use {output ->input.copyTo(output);output.fd.sync()}}
                ?: error(context.uiString(R.string.pdf_missing_original))
            renderer(ParcelFileDescriptor.open(staging,ParcelFileDescriptor.MODE_READ_ONLY)).use {
                require(it.pageCount>0) {context.uiString(R.string.pdf_empty)}
            }
            check(staging.renameTo(target)) {context.uiString(R.string.file_open_failed)}
            return target
        } catch(e: Exception) {staging.delete();target.delete();throw e}
    }

    fun retain(context: Context, store: BrainStore, id: String) = ProcessingGuard.exclusive {
        val item=store.get(id) ?: return@exclusive
        if(item.type!="pdf" || item.attachment.isNotBlank() && File(item.attachment).isFile) return@exclusive
        val uri=item.pendingMedia.takeIf {it.isNotBlank() && File(it).isFile}?.let {Uri.fromFile(File(it))}
            ?: item.documentUri.takeIf {it.isNotBlank()}?.let {Uri.parse(it)}
            ?: error(context.uiString(R.string.pdf_relink_required))
        val copy=copyPdf(context,uri)
        val db=store.writableDatabase
        var retained=false
        db.beginTransaction()
        try {
            if(store.get(id)!=null) store.update(id,attachment=copy.absolutePath,pendingMedia="")
            db.setTransactionSuccessful()
        } finally {
            try {db.endTransaction();retained=store.get(id)?.attachment==copy.absolutePath}
            finally {if(!retained) copy.delete()}
        }
        if(retained && item.pendingMedia.isNotBlank()) File(item.pendingMedia).delete()
    }

    fun scheduleRecovery(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("supermens-retain-pdfs",ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RetainPdfWorker>().build())
    }
    fun persist(context: Context, uri: Uri): Boolean {
        if(uri.scheme!="content") return false
        if(hasPermission(context,uri.toString())) return true
        return runCatching {
            context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.contentResolver.persistedUriPermissions.any { it.uri==uri && it.isReadPermission }
        }.getOrDefault(false)
    }
    fun open(context: Context, item: BrainItem): PdfSource {
        if(item.attachment.isNotBlank() && File(item.attachment).isFile) return PdfSource(renderer(ParcelFileDescriptor.open(File(item.attachment),ParcelFileDescriptor.MODE_READ_ONLY)))
        require(item.documentUri.isNotBlank()) { context.uiString(R.string.pdf_relink_required) }
        val uri=Uri.parse(item.documentUri)
        val descriptor=context.contentResolver.openFileDescriptor(uri,"r") ?: error(context.uiString(R.string.pdf_missing_original))
        try { return PdfSource(PdfRenderer(descriptor)) }
        catch(e: Exception) { descriptor.close() }
        // Cloud providers may expose a pipe rather than the seekable descriptor PdfRenderer needs.
        val temporary=File.createTempFile("supermens-pdf-",".pdf",context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> temporary.outputStream().use { input.copyTo(it) } } ?: error(context.uiString(R.string.pdf_missing_original))
            return PdfSource(renderer(ParcelFileDescriptor.open(temporary,ParcelFileDescriptor.MODE_READ_ONLY)),temporary)
        } catch(e: Exception) { temporary.delete();throw e }
    }
    private fun renderer(descriptor: ParcelFileDescriptor): PdfRenderer = try { PdfRenderer(descriptor) } catch(e: Exception) { descriptor.close();throw e }
    fun hasPermission(context: Context, reference: String): Boolean = context.contentResolver.persistedUriPermissions.any { it.uri.toString()==reference && it.isReadPermission }
    class PdfSource(val renderer: PdfRenderer, private val temporary: File?=null): AutoCloseable {
        override fun close() { try { renderer.close() } finally { temporary?.delete() } }
    }
}

/** Recovery needs neither weights nor charging, and missing originals cannot block other PDFs. */
class RetainPdfWorker(context: Context, params: WorkerParameters): Worker(context,params) {
    override fun doWork(): Result = BrainStore(applicationContext).use {store ->
        for(item in store.list().filter {it.type=="pdf"}) {
            if(isStopped) return@use Result.retry()
            runCatching {DocumentStorage.retain(applicationContext,store,item.id)}
        }
        Result.success()
    }
}
