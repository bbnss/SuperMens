// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import java.time.Instant
import java.time.ZoneId

internal data class DailyKey(val date: String, val zone: String) {
    val value get() = "$zone|$date"
}
internal object DailyTimeline {
    fun key(time: Long, zone: ZoneId = ZoneId.systemDefault()) = DailyKey(Instant.ofEpochMilli(time).atZone(zone).toLocalDate().toString(), zone.id)
    fun groups(items: List<BrainItem>, zone: ZoneId = ZoneId.systemDefault()): Map<DailyKey, List<BrainItem>> =
        items.sortedWith(compareByDescending<BrainItem> { it.createdAt }.thenBy { it.id }).groupBy { key(it.createdAt, zone) }
    fun titles(items: List<BrainItem>) = items.filter { it.titleState in setOf("legacy", "generated") && it.title.isNotBlank() }
}
