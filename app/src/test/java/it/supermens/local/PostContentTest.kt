package it.supermens.local

import org.junit.Assert.*
import org.junit.Test

class PostContentTest {
    private fun item(type: String, body: String="", summary: String="old") = BrainItem("id",type,"Title",summary,"","",body,"","","ready",0,0)
    private fun segment(text: String, source: String="ocr", time: Long=-1, page: Int=-1) = Segment(0,"id",text,time,page,source)

    @Test fun photoCopyContainsOnlyOcrAndPreviewUsesDescriptionInsteadOfOldSummary() {
        val segments=listOf(segment("shared", "testo condiviso"),segment("OCR line 1\nline 2"),segment("A red flower", "visione"))
        assertEquals("OCR line 1\nline 2",PostContent.copyText(item("image",body="mixed"),segments))
        assertEquals("A red flower",PostContent.preview(item("image"),segments))
        assertEquals("OCR line 1\nline 2",PostContent.preview(item("image"),segments.filter { it.source!="visione" }))
    }
    @Test fun photoWithoutOcrDoesNotCopyDescriptionOrMixedBody() {
        assertEquals("",PostContent.copyText(item("image","A red flower"),listOf(segment("A red flower","visione"))))
        assertEquals("",PostContent.copyText(item("image","Unknown legacy text"),emptyList()))
    }
    @Test fun copiesAllTranscriptSegmentsWithoutLabelsAndTimestamps() {
        val segments=(0 until 150).map { segment("Paragraph $it","caption youtube",it*1000L) }
        val copy=PostContent.copyText(item("youtube"),segments)
        assertTrue(copy.endsWith("Paragraph 149"));assertTrue(copy.contains("Paragraph 80\n\nParagraph 81"))
        assertFalse(copy.contains("caption youtube"));assertFalse(copy.contains("00:01"))
    }
    @Test fun audioIsOrderedAndClipOverlapIsRemovedWithoutSharedText() {
        val segments=listOf(segment("ignore","testo condiviso"),segment("in casa oggi", "gemma audio",1000,1),segment("Siamo in casa","gemma audio",0,0))
        assertEquals("Siamo in casa oggi",PostContent.copyText(item("audio"),segments))
    }
    @Test fun pdfKeepsParagraphsButNotPageMarkersAndPlainNoteUsesBody() {
        assertEquals("First\n\nLast",PostContent.copyText(item("pdf"),listOf(segment("First","pdf",page=1),segment("Last","pdf",page=301))))
        assertEquals("Original note",PostContent.copyText(item("note","Original note"),emptyList()))
    }
    @Test fun statusLabelsIncludeProgressAndErrors() {
        val image=item("image").copy(status="processing_vision")
        assertEquals("Descrizione della foto",PostContent.stage(image))
        assertEquals("Trascrizione 3/12",PostContent.stage(item("audio").copy(status="processing_audio 3/12")))
        assertTrue(PostContent.failed(image.copy(status="pending_ai · Model error")))
        assertFalse(PostContent.failed(image.copy(status="pending_ai")))
    }
}
