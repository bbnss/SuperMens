package it.supermens.local

import org.junit.Assert.*
import org.junit.Test

class PostTitlesTest {
    @Test fun parsesTitleAndCompleteSummaryWithoutTruncatingUnicode() {
        val text="Città, energia e 🌍. ".repeat(80)
        val parsed=PostTitles.parse("```xml\n<title>Le città investono nelle energie rinnovabili.</title>\n<summary>$text</summary>\n```","summary")
        assertEquals("Le città investono nelle energie rinnovabili.",parsed.first)
        assertEquals(text.trim(),parsed.second)
    }
    @Test fun rejectsMissingTitleEmptyBodyAndMultipleTitleSentences() {
        listOf("<summary>Text</summary>","<title></title><summary>Text</summary>",
            "<title>Topic</title><summary></summary>","<title>One. Two.</title><summary>Text</summary>",
            "<title>Topic</title><summary>Text</summary>Some commentary").forEach {
            assertTrue(it,runCatching {PostTitles.parse(it,"summary")}.isFailure)
        }
    }
    @Test fun onlyValidOutputIsCheckpointedAndACompletedResultIsReused() {
        var calls=0;var checkpoint:String?=null
        fun run()=PostTitles.generate("prompt",read={checkpoint},write={checkpoint=it},parse={PostTitles.parse(it,"summary")}) {
            calls++
            if(calls==1) "Malformed" else "<title>Topic</title><summary>Facts</summary>"
        }
        assertEquals("Topic" to "Facts",run());assertEquals(2,calls)
        assertEquals("Topic" to "Facts",run());assertEquals(2,calls)
    }
    @Test fun malformedOutputRemainsRetryableWithoutSavingAnInvalidCheckpoint() {
        var calls=0;var writes=0
        assertTrue(runCatching {PostTitles.generate("prompt",write={writes++},parse={PostTitles.parse(it,"summary")}) {calls++;"Invalid"}}.isFailure)
        assertEquals(2,calls);assertEquals(0,writes)
    }
    @Test fun interruptedGenerationCannotPublishItsResult() {
        var cancelled=false;var writes=0
        assertTrue(runCatching {PostTitles.generate("prompt",check={if(cancelled) throw InterruptedException()},write={writes++},parse={PostTitles.parse(it,"summary")}) {
            cancelled=true;"<title>Topic</title><summary>Facts</summary>"
        }}.isFailure)
        assertEquals(0,writes)
    }
}
