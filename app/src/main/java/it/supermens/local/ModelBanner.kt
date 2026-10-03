// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable internal fun ModelBanner(dismissed:Boolean,onDismiss:()->Unit,onError:(String)->Unit) {
    val context=LocalContext.current
    var state by remember {mutableStateOf(LocalModel.state(context))}
    var chooseNetwork by remember {mutableStateOf(false)}
    fun start(mobile:Boolean) {
        chooseNetwork=false
        runCatching {
            if(state.waitingForWifi && mobile) LocalModel.cancel(context)
            LocalModel.start(context,mobile);state=LocalModel.state(context)
        }.onFailure {onError(it.message ?: context.uiString(R.string.download_not_started))}
    }
    LaunchedEffect(Unit) {while(true) {state=LocalModel.state(context);delay(1500)}}
    if(!LocalModel.ready(context) && (!dismissed || state.active)) {
        Surface(color=appAccentSurface,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp)) {
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(context.uiString(R.string.model_banner_title),color=appAccent)
                Text(if(state.active) state.message else context.uiString(R.string.model_banner_hint))
                if(state.active) LinearProgressIndicator(progress=state.progress/100f,modifier=Modifier.fillMaxWidth())
                Row {
                    if(!state.active) TextButton(onClick={
                        val manager=context.getSystemService(ConnectivityManager::class.java)
                        val network=manager.getNetworkCapabilities(manager.activeNetwork)
                        val wifi=network?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true && network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && !manager.isActiveNetworkMetered
                        if(wifi) start(false) else chooseNetwork=true
                    }) {Text(context.uiString(R.string.download_model))}
                    if(state.waitingForWifi) TextButton(onClick={chooseNetwork=true}) {Text(context.uiString(R.string.download_mobile))}
                    if(!state.active) TextButton(onClick=onDismiss) {Text(context.uiString(R.string.later))}
                }
                if(!state.active && LocalModel.downloadId(context)>=0) Text(state.message,color=appMuted)
            }
        }
    }
    if(chooseNetwork) AlertDialog(onDismissRequest={chooseNetwork=false},title={Text(context.uiString(R.string.model_network_title))},
        text={Text(context.uiString(R.string.model_network_explanation))},
        confirmButton={TextButton(onClick={start(true)}) {Text(context.uiString(R.string.download_mobile))}},
        dismissButton={TextButton(onClick={start(false)}) {Text(context.uiString(R.string.download_wifi))}})
}
