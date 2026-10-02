// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

import org.junit.Assert.*
import org.junit.Test

class LanguageChoiceTest {
    @Test fun italianIsSelectedOnlyForItalianTags() {
        listOf("it","it-IT","it-CH","IT_it","italiano","Italian").forEach {assertEquals("it",LanguageChoice.code(it))}
        listOf("en","en-US","English","fr-FR","de-DE","ja-JP","","invalid").forEach {assertEquals("en",LanguageChoice.code(it))}
    }
    @Test fun missingAnswerAndProgressAreEnglish() {
        val progress=mutableListOf<String>()
        val (answer,evidence)=PostQuestions.answer("A short note about the sea.",emptyList(),"What is the fare?","English",{prompt->
            if(prompt.startsWith("Interpreta")) "TIPO: mirata\nTERMINI: fare" else "NON_TROVATO"
        },{progress+=it})
        assertEquals("I could not find enough information in the text to answer the question.",answer)
        assertEquals("",evidence)
        assertEquals("Interpreting the question…",progress.first())
        assertTrue(progress.any {it.startsWith("Reading the text:")})
    }
    @Test fun processingStagesFollowTheChosenLanguage() {
        val item=BrainItem("a","audio","Audio","","","","","","","processing_audio 2/9",0,0)
        assertEquals("Transcribing 2/9",PostContent.stage(item,"en"))
        assertEquals("Transcribing 2/9",PostContent.stage(item,"fr-FR"))
        assertEquals("Trascrizione 2/9",PostContent.stage(item,"it-IT"))
    }
}
