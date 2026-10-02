// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

object AppLanguage {
    fun code(context: Context): String {
        val manager=context.getSystemService(LocaleManager::class.java)
        // Use the user's first preference before Android resolves available resource languages.
        val chosen=manager.applicationLocales.takeUnless { it.isEmpty } ?: manager.systemLocales
        return LanguageChoice.code(chosen[0]?.toLanguageTag().orEmpty())
    }
    fun context(base: Context): Context {
        val code=code(base)
        val current=base.resources.configuration.locales
        if(current.size()==1 && current[0].language==code) return base
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(code))) })
    }
}

internal fun Context.uiString(id: Int, vararg args: Any): String = AppLanguage.context(this).getString(id,*args)
