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
}
