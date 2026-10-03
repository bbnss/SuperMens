// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

internal object PostFile {
    fun intent(context:Context,item:BrainItem):Intent {
        val original=item.documentUri.takeIf {it.isNotBlank()}?.let {Uri.parse(it)}
        val uri=original?.takeIf {runCatching {context.contentResolver.openFileDescriptor(it,"r")?.use {true} ?: false}.getOrDefault(false)}
            ?: item.attachment.takeIf {it.isNotBlank() && File(it).isFile}?.let {FileProvider.getUriForFile(context,"${context.packageName}.files",File(it))}
            ?: throw java.io.FileNotFoundException(context.uiString(if(item.type=="video") R.string.video_relink_hint else R.string.pdf_relink_hint))
        val extension=File(item.attachment.ifBlank {item.title}).extension.lowercase()
        val mime=when(item.type) {
            "pdf" -> "application/pdf"
            else -> context.contentResolver.getType(uri).takeUnless {it.isNullOrBlank() || it=="application/octet-stream"}
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: when(item.type) {"video"->"video/*";"audio"->"audio/*";"image"->"image/*";"transcript"->"text/plain";else->"application/octet-stream"}
        }
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {
            clipData=ClipData.newRawUri(item.title,uri)
        }
    }
}
