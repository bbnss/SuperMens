// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

class StorageTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var store: BrainStore
    @Before fun setup() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        root=File(base.cacheDir,"storage-test-${UUID.randomUUID()}").apply { mkdirs() }
        context=object: ContextWrapper(base) {
            override fun getFilesDir()=File(root,"files").apply { mkdirs() }
            override fun getCacheDir()=File(root,"cache").apply { mkdirs() }
            override fun getDatabasePath(name: String)=File(root,name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,handler)
        }
        store=BrainStore(context)
    }
    @After fun cleanup() { store.close();root.deleteRecursively() }
    @Test fun searchMatchesPrefixesAcrossTitleBodySummaryAndAnswers() {
        val id=store.add("note","Travel planning",body="Budget allocation")
        store.update(id,summary="Booking confirmed",status="ready")
        store.addAnswer(id,"Where is the departure?","Depart from Milano Centrale","","test")
        for(query in listOf("Trav", "travel plan", "BUDG alloc", "book confirm", "Mila Central")) {
            assertEquals("Search: $query",listOf(id),store.list(query).map {it.id})
        }
        assertTrue(store.list("travel unavailable").isEmpty())
        assertTrue(store.list("!!!").isEmpty())
    }
    private fun image(width: Int, height: Int, name: String): File {
        val bmp=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(80,160,220))
        val file=File(root,name)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG,95,it) };bmp.recycle()
        return file
    }
    private fun pdf(pages: Int): File {
        val file=File(root,"${UUID.randomUUID()}.pdf")
        val document=PdfDocument()
        try {
            repeat(pages) { index->val page=document.startPage(PdfDocument.PageInfo.Builder(80,100,index+1).create());document.finishPage(page) }
            file.outputStream().use { document.writeTo(it) }
        } finally { document.close() }
        return file
    }
    @Test fun photoIsDownscaledRotatedAndStoredOnlyAsWebp() {
        val original=image(4000,2000,"photo.jpg")
        ExifInterface(original.absolutePath).apply { setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_ROTATE_90.toString());saveAttributes() }
        val compressed=ImageStorage.importFile(context,original,textSensitive=false)
        val bitmap=BitmapFactory.decodeFile(compressed.absolutePath)
        assertEquals(800,bitmap.width);assertEquals(1600,bitmap.height)
        assertEquals("webp",compressed.extension)
        assertTrue(ImageStorage.isOptimized(compressed));assertTrue(compressed.length()<original.length())
        assertEquals(1,File(context.filesDir,"attachments").listFiles()!!.size)
        val png=ImageStorage.inferenceBytes(compressed)
        val inference=BitmapFactory.decodeByteArray(png,0,png.size)
        assertEquals(bitmap.width,inference.width);assertEquals(bitmap.getPixel(50,50),inference.getPixel(50,50))
        bitmap.recycle();inference.recycle()
    }
    @Test fun screenshotKeepsMoreResolutionAndSmallImagesAreNotUpscaled() {
        val large=ImageStorage.importFile(context,image(3000,4000,"screenshot.jpg"))
        BitmapFactory.decodeFile(large.absolutePath).let { assertEquals(1800,it.width);assertEquals(2400,it.height);it.recycle() }
        val small=ImageStorage.importFile(context,image(200,100,"small.jpg"),textSensitive=false)
        BitmapFactory.decodeFile(small.absolutePath).let { assertEquals(200,it.width);assertEquals(100,it.height);it.recycle() }
    }
    @Test fun failedImageImportDoesNotLeaveAnAttachment() {
        val invalid=File(root,"broken.jpg").apply { writeText("not an image") }
        assertTrue(runCatching { ImageStorage.importFile(context,invalid) }.isFailure)
        assertTrue(File(context.filesDir,"attachments").listFiles().isNullOrEmpty())
    }
    @Test fun pdfExtractionIncludesPage301AndKeepsCopyWithoutPersistentAccess() {
        val original=pdf(301)
        val id=store.add("pdf","Long PDF",attachment=original.absolutePath)
        var page=0
        PdfTextExtractor.extract(context,store,store.get(id)!!,ocr={ "Testo della pagina ${++page}" })
        assertEquals(301,store.get(id)!!.pdfPages);assertEquals(301,store.segments(id).size)
        assertTrue(store.get(id)!!.body.contains("Testo della pagina 301"))
        assertTrue(original.isFile);assertEquals(original.absolutePath,store.get(id)!!.attachment)
        assertTrue(File(store.get(id)!!.thumbnail).isFile)
    }
    @Test fun interruptedPdfPreservesPreviouslySavedTextAndOriginalCopy() {
        val original=pdf(3)
        val id=store.add("pdf","PDF",body="Testo già archiviato",attachment=original.absolutePath)
        store.update(id,summary="Riassunto precedente")
        var visited=0
        assertTrue(runCatching { PdfTextExtractor.extract(context,store,store.get(id)!!,shouldContinue={ ++visited<2 },ocr={ "Nuovo testo" }) }.exceptionOrNull() is InterruptedException)
        assertEquals("Testo già archiviato",store.get(id)!!.body)
        assertEquals("Riassunto precedente",store.get(id)!!.summary)
        assertEquals(0,store.get(id)!!.pdfPages);assertTrue(original.exists())
    }
    @Test fun blankPagesStillCountAsExtracted() {
        val id=store.add("pdf","Blank PDF",attachment=pdf(2).absolutePath)
        PdfTextExtractor.extract(context,store,store.get(id)!!,ocr={ "" })
        assertEquals(2,store.get(id)!!.pdfPages);assertEquals(listOf(1,2),store.segments(id).map { it.page })
    }
    @Test fun archiveRoundTripPreservesPdfReferenceAndOptimizedImageBytes() {
        val reference="content://example.documents/document/42"
        val pdfId=store.add("pdf","Referenced PDF",body="Testo del PDF",documentUri=reference)
        store.update(pdfId,pdfPages=8)
        val compressed=ImageStorage.importFile(context,image(3000,2000,"photo.jpg"),textSensitive=false)
        val bytes=compressed.readBytes()
        val imageId=store.add("image","Photo",attachment=compressed.absolutePath,thumbnail=compressed.absolutePath)
        val archive=File(root,"backup.zip")
        Backup.export(context,store,Uri.fromFile(archive))
        ZipFile(archive).use { zip->
            val manifest=JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText())
            val entries=manifest.getJSONArray("items")
            val obj=(0 until entries.length()).map { entries.getJSONObject(it) }.first { it.getString("id")==pdfId }
            assertEquals(reference,obj.getString("documentUri"));assertFalse(obj.has("attachment"))
            assertFalse(zip.entries().asSequence().any { it.name.endsWith(".pdf") })
        }
        assertEquals(2,Backup.import(context,store,Uri.fromFile(archive)))
        assertEquals(reference,store.get(pdfId)!!.documentUri);assertEquals(8,store.get(pdfId)!!.pdfPages)
        assertEquals("Testo del PDF",store.get(pdfId)!!.body);assertEquals("",store.get(pdfId)!!.attachment)
        assertArrayEquals(bytes,File(store.get(imageId)!!.attachment).readBytes())
        assertEquals(store.get(imageId)!!.attachment,store.get(imageId)!!.thumbnail)
    }
    @Test fun databaseUpgradeRetainsExistingPostsSegmentsAndAnswers() {
        store.close()
        val file=context.getDatabasePath("supermens.db")
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db->
            db.execSQL("CREATE TABLE items(id TEXT PRIMARY KEY,type TEXT NOT NULL,title TEXT NOT NULL DEFAULT '',summary TEXT NOT NULL DEFAULT '',source_url TEXT NOT NULL DEFAULT '',source TEXT NOT NULL DEFAULT '',body TEXT NOT NULL DEFAULT '',attachment TEXT NOT NULL DEFAULT '',thumbnail TEXT NOT NULL DEFAULT '',status TEXT NOT NULL DEFAULT 'saved',created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE segments(id INTEGER PRIMARY KEY,item_id TEXT,text TEXT,start_ms INTEGER,page INTEGER,source TEXT)")
            db.execSQL("CREATE TABLE answers(id INTEGER PRIMARY KEY,item_id TEXT,question TEXT,answer TEXT,evidence TEXT,model TEXT,created_at INTEGER)")
            db.execSQL("CREATE VIRTUAL TABLE item_fts USING fts4(item_id,title,summary,body)")
            db.execSQL("INSERT INTO items(id,type,title,body,created_at,updated_at) VALUES('old','note','Vecchia nota','Contenuto originale',1,1)")
            db.execSQL("INSERT INTO segments VALUES(1,'old','Contenuto originale',-1,-1,'testo')")
            db.execSQL("INSERT INTO answers VALUES(1,'old','Domanda','Risposta','','Gemma',1)")
            db.version=1
        }
        store=BrainStore(context)
        assertEquals("Contenuto originale",store.get("old")!!.body)
        assertEquals("",store.get("old")!!.documentUri);assertEquals(0,store.get("old")!!.pdfPages)
        assertEquals("Risposta",store.answers("old").single().answer)
        assertEquals("Contenuto originale",store.segments("old").single().text)
    }
    private fun grantedReference(path: String): Uri {
        val uri=Uri.parse("content://it.supermens.offline.test.documents/$path")
        context.grantUriPermission(context.packageName,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        assertTrue(DocumentStorage.persist(context,uri))
        return uri
    }
    @Test fun persistentPdfImportStoresOnlyReferenceAndText() {
        val uri=grantedReference("document")
        try {
            val id=Ingest.file(context,store,uri)
            assertEquals("",store.get(id)!!.attachment);assertEquals(uri.toString(),store.get(id)!!.documentUri)
            assertTrue(File(context.filesDir,"attachments").listFiles().isNullOrEmpty())
            PdfTextExtractor.extract(context,store,store.get(id)!!,ocr={ "Testo conservato" })
            assertEquals(2,store.get(id)!!.pdfPages);assertTrue(store.get(id)!!.body.contains("Testo conservato"))
            assertEquals("",store.get(id)!!.attachment)
        } finally { context.contentResolver.releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    @Test fun temporarySharedPdfStagesSourceAndArchivesOnlyText() {
        val uri=Uri.parse("content://it.supermens.offline.test.documents/temporary")
        context.grantUriPermission(context.packageName,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertFalse(DocumentStorage.persist(context,uri))
        val id=Ingest.file(context,store,uri)
        val item=store.get(id)!!
        assertEquals("",item.documentUri);assertEquals("",item.attachment)
        val pending=File(item.pendingMedia);assertTrue(pending.isFile)
        PdfTextExtractor.extract(context,store,item.copy(attachment=item.pendingMedia),ocr={ "Testo condiviso" })
        pending.delete();store.update(id,pendingMedia="")
        assertTrue(store.get(id)!!.body.contains("Testo condiviso"))
        val archive=File(root,"temporary-backup.zip")
        Backup.export(context,store,Uri.fromFile(archive))
        ZipFile(archive).use { zip->assertFalse(zip.entries().asSequence().any { it.name.endsWith(".pdf") }) }
    }
    @Test fun nonSeekablePersistentPdfUsesAndRemovesTemporaryCopy() {
        val uri=grantedReference("pipe")
        try {
            val id=store.add("pdf","Cloud PDF",documentUri=uri.toString())
            PdfTextExtractor.extract(context,store,store.get(id)!!,ocr={ "Testo cloud" })
            assertEquals(2,store.get(id)!!.pdfPages);assertEquals("",store.get(id)!!.attachment)
            assertFalse(context.cacheDir.listFiles()!!.any { it.name.startsWith("supermens-pdf-") })
        } finally { context.contentResolver.releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    @Test fun relinkRemovesPreviousCopyOnlyAfterSuccessfulExtraction() {
        val original=pdf(1)
        val uri=grantedReference("relinked")
        try {
            val id=store.add("pdf","PDF",body="Testo precedente",attachment=original.absolutePath)
            Ingest.relinkPdf(context,store,id,uri)
            assertTrue(original.exists());assertEquals("Testo precedente",store.get(id)!!.body)
            PdfTextExtractor.extract(context,store,store.get(id)!!,ocr={ "Testo ricollegato" })
            assertFalse(original.exists());assertEquals("",store.get(id)!!.attachment)
            assertEquals(2,store.get(id)!!.pdfPages)
        } finally { context.contentResolver.releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    @Test fun invalidRelinkKeepsPreviousCopyAndText() {
        val original=pdf(1)
        val uri=grantedReference("broken")
        try {
            val id=store.add("pdf","PDF",body="Testo precedente",attachment=original.absolutePath)
            Ingest.relinkPdf(context,store,id,uri)
            assertTrue(runCatching { PdfTextExtractor.extract(context,store,store.get(id)!!,ocr={ "" }) }.isFailure)
            assertTrue(original.exists());assertEquals("Testo precedente",store.get(id)!!.body)
            assertEquals(0,store.get(id)!!.pdfPages)
        } finally { context.contentResolver.releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    @Test fun imageAnalysisStopsAtDescriptionAndPreservesOcrWithoutSummary() {
        val id=store.add("image","Photo",attachment=image(100,100,"vision.jpg").absolutePath)
        store.addSegments(id,listOf(Segment(0,id,"OCR text",-1,-1,"ocr")))
        var calls=0
        ImageAnalysis.enrich(store,store.get(id)!!,true) { calls++;"A flower" }
        assertEquals(1,calls)
        assertEquals("ready",store.get(id)!!.status)
        assertEquals("",store.get(id)!!.summary)
        assertEquals("OCR text",PostContent.copyText(store.get(id)!!,store.segments(id)))
        ImageAnalysis.enrich(store,store.get(id)!!,true) { error("Existing description should be reused") }
        assertEquals(1,store.segments(id).count { it.source=="visione" })
    }
    @Test fun imageWithoutModelKeepsOcrAndAwaitsDescription() {
        val id=store.add("image","Photo")
        store.addSegments(id,listOf(Segment(0,id,"Available OCR",-1,-1,"ocr")))
        ImageAnalysis.enrich(store,store.get(id)!!,false) { error("No model should run") }
        assertEquals("pending_ai",store.get(id)!!.status)
        assertEquals("Available OCR",PostContent.copyText(store.get(id)!!,store.segments(id)))
        assertEquals("",store.get(id)!!.summary)
        assertTrue(runCatching {ImageAnalysis.enrich(store,store.get(id)!!,true) {""}}.isFailure)
        assertEquals("Available OCR",PostContent.copyText(store.get(id)!!,store.segments(id)))
    }
    @Test fun cameraSavesOnlyWebpAndRetryDoesNotDuplicateThePost() {
        val capture=image(3000,1500,"capture-test.jpg")
        val id=CameraCapture.save(context,store,capture)
        try {
            val saved=store.get(id)!!
            assertFalse(capture.exists())
            assertTrue(ImageStorage.isOptimized(File(saved.attachment)))
            assertEquals(saved.attachment,saved.thumbnail)
            assertEquals(id,CameraCapture.save(context,store,capture))
            assertEquals(1,store.list().size)
        } finally {androidx.work.WorkManager.getInstance(context).cancelUniqueWork("analyze-$id").result.get()}
    }
    @Test fun failedCameraImportKeepsTemporaryPhotoAndCreatesNoPost() {
        val invalid=File(root,"invalid-capture.jpg").apply {writeText("invalid image")}
        assertTrue(runCatching {CameraCapture.save(context,store,invalid)}.isFailure)
        assertTrue(invalid.exists());assertTrue(store.list().isEmpty())
    }
    @Test fun cameraUriExposesOnlyTemporaryCameraPath() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val capture=CameraCapture.create(base)
        try {
            val uri=CameraCapture.uri(base,capture)
            assertEquals("content",uri.scheme)
            assertTrue(uri.path!!.startsWith("/camera/"))
            base.contentResolver.openOutputStream(uri)!!.use {it.write(byteArrayOf(1,2,3))}
            assertEquals(3,capture.length())
        } finally {capture.delete()}
    }

}
