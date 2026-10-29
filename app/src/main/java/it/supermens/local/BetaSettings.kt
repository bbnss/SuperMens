// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable internal fun ReportResponseButton(text:String,onMessage:(String)->Unit) {
    val context=LocalContext.current
    var reporting by remember {mutableStateOf(false)}
    if(text.isNotBlank()) TextButton(onClick={reporting=true}) {Text(context.uiString(R.string.report_response))}
    if(reporting) AiReportDialog(text,{reporting=false},onMessage)
}

@Composable internal fun ModelCapabilityNotice() {
    val context=LocalContext.current
    val backend by LocalAi.backend.collectAsState()
    val loaded by LocalAi.loaded.collectAsState()
    if(loaded && backend=="CPU") Text(context.uiString(R.string.backend_cpu_warning),color=MaterialTheme.colors.error)
    else if(loaded && backend=="GPU" && !LocalAi.visionAvailable) Text(context.uiString(R.string.backend_gpu_text_warning),color=appMuted)
    if(DeviceHealth.paused) Text(context.uiString(R.string.wait_cooling),color=MaterialTheme.colors.error)
}

@Composable internal fun BetaSettings() {
    val context=LocalContext.current
    val backend by LocalAi.backend.collectAsState()
    var diagnostics by remember {mutableStateOf<String?>(null)}
    Text(context.uiString(R.string.model_compatibility),color=appMuted)
    val available=runCatching {LocalModel.availableBytes(context)}.getOrDefault(0L)
    Text(context.uiString(R.string.model_storage_status,Formatter.formatFileSize(context,LocalModel.expectedBytes),Formatter.formatFileSize(context,available)),style=MaterialTheme.typography.caption)
    Text(context.uiString(R.string.backend_status,backend))
    ModelCapabilityNotice()
    TextButton(onClick={diagnostics=ModelDiagnostics.snapshot(context)}) {Text(context.uiString(R.string.diagnostics))}
    if(BuildConfig.PRIVACY_EMAIL.isNotBlank()) TextButton(onClick={
        runCatching {context.startActivity(Intent(Intent.ACTION_SENDTO,android.net.Uri.parse("mailto:"+BuildConfig.PRIVACY_EMAIL)))}
    }) {Text(context.uiString(R.string.privacy_contact))}
    diagnostics?.let {snapshot ->AlertDialog(onDismissRequest={diagnostics=null},title={Text(context.uiString(R.string.diagnostics))},text={
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(context.uiString(R.string.diagnostics_explanation));Text(snapshot,style=MaterialTheme.typography.caption)
        }
    },confirmButton={TextButton(onClick={
        runCatching {context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,snapshot),context.uiString(R.string.share_diagnostics)))}
    }) {Text(context.uiString(R.string.share_diagnostics))}},dismissButton={TextButton(onClick={diagnostics=null}) {Text(context.uiString(R.string.close))}})}
}
