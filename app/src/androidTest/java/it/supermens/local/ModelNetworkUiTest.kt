// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*

class ModelNetworkUiTest {
    @get:Rule val rule=createEmptyComposeRule()
    @get:Rule val locale=AppLocaleRule("it")
    @Test fun downloadWaitsForExplicitConnectionChoice() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        Assume.assumeTrue(!LocalModel.ready(context) && LocalModel.downloadId(context)<0)
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.waitUntil(15000) {
                runCatching {rule.onAllNodesWithContentDescription("Impostazioni").fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)
            }
            rule.onNodeWithContentDescription("Impostazioni").performClick()
            rule.onNodeWithText("Scarica Gemma 4 E2B · 2,58 GB").performClick()
            rule.onNodeWithText("Connessione per il download").assertIsDisplayed()
            rule.onNodeWithText("Autorizza dati mobili").assertIsDisplayed()
            rule.onNodeWithText("Attendi Wi-Fi").assertIsDisplayed()
            assertEquals("No download starts before consent",-1L,LocalModel.downloadId(context))
            androidx.test.espresso.Espresso.pressBack()
            assertEquals(-1L,LocalModel.downloadId(context))
        }
    }
    @Test fun firstLaunchBannerStartsDownloadImmediatelyOnWifi() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        Assume.assumeTrue(!LocalModel.ready(context));LocalModel.cancel(context)
        val manager=context.getSystemService(android.net.ConnectivityManager::class.java)
        instrumentation.uiAutomation.executeShellCommand("svc wifi enable").close()
        rule.waitUntil(20000) {manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)==true}
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                rule.onNodeWithText("Attiva l’intelligenza locale").assertIsDisplayed()
                assertEquals(-1L,LocalModel.downloadId(context))
                rule.onNodeWithText(context.uiString(R.string.download_model)).performClick()
                rule.waitUntil(10000) {LocalModel.downloadId(context)>=0}
                rule.onAllNodesWithText("Connessione per il download").assertCountEquals(0)
                assertTrue(LocalModel.state(context).active)
            } finally {LocalModel.cancel(context)}
        }
    }
    @Test fun mobileBannerRequiresConsentAndCanWaitForWifi() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        Assume.assumeTrue(!LocalModel.ready(context));LocalModel.cancel(context)
        val manager=context.getSystemService(android.net.ConnectivityManager::class.java)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
        instrumentation.uiAutomation.executeShellCommand("svc wifi disable").close()
        try {
            rule.waitUntil(20000) {manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)!=true}
            ActivityScenario.launch(MainActivity::class.java).use {
                rule.onNodeWithText(context.uiString(R.string.download_model)).performClick()
                rule.onNodeWithText("Connessione per il download").assertIsDisplayed();assertEquals(-1L,LocalModel.downloadId(context))
                rule.onNodeWithText("Attendi Wi-Fi").performClick()
                rule.waitUntil(15000) {LocalModel.downloadId(context)>=0}
                val download=LocalModel.downloadId(context)
                context.getSystemService(android.app.DownloadManager::class.java).query(android.app.DownloadManager.Query().setFilterById(download)).use {cursor ->
                    assertTrue(cursor.moveToFirst());assertEquals(0L,cursor.getLong(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)))
                }
            }
        } finally {LocalModel.cancel(context);instrumentation.uiAutomation.executeShellCommand("svc wifi enable").close()}
    }
}
