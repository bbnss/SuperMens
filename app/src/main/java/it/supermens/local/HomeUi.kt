package it.supermens.local

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory

internal val appBackground = Color(0xFF10151D)
internal val appPanel = Color(0xFF1C2531)
internal val appAccent = Color(0xFF83C9F4)
internal val appMuted = Color(0xFFA7B5C7)
internal val appAccentSurface = Color(0xFF233E53)

@Composable internal fun UiIcon(resource: Int, description: String? = null, modifier: Modifier = Modifier.size(22.dp), tint: Color = appAccent) {
    Icon(painterResource(resource), description, modifier, tint = tint)
}

internal data class ActivityEntry(val item: BrainItem, val text: String, val running: Boolean = false, val failed: Boolean = false)

@Composable internal fun ActivityStrip(entries: List<ActivityEntry>, recording: Boolean, dictating: Boolean, dictationStatus: String, savingPhoto: Boolean, onDetails: () -> Unit, onStop: () -> Unit) {
    val context=LocalContext.current
    if(entries.isEmpty() && !recording && !dictating && !savingPhoto) return
    val active = entries.firstOrNull { it.running } ?: entries.firstOrNull { it.failed } ?: entries.firstOrNull()
    val waiting = entries.count { !it.running && !it.failed }
    val text = when {
        recording -> context.uiString(R.string.recording_status)
        dictating -> dictationStatus.ifBlank { context.uiString(R.string.dictation_status) }
        savingPhoto -> context.uiString(R.string.saving_photo)
        else -> active?.text.orEmpty() + if(waiting > 0 && active?.running == true) context.uiString(R.string.pending_count,waiting) else if(entries.size > 1) context.uiString(R.string.activity_count,entries.size) else ""
    }
    Surface(color=appAccentSurface.copy(alpha=.6f), shape=RoundedCornerShape(12.dp), modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp, vertical=4.dp).clickable(onClick=onDetails)) {
        Row(Modifier.heightIn(min=48.dp).padding(start=12.dp, end=4.dp), verticalAlignment=Alignment.CenterVertically) {
            if(active?.running == true || recording || dictating || savingPhoto) CircularProgressIndicator(Modifier.size(15.dp),strokeWidth=2.dp)
            else UiIcon(if(active?.failed == true) R.drawable.ui_warning else R.drawable.ui_activity,modifier=Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, Modifier.weight(1f), fontSize=12.sp, maxLines=1, overflow=TextOverflow.Ellipsis, color=appMuted)
            if(recording || dictating) TextButton(onClick=onStop) { Text(if(recording) context.uiString(R.string.save) else context.uiString(R.string.finish), color=appAccent) }
            else IconButton(onClick=onDetails) { UiIcon(R.drawable.ui_chevron,context.uiString(R.string.show_activities),Modifier.size(18.dp)) }
        }
    }
}

@Composable internal fun HomeScreen(
    items: List<BrainItem>, previews: Map<String,String>, total: Int, query: String, onQuery: (String)->Unit,
    filter: String, onFilter: (String)->Unit, entries: List<ActivityEntry>, recording: Boolean,
    dictating: Boolean, dictationStatus: String, savingPhoto: Boolean, onActivities: ()->Unit, onStop: ()->Unit,
    onOpen: (BrainItem)->Unit, onNote: ()->Unit, onRecord: ()->Unit, onDictate: ()->Unit,
    onFile: ()->Unit, onCamera: ()->Unit, onPaste: ()->Unit
) {
    val context=LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val keyboard=LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=4.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("all" to context.uiString(R.string.filter_all),"note" to context.uiString(R.string.filter_notes),"youtube" to context.uiString(R.string.filter_youtube),"video" to context.uiString(R.string.filter_video),"social" to context.uiString(R.string.filter_social),"pdf" to context.uiString(R.string.filter_pdf),"audio" to context.uiString(R.string.filter_audio),"image" to context.uiString(R.string.filter_images),"web" to context.uiString(R.string.filter_web)).forEach { (key,label) ->
                Surface(color=if(filter==key) appAccentSurface else appPanel, shape=RoundedCornerShape(24.dp), border=if(filter==key) BorderStroke(1.dp,appAccent.copy(alpha=.5f)) else null, modifier=Modifier.heightIn(min=48.dp).clickable { onFilter(key) }) {
                    Row(Modifier.padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) { UiIcon(typeIcon(key),modifier=Modifier.size(16.dp),tint=typeColor(key));Text(label,color=if(filter==key) typeColor(key) else appMuted,fontSize=13.sp,fontWeight=if(filter==key) FontWeight.SemiBold else FontWeight.Normal) }
                }
            }
        }
        ActivityStrip(entries,recording,dictating,dictationStatus,savingPhoto,onActivities,onStop)
        if(query.isNotBlank() || filter!="all") Text(context.uiString(R.string.result_count,items.size,total),Modifier.padding(start=20.dp,top=6.dp),color=appMuted,fontSize=12.sp)
        if(items.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
            Column(Modifier.padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                UiIcon(if(total==0) R.drawable.ui_note else R.drawable.ui_search,modifier=Modifier.size(38.dp))
                Text(if(total==0) context.uiString(R.string.empty_heading) else context.uiString(R.string.no_results),fontSize=19.sp,fontWeight=FontWeight.SemiBold)
                Text(if(total==0) context.uiString(R.string.empty_hint) else context.uiString(R.string.no_results_hint),color=appMuted,fontSize=14.sp)
            }
        } else LazyVerticalGrid(GridCells.Fixed(2),modifier=Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            items(items,key={it.id}) { item -> PostCard(item,previews[item.id].orEmpty()) { onOpen(item) } }
        }
        Surface(color=appBackground,elevation=8.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                TextField(query,onQuery,Modifier.weight(1f),placeholder={Text(context.uiString(R.string.search_posts),fontSize=14.sp,color=appMuted)},singleLine=true,shape=RoundedCornerShape(20.dp),leadingIcon={UiIcon(R.drawable.ui_search,tint=appMuted)},trailingIcon=if(query.isNotEmpty()) {{IconButton(onClick={onQuery("")}) {UiIcon(R.drawable.ui_close,context.uiString(R.string.clear_search),tint=appMuted)}}} else null,
                    colors=TextFieldDefaults.textFieldColors(backgroundColor=appPanel,focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent,cursorColor=appAccent),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={keyboard?.hide()}))
                Box {
                    Surface(color=appAccent,shape=RoundedCornerShape(18.dp)) {
                        IconButton(onClick={menu=true;keyboard?.hide()},modifier=Modifier.size(56.dp)) { UiIcon(R.drawable.ui_add,context.uiString(R.string.add_content),Modifier.size(26.dp),appBackground) }
                    }
                    DropdownMenu(menu,{menu=false},modifier=Modifier.width(230.dp).background(appPanel)) {
                        listOf(Triple(context.uiString(R.string.written_note),R.drawable.ui_note,onNote),Triple(context.uiString(R.string.voice_note),R.drawable.ui_mic,onRecord),Triple(context.uiString(R.string.dictation),R.drawable.ui_dictation,onDictate),Triple(context.uiString(R.string.import_file),R.drawable.ui_file,onFile),Triple(context.uiString(R.string.camera),R.drawable.ui_camera,onCamera),Triple(context.uiString(R.string.paste_content),R.drawable.ui_paste,onPaste)).forEach { (label,icon,action) ->
                            DropdownMenuItem(onClick={menu=false;action()},enabled=!(recording || dictating) || label==context.uiString(R.string.written_note) || label==context.uiString(R.string.paste_content)) {
                                UiIcon(icon,tint=appMuted);Spacer(Modifier.width(14.dp));Text(label,fontSize=14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun PostCard(item: BrainItem, preview: String, onClick: ()->Unit) {
    val context=LocalContext.current
    val visual=item.type !in setOf("note","audio")
    Surface(modifier=Modifier.fillMaxWidth().heightIn(min=if(visual) 236.dp else 194.dp).clickable(onClick=onClick),color=appPanel,shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,Color.White.copy(alpha=.045f))) {
        Column {
            if(visual) PostPreview(item,Modifier.fillMaxWidth().height(108.dp))
            Row(Modifier.padding(start=14.dp,end=14.dp,top=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                UiIcon(typeIcon(item.type),modifier=Modifier.size(16.dp),tint=typeColor(item.type))
                Text(typeLabel(context,item.type),color=typeColor(item.type),fontSize=11.sp,fontWeight=FontWeight.SemiBold,maxLines=1)
            }
            Text(item.title,Modifier.padding(start=14.dp,end=14.dp,top=7.dp),fontWeight=FontWeight.SemiBold,fontSize=14.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
            val status=if(item.status.startsWith("processing") || PostContent.failed(item)) PostContent.stage(item,AppLanguage.code(context)) else preview.ifBlank { if(item.status.startsWith("pending_ai") || item.status=="saved") PostContent.stage(item,AppLanguage.code(context)) else item.source }
            Text(status,Modifier.padding(start=14.dp,end=14.dp,top=6.dp,bottom=12.dp),color=appMuted,fontSize=12.sp,maxLines=if(visual) 2 else 4,overflow=TextOverflow.Ellipsis)
        }
    }
}

internal fun typeIcon(type: String): Int = when(type) { "image"->R.drawable.ui_image;"note","pdf","transcript"->R.drawable.ui_note;"audio"->R.drawable.ui_mic;"video","youtube"->R.drawable.ui_play;else->R.drawable.ui_web }
internal fun typeLabel(context: android.content.Context,type: String): String = when(type) { "image"->context.uiString(R.string.type_image);"note"->context.uiString(R.string.type_note);"pdf"->context.uiString(R.string.filter_pdf);"audio"->context.uiString(R.string.filter_audio);"video"->context.uiString(R.string.filter_video);"youtube"->context.uiString(R.string.filter_youtube);"transcript"->context.uiString(R.string.type_transcript);"web"->context.uiString(R.string.filter_web);else->type.replaceFirstChar { it.uppercase() } }

@Composable internal fun PostPreview(item: BrainItem,modifier: Modifier=Modifier) {
    val bmp=remember(item.thumbnail) { runCatching {
        if(item.thumbnail.isBlank()) null else {
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeFile(item.thumbnail,bounds)
            var sample=1
            while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1200) sample*=2
            BitmapFactory.decodeFile(item.thumbnail,BitmapFactory.Options().apply { inSampleSize=sample })
        }
    }.getOrNull() }
    DisposableEffect(bmp) { onDispose { bmp?.recycle() } }
    if(bmp!=null) Image(bmp.asImageBitmap(),null,modifier,contentScale=ContentScale.Crop)
    else Box(modifier.background(appAccentSurface),contentAlignment=Alignment.Center) { UiIcon(typeIcon(item.type),modifier=Modifier.size(36.dp)) }
}

internal fun typeColor(type:String):Color = Color(when(type) {
    "note"->0xFFF4C66A;"youtube"->0xFFFF6B6B;"video"->0xFFAC93E8
    "social","x","instagram","facebook","tiktok"->0xFFE79AB9
    "pdf"->0xFFED9D7C;"audio"->0xFF62CEC4;"image"->0xFF91CD87;"web"->0xFF82BDF1
    else->0xFFA7B5C7
})
