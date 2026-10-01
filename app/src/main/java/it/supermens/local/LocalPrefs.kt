package it.supermens.local

import android.content.Context
import java.net.URI

object LocalPrefs {
    private fun prefs(context:Context)=context.getSharedPreferences("local_options",Context.MODE_PRIVATE)
    fun chargingOnly(context:Context)=prefs(context).getBoolean("charging_only",false)
    fun setChargingOnly(context:Context,value:Boolean) { prefs(context).edit().putBoolean("charging_only",value).apply() }
    fun invidiousInstance(context:Context)=prefs(context).getString("invidious_instance","").orEmpty()
    fun setInvidiousInstance(context:Context,value:String) {
        val trimmed=value.trim().trimEnd('/')
        if(trimmed.isNotEmpty()) {
            val uri=URI(trimmed)
            require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null && (uri.rawPath.isNullOrBlank() || uri.rawPath=="/")) { context.uiString(R.string.invidious_url_error) }
        }
        prefs(context).edit().putString("invidious_instance",trimmed).apply()
    }
}
