// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

/** Display slices only: stored text and copying are always complete. */
object TranscriptDisplay {
    const val previewCharacters = 1200
    const val blockCharacters = 1600
    fun blocks(segments: List<Segment>, body: String): List<Segment> {
        val source = segments.ifEmpty { listOf(Segment(0,"",body,-1,-1,"")) }
        return source.flatMap { segment -> segment.text.chunked(blockCharacters).mapIndexed { index, text ->
            segment.copy(text=text,startMs=if(index==0) segment.startMs else -1,page=if(index==0) segment.page else -1)
        } }
    }
    fun preview(blocks: List<Segment>): List<Segment> {
        var remaining = previewCharacters
        return blocks.mapNotNull { block ->
            if(remaining<=0) null else block.copy(text=block.text.take(remaining)).also { remaining-=it.text.length }
        }
    }
    fun expandable(blocks: List<Segment>) = blocks.sumOf { it.text.length.toLong() }>previewCharacters
}
