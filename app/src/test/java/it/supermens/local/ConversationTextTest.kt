// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import org.junit.Assert.*
import org.junit.Test

class ConversationTextTest {
    @Test fun translationChunksPreserveEveryCharacterAndUnicodeBoundary() {
        val input=("Ciao 🌍! 中文 العربية\n\n".repeat(500))+"Ultimo paragrafo."
        val chunks=ConversationText.chunks(input,100)
        assertEquals(input,chunks.joinToString(""))
        assertTrue(chunks.all {ConversationText.bytes(it)<=100})
        assertTrue(chunks.none {it.first().isLowSurrogate() || it.last().isHighSurrogate()})
        assertEquals(emptyList<String>(),ConversationText.chunks(""))
    }
    @Test fun contextKeepsWholeRecentExchangesWithoutDraftsOrOrphanedReplies() {
        val history=listOf(
            PostMessage(1,"p","user","old ".repeat(100),"chat","it","done"),
            PostMessage(2,"p","assistant","old reply","chat","it","done",1),
            PostMessage(3,"p","user","recent","chat","it","done"),
            PostMessage(4,"p","assistant","recent reply","chat","it","done",3),
            PostMessage(5,"p","user","draft","chat","it","draft"),
            PostMessage(6,"p","assistant","orphan","chat","it","done",999))
        assertEquals(listOf(3L,4L),ConversationText.recent(history,80).map {it.id})
        assertEquals(emptyList<PostMessage>(),ConversationText.recent(history,-1))
    }
    @Test fun additionalAudioTextIsNotMergedIntoTheTranscriptOverlap() {
        val item=BrainItem("p","audio","title","","","","","","","ready",0,0)
        val segments=listOf(Segment(1,"p","Siamo in casa",0,0,"gemma audio"),Segment(2,"p","in casa oggi",1000,1,"gemma audio"),Segment(3,"p","Nota aggiunta",-1,-1,"manual"))
        assertEquals("Siamo in casa oggi\n\nNota aggiunta",PostContent.copyText(item,segments))
        assertTrue(PostContent.storedBody(segments).contains("Nota aggiunta"))
    }
    @Test fun manualPhotoTextIsVisibleWithoutMixingInTheDescription() {
        val item=BrainItem("p","image","title","","","","","","","ready",0,0)
        val segments=listOf(Segment(1,"p","OCR",-1,-1,"ocr"),Segment(2,"p","Description",-1,-1,"visione"),Segment(3,"p","Added",-1,-1,"manual"))
        assertEquals("OCR\n\nAdded",PostContent.copyText(item,segments))
        assertEquals("Description",PostContent.description(segments))
    }
}
