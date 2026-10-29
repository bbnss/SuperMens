// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import org.json.JSONObject

internal object ModelDiagnostics {
    private fun prefs(context:Context)=context.getSharedPreferences("model_diagnostics",Context.MODE_PRIVATE)
    fun backend(context:Context,name:String,vision:Boolean,elapsedMs:Long) {
        prefs(context).edit().putString("backend",name).putBoolean("vision",vision).putLong("load_ms",elapsedMs).apply()
    }
    fun generation(context:Context,result:GenerationResult) {
        prefs(context).edit().putLong("generation_ms",result.elapsedMs).putLong("first_token_ms",result.firstTokenMs ?: -1)
            .putInt("output_chars",result.text.length).apply()
    }
    fun foregroundFailure(context:Context) {prefs(context).edit().putBoolean("foreground_failed",true).apply()}
    fun snapshot(context:Context):String {
        val memory=ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val p=prefs(context)
        return JSONObject().put("app_version",BuildConfig.VERSION_NAME).put("version_code",BuildConfig.VERSION_CODE)
            .put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL).put("soc",Build.SOC_MODEL)
            .put("android_sdk",Build.VERSION.SDK_INT).put("ram_total_bytes",memory.totalMem)
            .put("ram_available_bytes",memory.availMem).put("model_label",LocalModel.label).put("model_ready",LocalModel.ready(context))
            .put("backend",p.getString("backend","untested")).put("vision",p.getBoolean("vision",false))
            .put("load_ms",p.getLong("load_ms",-1)).put("generation_ms",p.getLong("generation_ms",-1))
            .put("first_token_ms",p.getLong("first_token_ms",-1)).put("output_chars",p.getInt("output_chars",0))
            .put("thermal_status",DeviceHealth.thermalStatus).put("foreground_failed",p.getBoolean("foreground_failed",false))
            .put("device_certified",false).toString(2)
    }
}
