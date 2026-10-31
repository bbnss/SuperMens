// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import androidx.activity.compose.setContent
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AiReportUiTest {
    @get:Rule val rule=createEmptyComposeRule()
    @get:Rule val locale=AppLocaleRule("en")
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun dialog(configured:Boolean=true,onMessage:(String)->Unit={},
                       submit:(String,String,String?)->Unit,check:()->Unit) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            scenario.onActivity {activity -> activity.setContent {
                MaterialTheme {AiReportDialog("Synthetic original",{},onMessage,configured,submit)}
            }}
            rule.waitUntil(15000) {
                runCatching {rule.onAllNodesWithText(context.uiString(R.string.report_response))
                    .fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)
            }
            rule.onNodeWithText(context.uiString(R.string.report_response)).assertIsDisplayed()
            check()
        }
    }
    private fun consentAndSend() {
        rule.onNodeWithTag("report-consent").performScrollTo().performClick()
        rule.onNodeWithText(context.uiString(R.string.send)).performClick()
    }
    @Test fun consentAndDiagnosticsStartOffAndOnlyEditedTextIsSubmitted() {
        val sent=AtomicReference<List<String?>>()
        val message=AtomicReference<String>()
        dialog(onMessage={message.set(it)},submit={reason,text,diagnostics ->sent.set(listOf(reason,text,diagnostics))}) {
            rule.onNodeWithTag("report-consent").assertIsOff()
            rule.onNodeWithTag("report-diagnostics").assertIsOff()
            rule.onNodeWithText(context.uiString(R.string.send)).assertIsNotEnabled()
            rule.onNodeWithTag("report-preview").performTextReplacement("Synthetic redacted")
            consentAndSend()
            rule.waitUntil(5000) {message.get()!=null}
            assertEquals(listOf("harmful","Synthetic redacted",null),sent.get())
            assertEquals(context.uiString(R.string.report_sent),message.get())
        }
    }
    @Test fun optionalDiagnosticsMatchThePreviewExactly() {
        val sent=AtomicReference<String>()
        dialog(submit={_,_,diagnostics ->sent.set(diagnostics)}) {
            rule.onNodeWithTag("report-diagnostics").performScrollTo().performClick()
            val preview=rule.onNodeWithText("\"app_version\"",substring=true).fetchSemanticsNode()
                .config[SemanticsProperties.Text].joinToString("") {it.text}
            consentAndSend()
            rule.waitUntil(5000) {sent.get()!=null}
            assertEquals(preview,sent.get())
            val diagnostics=JSONObject(sent.get())
            listOf("prompt","archive","url","serial","android_id").forEach {assertFalse(diagnostics.has(it))}
        }
    }
    private fun failedSubmission(error:Exception,expected:Int) {
        val attempts=AtomicInteger();val successes=AtomicInteger()
        dialog(onMessage={successes.incrementAndGet()},submit={_,_,_ ->attempts.incrementAndGet();throw error}) {
            consentAndSend()
            rule.waitUntil(5000) {rule.onAllNodesWithText(context.uiString(expected)).fetchSemanticsNodes().isNotEmpty()}
            rule.onNodeWithText(context.uiString(expected)).performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("report-preview").assertTextContains("Synthetic original")
            rule.waitForIdle()
            assertEquals(1,attempts.get());assertEquals(0,successes.get())
        }
    }
    @Test fun timeoutIsUnconfirmedAndDoesNotAutomaticallyRetry() =
        failedSubmission(SocketTimeoutException("synthetic timeout"),R.string.report_send_unconfirmed)
    @Test fun offlineFailureKeepsThePreviewAndDoesNotAutomaticallyRetry() =
        failedSubmission(IOException("synthetic offline"),R.string.report_send_failed)
    @Test fun quotaOrEmailFailureNeverShowsSuccess() =
        failedSubmission(ReportServiceUnavailable(),R.string.report_service_unavailable)
    @Test fun missingEndpointDisablesSendingEvenAfterConsent() {
        dialog(configured=false,submit={_,_,_ ->fail("Must not submit")}) {
            rule.onNodeWithTag("report-consent").performScrollTo().performClick()
            rule.onNodeWithText(context.uiString(R.string.send)).assertIsNotEnabled()
        }
    }
    @Test fun protocolRequiresExplicitPositiveJsonAndPayloadEnforcesBothByteLimits() {
        AiReport.confirm("""{"version":1,"ok":true}""")
        for(reply in listOf("{}","""{"version":1,"ok":"true"}""","""{"version":"1","ok":true}""",
            """{"version":1,"ok":false,"error":"quota_exceeded"}""","<html>login</html>")) {
            assertThrows(Exception::class.java) {AiReport.confirm(reply)}
        }
        val body=JSONObject(String(AiReport.payload("other","Synthetic redacted",null),Charsets.UTF_8))
        assertFalse(body.has("diagnostics"));assertEquals("Synthetic redacted",body.getString("response"))
        assertThrows(IllegalArgumentException::class.java) {AiReport.payload("other","😀".repeat(4001),null)}
        assertThrows(IllegalArgumentException::class.java) {AiReport.payload("other","\u0000".repeat(6000),null)}
    }
}
