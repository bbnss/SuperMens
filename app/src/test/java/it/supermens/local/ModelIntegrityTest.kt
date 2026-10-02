// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ModelIntegrityTest {
    private val abcSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    @Test fun expectedDigestAndSizeAreRequired() {
        val file = File.createTempFile("model-integrity", ".bin")
        try {
            file.writeText("abc")
            assertTrue(ModelIntegrity.matches(file, 3, abcSha256))
            assertFalse(ModelIntegrity.matches(file, 4, abcSha256))
            file.writeText("abd")
            assertFalse(ModelIntegrity.matches(file, 3, abcSha256))
        } finally { file.delete() }
    }
    @Test fun cancellationInterruptsValidation() {
        val file = File.createTempFile("model-integrity", ".bin")
        try {
            file.writeText("abc")
            try {
                ModelIntegrity.matches(file, 3, abcSha256) { throw InterruptedException("cancelled") }
                fail("Expected cancellation")
            } catch (error: InterruptedException) { assertEquals("cancelled", error.message) }
        } finally { file.delete() }
    }
}
