// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.net.SocketTimeoutException

@Composable internal fun AiReportDialog(response:String,onDismiss:()->Unit,onMessage:(String)->Unit,
    configured:Boolean=AiReport.configured,
    submit:((String,String,String?)->Unit)?=null) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var reason by remember {mutableStateOf("harmful")}
    var includeDiagnostics by remember {mutableStateOf(false)}
    var diagnosticsPreview by remember {mutableStateOf<String?>(null)}
    var consent by remember {mutableStateOf(false)}
    var sending by remember {mutableStateOf(false)}
    var failure by remember {mutableStateOf("")}
    // Editing the preview allows users to redact private passages before transmission.
    var selected by remember {mutableStateOf(response)}
    AlertDialog(onDismissRequest={if(!sending) onDismiss()},title={Text(context.uiString(R.string.report_response))},text={
        Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(context.uiString(R.string.report_explanation))
            if(!configured) Text(context.uiString(R.string.report_not_configured),color=MaterialTheme.colors.error)
            listOf("child_safety" to R.string.report_child,"harmful" to R.string.report_harmful,"deceptive" to R.string.report_deceptive,"other" to R.string.report_other).forEach {(code,label) ->
                Row {RadioButton(selected=reason==code,onClick={reason=code},enabled=!sending);TextButton(onClick={reason=code},enabled=!sending) {Text(context.uiString(label))}}
            }
            OutlinedTextField(selected,{selected=it},Modifier.fillMaxWidth().testTag("report-preview"),enabled=!sending,label={Text(context.uiString(R.string.report_preview))},maxLines=6)
            Row {Checkbox(includeDiagnostics,{
                includeDiagnostics=it
                diagnosticsPreview=if(it) ModelDiagnostics.snapshot(context) else null
            },enabled=!sending,modifier=Modifier.testTag("report-diagnostics"));Text(context.uiString(R.string.report_diagnostics))}
            if(includeDiagnostics) Text(diagnosticsPreview.orEmpty(),style=MaterialTheme.typography.caption)
            Row {Checkbox(consent,{consent=it},enabled=!sending,modifier=Modifier.testTag("report-consent"));Text(context.uiString(R.string.report_consent))}
            if(failure.isNotBlank()) Text(failure,color=MaterialTheme.colors.error)
            if(sending) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    },confirmButton={TextButton(enabled=configured && consent && !sending && selected.isNotBlank() && selected.toByteArray(Charsets.UTF_8).size<=AiReportTransport.MAX_TEXT_BYTES,onClick={
        sending=true;failure=""
        scope.launch {
            runCatching {withContext(Dispatchers.IO) {
                if(submit!=null) submit(reason,selected,diagnosticsPreview)
                else AiReport.send(context,reason,selected,diagnosticsPreview)
            }}
                .onSuccess {onMessage(context.uiString(R.string.report_sent));onDismiss()}
                .onFailure {
                    if(it is CancellationException) throw it
                    failure=context.uiString(when(it) {
                        is SocketTimeoutException -> R.string.report_send_unconfirmed
                        is ReportServiceUnavailable -> R.string.report_service_unavailable
                        else -> R.string.report_send_failed
                    })
                }
            sending=false
        }
    }) {Text(context.uiString(R.string.send))}},dismissButton={TextButton(enabled=!sending,onClick=onDismiss) {Text(context.uiString(R.string.cancel))}})
}
