package it.supermens.local

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

class DetailTest {
    @get:Rule val rule=createEmptyComposeRule()
    @get:Rule val locale=AppLocaleRule("it")
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
            rule.onNodeWithText("Domande e risposte").assertIsDisplayed()
            rule.onNodeWithText("Risposta salvata di prova").assertIsDisplayed()
            rule.onNodeWithText("Segmento 79: "+"Questa è una trascrizione lunga. ".repeat(8)).performScrollTo()
            rule.onNodeWithText("Chiedi sul contenuto").performTextInput("Qual è il contenuto?")
            rule.onNodeWithText("Invia").performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Domande e risposte").assertIsDisplayed()
            rule.onNodeWithText("Risposta salvata di prova").assertIsDisplayed()
        } finally { scenario?.close();store.delete(id);store.close() }
    }
}
