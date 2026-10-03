// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable internal fun TextEntryDialog(title:String,value:String,onValue:(String)->Unit,saving:Boolean,onSave:()->Unit,onDismiss:()->Unit) {
    val context=LocalContext.current
    Dialog(onDismissRequest={if(!saving) onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Box(Modifier.fillMaxSize().imePadding().padding(16.dp)) {
            Surface(color=appPanel,shape=RoundedCornerShape(24.dp),modifier=Modifier.fillMaxWidth().heightIn(max=640.dp)) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(title,fontSize=22.sp,color=Color.White)
                    Text(context.uiString(R.string.text_entry_hint),color=appMuted,fontSize=14.sp)
                    OutlinedTextField(value,onValue,Modifier.fillMaxWidth().weight(1f).testTag("text-entry"),enabled=!saving,
                        placeholder={Text(context.uiString(R.string.write_here))},textStyle=LocalTextStyle.current.copy(fontSize=17.sp),
                        colors=TextFieldDefaults.outlinedTextFieldColors(textColor=Color.White,backgroundColor=appBackground,cursorColor=appAccent,focusedBorderColor=appAccent,unfocusedBorderColor=appMuted,placeholderColor=appMuted))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        TextButton(enabled=!saving,onClick={
                            val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val pasted=clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            if(pasted.isNotEmpty()) onValue(value+pasted)
                        }) {Text(context.uiString(R.string.paste_text))}
                        Text(context.uiString(R.string.character_count,value.length),color=appMuted,fontSize=12.sp)
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                        TextButton(enabled=!saving,onClick=onDismiss) {Text(context.uiString(R.string.cancel))}
                        Spacer(Modifier.width(8.dp))
                        Button(enabled=!saving && value.isNotBlank(),onClick=onSave,shape=RoundedCornerShape(14.dp)) {
                            Text(context.uiString(if(saving) R.string.saving_text else R.string.save))
                        }
                    }
                }
            }
        }
    }
}
