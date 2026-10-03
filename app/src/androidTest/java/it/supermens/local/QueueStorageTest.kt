// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class QueueStorageTest {
    private lateinit var context:Context
    private lateinit var root:File
    private lateinit var store:BrainStore
    @Before fun setup() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        root=File(base.cacheDir,"queue-test-${UUID.randomUUID()}").apply {mkdirs()}
        context=object:ContextWrapper(base) {
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getDatabasePath(name:String)=File(root,name)
            override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,handler:DatabaseErrorHandler?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,handler)
        }
        store=BrainStore(context)
    }
    @After fun cleanup() {store.close();root.deleteRecursively()}
    @Test fun versionTwoMigrationPreservesItemsSegmentsAnswersAndLegacyVideo() {
        val id=store.add("video","Old video",attachment="old.mp4")
        store.addSegments(id,listOf(Segment(0,id,"Original transcript",0,0,"gemma audio")))
        store.addAnswer(id,"Question","Answer","Evidence","model")
        store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("supermens.db").path,null,SQLiteDatabase.OPEN_READWRITE).use {db ->
            db.execSQL("DROP TABLE processing_jobs");db.execSQL("DROP TABLE summary_chunks")
            db.execSQL("ALTER TABLE items DROP COLUMN pending_media");db.execSQL("ALTER TABLE items DROP COLUMN source_quality");db.version=2
        }
        store=BrainStore(context)
        assertEquals("old.mp4",store.get(id)!!.attachment);assertEquals("Original transcript",store.segments(id).single().text)
        assertEquals("Answer",store.answers(id).single().answer);assertTrue(ProcessingQueue.jobs(store).isEmpty())
        assertEquals(4,store.readableDatabase.version)
    }
    @Test fun requestsAreDeduplicatedAndManualQuestionPersistsAcrossReopen() {
        val id=store.add("note","Queue fixture",body="Body")
        ProcessingQueue.enqueue(context,id);ProcessingQueue.enqueue(context,id)
        assertEquals(1,ProcessingQueue.jobs(store).count {it.kind=="process"})
        ProcessingQueue.question(context,id,"Where?")
        store.close();store=BrainStore(context)
        assertEquals("Where?",ProcessingQueue.jobs(store).single {it.kind=="question"}.payload)
        ProcessingQueue.enqueue(context,id,manual=true)
        assertTrue(ProcessingQueue.jobs(store).single {it.kind=="process"}.manual)
    }
    @Test fun failedJobIsRetriedExplicitlyAndOldFailureDoesNotBlockIt() {
        val id=store.add("web","Fixture",sourceUrl="https://example.org")
        ProcessingQueue.enqueue(context,id)
        val acquisition=ProcessingQueue.jobs(store).single {it.kind=="acquire"}
        ProcessingQueue.state(store,acquisition,"failed",error=true)
        ProcessingQueue.enqueue(context,id,manual=true)
        assertEquals(1,ProcessingQueue.jobs(store).count {it.kind=="acquire" && it.state=="waiting"})
        assertEquals("superseded",ProcessingQueue.jobs(store).first {it.id==acquisition.id}.state)
    }
    @Test fun reshareRetriesFailedArticleWithoutDuplicatingOrReacquiringReadyArticle() {
        val url="https://example.org/news/reshare-fixture"
        val id=store.add("web","Failed article",sourceUrl=url)
        ProcessingQueue.enqueue(context,id)
        val failed=ProcessingQueue.jobs(store).single {it.kind=="acquire"}
        ProcessingQueue.state(store,failed,"failed",error=true)
        store.update(id,status="extraction_failed · fixture")
        val intent=android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type="text/plain";putExtra(android.content.Intent.EXTRA_TEXT,"An article $url")
        }
        assertEquals(listOf(id),Ingest.intake(context,store,intent))
        assertEquals(1,store.list().size)
        assertEquals("superseded",ProcessingQueue.jobs(store).first {it.id==failed.id}.state)
        assertTrue(ProcessingQueue.jobs(store).single {it.kind=="acquire" && it.state=="waiting"}.manual)
        ProcessingQueue.jobs(store).forEach {ProcessingQueue.state(store,it,"done")}
        store.update(id,status="ready")
        val before=ProcessingQueue.jobs(store)
        assertEquals(id,Ingest.text(context,store,url))
        assertEquals(before,ProcessingQueue.jobs(store))
        assertNotEquals(id,Ingest.text(context,store,"https://example.org/news/browser-fixture"))
        assertEquals(2,store.list().size)
    }
    @Test fun deleteRemovesQueuedJobsCheckpointsAndFilesWithoutWaitingForInference() {
        val file=File(context.filesDir,"article-preview.webp").apply {writeText("preview fixture")}
        val id=store.add("web","Waiting article",sourceUrl="https://example.org",thumbnail=file.absolutePath)
        val other=store.add("note","Other post",body="Keep me")
        store.addSegments(id,listOf(Segment(0,id,"Article text",-1,-1,"web")))
        store.addAnswer(id,"Question","Answer","","model")
        store.writableDatabase.execSQL("INSERT INTO summary_chunks VALUES(?,?,?)",arrayOf(id,"chunk","Checkpoint"))
        ProcessingQueue.enqueue(context,id);ProcessingQueue.enqueue(context,other)
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val removed=java.util.concurrent.CountDownLatch(1)
        val errors=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val native=Thread {ProcessingGuard.exclusive {entered.countDown();release.await(10,TimeUnit.SECONDS)}}
        val deletion=Thread {try {ProcessingQueue.remove(context,id)} catch(t:Throwable) {errors.set(t)} finally {removed.countDown()}}
        native.start()
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));deletion.start()
            assertTrue("Deletion must not wait for another post's inference",removed.await(3,TimeUnit.SECONDS))
            assertNull(errors.get());assertNull(store.get(id));assertFalse(file.exists())
            assertTrue(store.segments(id).isEmpty());assertTrue(store.answers(id).isEmpty())
            assertFalse(ProcessingQueue.jobs(store).any {it.itemId==id})
            assertTrue(ProcessingQueue.jobs(store).any {it.itemId==other})
            store.readableDatabase.rawQuery("SELECT COUNT(*) FROM summary_chunks WHERE item_id=?",arrayOf(id)).use {it.moveToFirst();assertEquals(0,it.getInt(0))}
            assertNotNull(store.get(other))
        } finally {release.countDown();native.join(5000);deletion.join(5000)}
    }
    @Test fun lateAcquisitionTranscriptionAndAnswersCannotRestoreDeletedPost() {
        val id=store.add("web","Deleted article")
        ProcessingQueue.enqueue(context,id)
        val job=ProcessingQueue.jobs(store).first()
        ProcessingQueue.remove(context,id)
        store.addSegments(id,listOf(Segment(0,id,"Late article",-1,-1,"web")))
        store.upsertAudioClip(id,0,0,"Late transcript")
        store.addAnswer(id,"Late question","Late answer","","model")
        store.update(id,status="ready")
        ProcessingQueue.state(store,job,"waiting")
        ProcessingQueue.enqueue(context,id)
        ProcessingQueue.question(context,id,"Where?")
        assertNull(store.get(id));assertTrue(store.segments(id).isEmpty())
        assertTrue(store.answers(id).isEmpty());assertTrue(ProcessingQueue.jobs(store).isEmpty())
    }
    @Test fun completeShareContainsAllSegmentsAnswersAndOcr() {
        val id=store.add("image","Shared image",sourceUrl="https://example.org")
        val segments=(0..100).map {Segment(0,id,"OCR line $it",-1,-1,"ocr")}+Segment(0,id,"A blue document",-1,-1,"visione")
        store.addSegments(id,segments);store.addAnswer(id,"Question?","Saved answer","","model")
        val text=PostShare.text(context,store.get(id)!!,store.segments(id),store.answers(id))
        for(value in listOf("Shared image","https://example.org","A blue document","Question?","Saved answer","OCR line 100")) assertTrue(value,text.contains(value))
    }
    @Test fun pdfIncludesFinalSegmentBeyondVisibleEighty() {
        // FileProvider uses the app's real cache; use the real store for this one share snapshot.
        val actual=InstrumentationRegistry.getInstrumentation().targetContext
        BrainStore(actual).use {db ->
            val id=db.add("note","Complete PDF",body="Body")
            try {
                db.addSegments(id,(0..180).map {Segment(0,id,"Paragraph $it "+"complete text ".repeat(10),-1,-1,"import")})
                db.update(id,summary="Summary fixture",status="ready")
                db.addAnswer(id,"Question fixture","Answer fixture","","model")
                val intent=PostShare.prepare(actual,db,id,true)
                val uri=intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM,android.net.Uri::class.java)!!
                actual.contentResolver.openFileDescriptor(uri,"r")!!.use {descriptor ->PdfRenderer(descriptor).use {renderer ->
                    assertTrue(renderer.pageCount>3)
                    val text=buildString {for(i in 0 until renderer.pageCount) renderer.openPage(i).use {append(it.textContents.joinToString {it.text})}}
                    assertTrue(text.contains("Paragraph 180"));assertTrue(text.contains("Summary fixture"));assertTrue(text.contains("Answer fixture"))
                }}
                actual.contentResolver.delete(uri,null,null)
            } finally {db.delete(id)}
        }
    }
    @Test fun bundledOcrWorksWithoutPlayServicesDownload() {
        val bmp=Bitmap.createBitmap(1000,300,Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        Canvas(bmp).drawText("SUPERMENS OFFLINE TEXT 12345",30f,150f,Paint().apply {color=Color.BLACK;textSize=45f;isAntiAlias=true})
        val recognizer=com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result=com.google.android.gms.tasks.Tasks.await(recognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp,0)),30,TimeUnit.SECONDS).text
            assertTrue(result,result.contains("OFFLINE TEXT"));assertTrue(result.contains("12345"))
        } finally {recognizer.close();bmp.recycle()}
    }
    private fun fixture(name:String):File = File(root,name).also {target ->InstrumentationRegistry.getInstrumentation().context.assets.open(name).use {input ->target.outputStream().use {input.copyTo(it)}}}
    @Test fun newVideoKeepsWebpAndMonoCompressedAudioWithoutOriginalCopy() {
        val original=fixture("video-audio.mp4")
        val id=Ingest.file(context,store,android.net.Uri.fromFile(original),"video/mp4")
        val staged=File(store.get(id)!!.pendingMedia)
        assertEquals("",store.get(id)!!.attachment);assertTrue(staged.isFile)
        ProcessingGuard.exclusive {VideoStorage.prepare(context,store,store.get(id)!!)}
        val item=store.get(id)!!
        assertFalse(staged.exists());assertTrue(original.exists());assertEquals("",item.attachment)
        assertTrue(File(item.pendingMedia).isFile);assertTrue(item.pendingMedia.endsWith(".m4a"))
        val extractor=android.media.MediaExtractor()
        try {extractor.setDataSource(item.pendingMedia);val format=extractor.getTrackFormat(0)
            assertEquals(16000,format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(1,format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT))
            assertEquals("audio/mp4a-latm",format.getString(android.media.MediaFormat.KEY_MIME))
        } finally {extractor.release()}
        val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
        android.graphics.BitmapFactory.decodeFile(item.thumbnail,bounds)
        assertTrue(maxOf(bounds.outWidth,bounds.outHeight)<=720)
        assertFalse(context.filesDir.walkTopDown().any {it.extension=="mp4"})
        if(!LocalModel.ready(context)) {
            assertTrue(runCatching {PostProcessor(context,store,{}).process(item)}.exceptionOrNull() is ModelWaiting)
            assertTrue(File(store.get(id)!!.pendingMedia).isFile)
        }
    }
    @Test fun silentVideoIsValidWithoutInventedTranscriptOrPendingAudio() {
        val id=Ingest.file(context,store,android.net.Uri.fromFile(fixture("video-silent.mp4")),"video/mp4")
        ProcessingGuard.exclusive {VideoStorage.prepare(context,store,store.get(id)!!)}
        val item=store.get(id)!!
        assertEquals("",item.pendingMedia);assertEquals("silent_video",item.sourceQuality)
        assertTrue(File(item.thumbnail).isFile)
        PostProcessor(context,store,{}).process(item)
        assertEquals("ready",store.get(id)!!.status);assertTrue(store.segments(id).isEmpty())
    }

    @Test fun importReceiptSurvivesReopenAndAllowsNewExplicitImport() {
        var operations=0
        val first=Ingest.once(store,"request-1") {operations++;listOf(Ingest.text(context,store,"Receipt fixture"))}
        store.close();store=BrainStore(context)
        assertEquals(first,Ingest.once(store,"request-1") {operations++;error("Must not replay")})
        val second=Ingest.once(store,"request-2") {operations++;listOf(Ingest.text(context,store,"Receipt fixture"))}
        assertEquals(2,operations);assertNotEquals(first,second)
        assertEquals(2,store.list().size);assertEquals(2,ProcessingQueue.jobs(store).size)
    }
    @Test fun failedImportDoesNotCommitReceiptOrPartialPosts() {
        assertTrue(runCatching {Ingest.once(store,"failed-request") {store.add("note","Partial");error("Failed")}}.isFailure)
        assertTrue(store.list().isEmpty())
        val ids=Ingest.once(store,"failed-request") {listOf(store.add("note","Retry"))}
        assertEquals("Retry",store.get(ids.single())!!.title)
    }
    @Test fun readyPostSettlesStaleProcessButKeepsNewQuestionAndSummary() {
        val id=store.add("video","Ready fixture")
        ProcessingQueue.enqueue(context,id,enrichOnly=true)
        store.update(id,summary="Completed summary",status="ready")
        ProcessingQueue.question(context,id,"New question")
        ProcessingQueue.summary(context,id)
        ProcessingQueue.reconcile(context,store)
        assertEquals("done",ProcessingQueue.jobs(store).single {it.kind=="process"}.state)
        assertEquals("waiting",ProcessingQueue.jobs(store).single {it.kind=="question"}.state)
        assertEquals("waiting",ProcessingQueue.jobs(store).single {it.kind=="summary"}.state)
    }
    @Test fun acquisitionCompletionPublishesPendingBeforeProcessIsEligible() {
        val id=store.add("web","Acquisition",sourceUrl="https://example.org")
        ProcessingQueue.enqueue(context,id)
        val acquire=ProcessingQueue.jobs(store).single {it.kind=="acquire"}
        ProcessingQueue.complete(store,acquire) {store.update(id,status="pending_ai")}
        assertEquals("pending_ai",store.get(id)!!.status)
        assertEquals("process",QueueOrder.next(ProcessingQueue.jobs(store),emptySet(),{true},{0})!!.kind)
        val process=ProcessingQueue.jobs(store).single {it.kind=="process"}
        ProcessingQueue.complete(store,process) {store.update(id,summary="Finished",status="ready")}
        assertEquals("ready",store.get(id)!!.status);assertTrue(ProcessingQueue.jobs(store).all {it.state=="done"})
    }
    @Test fun failedCompletionRollsBackPublishedStateAndReceipt() {
        val id=store.add("note","Atomic fixture")
        ProcessingQueue.enqueue(context,id)
        val job=ProcessingQueue.jobs(store).single()
        assertTrue(runCatching {ProcessingQueue.complete(store,job) {store.update(id,status="ready");error("Publication failure")}}.isFailure)
        assertEquals("pending_ai",store.get(id)!!.status);assertEquals("waiting",ProcessingQueue.jobs(store).single().state)
    }
    @Test fun oldPendingStatusWithCompletedSummaryIsRecoveredWithoutRetranscribing() {
        val id=store.add("video","Previously completed video")
        ProcessingQueue.enqueue(context,id,enrichOnly=true)
        val job=ProcessingQueue.jobs(store).single()
        ProcessingQueue.complete(store,job) {store.update(id,status="ready",summary="Final video summary")}
        store.update(id,status="pending_ai")
        ProcessingQueue.reconcile(context,store)
        assertEquals("ready",store.get(id)!!.status)
        assertEquals(listOf(job.id),ProcessingQueue.jobs(store).map {it.id})
    }
    @Test fun versionThreeUpgradePreservesArchiveAndQueueWhileAddingReceipts() {
        val id=store.add("note","Existing archive",body="Existing body")
        store.update(id,summary="Existing summary",status="ready");store.addAnswer(id,"Question","Answer","Evidence","model")
        ProcessingQueue.question(context,id,"Pending question")
        store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("supermens.db").path,null,SQLiteDatabase.OPEN_READWRITE).use {db ->db.execSQL("DROP TABLE ingest_receipts");db.version=3}
        store=BrainStore(context)
        assertEquals(4,store.readableDatabase.version);assertEquals("Existing summary",store.get(id)!!.summary)
        assertEquals("Answer",store.answers(id).single().answer);assertEquals("Pending question",ProcessingQueue.jobs(store).single().payload)
        assertEquals(listOf(id),Ingest.once(store,"upgrade-request") {listOf(id)})
    }
    @Test fun sharedRedditTitleHtmlAndClipLinkArePreserved() {
        val intent=android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type="text/plain";putExtra(android.content.Intent.EXTRA_TITLE,"Reddit title")
            putExtra(android.content.Intent.EXTRA_HTML_TEXT,"<p>Full shared body</p><p>https://www.reddit.com/r/test/comments/fixture/</p>")
        }
        val id=Ingest.intake(context,store,intent).single()
        assertTrue(store.get(id)!!.body.contains("Reddit title"));assertTrue(store.get(id)!!.body.contains("Full shared body"))
        val linkIntent=android.content.Intent(android.content.Intent.ACTION_SEND).apply {type="text/plain";clipData=android.content.ClipData.newRawUri("Link",android.net.Uri.parse("https://www.reddit.com/r/test/comments/another/"))}
        val link=Ingest.intake(context,store,linkIntent).single();assertEquals("web",store.get(link)!!.type)
    }
}
