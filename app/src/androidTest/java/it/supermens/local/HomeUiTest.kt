// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HomeUiTest {
    @get:Rule val rule=createEmptyComposeRule()
    @get:Rule val locale=AppLocaleRule("it")
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun launch(): ActivityScenario<MainActivity> {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
        return ActivityScenario.launch(MainActivity::class.java)
    }
    private fun waitText(text: String) = rule.waitUntil(15000) {rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
    private fun delete(store: BrainStore,id: String) {
        WorkManager.getInstance(context).cancelUniqueWork("analyze-$id").result.get()
        store.delete(id)?.let {item->listOf(item.attachment,item.thumbnail).filter {it.isNotBlank()}.distinct().forEach {java.io.File(it).delete()}}
    }

    @Test fun homeHasFiltersAbovePostsAndSearchBelowWithCompleteAddMenu() {
        val store=BrainStore(context);val id=store.add("note","Home layout test",body="Sample")
        store.update(id,summary="Sample",status="ready")
        val scenario=launch()
        try {
            waitText("Home layout test")
            val filter=rule.onNodeWithText("Tutti").fetchSemanticsNode().boundsInRoot
            val card=rule.onNodeWithText("Home layout test").fetchSemanticsNode().boundsInRoot
            val search=rule.onNodeWithText("Cerca nei post").fetchSemanticsNode().boundsInRoot
            assertTrue(filter.bottom<card.top);assertTrue(card.bottom<search.top)
            rule.onNodeWithContentDescription("Aggiungi contenuto").performClick()
            listOf("Nota scritta","Nota vocale","Dettatura","Importa file","Fotocamera","Incolla contenuto").forEach {rule.onNodeWithText(it).assertIsDisplayed()}
            rule.onNodeWithText("Nota scritta").performClick()
            rule.onNodeWithText("Scrivi qui").assertIsDisplayed()
            rule.onNodeWithText("Annulla").performClick()
            rule.onNodeWithText("Cerca nei post").performTextInput("unfindablexyz")
            waitText("Nessun risultato")
            rule.onNodeWithContentDescription("Cancella ricerca").performClick()
            waitText("Home layout test")
        } finally {scenario.close();delete(store,id);store.close()}
    }
    @Test fun activityFromHiddenPostRemainsVisibleAndCanBeOpened() {
        val store=BrainStore(context);val id=store.add("audio","Hidden activity test")
        store.update(id,status="processing_audio 3/12",summary="Audio")
        val scenario=launch()
        try {
            waitText("Hidden activity test")
            rule.onNodeWithText("Note").performClick()
            rule.waitUntil(15000) {rule.onAllNodesWithText("Trascrizione 3/12",substring=true).fetchSemanticsNodes().isNotEmpty()}
            rule.onNodeWithText("Trascrizione 3/12",substring=true).performClick()
            rule.onNodeWithText("Hidden activity test").assertIsDisplayed()
            rule.onNodeWithText("Hidden activity test").performClick()
            waitText("Scheda")
            rule.onNodeWithText("Hidden activity test").assertIsDisplayed()
        } finally {scenario.close();delete(store,id);store.close()}
    }
    @Test fun imageDetailSeparatesDescriptionAndCopiesOnlyAllOcr() {
        val store=BrainStore(context);val id=store.add("image","Image copy test")
        val ocr=(0 until 100).map {Segment(0,id,"OCR paragraph $it",-1,-1,"ocr")}
        store.addSegments(id,ocr+Segment(0,id,"Unique photo description",-1,-1,"visione"))
        store.update(id,summary="Old redundant summary",status="ready")
        val scenario=launch()
        try {
            waitText("Image copy test");rule.onNodeWithText("Image copy test").performClick()
            waitText("Descrizione")
            rule.onNodeWithText("Old redundant summary").assertDoesNotExist()
            rule.onNodeWithText("Rigenera riassunto").assertDoesNotExist()
            rule.onNodeWithTag("detail-content").performScrollToNode(hasText("Copia testo OCR"));rule.onNodeWithText("Copia testo OCR").performClick()
            rule.onNodeWithText("Testo copiato").assertIsDisplayed()
            var copied=""
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.READ_CLIPBOARD_IN_BACKGROUND")
            try {
                scenario.onActivity { copied=(it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString() }
            } finally {instrumentation.uiAutomation.dropShellPermissionIdentity()}
            assertEquals(ocr.joinToString("\n\n") {it.text},copied)
            assertFalse(copied.contains("Unique photo description"))
            assertTrue(copied.endsWith("OCR paragraph 99"))
        } finally {scenario.close();delete(store,id);store.close()}
    }
    @Test fun cameraSuccessAndCancellationUseFullSizeOutputAndDoNotLeaveOriginals() {
        val store=BrainStore(context);val before=store.list().map {it.id}.toSet()
        var cancel=false;var output: Uri?=null
        val monitor=object: Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if(intent.action!=MediaStore.ACTION_IMAGE_CAPTURE) return null
                output=intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT,Uri::class.java)
                assertNotNull(output)
                if(!cancel) {
                    val image=Bitmap.createBitmap(2200,1100,Bitmap.Config.ARGB_8888)
                    image.eraseColor(android.graphics.Color.BLUE)
                    context.contentResolver.openOutputStream(output!!)!!.use {image.compress(Bitmap.CompressFormat.JPEG,95,it)}
                    image.recycle()
                }
                return Instrumentation.ActivityResult(if(cancel) Activity.RESULT_CANCELED else Activity.RESULT_OK,Intent())
            }
        }
        instrumentation.addMonitor(monitor)
        val scenario=launch()
        try {
            rule.onNodeWithContentDescription("Aggiungi contenuto").performClick()
            waitText("Fotocamera")
            rule.onNodeWithText("Fotocamera").performClick()
            rule.waitUntil(25000) {store.list().any {it.id !in before}}
            val saved=store.list().first {it.id !in before}
            assertEquals("image",saved.type);assertTrue(ImageStorage.isOptimized(java.io.File(saved.attachment)))
            assertEquals(0,java.io.File(context.cacheDir,"camera").listFiles()?.size ?: 0)
            waitText("Foto salvata")
            cancel=true
            rule.onNodeWithContentDescription("Aggiungi contenuto").performClick()
            waitText("Fotocamera")
            rule.onNodeWithText("Fotocamera").performClick()
            rule.waitForIdle()
            assertEquals(before.size+1,store.list().size)
            assertEquals(0,java.io.File(context.cacheDir,"camera").listFiles()?.size ?: 0)
        } finally {scenario.close();instrumentation.removeMonitor(monitor);store.list().filter {it.id !in before}.forEach {delete(store,it.id)};store.close()}
    }
    @Test fun importedVideoIsNotReplayedAfterRecreationAndThreeCameraCaptures() {
        val store=BrainStore(context);val before=store.list().map {it.id}.toSet()
        val video=Uri.parse("content://it.supermens.offline.test.documents/video")
        context.grantUriPermission(context.packageName,video,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val monitor=object:Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
                if(intent.action==Intent.ACTION_OPEN_DOCUMENT) return Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(video).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                if(intent.action!=MediaStore.ACTION_IMAGE_CAPTURE) return null
                val output=intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT,Uri::class.java)!!
                val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.BLUE)
                context.contentResolver.openOutputStream(output)!!.use {bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)};bitmap.recycle()
                return Instrumentation.ActivityResult(Activity.RESULT_OK,Intent())
            }
        }
        instrumentation.addMonitor(monitor)
        val scenario=launch()
        try {
            rule.onNodeWithContentDescription("Aggiungi contenuto").performClick();rule.onNodeWithText("Importa file").performClick()
            rule.waitUntil(25000) {store.list().any {it.id !in before && it.type=="video" && it.status=="ready"}}
            val videoId=store.list().single {it.id !in before && it.type=="video"}.id
            repeat(3) {index ->
                scenario.recreate();waitText("Cerca nei post")
                rule.onNodeWithContentDescription("Aggiungi contenuto").performClick();rule.onNodeWithText("Fotocamera").performClick()
                rule.waitUntil(25000) {store.list().count {it.id !in before && it.type=="image"}==index+1}
                assertEquals(listOf(videoId),store.list().filter {it.id !in before && it.type=="video"}.map {it.id})
            }
            rule.onNodeWithContentDescription("Aggiungi contenuto").performClick();rule.onNodeWithText("Importa file").performClick()
            rule.waitUntil(25000) {store.list().count {it.id !in before && it.type=="video"}==2}
        } finally {scenario.close();instrumentation.removeMonitor(monitor);store.list().filter {it.id !in before}.forEach {ProcessingQueue.remove(context,it.id)};store.close()}
    }
    @Test fun pastedTextIsVisibleAndDraftSurvivesRecreation() {
        val store=BrainStore(context);val before=store.list().map {it.id}.toSet()
        val text="Visible pasted text "+"long text ".repeat(200)
        val scenario=launch()
        try {
            scenario.onActivity {(it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("fixture",text))}
            rule.onNodeWithContentDescription("Aggiungi contenuto").performClick();rule.onNodeWithText("Nota scritta").performClick()
            rule.onNodeWithText("Salva").assertIsNotEnabled()
            rule.onNodeWithText("Incolla").performClick();rule.onNodeWithTag("text-entry").assertTextContains(text)
            val snapshot=java.io.File(context.getExternalFilesDir(null),"review-text-entry.png")
            snapshot.outputStream().use {rule.onNodeWithTag("text-entry").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
            scenario.recreate();rule.onNodeWithTag("text-entry").assertTextContains(text)
            rule.onNodeWithText("Salva").performClick()
            rule.waitUntil(15000) {store.list().any {it.id !in before && it.body==text}}
            assertEquals(1,store.list().count {it.id !in before})
        } finally {scenario.close();store.list().filter {it.id !in before}.forEach {ProcessingQueue.remove(context,it.id)};store.close()}
    }
}
