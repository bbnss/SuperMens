package it.supermens.local

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DailyTimelineTest {
    private fun post(id:String,time:String)=BrainItem(id,"note",id,"","","","","","","ready",Instant.parse(time).toEpochMilli(),0)
    @Test fun midnightAndZoneChangesGroupByCreationRatherThanModification() {
        val a=post("a","2026-10-11T21:59:00Z").copy(updatedAt=Instant.parse("2026-10-13T10:00:00Z").toEpochMilli())
        val b=post("b","2026-10-11T22:01:00Z")
        val rome=DailyTimeline.groups(listOf(a,b),ZoneId.of("Europe/Rome"))
        assertEquals(listOf("2026-10-12","2026-10-11"),rome.keys.map {it.date})
        assertEquals(listOf("b","a"),rome.values.flatten().map {it.id})
        assertEquals(1,DailyTimeline.groups(listOf(a,b),ZoneId.of("UTC")).size)
    }
    @Test fun onlyLegacyAndGeneratedTitlesEnterTheList() {
        val a=post("a","2026-10-11T21:59:00Z")
        assertEquals(listOf("legacy","generated"),DailyTimeline.titles(listOf(a,a.copy(id="legacy",titleState="legacy"),a.copy(id="generated",titleState="generated"))).map {it.id})
    }
}
