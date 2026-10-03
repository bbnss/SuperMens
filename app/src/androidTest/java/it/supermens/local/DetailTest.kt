// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.*
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

class DetailTest {
    @get:Rule val rule=createEmptyComposeRule()
    @get:Rule val locale=AppLocaleRule("it")
    @Test fun consecutiveSharesBeforeRecompositionBothReachTheArchive() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
        val first="First share regression fixture"
        val second="Second share regression fixture"
        BrainStore(context).use {store ->
            val scenario=ActivityScenario.launch(MainActivity::class.java)
            var launchIntent:android.content.Intent?=null
            try {
                scenario.onActivity {activity ->
                    launchIntent=android.content.Intent(activity.intent)
                    val receive=MainActivity::class.java.getDeclaredMethod("onNewIntent",android.content.Intent::class.java).apply {isAccessible=true}
                    listOf(first,second).forEach {text ->receive.invoke(activity,android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type="text/plain";putExtra(android.content.Intent.EXTRA_TEXT,text)
                    })}
                }
                rule.waitUntil(15000) {store.list().any {it.title==first} && store.list().any {it.title==second}}
                org.junit.Assert.assertEquals(1,store.list().count {it.title==first})
                org.junit.Assert.assertEquals(1,store.list().count {it.title==second})
            } finally {
                // ActivityScenario matches lifecycle callbacks against the original launch intent.
                scenario.onActivity {it.intent=launchIntent}
                scenario.close();store.list().filter {it.title in setOf(first,second)}.forEach {ProcessingQueue.remove(context,it.id)}
            }
        }
    }
    @Test fun waitingArticleCanBeDeletedFromDetailAndDoesNotReturnAfterQueueRecovery() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
        BrainStore(context).use {store ->
            val id=store.add("note","Pending deletion fixture",body="Pending content")
            ProcessingQueue.enqueue(context,id)
            store.update(id,status="pending_ai")
            val scenario=ActivityScenario.launch(MainActivity::class.java)
            try {
                rule.waitUntil(15000) {rule.onAllNodesWithText("Pending deletion fixture").fetchSemanticsNodes().isNotEmpty()}
                rule.onNodeWithText("Pending deletion fixture").performClick()
                rule.onNodeWithTag("detail-content").performScrollToNode(hasText(AppLanguage.context(context).uiString(R.string.delete_item)));rule.onNodeWithText(AppLanguage.context(context).uiString(R.string.delete_item)).assertIsEnabled().performClick()
                rule.waitUntil(10000) {store.get(id)==null}
                ProcessingQueue.reconcile(context,store)
                org.junit.Assert.assertFalse(ProcessingQueue.jobs(store).any {it.itemId==id})
                org.junit.Assert.assertNull(store.get(id))
            } finally {scenario.close();store.delete(id)}
        }
    }
    @Test fun answersFollowSummaryAndSendingScrollsBackFromTranscript() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
        val store=BrainStore(context)
        val id=store.add("note","UI test note",body="Testo del post")
        var scenario: ActivityScenario<MainActivity>?=null
        try {
            store.addSegments(id,(0 until 80).map { Segment(0,id,"Segmento $it: "+"Questa è una trascrizione lunga. ".repeat(8),it*1000L,-1,"trascrizione") })
            store.update(id,summary="Riassunto di prova",status="ready")
            store.addAnswer(id,"Domanda salvata di prova","Risposta salvata di prova","","Gemma")
            scenario=ActivityScenario.launch(MainActivity::class.java)
            rule.waitUntil(15000) { rule.onAllNodesWithText("UI test note").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("UI test note").performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithText("Risposta salvata di prova").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("detail-content").performScrollToNode(hasText("Domande e risposte"))
            rule.onNodeWithText("Domande e risposte").assertIsDisplayed()
            rule.onNodeWithText("Risposta salvata di prova").assertIsDisplayed()
            rule.onNodeWithTag("detail-content").performScrollToNode(hasText("Mostra di più"))
            rule.onNodeWithText("Mostra di più").performClick()
            rule.onNodeWithTag("detail-content").performScrollToNode(hasText("Segmento 79: "+"Questa è una trascrizione lunga. ".repeat(8)))
            rule.onNodeWithText("Chiedi sul contenuto").performTextInput("Qual è il contenuto?")
            rule.onNodeWithText("Invia").performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Domande e risposte").assertIsDisplayed()
            rule.onNodeWithText("Risposta salvata di prova").assertIsDisplayed()
        } finally { scenario?.close();store.delete(id);store.close() }
    }
    @Test fun singleLongTranscriptCanExpandCollapseAndReturnToTop() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
        BrainStore(context).use {store ->
            val id=store.add("note","Single long transcript",body="Text")
            val text="First paragraph "+"Transcript body ".repeat(600)+" FINAL_TRANSCRIPT_MARKER"
            store.addSegments(id,listOf(Segment(0,id,text,-1,-1,"import")));store.update(id,summary="Summary fixture",status="ready")
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                try {
                    rule.waitUntil(15000) {rule.onAllNodesWithText("Single long transcript").fetchSemanticsNodes().isNotEmpty()}
                    rule.onNodeWithText("Single long transcript").performClick()
                    rule.onNodeWithTag("detail-content").performScrollToNode(hasText("Mostra di più"))
                    rule.onAllNodesWithText("FINAL_TRANSCRIPT_MARKER",substring=true).assertCountEquals(0)
                    rule.onNodeWithText("Mostra di più").performClick()
                    rule.onNodeWithTag("detail-content").performScrollToNode(hasText("FINAL_TRANSCRIPT_MARKER",substring=true))
                    rule.onNodeWithText("FINAL_TRANSCRIPT_MARKER",substring=true).assertIsDisplayed()
                    rule.onNodeWithTag("detail-content").performScrollToNode(hasText("Torna in cima"));rule.onNodeWithText("Torna in cima").performClick()
                    rule.onNodeWithText("Single long transcript").assertIsDisplayed()
                    rule.onNodeWithTag("detail-content").performScrollToNode(hasText("Mostra meno"));rule.onNodeWithText("Mostra meno").performClick()
                    rule.onAllNodesWithText("FINAL_TRANSCRIPT_MARKER",substring=true).assertCountEquals(0)
                    org.junit.Assert.assertEquals(text,PostContent.copyText(store.get(id)!!,store.segments(id)))
                } finally {store.delete(id)}
            }
        }
    }
    @Test fun consumedLaunchShareIsNotImportedAgainAfterActivityRecreation() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
        val text="Launch share recreation fixture"
        val intent=android.content.Intent(context,MainActivity::class.java).setAction(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT,text)
        BrainStore(context).use {store ->
            ActivityScenario.launch<MainActivity>(intent).use {scenario ->
                try {
                    rule.waitUntil(15000) {store.list().count {it.title==text}==1}
                    rule.waitForIdle()
                    // ActivityScenario matches recreated Activities against its launch intent.
                    // Restoring that intent here also exercises the stale SHARE replay regression.
                    scenario.onActivity {it.intent=android.content.Intent(intent)}
                    scenario.recreate();rule.waitForIdle();scenario.recreate();rule.waitForIdle()
                    org.junit.Assert.assertEquals(1,store.list().count {it.title==text})
                } finally {store.list().filter {it.title==text}.forEach {ProcessingQueue.remove(context,it.id)}}
            }
        }
    }
}
