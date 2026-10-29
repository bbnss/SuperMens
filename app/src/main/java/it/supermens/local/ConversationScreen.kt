// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

@Composable internal fun ConversationScreen(store:BrainStore,id:String?,type:String,refresh:Int,onRefresh:()->Unit,
    onCreated:(String)->Unit,onMessage:(String)->Unit,onError:(String)->Unit,onDeleted:()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val lifecycleOwner=LocalLifecycleOwner.current
    var postId by rememberSaveable {mutableStateOf(id)}
    var input by rememberSaveable {mutableStateOf("")}
    var draftId by rememberSaveable {mutableStateOf<Long?>(null)}
    var mode by rememberSaveable {mutableStateOf(if(type=="translation") "translate" else "chat")}
    var language by rememberSaveable {mutableStateOf(AppLanguage.deviceTag(context))}
    var targetLoaded by rememberSaveable {mutableStateOf(id==null)}
    var saving by remember {mutableStateOf(false)}
    var recording by remember {mutableStateOf(false)}
    var recorder by remember {mutableStateOf<MediaRecorder?>(null)}
    var recordingFile by remember {mutableStateOf<File?>(null)}
    var languageDialog by remember {mutableStateOf(false)}
    var reading by remember {mutableStateOf(false)}
    var voiceMode by rememberSaveable {mutableStateOf(LocalPrefs.voiceReplies(context))}
    var voiceMissing by remember {mutableStateOf(false)}
    var integrate by remember {mutableStateOf(false)}
    var addedText by rememberSaveable {mutableStateOf("")}
    var shareMenu by remember {mutableStateOf(false)}
    val item by produceState<BrainItem?>(null,postId,refresh) {value=withContext(Dispatchers.IO) {postId?.let {store.get(it)}}}
    var messagesLoaded by remember {mutableStateOf(false)}
    val messages by produceState<List<PostMessage>>(emptyList(),postId,refresh) {value=withContext(Dispatchers.IO) {postId?.let {store.messages(it)} ?: emptyList()};messagesLoaded=true}
    val segments by produceState<List<Segment>>(emptyList(),postId,refresh) {value=withContext(Dispatchers.IO) {postId?.let {store.segments(it)} ?: emptyList()}}
    val jobs by produceState<List<ProcessingJob>>(emptyList(),postId,refresh) {value=withContext(Dispatchers.IO) {ProcessingQueue.jobs(store).filter {it.itemId==postId}}}
    val active=jobs.firstOrNull {it.state=="running"} ?: jobs.firstOrNull {it.state=="waiting"}
    val failed=jobs.lastOrNull {it.state=="failed"}
    val busy=saving || recording || active!=null || failed!=null
    val voice=remember {OfflineVoice(context,{voiceMissing=true},{reading=it})}
    val listState=rememberLazyListState()
    val live by LocalAi.preview.collectAsState()
    LaunchedEffect(item) {if(!targetLoaded && item!=null) {language=item!!.targetLanguage.ifBlank {AppLanguage.deviceTag(context)};targetLoaded=true}}
    LaunchedEffect(messages) {
        messages.firstOrNull {it.state=="draft"}?.let {draft ->if(draftId==null && input.isBlank()) {draftId=draft.id;input=draft.text}}
    }
    LaunchedEffect(input,draftId) {
        val draft=draftId;val target=postId
        if(draft!=null && target!=null) {
            delay(400)
            withContext(Dispatchers.IO) {
                if(store.messages(target).any {it.id==draft && it.state=="draft"}) store.editMessage(target,draft,input,"draft",mode,language)
            }
        }
    }
    var seenReply by rememberSaveable {mutableStateOf<Long?>(null)}
    LaunchedEffect(messages.lastOrNull()?.id,messagesLoaded) {
        if(!messagesLoaded) return@LaunchedEffect
        val reply=messages.lastOrNull {it.role=="assistant"}
        if(reply!=null) {
            if(seenReply!=null && reply.id>seenReply!! && voiceMode && !recording && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) voice.speak(reply.text,reply.language)
            seenReply=reply.id
        } else if(seenReply==null) seenReply=0
        if(messages.isNotEmpty()) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1)-1)
    }
    fun adopt(result:String) {postId=result;onCreated(result);onRefresh()}
    fun persistAudio(file:File) {
        runCatching {ConversationActions.submit(context,store,postId,type,"",mode,language,audio=file)}
            .onSuccess {adopt(it)}.onFailure {file.delete();onError(it.message.orEmpty())}
    }
    fun stopRecording(cancel:Boolean=false) {
        val currentRecorder=recorder ?: return
        val file=recordingFile
        val success=runCatching {currentRecorder.stop();true}.getOrDefault(false)
        currentRecorder.release();recorder=null;recordingFile=null;recording=false
        if(!cancel && success && file!=null && file.length()>0) persistAudio(file)
        else {file?.delete();if(!cancel) onError(context.uiString(R.string.empty_recording))}
    }
    val stopLatest by rememberUpdatedState({stopRecording()})
    DisposableEffect(lifecycleOwner) {
        val observer=LifecycleEventObserver {_,event ->if(event==Lifecycle.Event.ON_STOP) {stopLatest();voice.stop()}}
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {lifecycleOwner.lifecycle.removeObserver(observer);stopLatest();voice.close()}
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted ->
        if(!granted) onError(context.uiString(R.string.microphone_denied))
        else {
            voice.stop()
            val file=File(File(context.filesDir,"conversation_audio").apply {mkdirs()},"${UUID.randomUUID()}.m4a")
            val capture=MediaRecorder(context)
            runCatching {
                capture.setAudioSource(MediaRecorder.AudioSource.MIC);capture.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                capture.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);capture.setOutputFile(file.absolutePath);capture.prepare();capture.start()
            }.onSuccess {recorder=capture;recordingFile=file;recording=true}
                .onFailure {capture.release();file.delete();onError(it.message.orEmpty())}
        }
    }
    val filePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->if(uri!=null) {
        saving=true
        scope.launch {
            runCatching {withContext(Dispatchers.IO) {
                val mime=context.contentResolver.getType(uri).orEmpty()
                val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst()) it.getString(0) else ""}.orEmpty()
                val audio=mime.startsWith("audio/") || listOf(".m4a",".mp3",".wav",".ogg",".flac",".aac",".opus").any {name.endsWith(it,true)}
                if(audio) {
                    val file=File(File(context.filesDir,"conversation_audio").apply {mkdirs()},"${UUID.randomUUID()}.audio")
                    try {
                        context.contentResolver.openInputStream(uri)?.use {source ->file.outputStream().use {source.copyTo(it)}} ?: error(context.uiString(R.string.import_failed))
                        require(file.length()>0) {context.uiString(R.string.audio_empty)}
                        ConversationActions.submit(context,store,postId,type,"",mode,language,audio=file) to ""
                    } catch(e:Exception) {file.delete();throw e}
                } else {
                    require(mime.startsWith("text/") || listOf(".txt",".md",".srt",".vtt",".csv").any {name.endsWith(it,true)}) {context.uiString(R.string.import_failed)}
                    val bytes=context.contentResolver.openInputStream(uri)?.use {it.readNBytes(2_000_001)} ?: error(context.uiString(R.string.import_failed))
                    require(bytes.size<=2_000_000) {context.uiString(R.string.file_text_too_large)}
                    null to bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                }
            }}.onSuccess {(newId,text) ->if(newId!=null) adopt(newId) else input+=text}
                .onFailure {onError(it.message.orEmpty())}
            saving=false
        }
    }}
    fun send() {
        if(busy || input.isBlank()) return
        saving=true;voice.stop()
        scope.launch {
            runCatching {withContext(Dispatchers.IO) {ConversationActions.submit(context,store,postId,type,input.trim(),mode,language,draftId)}}
                .onSuccess {input="";draftId=null;adopt(it)}.onFailure {onError(it.message.orEmpty())}
            saving=false
        }
    }
    fun share(pdf:Boolean) {
        val target=postId ?: return
        saving=true;shareMenu=false
        scope.launch {
            runCatching {withContext(Dispatchers.IO) {PostShare.prepare(context,store,target,pdf)}}
                .onSuccess {context.startActivity(Intent.createChooser(it,context.uiString(R.string.share_post)))}.onFailure {onError(it.message.orEmpty())}
            saving=false
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(state=listState,modifier=Modifier.weight(1f).fillMaxWidth().testTag("conversation-content"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Text(context.uiString(R.string.ai_disclosure),color=appMuted)
                ModelCapabilityNotice()
                if(type=="chat") Text(context.uiString(R.string.chat_warning),color=appMuted)
                item?.let {Text(it.title,style=MaterialTheme.typography.h6)}
                Row {
                    TextButton(onClick={voiceMode=!voiceMode;LocalPrefs.setVoiceReplies(context,voiceMode);if(!voiceMode) voice.stop()}) {
                        UiIcon(if(voiceMode) R.drawable.ui_mic else R.drawable.ui_note)
                        Text(context.uiString(if(voiceMode) R.string.voice_mode else R.string.text_mode))
                    }
                    if(reading) TextButton(onClick={voice.stop()}) {Text(context.uiString(R.string.stop_reading))}
                }
                if(type=="translation") {
                    Row {
                        TextButton(enabled=!busy,onClick={mode="translate"}) {Text((if(mode=="translate") "✓ " else "")+context.uiString(R.string.continue_translation))}
                        TextButton(enabled=!busy,onClick={mode="chat"}) {Text((if(mode=="chat") "✓ " else "")+context.uiString(R.string.chat_on_text))}
                    }
                    TextButton(enabled=!busy,onClick={languageDialog=true}) {Text(context.uiString(R.string.target_language)+": "+TranslationLanguages.name(language,Locale.forLanguageTag(AppLanguage.code(context))))}
                }
                if(messages.isEmpty()) Text(context.uiString(R.string.new_conversation_hint),color=appMuted)
                item?.summary?.takeIf {it.isNotBlank()}?.let {Text(context.uiString(R.string.summary),color=appAccent);Text(it);ReportResponseButton(it,onMessage)}
            }
            items(messages,key={it.id}) {m ->
                Surface(color=if(m.role=="user") appAccentSurface else appPanel,shape=RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(if(m.role=="user") context.uiString(R.string.conversation_you) else if(m.mode=="translate") context.uiString(R.string.translation)+" · "+TranslationLanguages.name(m.language) else LocalAi.modelName(context),color=appAccent)
                        if(m.text.isNotBlank()) Text(m.text)
                        if(m.state=="transcribing") Text(context.uiString(R.string.processing),color=appMuted)
                        if(m.state=="draft") Text(context.uiString(R.string.correct_transcript),color=appMuted)
                        if(m.role=="assistant") ReportResponseButton(m.text,onMessage)
                        if(m.contextLimited) Text(context.uiString(R.string.context_limited),color=appMuted)
                        Row {
                            if(m.text.isNotBlank()) TextButton(onClick={
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("SuperMens",m.text));onMessage(context.uiString(R.string.text_copied))
                            }) {Text(context.uiString(R.string.copy_text))}
                            if(m.role=="assistant") TextButton(enabled=!recording,onClick={voice.speak(m.text,m.language)}) {Text(context.uiString(R.string.listen_reply))}
                            if(type=="translation" && m.mode=="translate" && m.role=="user" && m.state=="done") TextButton(enabled=!busy,onClick={input=m.text;mode="translate";language=m.language;draftId=null}) {Text(context.uiString(R.string.retranslate))}
                        }
                    }
                }
            }
            item {
                val additions=segments.filter {PostContent.isAddition(it)}
                if(additions.isNotEmpty()) {Text(context.uiString(R.string.manual_additions),color=appAccent);additions.forEach {Text(it.text)}}
                if(active!=null) {
                    ModelCapabilityNotice()
                    live?.takeIf {it.itemId==postId}?.let {Text(context.uiString(R.string.ai_preview),color=appMuted);Text(it.text)}
                    TextButton(onClick={postId?.let {ProcessingQueue.stop(context,it);onRefresh()}}) {Text(context.uiString(R.string.stop_processing))}
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(active.progress.ifBlank {context.uiString(R.string.conversation_pending)},color=appMuted)
                }
                if(failed!=null) {
                    Text(failed.progress,color=MaterialTheme.colors.error)
                    TextButton(enabled=!saving,onClick={postId?.let {if(failed.kind=="summary") ProcessingQueue.summary(context,it) else ProcessingQueue.retryConversation(context,it);onRefresh()}}) {Text(context.uiString(R.string.conversation_retry))}
                }
                if(postId!=null) {
                    Row {
                        TextButton(enabled=!busy,onClick={integrate=true}) {Text(context.uiString(R.string.integrate_text))}
                        TextButton(enabled=!busy && item?.body?.isNotBlank()==true,onClick={postId?.let {ProcessingQueue.summary(context,it);onRefresh()}}) {Text(context.uiString(if(item?.summary.isNullOrBlank()) R.string.create_summary else R.string.regenerate_summary))}
                    }
                    Box {
                        TextButton(enabled=!saving,onClick={shareMenu=true}) {Text(context.uiString(R.string.share_post))}
                        DropdownMenu(shareMenu,{shareMenu=false}) {
                            DropdownMenuItem(onClick={share(false)}) {Text(context.uiString(R.string.share_photo_text))}
                            DropdownMenuItem(onClick={share(true)}) {Text(context.uiString(R.string.share_pdf))}
                        }
                    }
                    TextButton(enabled=!saving && !recording,onClick={
                        saving=true;scope.launch {
                            runCatching {withContext(Dispatchers.IO) {postId?.let {ProcessingQueue.remove(context,it)}}}
                                .onSuccess {onDeleted()}.onFailure {onError(it.message.orEmpty());saving=false}
                        }
                    }) {Text(context.uiString(R.string.delete_item),color=MaterialTheme.colors.error)}
                }
            }
        }
        if(recording) Row(Modifier.fillMaxWidth()) {
            TextButton(onClick={stopRecording()}) {Text(context.uiString(R.string.finish_recording))}
            TextButton(onClick={stopRecording(true)}) {Text(context.uiString(R.string.cancel_recording))}
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp)) {
                TextButton(enabled=!busy,onClick={
                    val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    input+=clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                }) {Text(context.uiString(R.string.paste_text))}
                IconButton(enabled=!busy && draftId==null,onClick={filePicker.launch(arrayOf("text/*","audio/*","application/ogg","application/octet-stream"))}) {UiIcon(R.drawable.ui_file,context.uiString(R.string.import_text_audio))}
                IconButton(enabled=!busy && draftId==null,onClick={permission.launch(Manifest.permission.RECORD_AUDIO)}) {UiIcon(R.drawable.ui_mic,context.uiString(R.string.voice_note))}
            }
            Row(Modifier.fillMaxWidth().padding(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(input,{input=it},Modifier.weight(1f).heightIn(max=180.dp).testTag("conversation-input"),enabled=!saving,maxLines=6,placeholder={Text(context.uiString(R.string.write_here))})
                Button(enabled=!busy && input.isNotBlank(),onClick={send()},modifier=Modifier.testTag("conversation-send")) {Text(context.uiString(R.string.send))}
            }
        }
    }
    if(languageDialog) LanguageDialog(language,{language=it;languageDialog=false;postId?.let {id ->scope.launch(Dispatchers.IO) {store.setTargetLanguage(id,it)}}},{languageDialog=false})
    if(integrate) TextEntryDialog(context.uiString(R.string.integrate_text),addedText,{addedText=it},saving,onSave={
        saving=true;scope.launch {
            runCatching {withContext(Dispatchers.IO) {postId?.let {ProcessingGuard.exclusive {store.integrateText(it,addedText)}}}}
                .onSuccess {integrate=false;addedText="";onRefresh()}.onFailure {onError(it.message.orEmpty())}
            saving=false
        }
    },onDismiss={integrate=false})
    if(voiceMissing) AlertDialog(onDismissRequest={voiceMissing=false},title={Text(context.uiString(R.string.voice_mode))},text={Text(context.uiString(R.string.voice_unavailable))},confirmButton={TextButton(onClick={voiceMissing=false;runCatching {context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))}.onFailure {onError(it.message.orEmpty())}}) {Text(context.uiString(R.string.voice_settings))}},dismissButton={TextButton(onClick={voiceMissing=false}) {Text(context.uiString(R.string.close))}})
}

@Composable private fun LanguageDialog(current:String,onSelect:(String)->Unit,onDismiss:()->Unit) {
    val context=LocalContext.current
    var query by rememberSaveable {mutableStateOf("")}
    val display=Locale.forLanguageTag(AppLanguage.code(context))
    AlertDialog(onDismissRequest=onDismiss,title={Text(context.uiString(R.string.target_language))},text={
        Column(Modifier.heightIn(max=450.dp)) {
            Text(context.uiString(R.string.language_quality),color=appMuted)
            OutlinedTextField(query,{query=it.take(80)},Modifier.fillMaxWidth(),label={Text(context.uiString(R.string.language_search))})
            Column(Modifier.weight(1f,false).verticalScroll(rememberScrollState())) {
                (listOf(current)+TranslationLanguages.tags).distinct().filter {it.contains(query,true) || TranslationLanguages.name(it,display).contains(query,true)}.forEach {tag ->
                    TextButton(onClick={onSelect(tag)},modifier=Modifier.fillMaxWidth()) {Text(TranslationLanguages.name(tag,display)+(if(tag==current) " ✓" else ""))}
                }
            }
        }
    },confirmButton={TextButton(enabled=query.isNotBlank(),onClick={onSelect(query.trim())}) {Text(context.uiString(R.string.other_language))}},dismissButton={TextButton(onClick=onDismiss) {Text(context.uiString(R.string.cancel))}})
}
