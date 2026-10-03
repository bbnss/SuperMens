// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class LanguageUiTest {
    @get:Rule val rule=createEmptyComposeRule()
    @get:Rule val locale=AppLocaleRule("en")
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun launch(): ActivityScenario<MainActivity> {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
        return ActivityScenario.launch(MainActivity::class.java)
    }
    private fun waitText(text:String)=rule.waitUntil(15000) {rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}

    @Test fun englishHomeMenuDetailAndSettingsUseTranslatedStrings() {
        val store=BrainStore(context)
        val id=store.add("image","English image test")
        store.addSegments(id,listOf(Segment(0,id,"OCR sample",-1,-1,"ocr"),Segment(0,id,"Photo description",-1,-1,"visione")))
        store.update(id,status="ready")
        val scenario=launch()
        try {
            waitText("English image test")
            rule.onNodeWithText("All").assertIsDisplayed()
            rule.onNodeWithText("Search posts").assertIsDisplayed()
            rule.onNodeWithContentDescription("Add content").performClick()
            listOf("Written note","Voice note","Dictation","Import file","Camera","Paste content").forEach {rule.onNodeWithText(it).assertIsDisplayed()}
            rule.onNodeWithText("Written note").performClick()
            rule.onNodeWithText("Write here").assertIsDisplayed()
            rule.onNodeWithText("Cancel").performClick()
            rule.onNodeWithText("English image test").performClick()
            waitText("Description")
            rule.onNodeWithTag("detail-content").performScrollToNode(hasText("Questions and answers"));rule.onNodeWithText("Questions and answers").assertIsDisplayed()
            rule.onNodeWithTag("detail-content").performScrollToNode(hasText("Copy OCR text"));rule.onNodeWithText("Copy OCR text").performClick()
            rule.onNodeWithText("Text copied").assertIsDisplayed()
            rule.onNodeWithContentDescription("Back").performClick()
            rule.onNodeWithContentDescription("Settings").performClick()
            waitText("Gemma 4 E2B on your phone")
            rule.onNodeWithText("BBNSS").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Made with").assertIsDisplayed()
            rule.onNodeWithContentDescription("Claude Code").assertIsDisplayed()
            if(context.getString(R.string.repository_url).isBlank()) {
                rule.onNodeWithText("BBNSS").performClick()
                rule.onNodeWithText("The repository will be published soon.").assertIsDisplayed()
            }
        } finally {scenario.close();store.delete(id);store.close()}
    }
    @Test fun postsOccupyTwoColumnsInTheSameRow() {
        val store=BrainStore(context)
        val ids=listOf("Grid row A","Grid row B").map {title->store.add("note",title,body="Sample").also {store.update(it,summary="Sample",status="ready")}}
        val scenario=launch()
        try {
            assertEquals(2,store.list("Grid row").size)
            waitText("Grid row B")
            rule.onNodeWithText("Search posts").performTextInput("Grid row")
            rule.onNode(hasSetTextAction()).performImeAction()
            waitText("Grid row A");waitText("Grid row B")
            val a=rule.onNodeWithText("Grid row A").fetchSemanticsNode().boundsInRoot
            val b=rule.onNodeWithText("Grid row B").fetchSemanticsNode().boundsInRoot
            assertTrue("Both cards must be on the same row",abs(a.top-b.top)<2f)
            assertTrue("The cards must occupy separate columns",abs(a.left-b.left)>50f)
        } catch(error: Throwable) {
            rule.onRoot(useUnmergedTree=true).printToLog("supermensGrid")
            instrumentation.uiAutomation.takeScreenshot()?.let {bmp->
                java.io.File(context.cacheDir,"grid-failure.png").outputStream().use {bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                bmp.recycle()
            }
            throw error
        } finally {scenario.close();ids.forEach {store.delete(it)};store.close()}
    }
    @Test fun unsupportedPrimaryLanguageFallsBackToEnglishEvenWithItalianSecond() {
        val manager=context.getSystemService(LocaleManager::class.java)
        fun choose(tags:String) {
            instrumentation.runOnMainSync {manager.applicationLocales=LocaleList.forLanguageTags(tags)}
            instrumentation.waitForIdleSync()
        }
        for(tags in listOf("fr-FR","de-DE","ja-JP","fr-FR,it-IT")) {
            choose(tags)
            assertEquals("en",AppLanguage.code(context))
            assertEquals("Settings",context.uiString(R.string.settings))
            assertEquals("Search posts",context.uiString(R.string.search_posts))
        }
        val scenario=launch()
        try {
            waitText("Search posts")
            rule.onNodeWithContentDescription("Settings").assertIsDisplayed()
            rule.onNodeWithText("Tutti").assertDoesNotExist()
        } finally {scenario.close()}
        choose("it-CH,en-US")
        assertEquals("Impostazioni",context.uiString(R.string.settings))
    }
}
