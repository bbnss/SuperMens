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
        assertEquals(3,store.readableDatabase.version)
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

}
