// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import org.junit.Assert.*
import org.junit.Test

class EngineStartupTest {
    @Test fun gpuFailureAndThrowingCleanupStillFallBackToCpu() {
        val attempts = mutableListOf<String>()
        val result = EngineStartup.initialize(listOf("GPU", "CPU"), { it }, {
            attempts += it
            if (it == "GPU") error("GPU unavailable")
        }, { error("Engine is not initialized.") })
        assertEquals(listOf("GPU", "CPU"), attempts)
        assertEquals("CPU" to 1, result)
    }
    @Test fun gpuSuccessDoesNotCreateCpuEngineOrCloseWorkingEngine() {
        val created = mutableListOf<String>()
        val result = EngineStartup.initialize(listOf("GPU", "CPU"), { created += it; it }, {}, { fail("Successful engine was closed") })
        assertEquals(listOf("GPU"), created)
        assertEquals("GPU" to 0, result)
    }
    @Test fun failureReportsInitializationCauseRatherThanCleanupError() {
        try {
            EngineStartup.initialize(listOf("GPU", "CPU"), { it }, { error("$it initialization failed") }, { error("Engine is not initialized.") })
            fail("Expected failure")
        } catch (error: IllegalStateException) {
            assertEquals("CPU initialization failed", error.message)
            assertEquals("CPU initialization failed", error.cause?.message)
            assertEquals("Engine is not initialized.", error.cause?.suppressed?.single()?.message)
        }
    }
    @Test fun constructorFailureAlsoTriesNextBackend() {
        assertEquals("CPU" to 1, EngineStartup.initialize(listOf("GPU", "CPU"), {
            if (it == "GPU") error("GPU constructor failed") else it
        }, {}, {}))
    }
}
