// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local
import org.junit.Assert.*
import org.junit.Test

class TranscriptDisplayTest {
    @Test fun oneHugeSegmentHasShortPreviewAndCompleteDisplayBlocks() {
        val text="Long transcript ".repeat(5000)
        val original=Segment(1,"post",text,42000,3,"import")
        val blocks=TranscriptDisplay.blocks(listOf(original),"")
        assertEquals(text,blocks.joinToString("") {it.text})
        assertEquals(1200,TranscriptDisplay.preview(blocks).sumOf {it.text.length})
        assertTrue(TranscriptDisplay.expandable(blocks));assertEquals(42000,blocks.first().startMs)
        assertTrue(blocks.drop(1).all {it.startMs==-1L && it.page==-1})
        assertEquals(text,original.text)
    }
    @Test fun manySmallSegmentsAndBodyHaveTheSameLimit() {
        val segments=(0..200).map {Segment(it.toLong(),"post","Text $it ".repeat(20),it*1000L,-1,"caption")}
        val blocks=TranscriptDisplay.blocks(segments,"")
        assertEquals(segments.joinToString("") {it.text},blocks.joinToString("") {it.text})
        assertEquals(1200,TranscriptDisplay.preview(blocks).sumOf {it.text.length})
        assertTrue(TranscriptDisplay.expandable(TranscriptDisplay.blocks(emptyList(),"A".repeat(1201))))
    }
    @Test fun shortTextIsCompleteWithoutAnExpansionControl() {
        val blocks=TranscriptDisplay.blocks(emptyList(),"Short note")
        assertEquals("Short note",TranscriptDisplay.preview(blocks).single().text)
        assertFalse(TranscriptDisplay.expandable(blocks));assertTrue(TranscriptDisplay.blocks(emptyList(),"").isEmpty())
    }
}
