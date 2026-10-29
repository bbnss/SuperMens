// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

/** Pure policies shared by device checks, archive streams and their regression tests. */
internal object BetaPolicy {
    const val storageReserve = 512L * 1024 * 1024
    const val idleUnloadMs = 5 * 60 * 1000L
    const val maxArchiveBytes = 2_000_000_000L
    const val maxArchiveEntries = 9999
    fun enoughSpace(available: Long, incoming: Long) = incoming >= 0 && available >= incoming && available - incoming >= storageReserve
    fun thermalPause(status: Int) = status >= 3 // PowerManager.THERMAL_STATUS_SEVERE
}

internal class ArchiveBudget(private val maxBytes: Long = BetaPolicy.maxArchiveBytes,
                             private val maxEntries: Int = BetaPolicy.maxArchiveEntries) {
    private var bytes = 0L
    private var entries = 0
    fun entry() { require(entries < maxEntries) { "Backup exceeds the supported entry count" }; entries++ }
    fun add(count: Long) {
        require(count >= 0 && count <= maxBytes - bytes) { "Backup exceeds the supported uncompressed size" }
        bytes += count
    }
}
