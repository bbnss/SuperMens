// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

object DocumentStorage {
    fun persist(context: Context, uri: Uri): Boolean {
        if(uri.scheme!="content") return false
        if(hasPermission(context,uri.toString())) return true
        return runCatching {
            context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.contentResolver.persistedUriPermissions.any { it.uri==uri && it.isReadPermission }
        }.getOrDefault(false)
    }
    fun open(context: Context, item: BrainItem): PdfSource {
        if(item.attachment.isNotBlank() && (item.documentUri.isBlank() || !hasPermission(context,item.documentUri))) return PdfSource(renderer(ParcelFileDescriptor.open(File(item.attachment),ParcelFileDescriptor.MODE_READ_ONLY)))
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
