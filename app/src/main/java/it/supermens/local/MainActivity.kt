// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.BatteryManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer as MlSpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val bg=appBackground; private val panel=appPanel; private val accent=appAccent

class MainActivity:ComponentActivity() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguage.context(newBase)) }
    private var shareVersion by mutableIntStateOf(0)
    private val pendingShares=mutableListOf<Intent>()
    private val inFlightShares=mutableSetOf<String>()
    private var recorder:MediaRecorder?=null
    private var recordingFile:File?=null
    private var recording by mutableStateOf(false)
    private val dictationScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var dictation:MlSpeechRecognizer?=null
    private var dictationJob:Job?=null
    private var fallbackRecorder:MediaRecorder?=null
    private var fallbackFile:File?=null
    private var fallbackSaved:((String)->Unit)?=null
    private var dictating by mutableStateOf(false)
    private var dictationStatus by mutableStateOf("")
    private var dictationPreview by mutableStateOf("")
    private var dictationListening=false
    private var dictationStopping=false
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.i("superMensModel", runCatching {
            val model = LocalModel.file(this)
            "file=${model.absolutePath} exists=${model.exists()} bytes=${model.length()} downloadId=${LocalModel.downloadId(this)} state=${LocalModel.state(this)}"
        }.getOrElse { "diagnostic error=${it.message}" })
        if(savedInstanceState==null) receiveShare(intent) else {
            savedInstanceState.getParcelableArrayList("pendingShares",Intent::class.java)?.let {pendingShares.addAll(it)}
            if(pendingShares.isNotEmpty()) shareVersion++
        }
        setContent { App(this) }
    }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent); setIntent(intent);receiveShare(intent) }
    private fun receiveShare(intent:Intent) {
        if(intent.action==Intent.ACTION_SEND || intent.action==Intent.ACTION_SEND_MULTIPLE) pendingShares+=Intent(intent).apply {
            putExtra("supermens_request_id",UUID.randomUUID().toString())
        }
        shareVersion++
    }
    fun shareSignal()=shareVersion
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putParcelableArrayList("pendingShares",ArrayList(pendingShares))
        super.onSaveInstanceState(outState)
    }
    fun consumeShares():List<Intent> = pendingShares.filter {inFlightShares.add(it.getStringExtra("supermens_request_id")!!)}.map {Intent(it)}
    fun acknowledgeShare(requestId:String) {
        pendingShares.removeAll {it.getStringExtra("supermens_request_id")==requestId}
        inFlightShares.remove(requestId)
        if(pendingShares.isEmpty() && (intent.action==Intent.ACTION_SEND || intent.action==Intent.ACTION_SEND_MULTIPLE)) intent=Intent(Intent.ACTION_MAIN)
    }
    fun isRecording()=recording
    fun isDictating()=dictating
    fun isDictationListening()=dictationListening || fallbackRecorder!=null
    fun dictationStatus()=dictationStatus
    fun dictationPreview()=dictationPreview
    fun stopDictation() {
        if(!dictating || dictationStopping) return
        if(fallbackRecorder!=null) {
            val file=fallbackFile
            val stopped=runCatching { fallbackRecorder?.stop();true }.getOrDefault(false)
            fallbackRecorder?.release();fallbackRecorder=null;fallbackFile=null
            dictating=false;dictationStatus="";dictationPreview=""
            if(stopped && file!=null && file.length()>0) {
                val id=BrainStore(this).add("audio",uiString(R.string.dictation_to_transcribe),source="dettatura Gemma",attachment=file.absolutePath)
                Ingest.enqueue(this,id)
                fallbackSaved?.invoke(id)
            } else file?.delete()
            fallbackSaved=null
            return
        }
        if(!dictationListening) {
            dictationJob?.cancel()
            return
        }
        dictationStopping=true
        dictationStatus=uiString(R.string.completing_dictation)
        dictationScope.launch { runCatching { dictation?.stopRecognition() }.onFailure { Log.w("superMensDictation","stopRecognition failed",it) } }
    }
    private fun startFallbackDictation(onSaved:(String)->Unit) {
        val file=File(filesDir,"attachments/${System.currentTimeMillis()}-detta.m4a").apply { parentFile?.mkdirs() }
        val recorder=MediaRecorder(this)
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare();recorder.start()
            fallbackRecorder=recorder;fallbackFile=file;fallbackSaved=onSaved
            dictationStatus=uiString(R.string.mlkit_fallback)
        } catch(e:Exception) { recorder.release();file.delete();throw e }
    }
    fun startDictation(onText:(String)->Unit,onError:(String)->Unit,onFallbackSaved:(String)->Unit) {
        if(dictating) return
        dictating=true;dictationStopping=false;dictationListening=false
        if(ProcessingGuard.busy) {startFallbackDictation(onFallbackSaved);return}
        dictationPreview="";dictationStatus=uiString(R.string.check_speech_model)
        dictationJob=dictationScope.launch {
            var recognizer:MlSpeechRecognizer?=null
            val finalText=StringBuilder()
            var delivered=false
            var receivedSpeech=false
            fun finish() {
                if(delivered) return
                delivered=true
                val text=finalText.toString().trim()
                if(text.isBlank()) onError(uiString(R.string.no_dictation_text))
                else onText(text)
            }
            try {
                val client=SpeechRecognition.getClient(speechRecognizerOptions {
                    locale=Locale.forLanguageTag(if(AppLanguage.code(this@MainActivity)=="it") "it-IT" else "en-US")
                    preferredMode=SpeechRecognizerOptions.Mode.MODE_BASIC
                })
                recognizer=client
                dictation=client
                when(client.checkStatus()) {
                    FeatureStatus.AVAILABLE -> Unit
                    FeatureStatus.DOWNLOADABLE -> {
                        dictationStatus=uiString(R.string.download_speech_model)
                        var downloaded=false
                        client.download().collect { update ->
                            when(update) {
                                is DownloadStatus.DownloadStarted -> dictationStatus=uiString(R.string.download_speech_model)
                                is DownloadStatus.DownloadProgress -> dictationStatus=uiString(R.string.speech_model_progress,update.totalBytesDownloaded / 1_048_576)
                                is DownloadStatus.DownloadCompleted -> downloaded=true
                                is DownloadStatus.DownloadFailed -> throw IllegalStateException(uiString(R.string.speech_download_failed),update.e)
                            }
                        }
                        if(!downloaded || client.checkStatus()!=FeatureStatus.AVAILABLE) throw IllegalStateException(uiString(R.string.speech_not_ready))
                    }
                    FeatureStatus.DOWNLOADING -> throw IllegalStateException(uiString(R.string.speech_downloading))
                    else -> throw IllegalStateException(uiString(R.string.mlkit_unavailable))
                }
                dictationListening=true
                dictationStatus=uiString(R.string.listening)
                client.startRecognition(speechRecognizerRequest { audioSource=AudioSource.fromMic() }).collect { response ->
                    when(response) {
                        is SpeechRecognizerResponse.PartialTextResponse -> { if(response.text.isNotBlank()) receivedSpeech=true;dictationPreview=listOf(finalText.toString(),response.text).filter { it.isNotBlank() }.joinToString(" ") }
                        is SpeechRecognizerResponse.FinalTextResponse -> {
                            val part=response.text.trim()
                            if(part.isNotBlank()) {
                                receivedSpeech=true
                                if(finalText.isNotEmpty()) finalText.append(' ')
                                finalText.append(part)
                                dictationPreview=finalText.toString()
                            }
                        }
                        is SpeechRecognizerResponse.CompletedResponse -> { if(finalText.isBlank() && dictationPreview.isNotBlank()) finalText.append(dictationPreview);finish() }
                        is SpeechRecognizerResponse.ErrorResponse -> throw response.e
                    }
                }
                finish()
            } catch(e:CancellationException) {
                throw e
            } catch(e:Exception) {
                Log.w("superMensDictation","ML Kit Basic failed",e)
                if(!delivered) {
                    if(finalText.isNotBlank()) finish()
                    else if(!receivedSpeech) runCatching { startFallbackDictation(onFallbackSaved) }.onFailure { onError(uiString(R.string.dictation_unavailable,it.message ?: e.message.orEmpty())) }
                    else onError(uiString(R.string.dictation_interrupted,e.message ?: uiString(R.string.unknown_error)))
                }
            } finally {
                dictationListening=false;dictationStopping=false
                if(fallbackRecorder==null) {dictating=false;dictationStatus="";dictationPreview=""}
                if(dictation===recognizer) dictation=null
                recognizer?.close()
                dictationJob=null
            }
        }
    }
    fun startRecording() {
        val f=File(filesDir,"attachments/${System.currentTimeMillis()}.m4a");f.parentFile?.mkdirs()
        val r=MediaRecorder(this)
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC);r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);r.setOutputFile(f.absolutePath)
            r.prepare();r.start();recorder=r;recordingFile=f;recording=true
        } catch(e:Exception) {r.release();f.delete();throw e}
    }
    fun stopRecording():File? {
        val f=recordingFile
        val stopped=runCatching {recorder?.stop();true}.getOrDefault(false)
        recorder?.release();recorder=null;recordingFile=null;recording=false
        if(!stopped) f?.delete()
        return f?.takeIf { stopped && it.exists() && it.length()>0 }
    }
    override fun onStop() {
        stopDictation()
        if(recording) stopRecording()?.let { file ->
            val id=BrainStore(this).add("audio",uiString(R.string.voice_note),attachment=file.absolutePath)
            Ingest.enqueue(this,id)
        }
        super.onStop()
    }
    override fun onDestroy() { recorder?.release();fallbackRecorder?.release();dictationJob?.cancel();dictationScope.cancel();super.onDestroy() }
}

@Composable private fun App(activity:MainActivity) {
    val context=LocalContext.current;val store=remember { BrainStore(context) };val scope=rememberCoroutineScope()
    var current by rememberSaveable { mutableStateOf<String?>(null) };var screen by rememberSaveable { mutableStateOf("home") };var refresh by remember { mutableIntStateOf(0) };var message by remember { mutableStateOf("") }
    var showNote by rememberSaveable { mutableStateOf(false) };var noteText by rememberSaveable { mutableStateOf("") };var query by rememberSaveable { mutableStateOf("") };var filter by rememberSaveable { mutableStateOf("all") }
    val items by produceState<List<BrainItem>>(emptyList(),refresh,query,filter,screen) { value=withContext(Dispatchers.IO){store.list(query,filter)} }
    val archive by produceState<List<BrainItem>>(emptyList(),refresh) { value=withContext(Dispatchers.IO){store.list()} }
    val previews by produceState<Map<String,String>>(emptyMap(),items,refresh) { value=withContext(Dispatchers.IO){items.associate { it.id to PostContent.preview(it,if(it.type=="image") store.segments(it.id) else emptyList()) }} }
    val queueJobs by produceState<List<ProcessingJob>>(emptyList(),refresh) { value=withContext(Dispatchers.IO) { ProcessingQueue.jobs(store) } }
    val queuePositions=queueJobs.filter {it.kind!="acquire" && it.state=="waiting"}.sortedWith(compareByDescending<ProcessingJob> {it.manual}.thenBy {job ->if(job.manual) job.createdAt else archive.firstOrNull {it.id==job.itemId}?.createdAt ?: job.createdAt}).mapIndexed {index,job ->job.id to index+1}.toMap()
    val entries=archive.mapNotNull { item ->
        val jobs=queueJobs.filter {it.itemId==item.id && it.state in setOf("waiting","running","failed")}
        val running=jobs.firstOrNull {it.state=="running"}
        val waiting=jobs.firstOrNull {it.state=="waiting"}
        when {
            running!=null -> ActivityEntry(item,running.progress.ifBlank {if(running.kind=="question") context.uiString(R.string.queued) else PostContent.stage(item,AppLanguage.code(context))},running=true)
            waiting!=null -> ActivityEntry(item,when {
                waiting.kind=="acquire" -> context.uiString(R.string.acquiring_source)
                !ProcessingQueue.eligible(context,waiting) -> context.uiString(R.string.wait_charging)
                !LocalModel.ready(context) -> context.uiString(R.string.install_model_hint)
                else -> context.uiString(R.string.queue_position,queuePositions[waiting.id] ?: 1)
            })
            item.status.startsWith("processing") && jobs.isEmpty() -> ActivityEntry(item,PostContent.stage(item,AppLanguage.code(context)),running=true)
            jobs.any {it.state=="failed"} || PostContent.failed(item) -> ActivityEntry(item,PostContent.stage(item,AppLanguage.code(context)),failed=true)
            else -> null
        }
    }.sortedWith(compareBy<ActivityEntry> {if(it.running) 0 else if(it.failed) 1 else 2}.thenBy {it.item.createdAt})
    var modelBannerDismissed by rememberSaveable {mutableStateOf(false)}
    var savingNote by remember {mutableStateOf(false)}
    var showActivities by remember { mutableStateOf(false) }
    var lastError by rememberSaveable { mutableStateOf("") }
    fun reportError(text: String) { lastError=text.ifBlank { context.uiString(R.string.operation_failed) };message=lastError }
    val snackbar=remember { SnackbarHostState() }
    LaunchedEffect(message) { if(message.isNotBlank()) { val shown=message;snackbar.showSnackbar(shown,duration=SnackbarDuration.Short);if(message==shown) message="" } }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraRetry by rememberSaveable { mutableStateOf(false) }
    var cameraSaving by rememberSaveable { mutableStateOf(false) }
    fun saveCamera() {
        val path=cameraPath ?: return
        if(cameraSaving) return
        cameraSaving=true;cameraRetry=false
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { CameraCapture.save(context,store,File(path)) } }
                .onSuccess { cameraPath=null;refresh++;message=context.uiString(R.string.photo_saved) }
                .onFailure { reportError(it.message ?: context.uiString(R.string.save_photo_failed));cameraRetry=true }
            cameraSaving=false
        }
    }
    LaunchedEffect(Unit) { if(cameraSaving && cameraPath!=null) { cameraSaving=false;saveCamera() } }
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success->
        if(success) saveCamera() else { cameraPath?.let { File(it).delete() };cameraPath=null }
    }
    fun launchCamera() {
        if(cameraSaving) return
        if(cameraPath!=null) { cameraRetry=true;return }
        runCatching {
            val file=CameraCapture.create(context);cameraPath=file.absolutePath
            camera.launch(CameraCapture.uri(context,file))
        }.onFailure { cameraPath?.let { File(it).delete() };cameraPath=null;reportError(context.uiString(R.string.camera_unavailable,it.message.orEmpty())) }
    }
    fun stopVoice() {
        if(activity.isRecording()) {
            val file=activity.stopRecording()
            if(file==null) { reportError(context.uiString(R.string.empty_recording));return }
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { val id=store.add("audio",context.uiString(R.string.voice_note),attachment=file.absolutePath);Ingest.enqueue(context,id);id } }
                    .onSuccess { refresh++;message=context.uiString(R.string.voice_note_saved) }.onFailure { reportError(it.message.orEmpty()) }
            }
        } else if(activity.isDictating()) activity.stopDictation()
    }
    var fileRequest by rememberSaveable {mutableStateOf<String?>(null)}
    var pendingFiles by rememberSaveable {mutableStateOf(arrayListOf<String>())}
    val filePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {uris ->
        if(fileRequest!=null) {
            uris.forEach {if(it.scheme=="content") DocumentStorage.persist(context,it)}
            pendingFiles=ArrayList(uris.map {it.toString()})
            if(uris.isEmpty()) fileRequest=null
        }
    }
    LaunchedEffect(fileRequest,pendingFiles) {
        val request=fileRequest
        if(request!=null && pendingFiles.isNotEmpty()) {
            val results=pendingFiles.mapIndexed {index,reference ->
                runCatching {withContext(Dispatchers.IO) {Ingest.once(store,"$request:$index") {listOf(Ingest.file(context,store,Uri.parse(reference)))}}}
                    .onFailure {if(it is CancellationException) throw it}
            }
            refresh++;message=context.uiString(R.string.file_import_count,results.count {it.isSuccess},results.size)
            results.firstOrNull {it.isFailure}?.exceptionOrNull()?.let {reportError(it.message ?: context.uiString(R.string.import_failed))}
            pendingFiles=arrayListOf();fileRequest=null
        }
    }
    var relinkTarget by remember { mutableStateOf<String?>(null) }
    val pdfPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri->
        val target=relinkTarget;relinkTarget=null
        if(uri!=null && target!=null) scope.launch(Dispatchers.IO) {
            runCatching { Ingest.relinkPdf(context,store,target,uri) }
                .onSuccess { withContext(Dispatchers.Main){refresh++;message=context.uiString(R.string.pdf_relinked)} }
                .onFailure { withContext(Dispatchers.Main){reportError(it.message ?: context.uiString(R.string.pdf_relink_failed))} }
        }
    }
    val videoPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->
        val target=relinkTarget;relinkTarget=null
        if(uri!=null && target!=null) scope.launch {
            runCatching {withContext(Dispatchers.IO) {Ingest.relinkVideo(context,store,target,uri)}}
                .onSuccess {refresh++;message=context.uiString(R.string.queued)}.onFailure {reportError(it.message.orEmpty())}
        }
    }
    val transcriptPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri-> if(uri!=null && current!=null) scope.launch(Dispatchers.IO) { runCatching { val targetId=current!!;val raw=context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }; val segments=YoutubeCaptions.parse(raw).ifEmpty { listOf(Segment(0,targetId,raw,-1,-1,"import")) };store.addSegments(targetId,segments.map { it.copy(itemId=targetId,source="import") });Ingest.enqueue(context,targetId,true,manual=true) }.onSuccess { withContext(Dispatchers.Main){refresh++;message=context.uiString(R.string.transcript_imported)} }.onFailure { withContext(Dispatchers.Main){reportError(it.message ?: context.uiString(R.string.import_failed))} } } }
    val exportPicker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri->if(uri!=null) scope.launch(Dispatchers.IO){runCatching { Backup.export(context,store,uri) }.onSuccess{withContext(Dispatchers.Main){message=context.uiString(R.string.archive_exported)}}.onFailure{withContext(Dispatchers.Main){reportError(it.message.orEmpty())}}} }
    val importPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri->if(uri!=null) scope.launch(Dispatchers.IO){runCatching { Backup.import(context,store,uri).also {Ingest.reschedulePending(context,store)} }.onSuccess{withContext(Dispatchers.Main){refresh++;message=context.uiString(R.string.items_imported,it)}}.onFailure{withContext(Dispatchers.Main){reportError(it.message.orEmpty())}}} }
    val modelPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri->if(uri!=null) scope.launch(Dispatchers.IO){runCatching {LocalModel.import(context,uri)}.onSuccess { withContext(Dispatchers.Main){refresh++;message=context.uiString(R.string.model_imported)} }.onFailure { withContext(Dispatchers.Main){reportError(it.message ?: context.uiString(R.string.model_import_failed))} }} }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted->if(granted) runCatching {activity.startRecording()}.onFailure{reportError(it.message.orEmpty())} else reportError(context.uiString(R.string.microphone_denied)) }
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val dictationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted->
        if(granted) runCatching {activity.startDictation({ spoken->scope.launch(Dispatchers.IO){val id=store.add("note",spoken.lineSequence().first().take(90),body=spoken,source="dettatura ML Kit Basic");Ingest.enqueue(context,id);withContext(Dispatchers.Main){refresh++;message=context.uiString(R.string.dictated_note_saved)}} },{reportError(it)},{refresh++;message=context.uiString(R.string.dictation_audio_saved)})}.onFailure{reportError(it.message.orEmpty())}
        else reportError(context.uiString(R.string.microphone_denied))
    }
    LaunchedEffect(screen) { while(true) { delay(3500);if(screen=="home" || screen=="detail") refresh++ } }
    LaunchedEffect(screen) { if(screen!="home" && activity.isDictating()) activity.stopDictation() }
    LaunchedEffect(Unit) { var wasReady=false;while(true) {val ready=LocalModel.ready(context);if(ready && !wasReady) ProcessingQueue.wakeProcessing(context);wasReady=ready;delay(5000)} }
    LaunchedEffect(Unit) { if(ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    LaunchedEffect(activity.shareSignal()) {
        // Preserve every incoming intent, even when several arrive before Compose updates.
        val shares=activity.consumeShares()
        if(shares.isNotEmpty()) scope.launch {
            for(intent in shares) {
                runCatching {withContext(Dispatchers.IO){Ingest.once(store,intent.getStringExtra("supermens_request_id")!!) {Ingest.intake(context,store,intent)}}}
                    .onSuccess {ids ->if(ids.isNotEmpty()) {refresh++;screen="home";current=ids.first();message=context.uiString(R.string.content_saved)}}
                    .onFailure {if(it is CancellationException) throw it;reportError(it.message.orEmpty())}
                activity.acknowledgeShare(intent.getStringExtra("supermens_request_id")!!)
            }
        }
    }
    BackHandler(enabled=screen!="home") { screen="home";current=null }
    MaterialTheme(colors=darkColors(primary=accent,background=bg,surface=panel,onSurface=Color.White)) {
        Scaffold(modifier=Modifier.statusBarsPadding().navigationBarsPadding(),snackbarHost={SnackbarHost(snackbar,Modifier.imePadding().padding(bottom=if(screen=="home" || screen=="detail") 84.dp else 12.dp))},topBar={
            Row(Modifier.fillMaxWidth().height(56.dp).background(bg).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                if(screen=="home") {
                    Text(context.uiString(R.string.post_count,archive.size),Modifier.weight(1f),fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                    if(lastError.isNotBlank()) IconButton(onClick={showActivities=true}) { UiIcon(R.drawable.ui_warning,context.uiString(R.string.show_last_error),tint=Color(0xFFFFB5A5)) }
                    IconButton(onClick={screen="settings"}) { UiIcon(R.drawable.ui_settings,context.uiString(R.string.settings)) }
                } else {
                    IconButton(onClick={screen="home";current=null}) { UiIcon(R.drawable.ui_back,context.uiString(R.string.back)) }
                    Text(if(screen=="settings") context.uiString(R.string.settings) else context.uiString(R.string.post_detail),fontSize=16.sp,color=appMuted)
                }
            }
        },backgroundColor=bg) { pad->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when(screen) {
                    "home"-> HomeScreen(items,previews,archive.size,query,{query=it},filter,{filter=it},entries,activity.isRecording(),activity.isDictating(),activity.dictationStatus(),cameraSaving,{showActivities=true},{stopVoice()},
                        {current=it.id;screen="detail"},{showNote=true},
                        {if(!activity.isDictating()) permission.launch(Manifest.permission.RECORD_AUDIO)},
                        {if(!activity.isRecording()) dictationPermission.launch(Manifest.permission.RECORD_AUDIO)},
                        {fileRequest=UUID.randomUUID().toString();filePicker.launch(arrayOf("application/pdf","image/*","audio/*","video/*","text/*"))},
                        {launchCamera()},
                        {
                            val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val pasted=clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().trim()
                            if(pasted.isNotBlank()) scope.launch {
                                runCatching { withContext(Dispatchers.IO){Ingest.text(context,store,pasted)} }
                                    .onSuccess {refresh++;message=context.uiString(R.string.pasted_saved)}.onFailure {reportError(it.message.orEmpty())}
                            } else message=context.uiString(R.string.clipboard_empty)
                        }, modelBanner={ModelBanner(modelBannerDismissed,{modelBannerDismissed=true},::reportError)})
                    "detail"-> current?.let { id->Detail(context,store,id,refresh,{refresh++},{message=it},{reportError(it)},{transcriptPicker.launch(arrayOf("text/*","application/x-subrip"))},{relinkTarget=id;pdfPicker.launch(arrayOf("application/pdf"))},{relinkTarget=id;videoPicker.launch(arrayOf("video/*"))},{screen="home";current=null;refresh++}) }
                    "settings"->SettingsScreen({message=it},{reportError(it)},{exportPicker.launch("supermens-locale-${System.currentTimeMillis()}.zip")},{importPicker.launch(arrayOf("application/zip"))},{modelPicker.launch(arrayOf("application/octet-stream","*/*"))})
                }
            }
        }
        if(showActivities) AlertDialog(onDismissRequest={showActivities=false},title={Text(context.uiString(R.string.activities))},text={
            Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                if(activity.isRecording() || activity.isDictating()) {
                    Text(if(activity.isRecording()) context.uiString(R.string.recording_status) else activity.dictationStatus(),color=accent)
                    if(activity.dictationPreview().isNotBlank()) Text(activity.dictationPreview())
                    TextButton(onClick={stopVoice()}) { Text(if(activity.isRecording()) context.uiString(R.string.save_voice_note) else context.uiString(R.string.end_dictation)) }
                }
                if(cameraSaving) Text(context.uiString(R.string.saving_photo),color=accent)
                if(lastError.isNotBlank()) {
                    Text(lastError,color=Color(0xFFFFB5A5))
                    TextButton(onClick={lastError=""}) { Text(context.uiString(R.string.hide_error)) }
                }
                entries.forEach { entry->
                    Column(Modifier.fillMaxWidth().clickable {current=entry.item.id;screen="detail";showActivities=false}) {
                        Text(entry.item.title,fontWeight=FontWeight.SemiBold)
                        Text(entry.text,fontSize=13.sp,color=appMuted)
                        if(entry.failed && entry.item.status.contains(" · ")) Text(entry.item.status.substringAfter(" · "),fontSize=12.sp,color=Color(0xFFFFB5A5))
                    }
                }
                if(entries.isEmpty() && !activity.isRecording() && !activity.isDictating() && lastError.isBlank() && !cameraSaving) Text(context.uiString(R.string.no_activities),color=appMuted)
            }
        },confirmButton={TextButton(onClick={showActivities=false}){Text(context.uiString(R.string.close))}})
        if(cameraRetry && cameraPath!=null) AlertDialog(onDismissRequest={cameraRetry=false},title={Text(context.uiString(R.string.photo_to_save))},text={Text(context.uiString(R.string.photo_retry_explanation))},confirmButton={TextButton(onClick={saveCamera()}){Text(context.uiString(R.string.retry))}},dismissButton={TextButton(onClick={cameraPath?.let { File(it).delete() };cameraPath=null;cameraRetry=false}){Text(context.uiString(R.string.delete_capture))}})
        if(showNote) TextEntryDialog(context.uiString(R.string.new_note),noteText,{noteText=it},savingNote,onSave={
            if(!savingNote && noteText.isNotBlank()) {
                savingNote=true
                scope.launch {
                    runCatching {withContext(Dispatchers.IO) {Ingest.text(context,store,noteText)}}
                        .onSuccess {refresh++;noteText="";showNote=false;message=context.uiString(R.string.note_saved)}
                        .onFailure {reportError(it.message.orEmpty())}
                    savingNote=false
                }
            }
        },onDismiss={showNote=false})
    }

}

@Composable private fun Detail(context:android.content.Context,store:BrainStore,id:String,refresh:Int,onRefresh:()->Unit,onMessage:(String)->Unit,onError:(String)->Unit,onImportTranscript:()->Unit,onRelinkPdf:()->Unit,onRelinkVideo:()->Unit,onDelete:()->Unit) {
    val scope=rememberCoroutineScope()
    val item by produceState<BrainItem?>(null,id,refresh){value=withContext(Dispatchers.IO){store.get(id)}}
    val segments by produceState<List<Segment>>(emptyList(),id,refresh){value=withContext(Dispatchers.IO){store.segments(id)}}
    val answers by produceState<List<SavedAnswer>>(emptyList(),id,refresh){value=withContext(Dispatchers.IO){store.answers(id)}}
    val textSegments=PostContent.textSegments(item?.type.orEmpty(),segments)
    val description=PostContent.description(segments)
    val copyText=item?.let { PostContent.copyText(it,segments) }.orEmpty()
    fun copyContent() {
        if(copyText.isBlank()) return
        val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(item?.title ?: context.uiString(R.string.text_label),copyText))
        onMessage(context.uiString(R.string.text_copied))
    }
    var question by remember(id){mutableStateOf("")}
    val detailJobs by produceState<List<ProcessingJob>>(emptyList(),id,refresh) { value=withContext(Dispatchers.IO) { ProcessingQueue.jobs(store).filter {it.itemId==id} } }
    val activeJob=detailJobs.firstOrNull {it.state=="running"} ?: detailJobs.firstOrNull {it.state=="waiting"}
    val busy=activeJob!=null
    val pendingQuestion=detailJobs.firstOrNull {it.kind=="question" && it.state in setOf("waiting","running")}?.payload
    val answerProgress=detailJobs.firstOrNull {it.kind=="question" && it.state in setOf("waiting","running")}?.progress?.ifBlank {context.uiString(R.string.queued)}.orEmpty()
    var sharing by remember(id) {mutableStateOf(false)}
    var deleting by remember(id) {mutableStateOf(false)}
    var shareMenu by remember(id) {mutableStateOf(false)}
    var integrateText by remember(id) {mutableStateOf(false)}
    var addedText by rememberSaveable(id) {mutableStateOf("")}
    fun share(pdf:Boolean) {
        sharing=true;shareMenu=false
        scope.launch {
            runCatching {withContext(Dispatchers.IO) {PostShare.prepare(context,store,id,pdf)}}
                .onSuccess {context.startActivity(Intent.createChooser(it,context.uiString(R.string.share_post)))}
                .onFailure {onError(it.message.orEmpty())}
            sharing=false
        }
    }
    var scrollRequest by remember(id){mutableIntStateOf(0)}
    val scrollState=key(id){rememberLazyListState()}
    val focusManager=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    LaunchedEffect(id,scrollRequest) {
        if(scrollRequest>0) {
            withFrameNanos { }
            scrollState.animateScrollToItem(1)
        }
    }
    LaunchedEffect(pendingQuestion,answers.size) {if(pendingQuestion!=null || scrollRequest>0) {withFrameNanos {};scrollState.animateScrollToItem(1)}}
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var showFullBody by rememberSaveable(id){mutableStateOf(false)}
    val displayBlocks=remember(textSegments,copyText) {TranscriptDisplay.blocks(textSegments,copyText)}
    val expandable=TranscriptDisplay.expandable(displayBlocks)
    val shownBlocks=if(showFullBody) displayBlocks else TranscriptDisplay.preview(displayBlocks)
    DisposableEffect(id){onDispose {player?.release()} }
    item?.let { i->
        val language=AppLanguage.code(context)
        fun ask() {
            val q=question.trim()
            if(q.isBlank() || busy) return
            focusManager.clearFocus();keyboard?.hide();scrollRequest++
            scope.launch {
                withContext(Dispatchers.IO) {ProcessingQueue.question(context,id,q)}
                question="";onRefresh()
            }
        }
        Column(Modifier.fillMaxSize().imePadding()) {
            LazyColumn(state=scrollState,modifier=Modifier.weight(1f).fillMaxWidth().testTag("detail-content"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item(key="header") { Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(i.type=="note" || i.type=="audio" || (i.type=="video" && i.thumbnail.isBlank())) {
                    Box(Modifier.size(54.dp).background(appAccentSurface,RoundedCornerShape(16.dp)),contentAlignment=Alignment.Center){UiIcon(typeIcon(i.type),modifier=Modifier.size(28.dp))}
                } else PostPreview(i,Modifier.fillMaxWidth().height(180.dp))
                Text(i.title,fontSize=22.sp,fontWeight=FontWeight.Bold)
                Text("${typeLabel(context,i.type)} · ${DateFormat.getDateTimeInstance(DateFormat.DEFAULT,DateFormat.DEFAULT,Locale.forLanguageTag(AppLanguage.code(context))).format(Date(i.createdAt))}",fontSize=12.sp,color=Color.LightGray)
                Box {
                    TextButton(enabled=!sharing,onClick={shareMenu=true}) {Text(if(sharing) context.uiString(R.string.preparing_share) else context.uiString(R.string.share_post))}
                    DropdownMenu(shareMenu,{shareMenu=false}) {
                        DropdownMenuItem(onClick={share(false)}) {Text(context.uiString(R.string.share_photo_text))}
                        DropdownMenuItem(onClick={share(true)}) {Text(context.uiString(R.string.share_pdf))}
                    }
                }
                if(i.sourceQuality.endsWith("_preview_failed")) Text(context.uiString(R.string.preview_unavailable),fontSize=12.sp,color=appMuted)
                if((i.type=="x" || PublicPage.supportedHost(i.sourceUrl)) && !i.sourceQuality.startsWith("complete")) {
                    Text(context.uiString(R.string.source_incomplete),fontSize=12.sp,color=appMuted)
                    TextButton(onClick={integrateText=true}) {Text(context.uiString(R.string.integrate_text))}
                }
                if(i.sourceUrl.isNotBlank()) TextButton(onClick={runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(i.sourceUrl)))}}) {Text(context.uiString(R.string.open_source))}
                if(i.type in setOf("pdf","video") || i.attachment.isNotBlank()) {
                    TextButton(onClick={
                        runCatching {context.startActivity(PostFile.intent(context,i))}.onFailure {error ->
                            if(error is android.content.ActivityNotFoundException) onError(context.uiString(R.string.no_file_viewer))
                            else {onError(error.message.orEmpty());if(i.type=="pdf") onRelinkPdf() else if(i.type=="video") onRelinkVideo()}
                        }
                    }) {Text(context.uiString(R.string.open_file))}
                }
                if(i.type=="video" && i.attachment.isBlank()) {
                    TextButton(enabled=!busy,onClick=onRelinkVideo) {Text(context.uiString(R.string.relink_video))}
                    if(i.documentUri.isBlank()) Text(context.uiString(R.string.video_relink_hint),fontSize=12.sp,color=appMuted)
                }
                if(i.type=="pdf") {
                    TextButton(enabled=!busy,onClick=onRelinkPdf) {Text(context.uiString(R.string.relink_pdf))}
                    if(i.documentUri.isBlank() && i.attachment.isBlank()) Text(context.uiString(R.string.original_not_retained),fontSize=12.sp,color=appMuted)
                    if(i.pdfPages>0) Text(context.uiString(R.string.pdf_page_count,i.pdfPages,if(i.documentUri.isNotBlank()) context.uiString(R.string.original_linked) else if(i.attachment.isNotBlank()) context.uiString(R.string.copy_kept) else context.uiString(R.string.original_not_retained)),fontSize=12.sp,color=appMuted)
                }
                if((i.type=="audio" || (i.type=="note" && i.source=="dettatura Gemma")) && i.attachment.isNotBlank()) TextButton(onClick={
                    runCatching {player?.release();player=MediaPlayer().apply {setDataSource(i.attachment);prepare();start()}}.onFailure {onError(it.message.orEmpty())}
                }) {Text(context.uiString(R.string.listen))}
                if(i.type=="youtube") TextButton(onClick=onImportTranscript){Text(context.uiString(R.string.import_transcript))}
                Surface(color=appAccentSurface,shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        val summaryText=if(i.type=="image") description else i.summary
                        if(summaryText.isNotBlank()) TextButton(onClick={
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(i.title,summaryText));onMessage(context.uiString(R.string.text_copied))
                        }) {UiIcon(R.drawable.ui_copy,modifier=Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(context.uiString(if(i.type=="image") R.string.copy_description else R.string.copy_summary))}
                        Text(if(i.type=="image") context.uiString(R.string.description) else if(i.summary.isBlank() && i.status.startsWith("pending_ai")) context.uiString(R.string.pending) else context.uiString(R.string.summary),fontWeight=FontWeight.Bold,color=accent)
                        Text(if(i.type=="image") description.ifBlank {
                            if(PostContent.failed(i)) context.uiString(R.string.description_failed)
                            else if(i.status.startsWith("processing")) context.uiString(R.string.description_preparing)
                            else context.uiString(R.string.description_pending)
                        } else i.summary.ifBlank { context.uiString(R.string.processing) },fontSize=14.sp)
                        if(detailJobs.any {it.kind!="question" && it.state in setOf("waiting","running","failed")} && (i.status.startsWith("processing") || i.status.startsWith("pending_ai") || PostContent.failed(i))) Text(PostContent.stage(i,AppLanguage.code(context)),fontSize=12.sp,color=appMuted)
                        if(i.status.contains(" · ")) Text(i.status.substringAfter(" · "),fontSize=11.sp,color=appMuted)
                    }
                }
                } }
                item(key="answers") { Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                detailJobs.filter {it.kind=="question" && it.state=="failed"}.takeLast(1).forEach {Text(context.uiString(R.string.question_failed)+": "+it.progress,fontSize=12.sp,color=Color(0xFFFF8B8B))}
                if(i.body.isNotBlank() || answers.isNotEmpty()) {
                    Text(context.uiString(R.string.questions_answers),fontWeight=FontWeight.Bold)
                    pendingQuestion?.let { q->
                        Surface(color=panel,shape=RoundedCornerShape(12.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text(q,fontWeight=FontWeight.Bold)
                                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
                                    Text(answerProgress,fontSize=13.sp,color=Color.LightGray)
                                }
                            }
                        }
                    }
                    if(pendingQuestion==null && answers.isEmpty()) Text(context.uiString(R.string.answers_placeholder),fontSize=12.sp,color=Color.LightGray)
                    answers.forEach { a->Surface(color=panel,shape=RoundedCornerShape(12.dp)){Column(Modifier.fillMaxWidth().padding(12.dp)){Text(a.question,fontWeight=FontWeight.Bold);Spacer(Modifier.height(6.dp));Text(a.answer);Text(a.model,fontSize=10.sp,color=Color.Gray)}} }
                }
                } }
                item(key="text-header") { Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(i.type=="youtube" && segments.isEmpty()) Text(if(i.status.startsWith("youtube_no_captions")) context.uiString(R.string.no_captions_hint) else context.uiString(R.string.captions_failed_hint),fontSize=12.sp,color=Color.LightGray)
                if(textSegments.isNotEmpty() || copyText.isNotBlank()) {
                    val transcript=i.type in setOf("youtube","audio","video") || (i.type=="note" && i.source=="dettatura Gemma")
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(if(i.type=="image") context.uiString(R.string.detected_text) else if(transcript) context.uiString(R.string.full_transcript) else if(textSegments.isNotEmpty()) context.uiString(R.string.extracted_text) else context.uiString(R.string.original_content),Modifier.weight(1f),fontWeight=FontWeight.Bold)
                        if(copyText.isNotBlank()) TextButton(onClick={copyContent()}) {
                            UiIcon(R.drawable.ui_copy,modifier=Modifier.size(18.dp));Spacer(Modifier.width(6.dp))
                            Text(if(i.type=="image") context.uiString(R.string.copy_ocr) else if(transcript) context.uiString(R.string.copy_transcript) else context.uiString(R.string.copy_text),fontSize=12.sp)
                        }
                    }
                    if(textSegments.isNotEmpty() && i.type!="image") Text(context.uiString(R.string.segment_count,textSegments.size,textSegments.count {it.startMs>=0}),fontSize=12.sp,color=appMuted)
                } else if(i.type=="image" && !i.status.startsWith("processing") && i.status!="saved") Text(context.uiString(R.string.no_image_text),fontSize=13.sp,color=appMuted)
                } }
                items(shownBlocks.size,key={"text-$it"}) {index ->
                    val segment=shownBlocks[index]
                    val marker=when {segment.startMs>=0 -> "%02d:%02d:%02d".format(segment.startMs/3600000,(segment.startMs/60000)%60,(segment.startMs/1000)%60);segment.page>=0 && i.type=="pdf" ->context.uiString(R.string.page_label,segment.page);else->""}
                    Column {
                        if(marker.isNotBlank()) Text(marker,Modifier.clickable(enabled=i.type=="youtube" && segment.startMs>=0) {
                            runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(i.sourceUrl+if(i.sourceUrl.contains('?')) "&t=${segment.startMs/1000}s" else "?t=${segment.startMs/1000}s")))}
                        },fontSize=12.sp,color=accent)
                        Text(segment.text,fontSize=14.sp)
                    }
                }
                item(key="text-controls") {
                    if(expandable) Row {
                        TextButton(onClick={showFullBody=!showFullBody;if(!showFullBody) scope.launch {scrollState.scrollToItem(2)}}) {Text(context.uiString(if(showFullBody) R.string.show_less else R.string.show_more))}
                        if(showFullBody) TextButton(onClick={scope.launch {scrollState.scrollToItem(0)}}) {Text(context.uiString(R.string.back_to_top))}
                    }
                }
                item(key="actions") { Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(horizontalAlignment=Alignment.Start) {
                    if(i.type!="image") TextButton(enabled=!busy && i.body.isNotBlank(),onClick={
                        scope.launch {withContext(Dispatchers.IO) {ProcessingQueue.summary(context,id)};onRefresh();onMessage(context.uiString(R.string.queued))}
                    }) {Text(context.uiString(R.string.regenerate_summary))}
                    if(i.type=="youtube") TextButton(enabled=!busy,onClick={Ingest.enqueue(context,id,false,manual=true);onMessage(context.uiString(R.string.captions_retry_started))}){Text(context.uiString(R.string.retry_captions))}
                    if(i.type in setOf("pdf","image","web","instagram","x","facebook","tiktok","transcript")) TextButton(enabled=!busy,onClick={Ingest.enqueue(context,id,false,manual=true);onMessage(context.uiString(R.string.extraction_retry_started))}){Text(context.uiString(R.string.retry_extraction))}
                    if(i.type=="audio" || i.type=="video") TextButton(enabled=!busy,onClick={if(i.type=="video" && i.attachment.isBlank() && i.pendingMedia.isBlank()) onRelinkVideo() else scope.launch(Dispatchers.IO){
                        if(i.status.startsWith("ready")) ProcessingGuard.exclusive {store.clearAudioClips(id)}
                        Ingest.enqueue(context,id,manual=true)
                        withContext(Dispatchers.Main){onRefresh();onMessage(context.uiString(R.string.transcription_started))}
                    }}){Text(if(i.status.startsWith("ready")) context.uiString(R.string.retranscribe) else context.uiString(R.string.resume_transcription))}
                }
                TextButton(enabled=!deleting,onClick={deleting=true;scope.launch {
                    runCatching {withContext(Dispatchers.IO){ProcessingQueue.remove(context,id)}}
                        .onSuccess {onDelete()}
                        .onFailure {deleting=false;onError(it.message ?: context.uiString(R.string.item_missing))}
                }}){Text(context.uiString(R.string.delete_item),color=Color(0xFFFF8B8B))}
                } }
            }
            if(i.body.isNotBlank() && (i.type!="youtube" || segments.isNotEmpty())) Surface(color=panel,elevation=8.dp) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(question,{question=it},Modifier.weight(1f),placeholder={Text(context.uiString(R.string.ask_content))},maxLines=3,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={ask()}))
                        Button(onClick={ask()},enabled=!busy && question.isNotBlank(),shape=RoundedCornerShape(12.dp),contentPadding=PaddingValues(horizontal=14.dp,vertical=12.dp)){Text(context.uiString(R.string.send))}
                    }
                }
            }
        }
    }
    if(integrateText) AlertDialog(onDismissRequest={integrateText=false},title={Text(context.uiString(R.string.integrate_text))},text={OutlinedTextField(addedText,{addedText=it},Modifier.fillMaxWidth(),maxLines=10)},confirmButton={TextButton(enabled=addedText.isNotBlank() && !busy,onClick={scope.launch {
        withContext(Dispatchers.IO) {ProcessingGuard.exclusive {store.addSegments(id,store.segments(id)+Segment(0,id,addedText,-1,-1,"testo condiviso"));store.update(id,sourceQuality="complete")};ProcessingQueue.summary(context,id)}
        addedText="";integrateText=false;onRefresh()
    }}) {Text(context.uiString(R.string.save))}},dismissButton={TextButton(onClick={integrateText=false}) {Text(context.uiString(R.string.cancel))}})

}

@Composable private fun SettingsScreen(onMessage:(String)->Unit,onError:(String)->Unit,onExport:()->Unit,onImport:()->Unit,onPickModel:()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val loaded by LocalAi.loaded.collectAsState()
    var unloadAllowed by remember {mutableStateOf(false)}
    var state by remember { mutableStateOf(LocalModel.state(context)) }
    var chargingOnly by remember { mutableStateOf(LocalPrefs.chargingOnly(context)) }
    var invidious by remember { mutableStateOf(LocalPrefs.invidiousInstance(context)) }
    var licenses by remember {mutableStateOf<String?>(null)}
    var downloadDialog by remember {mutableStateOf(false)}
    var checkingModel by remember {mutableStateOf(false)}
    fun startDownload(mobile: Boolean) {
        downloadDialog=false
        runCatching {
            if(state.waitingForWifi) LocalModel.cancel(context)
            LocalModel.start(context,allowMetered=mobile);state=LocalModel.state(context)
        }.onFailure {onError(it.message ?: context.uiString(R.string.download_not_started))}
    }
    if(downloadDialog) AlertDialog(onDismissRequest={downloadDialog=false},
        title={Text(context.uiString(R.string.model_network_title))},
        text={Text(context.uiString(if(state.waitingForWifi) R.string.model_mobile_restart else R.string.model_network_explanation))},
        confirmButton={TextButton(onClick={startDownload(true)}){Text(context.uiString(R.string.download_mobile))}},
        dismissButton={TextButton(onClick={if(state.waitingForWifi) downloadDialog=false else startDownload(false)}){Text(context.uiString(R.string.download_wifi))}})
    if(licenses!=null) AlertDialog(onDismissRequest={licenses=null},title={Text(context.uiString(R.string.licenses))},text={
        Text(licenses!!,Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),fontSize=12.sp)
    },confirmButton={TextButton(onClick={licenses=null}){Text(context.uiString(R.string.close))}})
    LaunchedEffect(Unit) { while(true) {state=LocalModel.state(context);unloadAllowed=withContext(Dispatchers.IO) {ProcessingQueue.canUnload(context)}; delay(1500)} }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(context.uiString(R.string.model_heading),fontSize=20.sp,fontWeight=FontWeight.Bold)
        Text(context.uiString(R.string.local_processing_explanation),fontSize=13.sp,color=Color.LightGray)
        Text(state.message,color=if(LocalModel.ready(context)) Color(0xFF8DE4B2) else Color.LightGray)
        if(state.active) LinearProgressIndicator(progress=state.progress/100f,modifier=Modifier.fillMaxWidth())
        if(!LocalModel.ready(context) && !state.active) Button(onClick={downloadDialog=true},shape=RoundedCornerShape(12.dp)){Text(context.uiString(R.string.download_model))}
        if(state.waitingForWifi) TextButton(onClick={downloadDialog=true}){Text(context.uiString(R.string.download_mobile))}
        if(state.active) TextButton(onClick={LocalModel.cancel(context);state=LocalModel.state(context)}){Text(context.uiString(R.string.cancel_download))}
        Button(onClick=onPickModel,shape=RoundedCornerShape(12.dp)){Text(context.uiString(R.string.import_model))}
        if(LocalModel.ready(context)) {
            Button(enabled=!checkingModel,onClick={scope.launch {
                checkingModel=true
                try {
                    val backend=withContext(Dispatchers.IO) {ProcessingGuard.exclusive {LocalAi.verify(context)}}
                    onMessage(context.uiString(R.string.model_check_success,backend))
                } catch(error:Exception) {onError(error.message.orEmpty())}
                finally {checkingModel=false}
            }}){Text(context.uiString(if(checkingModel) R.string.model_checking else R.string.model_check))}
            Text(context.uiString(if(loaded) R.string.model_in_ram else R.string.model_not_in_ram),fontSize=12.sp,color=appMuted)
            TextButton(enabled=loaded && unloadAllowed,onClick={scope.launch {
                val unloaded=withContext(Dispatchers.IO) {ProcessingGuard.exclusive {if(ProcessingQueue.canUnload(context,false)) {LocalAi.unload();true} else false}}
                onMessage(context.uiString(if(unloaded) R.string.model_unloaded else R.string.queued))
            }}) {Text(context.uiString(R.string.free_model_memory))}
            Text(context.uiString(R.string.free_memory_explanation),fontSize=12.sp,color=appMuted)
        }
        Text(context.uiString(R.string.model_file_explanation),fontSize=12.sp,color=Color.LightGray)
        Divider()
        Text(context.uiString(R.string.automatic_processing),fontSize=20.sp,fontWeight=FontWeight.Bold)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(context.uiString(R.string.charging_only),Modifier.weight(1f))
            Switch(checked=chargingOnly,onCheckedChange={ value->chargingOnly=value;LocalPrefs.setChargingOnly(context,value);scope.launch(Dispatchers.IO){Ingest.reschedulePending(context,BrainStore(context))} })
        }
        Text(context.uiString(R.string.charging_explanation),fontSize=12.sp,color=Color.LightGray)
        Divider()
        Text(context.uiString(R.string.youtube_captions),fontSize=20.sp,fontWeight=FontWeight.Bold)
        OutlinedTextField(invidious,{invidious=it},Modifier.fillMaxWidth(),label={Text(context.uiString(R.string.invidious_label))},singleLine=true)
        Button(onClick={runCatching { LocalPrefs.setInvidiousInstance(context,invidious);onMessage(if(invidious.isBlank()) context.uiString(R.string.invidious_disabled) else context.uiString(R.string.invidious_saved)) }.onFailure { onError(it.message ?: context.uiString(R.string.invalid_address)) }}) { Text(context.uiString(R.string.save_instance)) }
        Text(context.uiString(R.string.invidious_explanation),fontSize=12.sp,color=Color.LightGray)
        Divider()
        Text(context.uiString(R.string.separate_archive),fontSize=20.sp,fontWeight=FontWeight.Bold)
        Text(context.uiString(R.string.archive_explanation),fontSize=13.sp,color=Color.LightGray)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {Button(onClick=onExport,shape=RoundedCornerShape(12.dp)){Text(context.uiString(R.string.export_zip))};Button(onClick=onImport,shape=RoundedCornerShape(12.dp)){Text(context.uiString(R.string.import_zip))}}
        Divider()
        Text(context.uiString(R.string.ocr_explanation),fontSize=12.sp,color=Color.LightGray)
        Text(context.uiString(R.string.captions_explanation),fontSize=12.sp,color=Color.LightGray)
        TextButton(onClick={
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(context.getString(R.string.privacy_url)))) }
                .onFailure { onError(context.uiString(R.string.link_open_failed)) }
        }) { Text(context.uiString(R.string.privacy_policy)) }
        Text(context.uiString(R.string.license_notice),fontSize=12.sp,color=appMuted)
        TextButton(onClick={scope.launch {
            runCatching {withContext(Dispatchers.IO) {
                listOf("LICENSE_EXCEPTION.md","LICENSE","THIRD_PARTY_NOTICES.md").joinToString("\n\n") {name ->context.assets.open("licenses/$name").bufferedReader().use {it.readText()}}
            }}.onSuccess {licenses=it}.onFailure {onError(it.message.orEmpty())}
        }}) {Text(context.uiString(R.string.licenses))}
        Divider()
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape)) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(R.drawable.supermens_launcher),null,
                    Modifier.fillMaxSize().graphicsLayer(scaleX=1.015f,scaleY=1.015f),
                    contentScale=ContentScale.Crop
                )
            }
            Spacer(Modifier.width(12.dp));Text("SuperMens",fontSize=22.sp,fontWeight=FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
            Text(context.uiString(R.string.made_with),fontSize=13.sp,color=appMuted)
            Spacer(Modifier.width(8.dp))
            UiIcon(R.drawable.claude_code_mascot,"Claude Code",modifier=Modifier.size(28.dp),tint=Color.Unspecified)
            Spacer(Modifier.width(8.dp));Text("by",fontSize=13.sp,color=appMuted)
            TextButton(onClick={
                val url=context.getString(R.string.repository_url).trim()
                if(url.isBlank()) onMessage(context.uiString(R.string.repo_pending))
                else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }
                    .onFailure { onError(context.uiString(R.string.link_open_failed)) }
            }) { Text("BBNSS",fontSize=13.sp) }
        }
    }
}
