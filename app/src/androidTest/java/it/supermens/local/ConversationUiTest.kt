// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConversationUiTest {
    @get:Rule val rule=createEmptyComposeRule()
    @get:Rule val locale=AppLocaleRule("it")
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun launch():ActivityScenario<MainActivity> {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
        return ActivityScenario.launch(MainActivity::class.java)
    }
    private fun waitText(text:String)=rule.waitUntil(15000) {rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
    private fun menu(label:String) {
        rule.onNodeWithContentDescription("Aggiungi contenuto").performClick()
        val matches=rule.onAllNodesWithText(label)
        matches[matches.fetchSemanticsNodes().size-1].performClick()
    }
    @Test fun freeChatSavesOnePendingPostAndReopensAfterRecreation() {
        BrainStore(context).use {store ->
            val before=store.list().map {it.id}.toSet()
            launch().use {scenario ->try {
                waitText("Cerca nei post");menu("Chat libera")
                rule.onNodeWithText("Questo modello è piccolo",substring=true).assertIsDisplayed()
                rule.onNodeWithTag("conversation-input").performTextInput("Conversazione di prova locale")
                rule.onNodeWithTag("conversation-send").performClick()
                rule.waitUntil(15000) {store.list().any {it.id !in before && it.type=="chat"}}
                val post=store.list().single {it.id !in before && it.type=="chat"}
                assertEquals("Conversazione di prova locale",store.messages(post.id).single().text)
                assertEquals("",post.summary)
                scenario.recreate()
                rule.waitUntil(15000) {rule.onAllNodesWithTag("conversation-input").fetchSemanticsNodes().isNotEmpty()}
                assertEquals(1,store.messages(post.id).size)
                rule.onNodeWithContentDescription("Back").assertDoesNotExist()
                rule.onNodeWithContentDescription("Indietro").performClick()
                waitText(post.title);rule.onNodeWithText(post.title).performClick()
                rule.onNodeWithTag("conversation-input").assertExists()
            } finally {store.list().filter {it.id !in before}.forEach {ProcessingQueue.remove(context,it.id)}}}
        }
    }
    @Test fun translationPostKeepsLanguageAndSupportsBothContinuationModes() {
        BrainStore(context).use {store ->
            val id=store.add("translation","Traduzione riapribile")
            store.setTargetLanguage(id,"fr")
            val user=store.addMessage(id,"Hello world","translate","fr","done")
            store.addMessage(id,"Bonjour le monde","translate","fr","done",role="assistant",replyTo=user)
            launch().use {scenario ->try {
                waitText("Traduzione riapribile");rule.onNodeWithText("Traduzione riapribile").performClick()
                waitText("Lingua di destinazione: francese")
                rule.onNodeWithText("Testo").performClick();rule.onNodeWithText("Voce").performClick()
                rule.onNodeWithText("Chat sul testo").performClick()
                scenario.recreate()
                waitText("✓ Chat sul testo")
                rule.onNodeWithText("Continua a tradurre").performClick()
                rule.onNodeWithTag("conversation-input").performTextInput("Another sentence")
                rule.onNodeWithTag("conversation-send").performClick()
                rule.waitUntil(15000) {store.messages(id).size==3}
                val message=store.messages(id).last()
                assertEquals("translate",message.mode);assertEquals("fr",message.language)
                assertEquals("Bonjour le monde",store.messages(id)[1].text)
            } finally {ProcessingQueue.remove(context,id)}}
        }
    }
    @Test fun transcribedDraftCanBeCorrectedAndSentWithoutDuplicatingTheOriginalMessage() {
        BrainStore(context).use {store ->
            val id=store.add("translation","Trascrizione correggibile")
            store.setTargetLanguage(id,"de")
            val draft=store.addMessage(id,"Testo da correggere","translate","de","draft")
            launch().use {scenario ->try {
                waitText("Trascrizione correggibile");rule.onNodeWithText("Trascrizione correggibile").performClick()
                rule.waitUntil(15000) {rule.onAllNodesWithTag("conversation-input").fetchSemanticsNodes().isNotEmpty()}
                rule.onNodeWithTag("conversation-input").assertTextContains("Testo da correggere")
                rule.onNodeWithTag("conversation-input").performTextReplacement("Testo corretto")
                rule.waitUntil(15000) {store.messages(id).single().text=="Testo corretto"}
                scenario.recreate()
                rule.onNodeWithTag("conversation-input").assertTextContains("Testo corretto")
                rule.onNodeWithTag("conversation-send").performClick()
                rule.waitUntil(15000) {store.messages(id).single().state=="pending"}
                assertEquals(draft,store.messages(id).single().id)
                assertEquals("de",store.messages(id).single().language)
            } finally {ProcessingQueue.remove(context,id)}}
        }
    }
    @Test fun recordingCreatesAudioInTheConversationInsteadOfAnUnrelatedVoiceNote() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.RECORD_AUDIO)
        BrainStore(context).use {store ->
            val before=store.list().map {it.id}.toSet()
            launch().use {scenario ->try {
                waitText("Cerca nei post");menu("Chat libera")
                rule.onNodeWithContentDescription("Nota vocale").performClick()
                waitText("Termina registrazione")
                // Let MediaRecorder produce a valid AAC frame before stopping.
                val started=android.os.SystemClock.elapsedRealtime()
                rule.waitUntil(5000) {android.os.SystemClock.elapsedRealtime()-started>=2000}
                rule.onNodeWithText("Termina registrazione").performClick()
                rule.waitUntil(15000) {store.list().any {it.id !in before && it.type=="chat"}}
                val post=store.list().single {it.id !in before}
                assertEquals("chat",post.type)
                val message=store.messages(post.id).single()
                assertEquals("transcribing",message.state);assertTrue(java.io.File(message.audioPath).length()>0)
                assertEquals(AppLanguage.deviceTag(context),message.language)
                assertTrue(ProcessingQueue.jobs(store).any {it.itemId==post.id && it.kind=="transcribe"})
            } finally {store.list().filter {it.id !in before}.forEach {ProcessingQueue.remove(context,it.id)}}}
        }
    }
    @Test fun fileImportsUseTheCurrentTranslationOrChatWithoutCreatingGenericPosts() {
        var source=android.net.Uri.parse("content://it.supermens.offline.test.documents/text")
        context.grantUriPermission(context.packageName,source,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val monitor=object:android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent:android.content.Intent):android.app.Instrumentation.ActivityResult? =
                if(intent.action==android.content.Intent.ACTION_OPEN_DOCUMENT) android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK,android.content.Intent().setData(source).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)) else null
        }
        instrumentation.addMonitor(monitor)
        BrainStore(context).use {store ->
            val before=store.list().map {it.id}.toSet()
            launch().use {scenario ->try {
                waitText("Cerca nei post");menu("Traduzione")
                rule.onNodeWithContentDescription("Importa testo o audio").performClick()
                rule.waitUntil(15000) {runCatching {rule.onNodeWithTag("conversation-input").assertTextContains("Testo importato: città 🌍")}.isSuccess}
                rule.onNodeWithTag("conversation-send").performClick()
                rule.waitUntil(15000) {store.list().any {it.id !in before && it.type=="translation"}}
                val translation=store.list().single {it.id !in before}
                assertEquals(AppLanguage.deviceTag(context),translation.targetLanguage)
                assertEquals("Testo importato: città 🌍",store.messages(translation.id).single().text)
                rule.onNodeWithContentDescription("Indietro").performClick()
                waitText("Cerca nei post");menu("Chat libera")
                source=android.net.Uri.parse("content://it.supermens.offline.test.documents/audio")
                context.grantUriPermission(context.packageName,source,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                rule.onNodeWithContentDescription("Importa testo o audio").performClick()
                rule.waitUntil(15000) {store.list().any {it.id !in before && it.type=="chat"}}
                val chat=store.list().single {it.id !in before && it.type=="chat"}
                val audio=store.messages(chat.id).single()
                assertEquals("transcribing",audio.state);assertTrue(java.io.File(audio.audioPath).length()>0)
                assertEquals(2,store.list().count {it.id !in before})
            } finally {store.list().filter {it.id !in before}.forEach {ProcessingQueue.remove(context,it.id)}}}
        }
        instrumentation.removeMonitor(monitor)
    }
}
