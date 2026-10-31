// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import androidx.activity.compose.setContent
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Sends one synthetic email only when explicitly requested with -e liveReport true. */
class LiveAiReportTest {
    @get:Rule val rule=createEmptyComposeRule()
    @get:Rule val locale=AppLocaleRule("it")

    @Test fun editedDialogSubmitsOneSyntheticReportToTheConfiguredService() {
        val arguments=InstrumentationRegistry.getArguments()
        assumeTrue("Live email test is opt-in",arguments.getString("liveReport")=="true")
        assertTrue("A verified Apps Script endpoint must be configured",AiReport.configured)
        val marker=arguments.getString("reportMarker") ?: "SM-APP-${System.currentTimeMillis()}"
        require(marker.matches(Regex("SM-APP-[0-9TZ-]+")))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
        val feedback=AtomicReference<String>()
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            scenario.onActivity {activity -> activity.setContent {
                MaterialTheme {
                    // Use the actual Android sender: no mocked configuration or submit callback.
                    AiReportDialog("Bozza sintetica da sostituire prima dell’invio.",{}, {feedback.set(it)})
                }
            }}
            rule.waitUntil(15000) {runCatching {
                rule.onAllNodesWithTag("report-preview").fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)}
            rule.onNodeWithTag("report-consent").assertIsOff()
            rule.onNodeWithTag("report-diagnostics").assertIsOff()
            rule.onNodeWithText(context.uiString(R.string.send)).assertIsNotEnabled()
            rule.onNodeWithText(context.uiString(R.string.report_other)).performScrollTo().performClick()
            rule.onNodeWithTag("report-preview").performScrollTo().performTextReplacement(
                "Prova sintetica inviata dal dialogo Android SuperMens. Codice: $marker. Nessun dato personale. Diagnostica esclusa.")
            rule.onNodeWithTag("report-consent").performScrollTo().performClick()
            rule.onNodeWithText(context.uiString(R.string.send)).performClick()
            val failures=listOf(R.string.report_send_failed,R.string.report_send_unconfirmed,R.string.report_service_unavailable)
            rule.waitUntil(60000) {
                feedback.get()!=null || failures.any {id ->
                    rule.onAllNodesWithText(context.uiString(id)).fetchSemanticsNodes().isNotEmpty()
                }
            }
            assertEquals("Only a positive service reply may confirm submission",context.uiString(R.string.report_sent),feedback.get())
        }
    }
}
