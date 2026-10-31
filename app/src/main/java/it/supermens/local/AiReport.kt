// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import android.content.Context
import org.json.JSONObject
import java.io.IOException

internal object AiReport {
    val configured get()=AiReportTransport.endpointAllowed(BuildConfig.REPORT_ENDPOINT)
    fun payload(reason:String,response:String,diagnostics:String?):ByteArray {
        require(reason in setOf("child_safety","harmful","deceptive","other"))
        require(response.isNotBlank() && response.toByteArray(Charsets.UTF_8).size<=AiReportTransport.MAX_TEXT_BYTES)
        val body=JSONObject().put("version",1).put("reason",reason).put("response",response)
            .put("app_version",BuildConfig.VERSION_NAME)
        if(diagnostics!=null) body.put("diagnostics",JSONObject(diagnostics))
        return body.toString().toByteArray(Charsets.UTF_8).also {require(it.size<=AiReportTransport.MAX_REQUEST_BYTES)}
    }
    fun confirm(reply:String) {
        val value=JSONObject(reply)
        if(value.opt("version")!=1) throw IOException("Invalid report protocol")
        if(value.opt("ok")!=true) {
            when(value.optString("error")) {
                "quota_exceeded","busy","unavailable","email_failed" -> throw ReportServiceUnavailable()
                else -> throw IOException("Report rejected")
            }
        }
    }
    fun send(context:Context,reason:String,response:String,diagnostics:String?) {
        require(configured) {context.uiString(R.string.report_not_configured)}
        confirm(AiReportTransport.submit(BuildConfig.REPORT_ENDPOINT,payload(reason,response,diagnostics)))
        // No archive, prompt, persistent identifier or background retry is sent.
    }
}

internal class ReportServiceUnavailable : IOException("Report service unavailable")
