package it.supermens.local

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object CameraCapture {
    fun create(context: Context): File = File.createTempFile("capture-", ".jpg", File(context.cacheDir, "camera").apply { mkdirs() })
    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /** Keep the original on failed import so the user can retry; remove it only after a saved WebP. */
    @Synchronized fun save(context: Context, store: BrainStore, file: File): String {
        val source="fotocamera:${file.name}"
        store.findBySource(source)?.let { file.delete();Ingest.enqueue(context,it.id);return it.id }
        require(file.isFile && file.length() > 0) { context.uiString(R.string.no_camera_photo) }
        val compressed = ImageStorage.importFile(context, file, "Foto")
        val id = try { store.add("image", context.uiString(R.string.photo_title,java.text.DateFormat.getDateTimeInstance().format(java.util.Date())), source = source, attachment = compressed.absolutePath, thumbnail = compressed.absolutePath) }
            catch(e: Exception) { compressed.delete();throw e }
        file.delete()
        Ingest.enqueue(context, id)
        return id
    }
}
