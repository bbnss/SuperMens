package it.supermens.local

import org.junit.Assert.*
import org.junit.Test

class BetaPolicyTest {
    @Test fun storageIncludesWorkingSpaceAndUsesLongArithmetic() {
        val size=2_583_085_056L
        assertFalse(BetaPolicy.enoughSpace(size,size))
        assertTrue(BetaPolicy.enoughSpace(size+BetaPolicy.storageReserve,size))
        assertFalse(BetaPolicy.enoughSpace(size+BetaPolicy.storageReserve-1,size))
        assertFalse(BetaPolicy.enoughSpace(Long.MAX_VALUE, -1))
        assertTrue(BetaPolicy.enoughSpace(Long.MAX_VALUE,Long.MAX_VALUE-BetaPolicy.storageReserve))
    }
    @Test fun severeThermalsPauseEvenWhenCharging() {
        assertFalse(BetaPolicy.thermalPause(2))
        assertTrue(BetaPolicy.thermalPause(3))
        assertTrue(BetaPolicy.thermalPause(6))
    }
    @Test fun archiveLimitsCountAllEntriesAndExactlyUncompressedBytes() {
        val budget=ArchiveBudget(maxBytes=10,maxEntries=2)
        budget.entry();budget.add(6);budget.entry();budget.add(4)
        assertTrue(runCatching {budget.add(1)}.isFailure)
        assertTrue(runCatching {budget.entry()}.isFailure)
    }
    @Test fun archiveRejectsInvalidCountsWithoutOverflow() {
        val budget=ArchiveBudget()
        assertTrue(runCatching {budget.add(-1)}.isFailure)
        assertTrue(runCatching {budget.add(Long.MAX_VALUE)}.isFailure)
        budget.add(BetaPolicy.maxArchiveBytes)
    }
}
