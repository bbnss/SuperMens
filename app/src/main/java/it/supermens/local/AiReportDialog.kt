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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun AiReportDialog(response:String,onDismiss:()->Unit,onMessage:(String)->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var reason by remember {mutableStateOf("harmful")}
    var includeDiagnostics by remember {mutableStateOf(false)}
    var consent by remember {mutableStateOf(false)}
    var sending by remember {mutableStateOf(false)}
    var failure by remember {mutableStateOf("")}
    // Editing the preview allows users to redact private passages before transmission.
    var selected by remember {mutableStateOf(response)}
    AlertDialog(onDismissRequest={if(!sending) onDismiss()},title={Text(context.uiString(R.string.report_response))},text={
        Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(context.uiString(R.string.report_explanation))
            if(!AiReport.configured) Text(context.uiString(R.string.report_not_configured),color=MaterialTheme.colors.error)
            listOf("child_safety" to R.string.report_child,"harmful" to R.string.report_harmful,"deceptive" to R.string.report_deceptive,"other" to R.string.report_other).forEach {(code,label) ->
                Row {RadioButton(selected=reason==code,onClick={reason=code},enabled=!sending);TextButton(onClick={reason=code},enabled=!sending) {Text(context.uiString(label))}}
            }
            OutlinedTextField(selected,{selected=it},Modifier.fillMaxWidth(),enabled=!sending,label={Text(context.uiString(R.string.report_preview))},maxLines=6)
            Row {Checkbox(includeDiagnostics,{includeDiagnostics=it},enabled=!sending);Text(context.uiString(R.string.report_diagnostics))}
            if(includeDiagnostics) Text(ModelDiagnostics.snapshot(context),style=MaterialTheme.typography.caption)
            Row {Checkbox(consent,{consent=it},enabled=!sending);Text(context.uiString(R.string.report_consent))}
            if(failure.isNotBlank()) Text(failure,color=MaterialTheme.colors.error)
            if(sending) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    },confirmButton={TextButton(enabled=AiReport.configured && consent && !sending && selected.isNotBlank() && selected.toByteArray(Charsets.UTF_8).size<=16_000,onClick={
        sending=true;failure=""
        scope.launch {
            runCatching {withContext(Dispatchers.IO) {AiReport.send(context,reason,selected,includeDiagnostics)}}
                .onSuccess {onMessage(context.uiString(R.string.report_sent));onDismiss()}
                .onFailure {failure=context.uiString(R.string.report_send_failed)}
            sending=false
        }
    }) {Text(context.uiString(R.string.send))}},dismissButton={TextButton(enabled=!sending,onClick=onDismiss) {Text(context.uiString(R.string.cancel))}})
}
