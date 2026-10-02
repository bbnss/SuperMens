// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.UUID

data class BrainItem(
    val id: String, val type: String, val title: String, val summary: String,
    val sourceUrl: String, val source: String, val body: String,
    val attachment: String, val thumbnail: String, val status: String,
    val createdAt: Long, val updatedAt: Long, val documentUri: String = "", val pdfPages: Int = 0, val pendingMedia: String = "", val sourceQuality: String = "unknown"
)
data class Segment(val id: Long, val itemId: String, val text: String, val startMs: Long, val page: Int, val source: String)
data class SavedAnswer(val id: Long, val itemId: String, val question: String, val answer: String, val evidence: String, val model: String, val createdAt: Long)

class BrainStore(context: Context) : SQLiteOpenHelper(context, "supermens.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE items(id TEXT PRIMARY KEY,type TEXT NOT NULL,title TEXT NOT NULL DEFAULT '',summary TEXT NOT NULL DEFAULT '',source_url TEXT NOT NULL DEFAULT '',source TEXT NOT NULL DEFAULT '',body TEXT NOT NULL DEFAULT '',attachment TEXT NOT NULL DEFAULT '',thumbnail TEXT NOT NULL DEFAULT '',status TEXT NOT NULL DEFAULT 'saved',created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,document_uri TEXT NOT NULL DEFAULT '',pdf_pages INTEGER NOT NULL DEFAULT 0,pending_media TEXT NOT NULL DEFAULT '',source_quality TEXT NOT NULL DEFAULT 'unknown')""")
        db.execSQL("CREATE INDEX items_newest ON items(created_at DESC)")
        db.execSQL("""CREATE TABLE segments(id INTEGER PRIMARY KEY AUTOINCREMENT,item_id TEXT NOT NULL,text TEXT NOT NULL,start_ms INTEGER NOT NULL DEFAULT -1,page INTEGER NOT NULL DEFAULT -1,source TEXT NOT NULL DEFAULT '',FOREIGN KEY(item_id) REFERENCES items(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX segments_item ON segments(item_id)")
        db.execSQL("""CREATE TABLE answers(id INTEGER PRIMARY KEY AUTOINCREMENT,item_id TEXT NOT NULL,question TEXT NOT NULL,answer TEXT NOT NULL,evidence TEXT NOT NULL DEFAULT '',model TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL,FOREIGN KEY(item_id) REFERENCES items(id) ON DELETE CASCADE)""")
        createJobs(db)
        db.execSQL("CREATE VIRTUAL TABLE item_fts USING fts4(item_id, title, summary, body)")
    }
    override fun onOpen(db: SQLiteDatabase) { super.onOpen(db); db.execSQL("PRAGMA foreign_keys=ON") }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion<2) {
            db.execSQL("ALTER TABLE items ADD COLUMN document_uri TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE items ADD COLUMN pdf_pages INTEGER NOT NULL DEFAULT 0")
        }
        if(oldVersion<3) {
            db.execSQL("ALTER TABLE items ADD COLUMN pending_media TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE items ADD COLUMN source_quality TEXT NOT NULL DEFAULT 'unknown'")
            createJobs(db)
        }
    }

    private fun createJobs(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS processing_jobs(id INTEGER PRIMARY KEY AUTOINCREMENT,item_id TEXT NOT NULL,kind TEXT NOT NULL,manual INTEGER NOT NULL DEFAULT 0,payload TEXT NOT NULL DEFAULT '',state TEXT NOT NULL DEFAULT 'waiting',attempts INTEGER NOT NULL DEFAULT 0,progress TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL,FOREIGN KEY(item_id) REFERENCES items(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS jobs_pending ON processing_jobs(state,kind)")
        db.execSQL("CREATE TABLE IF NOT EXISTS summary_chunks(item_id TEXT NOT NULL,chunk_key TEXT NOT NULL,text TEXT NOT NULL,PRIMARY KEY(item_id,chunk_key),FOREIGN KEY(item_id) REFERENCES items(id) ON DELETE CASCADE)")
    }
    fun add(type: String, title: String, sourceUrl: String = "", source: String = "", body: String = "", attachment: String = "", thumbnail: String = "", documentUri: String = ""): String {
        val id = UUID.randomUUID().toString(); val now = System.currentTimeMillis()
        val v = ContentValues().apply { put("id",id); put("type",type); put("title",title); put("source_url",sourceUrl); put("source",source); put("body",body); put("attachment",attachment); put("thumbnail",thumbnail); put("document_uri",documentUri); put("created_at",now); put("updated_at",now) }
        writableDatabase.insertOrThrow("items",null,v)
        index(id)
        return id
    }
    fun get(id: String): BrainItem? = readableDatabase.rawQuery("SELECT * FROM items WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) item(it) else null }
    fun findByUrl(url:String):BrainItem? = readableDatabase.rawQuery("SELECT * FROM items WHERE source_url=? LIMIT 1",arrayOf(url)).use { if(it.moveToFirst()) item(it) else null }
    fun findBySource(source: String): BrainItem? = readableDatabase.rawQuery("SELECT * FROM items WHERE source=? LIMIT 1",arrayOf(source)).use { if(it.moveToFirst()) item(it) else null }
    fun list(query: String = "", type: String = "all"): List<BrainItem> {
        val ids = if (query.isBlank()) null else searchIds(query)
        if (ids != null && ids.isEmpty()) return emptyList()
        val result = mutableListOf<BrainItem>()
        val clause=when(type) { "all"->"";"social"->"WHERE type IN ('instagram','x','facebook','tiktok')";else->"WHERE type=?" }
        readableDatabase.rawQuery("SELECT * FROM items $clause ORDER BY created_at DESC", if(type=="all" || type=="social") null else arrayOf(type)).use { c ->
            while(c.moveToNext()) { val row=item(c); if(ids==null || row.id in ids) result.add(row) }
        }
        return result
    }
    private fun searchIds(query: String): Set<String> {
        val words=Regex("[\\p{L}\\p{N}]+").findAll(query).map { it.value }.take(8).toList()
        if(words.isEmpty()) return emptySet()
        // Adjacent terms imply AND in both standard and enhanced FTS3/4 syntax.
        val expression=words.joinToString(" ") { "\"$it*\"" }
        return readableDatabase.rawQuery("SELECT item_id FROM item_fts WHERE item_fts MATCH ?",arrayOf(expression)).use { c -> buildSet { while(c.moveToNext()) add(c.getString(0)) } }
    }
    fun update(id: String, title: String? = null, summary: String? = null, body: String? = null, thumbnail: String? = null, status: String? = null, attachment: String? = null, documentUri: String? = null, pdfPages: Int? = null, pendingMedia: String? = null, sourceQuality: String? = null) {
        val v=ContentValues().apply { title?.let { put("title",it) }; summary?.let { put("summary",it) }; body?.let { put("body",it) }; thumbnail?.let { put("thumbnail",it) }; status?.let { put("status",it) }; attachment?.let { put("attachment",it) }; documentUri?.let { put("document_uri",it) }; pdfPages?.let { put("pdf_pages",it) }; pendingMedia?.let { put("pending_media",it) }; sourceQuality?.let { put("source_quality",it) }; put("updated_at",System.currentTimeMillis()) }
        writableDatabase.update("items",v,"id=?",arrayOf(id))
        if(title!=null || summary!=null || body!=null) index(id)
    }
    fun changeType(id:String,type:String) {
        writableDatabase.update("items",ContentValues().apply {put("type",type);put("updated_at",System.currentTimeMillis())},"id=?",arrayOf(id))
        index(id)
    }
    fun delete(id: String): BrainItem? {
        val db=writableDatabase
        db.beginTransaction()
        try {
            val old=get(id) ?: return null
            db.delete("items","id=?",arrayOf(id));db.delete("item_fts","item_id=?",arrayOf(id))
            db.setTransactionSuccessful()
            return old
        } finally { db.endTransaction() }
    }
    fun addSegments(itemId:String, segments:List<Segment>) {
        writableDatabase.beginTransaction()
        try {
            if(get(itemId)==null) return
            writableDatabase.delete("segments","item_id=?",arrayOf(itemId)); segments.forEach { s ->
            val v=ContentValues().apply { put("item_id",itemId); put("text",s.text); put("start_ms",s.startMs); put("page",s.page); put("source",s.source) }
            writableDatabase.insertOrThrow("segments",null,v)
        }
            update(itemId,body=segments.joinToString("\n") { it.text })
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
    fun upsertAudioClip(itemId:String,index:Int,startMs:Long,text:String) {
        val db=writableDatabase
        db.beginTransaction()
        try {
            if(get(itemId)==null) return
            db.delete("segments","item_id=? AND source=? AND page=?",arrayOf(itemId,"gemma audio",index.toString()))
            val values=ContentValues().apply { put("item_id",itemId);put("text",text);put("start_ms",startMs);put("page",index);put("source","gemma audio") }
            db.insertOrThrow("segments",null,values)
            val all=segments(itemId)
            val joined=all.filter { it.source=="gemma audio" }.sortedBy { it.page }.fold("") { merged,segment -> TranscriptMerge.join(merged,segment.text) }
            val shared=all.filter { it.source=="testo condiviso" }.joinToString("\n") { it.text }
            db.update("items",ContentValues().apply {put("body",listOf(shared,joined).filter { it.isNotBlank() }.joinToString("\n\n"));put("updated_at",System.currentTimeMillis())},"id=?",arrayOf(itemId))
            index(itemId)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun clearAudioClips(itemId:String) {
        writableDatabase.delete("segments","item_id=? AND source=?",arrayOf(itemId,"gemma audio"))
        val shared=segments(itemId).filter { it.source=="testo condiviso" }.joinToString("\n") { it.text }
        update(itemId,body=shared,summary="",status="saved")
    }
    fun segments(itemId:String):List<Segment> = readableDatabase.rawQuery("SELECT id,item_id,text,start_ms,page,source FROM segments WHERE item_id=? ORDER BY id",arrayOf(itemId)).use { c -> buildList { while(c.moveToNext()) add(Segment(c.getLong(0),c.getString(1),c.getString(2),c.getLong(3),c.getInt(4),c.getString(5))) } }
    fun addAnswer(itemId:String, question:String, answer:String, evidence:String, model:String, createdAt:Long=System.currentTimeMillis()) {
        val db=writableDatabase
        db.beginTransaction()
        try {
            if(get(itemId)==null) return
            val v=ContentValues().apply { put("item_id",itemId); put("question",question); put("answer",answer); put("evidence",evidence); put("model",model); put("created_at",createdAt) }
            db.insertOrThrow("answers",null,v)
            index(itemId)
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    fun answers(itemId:String):List<SavedAnswer> = readableDatabase.rawQuery("SELECT id,item_id,question,answer,evidence,model,created_at FROM answers WHERE item_id=? ORDER BY created_at DESC",arrayOf(itemId)).use { c -> buildList { while(c.moveToNext()) add(SavedAnswer(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getLong(6))) } }
    fun restore(item:BrainItem,segments:List<Segment>,answers:List<SavedAnswer>) {
        writableDatabase.beginTransaction()
        try {
        val v=ContentValues().apply { put("id",item.id);put("type",item.type);put("title",item.title);put("summary",item.summary);put("source_url",item.sourceUrl);put("source",item.source);put("body",item.body);put("attachment",item.attachment);put("thumbnail",item.thumbnail);put("status",item.status);put("created_at",item.createdAt);put("updated_at",item.updatedAt);put("document_uri",item.documentUri);put("pdf_pages",item.pdfPages);put("source_quality",item.sourceQuality) }
        writableDatabase.insertWithOnConflict("items",null,v,SQLiteDatabase.CONFLICT_REPLACE)
        if (segments.isEmpty()) {
            writableDatabase.delete("segments","item_id=?",arrayOf(item.id))
        } else {
            addSegments(item.id,segments)
            if(segments.any { it.source=="gemma audio" }) {
                val shared=segments.filter { it.source=="testo condiviso" }.joinToString("\n") { it.text }
                val transcript=segments.filter { it.source=="gemma audio" }.sortedBy { it.page }.fold("") { all,segment -> TranscriptMerge.join(all,segment.text) }
                update(item.id,body=listOf(shared,transcript).filter { it.isNotBlank() }.joinToString("\n\n"))
            }
        }
        writableDatabase.delete("answers","item_id=?",arrayOf(item.id))
        answers.forEach { addAnswer(item.id,it.question,it.answer,it.evidence,it.model,it.createdAt) }
        index(item.id)
        writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
    private fun index(id:String) { val i=get(id) ?: return; writableDatabase.delete("item_fts","item_id=?",arrayOf(id)); val questions=answers(id).joinToString("\n") { it.question+"\n"+it.answer }; val v=ContentValues().apply { put("item_id",id);put("title",i.title);put("summary",i.summary);put("body",i.body+"\n"+questions) }; writableDatabase.insert("item_fts",null,v) }
    private fun item(c:android.database.Cursor)=BrainItem(c.getString(c.getColumnIndexOrThrow("id")),c.getString(c.getColumnIndexOrThrow("type")),c.getString(c.getColumnIndexOrThrow("title")),c.getString(c.getColumnIndexOrThrow("summary")),c.getString(c.getColumnIndexOrThrow("source_url")),c.getString(c.getColumnIndexOrThrow("source")),c.getString(c.getColumnIndexOrThrow("body")),c.getString(c.getColumnIndexOrThrow("attachment")),c.getString(c.getColumnIndexOrThrow("thumbnail")),c.getString(c.getColumnIndexOrThrow("status")),c.getLong(c.getColumnIndexOrThrow("created_at")),c.getLong(c.getColumnIndexOrThrow("updated_at")),c.getString(c.getColumnIndexOrThrow("document_uri")),c.getInt(c.getColumnIndexOrThrow("pdf_pages")),c.getString(c.getColumnIndexOrThrow("pending_media")),c.getString(c.getColumnIndexOrThrow("source_quality")))
}
