// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object Backup {
    fun export(context:Context,store:BrainStore,uri:Uri) {
        val manifest=JSONObject().put("format","supermens-local").put("version",1).put("items",JSONArray())
        val items=store.list()
        context.contentResolver.openOutputStream(uri,"w")!!.use { output-> ZipOutputStream(output).use { zip->
            for(item in items) {
                val obj=JSONObject().put("id",item.id).put("type",item.type).put("title",item.title).put("summary",item.summary).put("sourceUrl",item.sourceUrl).put("source",item.source).put("body",item.body).put("status",item.status).put("createdAt",item.createdAt).put("updatedAt",item.updatedAt).put("documentUri",item.documentUri).put("pdfPages",item.pdfPages).put("sourceQuality",item.sourceQuality)
                val seg=JSONArray();store.segments(item.id).forEach { seg.put(JSONObject().put("text",it.text).put("startMs",it.startMs).put("page",it.page).put("source",it.source)) };obj.put("segments",seg)
                val answers=JSONArray();store.answers(item.id).forEach { answers.put(JSONObject().put("question",it.question).put("answer",it.answer).put("evidence",it.evidence).put("model",it.model).put("createdAt",it.createdAt)) };obj.put("answers",answers)
                if(item.attachment.isNotBlank()) { val file=File(item.attachment);require(file.isFile) { context.uiString(R.string.backup_attachment_missing,item.title) };val path="files/${item.id}.${file.extension.take(8)}";obj.put("attachment",path);obj.put("imageOptimized",item.type=="image" && ImageStorage.isOptimized(file));zip.putNextEntry(ZipEntry(path));file.inputStream().use { it.copyTo(zip) };zip.closeEntry() }
                if(item.thumbnail==item.attachment && item.attachment.isNotBlank()) obj.put("thumbnail",obj.optString("attachment"))
                else if(item.thumbnail.isNotBlank()) File(item.thumbnail).takeIf { it.exists() }?.let { file-> val path="previews/${item.id}.${file.extension.take(8)}";obj.put("thumbnail",path);zip.putNextEntry(ZipEntry(path));file.inputStream().use { it.copyTo(zip) };zip.closeEntry() }
                val markdown=buildString {
                    append("---\nid: \"").append(item.id).append("\"\ntype: \"").append(item.type).append("\"\nsource: ").append(JSONObject.quote(item.sourceUrl)).append("\n---\n\n")
                    append("# ").append(item.title.replace('\n',' ')).append("\n\n")
                    if(item.sourceUrl.isNotBlank()) append("[").append(context.uiString(R.string.original_source)).append("](").append(item.sourceUrl).append(")\n\n")
                    if(item.summary.isNotBlank()) append("## ").append(context.uiString(R.string.summary)).append("\n\n").append(item.summary).append("\n\n")
                    val answers=store.answers(item.id)
                    if(answers.isNotEmpty()) {append("## ").append(context.uiString(R.string.questions_answers)).append("\n\n");answers.forEach { a->append("### ").append(a.question).append("\n\n").append(a.answer).append("\n\n")}}
                    val segments=store.segments(item.id)
                    if(segments.isNotEmpty()) {
                        append("## ").append(context.uiString(R.string.text_transcript)).append("\n\n")
                        segments.forEach { s->
                            val marker=when {s.startMs>=0->"${s.startMs/1000}s";s.page>=0->context.uiString(R.string.page_label,s.page);else->s.source}
                            if(marker.isNotBlank()) append("**").append(marker).append("** ")
                            append(s.text).append("\n\n")
                        }
                    } else if(item.body.isNotBlank()) append("## ").append(context.uiString(R.string.content)).append("\n\n").append(item.body).append("\n\n")
                    obj.optString("attachment").takeIf { it.isNotBlank() }?.let { append("[").append(context.uiString(R.string.attachment)).append("](../").append(it).append(")\n\n") }
                }
                zip.putNextEntry(ZipEntry("notes/${item.id}.md"));zip.write(markdown.toByteArray(Charsets.UTF_8));zip.closeEntry()
                manifest.getJSONArray("items").put(obj)
            }
            zip.putNextEntry(ZipEntry("manifest.json"));zip.write(manifest.toString().toByteArray());zip.closeEntry()
        } }
    }
    fun import(context:Context,store:BrainStore,uri:Uri):Int {
        val dir=File(context.cacheDir,"restore-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            context.contentResolver.openInputStream(uri)!!.use { input-> ZipInputStream(input).use { zip->
                var count=0; var totalBytes=0L; var entry=zip.nextEntry
                while(entry!=null) {
                    val name=entry.name
                    require(++count<10000) { context.uiString(R.string.backup_too_large) }
                    require(!name.startsWith("/") && !name.split('/').contains("..")) { context.uiString(R.string.backup_invalid_path) }
                    if(!entry.isDirectory) { val target=File(dir,name);target.parentFile?.mkdirs();target.outputStream().use { output->
                        val buffer=ByteArray(65536)
                        while(true) { val read=zip.read(buffer);if(read<0) break;totalBytes+=read;require(totalBytes<=2_000_000_000L) { context.uiString(R.string.backup_over_limit) };output.write(buffer,0,read) }
                    } }
                    zip.closeEntry();entry=zip.nextEntry
                }
            } }
            val root=JSONObject(File(dir,"manifest.json").readText())
            require(root.getString("format")=="supermens-local" && root.getInt("version")==1) { context.uiString(R.string.backup_unsupported) }
            val arr=root.getJSONArray("items");var imported=0
            val attachments=File(context.filesDir,"attachments").apply { mkdirs() }
            fun extracted(path:String):File { val file=File(dir,path).canonicalFile;require(file.path.startsWith(dir.canonicalPath+File.separator)) { context.uiString(R.string.backup_file_path_invalid) };return file }
            for(i in 0 until arr.length()) {
                val o=arr.getJSONObject(i);val id=o.getString("id");require(runCatching { UUID.fromString(id) }.isSuccess) { context.uiString(R.string.backup_id_invalid) }
                val attachment=o.optString("attachment").takeIf { it.isNotBlank() }?.let { path->
                    val source=extracted(path);require(source.isFile) { context.uiString(R.string.backup_file_missing,path) }
                    if(o.getString("type")=="image") {
                        if(o.optBoolean("imageOptimized") && source.extension=="webp") {
                            val candidate=File(attachments,"sm-image-v1-${UUID.randomUUID()}.webp")
                            source.copyTo(candidate)
                            if(ImageStorage.isOptimized(candidate)) candidate.absolutePath else { candidate.delete();ImageStorage.importFile(context,source,o.getString("title")).absolutePath }
                        } else ImageStorage.importFile(context,source,o.getString("title")).absolutePath
                    } else File(attachments,"${UUID.randomUUID()}.${source.extension.take(8)}").also { source.copyTo(it) }.absolutePath
                }.orEmpty()
                val thumbnail=o.optString("thumbnail").takeIf { it.isNotBlank() }?.let { path->if(path==o.optString("attachment")) attachment else extracted(path).takeIf { it.isFile }?.let { source->ImageStorage.importFile(context,source,textSensitive=false).absolutePath } }.orEmpty()
                val reference=o.optString("documentUri").takeIf { it.startsWith("content://") }.orEmpty()
                val item=BrainItem(id,o.getString("type"),o.getString("title"),o.optString("summary"),o.optString("sourceUrl"),o.optString("source"),o.optString("body"),attachment,thumbnail,o.optString("status","ready"),o.getLong("createdAt"),o.getLong("updatedAt"),reference,o.optInt("pdfPages"),sourceQuality=o.optString("sourceQuality","unknown"))
                val seg=o.optJSONArray("segments") ?: JSONArray();val segments=(0 until seg.length()).map { n-> val s=seg.getJSONObject(n);Segment(0,id,s.getString("text"),s.optLong("startMs",-1),s.optInt("page",-1),s.optString("source")) }
                val ans=o.optJSONArray("answers") ?: JSONArray();val answers=(0 until ans.length()).map { n->val a=ans.getJSONObject(n);SavedAnswer(0,id,a.getString("question"),a.getString("answer"),a.optString("evidence"),a.optString("model"),a.optLong("createdAt")) }
                val old=store.get(id)
                try { store.restore(item,segments,answers) }
                catch(e: Exception) { listOf(attachment,thumbnail).filter { it.isNotBlank() }.distinct().forEach { File(it).delete() };throw e }
                listOfNotNull(old?.attachment,old?.thumbnail).filter { it.isNotBlank() && it!=attachment && it!=thumbnail }.distinct().forEach { path ->
                    val file=File(path).canonicalFile
                    if(file.path.startsWith(context.filesDir.canonicalPath+File.separator) || file.path.startsWith(context.cacheDir.canonicalPath+File.separator)) file.delete()
                }
                imported++
            }
            return imported
        } finally { dir.deleteRecursively() }
    }
}
