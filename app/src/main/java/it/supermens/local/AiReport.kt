// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import android.content.Context
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal object AiReport {
    val configured get()=BuildConfig.REPORT_ENDPOINT.isNotBlank()
    fun send(context:Context,reason:String,response:String,diagnostics:Boolean) {
        require(configured) {context.uiString(R.string.report_not_configured)}
        require(reason in setOf("child_safety","harmful","deceptive","other"))
        require(response.isNotBlank() && response.toByteArray(Charsets.UTF_8).size<=16_000)
        val body=JSONObject().put("version",1).put("reason",reason).put("response",response)
            .put("app_version",BuildConfig.VERSION_NAME)
        if(diagnostics) body.put("diagnostics",JSONObject(ModelDiagnostics.snapshot(context)))
        val connection=URL(BuildConfig.REPORT_ENDPOINT).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects=false
            connection.connectTimeout=15_000;connection.readTimeout=15_000
            connection.requestMethod="POST";connection.doOutput=true
            connection.setRequestProperty("Content-Type","application/json; charset=utf-8")
            val bytes=body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use {it.write(bytes)}
            require(connection.responseCode==201) {context.uiString(R.string.report_send_failed)}
            // No archive, prompt, persistent identifier or background retry is sent.
        } finally {connection.disconnect()}
    }
}
