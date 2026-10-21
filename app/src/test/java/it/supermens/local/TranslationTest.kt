package it.supermens.local

import org.junit.Assert.*
import org.junit.Test

class TranslationTest {
    @Test fun targetNamesAreExplicitAndIndependentOfUiLocale() {
        assertEquals("Italian (it)",Translation.target("it"))
        assertEquals("Italian (it)",Translation.target("italiano"))
        assertEquals("English (en-US)",Translation.target("en-US").replace("English (United States)","English"))
        assertTrue(Translation.instruction("it").contains("Italian (it)"))
        assertTrue(Translation.instruction("it",true).contains("previous attempt"))
    }
    @Test fun onlyConfidentWrongLanguagesAreRejectedAndRegionalTagsAreEquivalent() {
        assertTrue(Translation.wrongLanguage("text","it") {DetectedLanguage("en",.95f)})
        assertFalse(Translation.wrongLanguage("text","it") {DetectedLanguage("en",.5f)})
        assertFalse(Translation.wrongLanguage("text","it-IT") {DetectedLanguage("it",.95f)})
        assertFalse(Translation.wrongLanguage("text","it") {null})
        assertFalse(Translation.wrongLanguage("text","custom language") {DetectedLanguage("en",.99f)})
    }
}
