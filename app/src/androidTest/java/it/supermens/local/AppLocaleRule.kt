// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

import android.app.LocaleManager
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Change only this test app's language and restore it after each UI test. */
class AppLocaleRule(private val tag: String) : ExternalResource() {
    private lateinit var manager: LocaleManager
    private lateinit var previous: LocaleList
    override fun before() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        manager=instrumentation.targetContext.getSystemService(LocaleManager::class.java)
        previous=manager.applicationLocales
        instrumentation.runOnMainSync { manager.applicationLocales=LocaleList.forLanguageTags(tag) }
        instrumentation.waitForIdleSync()
    }
    override fun after() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { manager.applicationLocales=previous }
        instrumentation.waitForIdleSync()
    }
}
