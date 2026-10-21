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
    @Test fun integratePreservesOriginalNoteAndSourceQualityWithoutAutomaticSummary() {
        val id=store.add("note","Original",body="Original body")
        store.update(id,summary="Existing summary",status="ready",sourceQuality="limited")
        store.integrateText(id,"Added words")
        assertEquals("Original body\nAdded words",store.get(id)!!.body)
        assertEquals("Existing summary",store.get(id)!!.summary)
        assertEquals("limited",store.get(id)!!.sourceQuality)
        assertEquals(listOf("original","manual"),store.segments(id).map {it.source})
        assertEquals(id,store.list("Added words").single().id)
        assertTrue(ProcessingQueue.jobs(store).isEmpty())
    }
    @Test fun conversationBackupRetainsModesLanguagesReplyLinksAndPendingAudio() {
        val id=store.add("translation","Translation test")
        store.setTargetLanguage(id,"fr-FR")
        val user=store.addMessage(id,"Original words","translate","fr-FR","done")
        store.addMessage(id,"Mots originaux","translate","fr-FR","done",role="assistant",replyTo=user,limited=true)
        store.integrateText(id,"Additional context")
        val audio=File(root,"pending.wav").apply {writeText("audio fixture")}
        store.addMessage(id,"","chat","fr-FR","transcribing",audio.absolutePath)
        val archive=File(root,"conversation.zip")
        Backup.export(context,store,Uri.fromFile(archive))
        ZipFile(archive).use {zip ->
            assertEquals(4,JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText()).getInt("version"))
            val markdown=zip.getInputStream(zip.getEntry("notes/$id.md")).bufferedReader().readText()
            assertTrue(markdown.contains("Original words"));assertTrue(markdown.contains("Mots originaux"));assertTrue(markdown.contains("Additional context"))
        }
        assertEquals(1,Backup.import(context,store,Uri.fromFile(archive)))
        val messages=store.messages(id)
        assertEquals(3,messages.size)
        assertEquals(messages[0].id,messages[1].replyTo)
        assertTrue(messages[1].contextLimited)
        assertEquals("fr-FR",store.get(id)!!.targetLanguage)
        assertEquals("audio fixture",File(messages[2].audioPath).readText())
        assertTrue(store.get(id)!!.body.contains("Additional context"))
        assertEquals(id,store.list("Mots originaux").single().id)
        store.delete(id)
        assertTrue(store.messages(id).isEmpty())
    }
    @Test fun backupVersionOneStillImportsWithoutConversationFields() {
        val id=store.add("note","Legacy",body="Kept")
        val archive=File(root,"legacy.zip")
        Backup.export(context,store,Uri.fromFile(archive))
        val legacy=File(root,"v1.zip")
        ZipFile(archive).use {zip ->
            java.util.zip.ZipOutputStream(legacy.outputStream()).use {output ->
                zip.entries().asSequence().forEach {entry ->
                    output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    if(entry.name=="manifest.json") {
                        val manifest=JSONObject(zip.getInputStream(entry).bufferedReader().readText()).put("version",1)
                        manifest.getJSONArray("items").getJSONObject(0).apply {remove("messages");remove("targetLanguage")}
                        output.write(manifest.toString().toByteArray())
                    } else zip.getInputStream(entry).use {it.copyTo(output)}
                    output.closeEntry()
                }
            }
        }
        assertEquals(1,Backup.import(context,store,Uri.fromFile(legacy)))
        assertEquals("Kept",store.get(id)!!.body);assertEquals("",store.get(id)!!.targetLanguage)
    }
    @Test fun interruptedTranslationResumesChunksAndPublishesOnlyOneAnswer() {
        val id=store.add("translation","Resume")
        val text="A paragraph with names and numbers 123. ".repeat(100)+"FINAL_SOURCE"
        val user=store.addMessage(id,text,"translate","fr","pending")
        val job=ProcessingJob(1,id,"conversation",true,user.toString(),"waiting",0,"",0)
        val chunks=ConversationText.chunks(text)
        val translated=mutableListOf<String>()
        var calls=0
        val failing=ConversationProcessor(context,store,{}, {},{it()},{true},createTitle={_,_->"Titolo della traduzione"}) {source, _ ->
            if(++calls==3) throw IllegalStateException("Injected interruption")
            source.also {translated+=it}
        }
        assertTrue(runCatching {failing.process(store.get(id)!!,job)}.isFailure)
        assertTrue(store.messages(id).none {it.role=="assistant"})
        val resumed=ConversationProcessor(context,store,{}, {},{it()},{true},createTitle={_,_->"Titolo della traduzione"}) {source, _ ->source.also {translated+=it}}
        resumed.process(store.get(id)!!,job)
        resumed.process(store.get(id)!!,job)
        assertEquals(chunks,translated)
        val reply=store.messages(id).single {it.role=="assistant"}
        assertEquals(user,reply.replyTo);assertTrue(reply.text.endsWith("FINAL_SOURCE"))
        assertEquals("done",store.messages(id).first().state)
        assertEquals(2,store.messages(id).size)
    }
    @Test fun sharedPdfHasProjectFooterOnEveryPageAndTextShareHasOneFinalSignature() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val shareContext=object:ContextWrapper(context) {override fun getCacheDir()=base.cacheDir}
        val id=store.add("note","Shared post",body="Paragraph with preserved text. ".repeat(1000)+" FINAL_EXPORT_MARKER")
        store.integrateText(id,"Added export context")
        val text=PostShare.prepare(shareContext,store,id,false).getStringExtra(Intent.EXTRA_TEXT)!!
        val url=context.getString(R.string.repository_url)
        assertTrue(text.endsWith("SuperMens · $url"));assertEquals(1,Regex(Regex.escape(url)).findAll(text).count())
        assertTrue(text.contains("Added export context"))
        val uri=PostShare.prepare(shareContext,store,id,true).getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java)!!
        try {
            shareContext.contentResolver.openFileDescriptor(uri,"r")!!.use {descriptor ->
                android.graphics.pdf.PdfRenderer(descriptor).use {renderer ->
                    assertTrue(renderer.pageCount>1)
                    val pages=(0 until renderer.pageCount).map {index ->renderer.openPage(index).use {page ->page.textContents.joinToString(" ") {it.text}}}
                    pages.forEach {assertTrue("Missing footer: $it",it.contains(url))}
                    assertTrue(pages.joinToString(" ").contains("FINAL_EXPORT_MARKER"))
                }
            }
        } finally {base.cacheDir.resolve("shares").listFiles()?.filter {it.name==uri.lastPathSegment}?.forEach {it.delete()}}
    }
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
            // The original is deliberately unavailable; recovery cannot invent its bytes.
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
    @Test fun persistentPdfImportKeepsPrivateCopyAndText() {
        val uri=grantedReference("document")
        try {
            val id=Ingest.file(context,store,uri)
            assertTrue(File(store.get(id)!!.attachment).isFile);assertEquals("",store.get(id)!!.documentUri)
            PdfTextExtractor.extract(context,store,store.get(id)!!,ocr={ "Testo conservato" })
            assertEquals(2,store.get(id)!!.pdfPages);assertTrue(store.get(id)!!.body.contains("Testo conservato"))
            assertTrue(File(store.get(id)!!.attachment).isFile)
        } finally { context.contentResolver.releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    @Test fun temporarySharedPdfKeepsAndArchivesOriginal() {
        val uri=Uri.parse("content://it.supermens.offline.test.documents/temporary")
        context.grantUriPermission(context.packageName,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertFalse(DocumentStorage.persist(context,uri))
        val id=Ingest.file(context,store,uri)
        val item=store.get(id)!!
        assertEquals("",item.documentUri);assertTrue(File(item.attachment).isFile)
        assertEquals("",item.pendingMedia)
        PdfTextExtractor.extract(context,store,item,ocr={ "Testo condiviso" })
        assertTrue(store.get(id)!!.body.contains("Testo condiviso"))
        val archive=File(root,"temporary-backup.zip")
        Backup.export(context,store,Uri.fromFile(archive))
        ZipFile(archive).use { zip->assertTrue(zip.entries().asSequence().any { it.name.endsWith(".pdf") }) }
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
    @Test fun relinkReplacesOnlyAfterNewCopyIsValidated() {
        val original=pdf(1)
        val uri=grantedReference("relinked")
        try {
            val id=store.add("pdf","PDF",body="Testo precedente",attachment=original.absolutePath)
            Ingest.relinkPdf(context,store,id,uri)
            assertFalse(original.exists());assertEquals("",store.get(id)!!.body)
            assertTrue(File(store.get(id)!!.attachment).isFile)
            PdfTextExtractor.extract(context,store,store.get(id)!!,ocr={ "Testo ricollegato" })
            assertFalse(original.exists());assertTrue(File(store.get(id)!!.attachment).isFile)
            assertEquals(2,store.get(id)!!.pdfPages)
        } finally { context.contentResolver.releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    @Test fun invalidRelinkKeepsPreviousCopyAndText() {
        val original=pdf(1)
        val uri=grantedReference("broken")
        try {
            val id=store.add("pdf","PDF",body="Testo precedente",attachment=original.absolutePath)
            assertTrue(runCatching { Ingest.relinkPdf(context,store,id,uri) }.isFailure)
            assertTrue(original.exists());assertEquals("Testo precedente",store.get(id)!!.body)
            assertEquals(0,store.get(id)!!.pdfPages)
        } finally { context.contentResolver.releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    @Test fun imageAnalysisStopsAtDescriptionAndPreservesOcrWithoutSummary() {
        val id=store.add("image","Photo",attachment=image(100,100,"vision.jpg").absolutePath)
        store.addSegments(id,listOf(Segment(0,id,"OCR text",-1,-1,"ocr")))
        var calls=0
        ImageAnalysis.enrich(store,store.get(id)!!,true) { calls++;ImageDescription("A flower grows in a garden","A flower") }
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
        assertTrue(runCatching {ImageAnalysis.enrich(store,store.get(id)!!,true) {ImageDescription("Title","")}}.isFailure)
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

    @Test fun fileViewerIntentKeepsPdfReferenceMimeAndReadGrant() {
        val actual=InstrumentationRegistry.getInstrumentation().targetContext
        val uri=grantedReference("viewer")
        try {
            val id=store.add("pdf","Original PDF",documentUri=uri.toString())
            val intent=PostFile.intent(actual,store.get(id)!!)
            assertEquals(Intent.ACTION_VIEW,intent.action);assertEquals(uri,intent.data);assertEquals("application/pdf",intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0);assertEquals(uri,intent.clipData!!.getItemAt(0).uri)
        } finally {context.contentResolver.releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
    }
    @Test fun fileViewerUsesAttachmentMimeAndReportsMissingOriginal() {
        val actual=InstrumentationRegistry.getInstrumentation().targetContext
        val attachment=File(File(actual.filesDir,"attachments").apply {mkdirs()},"viewer-${UUID.randomUUID()}.txt").apply {writeText("File fixture")}
        try {
            val id=store.add("file","Document.txt",attachment=attachment.absolutePath)
            assertEquals("text/plain",PostFile.intent(actual,store.get(id)!!).type)
            val missing=store.add("pdf","Missing PDF")
            assertTrue(runCatching {PostFile.intent(actual,store.get(missing)!!)}.exceptionOrNull() is java.io.FileNotFoundException)
        } finally {attachment.delete()}
    }
    @Test fun versionSixMigrationKeepsPostsAndRetiresDailyDataWithoutQueuingTitles() {
        val id=store.add("note","Legacy topic",body="Original")
        store.update(id,summary="Existing summary",status="ready")
        store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("supermens.db").path,null,SQLiteDatabase.OPEN_READWRITE).use {db ->
            db.execSQL("ALTER TABLE items DROP COLUMN title_state")
            db.execSQL("CREATE TABLE daily_summaries(day TEXT PRIMARY KEY,text TEXT)")
            db.execSQL("CREATE TABLE daily_chunks(day TEXT,text TEXT)")
            db.execSQL("CREATE TABLE daily_post_summaries(item_id TEXT,text TEXT)")
            db.execSQL("INSERT INTO daily_summaries VALUES('2026-10-21','Old overview')")
            db.version=6
        }
        store=BrainStore(context)
        assertEquals(7,store.readableDatabase.version)
        assertEquals("legacy",store.get(id)!!.titleState)
        assertEquals("Existing summary",store.get(id)!!.summary)
        assertEquals(1,DailyTimeline.titles(store.list()).size)
        assertTrue(ProcessingQueue.jobs(store).isEmpty())
        store.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE name LIKE 'daily_%'",null).use {assertEquals(0,it.count)}
    }
    @Test fun recoveringLegacyPdfPrefersItsSnapshotAndWorksWithoutTheOriginalGrant() {
        val snapshot=pdf(1)
        val id=store.add("pdf","Legacy PDF",documentUri="content://unavailable/original")
        store.update(id,pendingMedia=snapshot.absolutePath,pdfPages=1,summary="Kept summary",status="ready")
        DocumentStorage.retain(context,store,id)
        val recovered=store.get(id)!!
        assertFalse(snapshot.exists());assertEquals("",recovered.pendingMedia)
        assertEquals("Kept summary",recovered.summary)
        DocumentStorage.open(context,recovered).use {assertEquals(1,it.renderer.pageCount)}
        val archive=File(root,"pdf-backup.zip")
        store.update(id,title="Saved descriptive title",titleState="generated")
        Backup.export(context,store,Uri.fromFile(archive))
        store.delete(id);File(recovered.attachment).delete()
        Backup.import(context,store,Uri.fromFile(archive))
        val restored=store.get(id)!!
        assertEquals("generated",restored.titleState)
        assertEquals("Saved descriptive title",restored.title)
        DocumentStorage.open(context,restored).use {assertEquals(1,it.renderer.pageCount)}
    }
    @Test fun failedTitleRetainsTranslationCheckpointAndPublishesExactlyOnceAfterRetry() {
        val id=store.add("translation","Pending title")
        val user=store.addMessage(id,"Source words","translate","en","pending")
        val job=ProcessingJob(1,id,"conversation",true,user.toString(),"waiting",0,"",0)
        var translationCalls=0
        val failing=ConversationProcessor(context,store,{}, {},{it()},{true},createTitle={_,_->error("Title failed")}) {_,_->
            translationCalls++;"Translated words"
        }
        assertTrue(runCatching {failing.process(store.get(id)!!,job)}.isFailure)
        assertEquals(1,translationCalls)
        assertTrue(store.messages(id).none {it.role=="assistant"})
        assertEquals("pending",store.get(id)!!.titleState)
        val resumed=ConversationProcessor(context,store,{}, {},{it()},{true},createTitle={_,_->"The topic is translated faithfully"}) {_,_->
            translationCalls++;error("Should use the saved response")
        }
        resumed.process(store.get(id)!!,job);resumed.process(store.get(id)!!,job)
        assertEquals(1,translationCalls)
        assertEquals(1,store.messages(id).count {it.role=="assistant"})
        assertEquals("generated",store.get(id)!!.titleState)
        assertEquals("The topic is translated faithfully",store.get(id)!!.title)
        assertEquals(id,store.list("faithfully").single().id)
    }

    @Test fun failedPdfCopyPreservesThePreviousDocumentAndItsDerivedContent() {
        val original=pdf(1)
        val id=store.add("pdf","Previous title",attachment=original.absolutePath,body="Previous text")
        store.update(id,titleState="generated",summary="Previous summary",pdfPages=1,status="ready")
        val blocker=File(root,"not-a-directory").apply {writeText("blocked")}
        val failedContext=object:ContextWrapper(context) {override fun getFilesDir()=blocker}
        val uri=Uri.parse("content://it.supermens.offline.test.documents/relinked")
        context.grantUriPermission(context.packageName,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(runCatching {Ingest.relinkPdf(failedContext,store,id,uri)}.isFailure)
        assertTrue(original.isFile)
        assertEquals("Previous text",store.get(id)!!.body)
        assertEquals("Previous summary",store.get(id)!!.summary)
        assertEquals("generated",store.get(id)!!.titleState)
        assertTrue(ProcessingQueue.jobs(store).isEmpty())
    }
    @Test fun replayingConfirmedPdfReplacementDoesNotReplaceItAgain() {
        val old=pdf(1)
        val id=store.add("pdf","Original",attachment=old.absolutePath)
        val uri=Uri.parse("content://it.supermens.offline.test.documents/relinked")
        context.grantUriPermission(context.packageName,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Ingest.relinkPdf(context,store,id,uri,"confirmed-request")
        val replacement=store.get(id)!!.attachment
        Ingest.relinkPdf(context,store,id,uri,"confirmed-request")
        assertEquals(replacement,store.get(id)!!.attachment)
        assertTrue(File(replacement).isFile);assertFalse(old.exists())
    }

}
