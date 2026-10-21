package it.supermens.local

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class DailyHomeUiTest {
    @get:Rule val rule=createEmptyComposeRule()
    @get:Rule val locale=AppLocaleRule("it")
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun launch():ActivityScenario<MainActivity> {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
        return ActivityScenario.launch(MainActivity::class.java)
    }
    private fun waitTag(tag:String)=rule.waitUntil(15000) {runCatching {rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
    private fun waitText(text:String)=rule.waitUntil(15000) {rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}

    @Test fun titleListUpdatesOpensPostsSurvivesRecreationAndHidesInSearch() {
        BrainStore(context).use {store ->
            val first=store.add("note","Titolo precedente",body="Primo contenuto")
            val pending=store.add("note","File in attesa",body="Secondo contenuto")
            store.update(first,status="ready",titleState="legacy");store.update(pending,status="ready")
            val day=DailyTimeline.key(store.get(first)!!.createdAt)
            launch().use {scenario ->try {
                waitTag("daily-toggle:${day.value}")
                rule.onNodeWithTag("daily-title:$first").assertDoesNotExist()
                rule.onNodeWithTag("daily-toggle:${day.value}").performClick()
                rule.onNodeWithTag("daily-title:$first").assertIsDisplayed()
                rule.onNodeWithTag("daily-title:$pending").assertDoesNotExist()
                store.update(pending,title="Nuovo argomento elaborato",titleState="generated")
                waitTag("daily-title:$pending")
                rule.onNodeWithTag("daily-title:$pending").performClick()
                waitText("Scheda")
                rule.onNodeWithContentDescription("Indietro").performClick()
                waitTag("daily-title:$pending")
                scenario.recreate();waitTag("daily-title:$pending")
                rule.onNodeWithText("Cerca nei post").performTextInput("Titolo precedente")
                rule.waitUntil(15000) {rule.onAllNodesWithTag("daily-toggle:${day.value}").fetchSemanticsNodes().isEmpty()}
                rule.onNodeWithTag("day:${day.value}").assertIsDisplayed()
                rule.onNodeWithTag("daily-title:$first").assertDoesNotExist()
                rule.onNodeWithContentDescription("Cancella ricerca").performClick()
                waitTag("daily-title:$pending")
            } finally {ProcessingQueue.remove(context,first);ProcessingQueue.remove(context,pending)}}
        }
    }
    @Test fun returningFromAnOlderPostKeepsItsPositionEvenAfterNewPostsArrive() {
        BrainStore(context).use {store ->
            val ids=(0 until 45).map {index ->store.add("note","Timeline item $index",body="Body $index").also {
                store.update(it,status="ready",summary="An extended preview ".repeat(12),titleState="legacy")
                store.writableDatabase.execSQL("UPDATE items SET created_at=? WHERE id=?",arrayOf<Any>(System.currentTimeMillis()-index*1000,it))
            }}.toMutableList()
            launch().use {scenario ->try {
                waitTag("home-posts")
                rule.onNodeWithTag("home-posts").performScrollToNode(hasText("Timeline item 30"))
                val before=rule.onNodeWithText("Timeline item 30").fetchSemanticsNode().boundsInRoot.top
                rule.onNodeWithText("Timeline item 30").performClick();waitText("Scheda")
                ids+=store.add("note","Newly arrived post",body="New source").also {store.update(it,status="ready")}
                rule.onNodeWithContentDescription("Indietro").performClick();waitText("Timeline item 30")
                assertEquals(before,rule.onNodeWithText("Timeline item 30").fetchSemanticsNode().boundsInRoot.top,3f)
                scenario.recreate();waitText("Timeline item 30")
                assertEquals(before,rule.onNodeWithText("Timeline item 30").fetchSemanticsNode().boundsInRoot.top,3f)
            } finally {ids.forEach {ProcessingQueue.remove(context,it)}}}
        }
    }
    @Test fun backFromHomeAsksBeforeClosingAndDialogsTakePriority() {
        val scenario=launch()
        try {
            waitText("Cerca nei post")
            scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
            waitText("Uscire da SuperMens?")
            rule.onNodeWithText("Resta").performClick()
            assertEquals(Lifecycle.State.RESUMED,scenario.state)
            rule.onNodeWithContentDescription("Impostazioni").performClick();waitText("Impostazioni")
            scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
            waitText("Cerca nei post")
            rule.onNodeWithText("Uscire da SuperMens?").assertDoesNotExist()
            scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
            waitText("Uscire da SuperMens?")
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            rule.waitUntil(10000) {rule.onAllNodesWithText("Uscire da SuperMens?").fetchSemanticsNodes().isEmpty()}
            scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()};waitText("Uscire da SuperMens?")
            rule.onNodeWithText("Esci").performClick()
            rule.waitUntil(10000) {scenario.state==Lifecycle.State.DESTROYED}
        } finally {scenario.close()}
    }
    @Test fun relinkingRequiresBothConfirmationsAndCancellationKeepsTheCopy() {
        BrainStore(context).use {store ->
            val original=File(context.filesDir,"attachments/ui-original.pdf").apply {parentFile!!.mkdirs()}
            val pdf=PdfDocument()
            try {val page=pdf.startPage(PdfDocument.PageInfo.Builder(80,100,1).create());pdf.finishPage(page);original.outputStream().use {pdf.writeTo(it)}} finally {pdf.close()}
            val id=store.add("pdf","PDF conservato",attachment=original.absolutePath)
            store.update(id,status="ready",pdfPages=1)
            val uri=Uri.parse("content://it.supermens.offline.test.documents/relinked")
            context.grantUriPermission(context.packageName,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            var pickerCalls=0
            val monitor=object:Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
                    if(intent.action!=Intent.ACTION_OPEN_DOCUMENT) return null
                    pickerCalls++
                    return Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
                }
            }
            instrumentation.addMonitor(monitor)
            launch().use {scenario ->try {
                waitText("PDF conservato");rule.onNodeWithText("PDF conservato").performClick();waitText("Ricollega PDF")
                rule.onNodeWithText("Ricollega PDF").performClick();waitText("Scegli PDF")
                assertEquals(0,pickerCalls);assertTrue(original.isFile)
                rule.onNodeWithText("Annulla").performClick()
                rule.onNodeWithText("Ricollega PDF").performClick();rule.onNodeWithText("Scegli PDF").performClick()
                waitText("Conferma sostituzione PDF");assertEquals(1,pickerCalls)
                assertEquals(original.absolutePath,store.get(id)!!.attachment)
                scenario.recreate();waitText("Conferma sostituzione PDF")
                rule.onNodeWithText("Annulla").performClick()
                assertTrue(original.isFile);assertEquals(original.absolutePath,store.get(id)!!.attachment)
                rule.onNodeWithText("Ricollega PDF").performClick();rule.onNodeWithText("Scegli PDF").performClick()
                waitText("Conferma sostituzione PDF");rule.onNodeWithText("Sostituisci PDF").performClick()
                rule.waitUntil(15000) {store.get(id)?.attachment!=original.absolutePath}
                assertFalse(original.exists());assertTrue(File(store.get(id)!!.attachment).isFile)
            } finally {ProcessingQueue.remove(context,id);instrumentation.removeMonitor(monitor)}}
        }
    }
}
