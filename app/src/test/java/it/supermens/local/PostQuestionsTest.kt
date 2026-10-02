// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

import org.junit.Assert.*
import org.junit.Test

class PostQuestionsTest {
    @Test fun malformedQueryPlanFallsBackToTheOriginalQuestion() {
        val (answer,evidence)=PostQuestions.answer("Introduzione. ".repeat(2500)+"Il ricavo annuo è 456 euro.",emptyList(),"Qual è il ricavo annuo?","italiano",{ prompt->
            if(prompt.startsWith("Interpreta")) "risposta non valida" else "Il ricavo annuo è 456 euro."
        })
        assertTrue(answer.contains("456"));assertTrue(evidence.contains("456"))
    }
    @Test fun aFailedExpansionDoesNotPreventAnswering() {
        val (answer,_)=PostQuestions.answer("La tariffa è 19 euro.",emptyList(),"Qual è la tariffa?","italiano",{ prompt->
            if(prompt.startsWith("Interpreta")) throw IllegalArgumentException("bad output") else "19 euro"
        })
        assertEquals("19 euro",answer)
    }
    @Test fun fallbackScansTheEndAndRejectsInventedQuotes() {
        val body="Cronaca del viaggio senza dettagli utili. ".repeat(600)+"La chiave di accesso è QX92."
        var scanned=0
        val (answer,evidence)=PostQuestions.answer(body,emptyList(),"Come entro?","italiano",{ prompt->
            when {
                prompt.startsWith("Interpreta") -> "TIPO: mirata\nTERMINI: login"
                prompt.startsWith("Trova") -> { scanned++;if(prompt.contains("QX92")) "La chiave di accesso è QX92.\nLa password inventata è ABC99." else "NON_TROVATO" }
                else -> "Usa QX92."
            }
        })
        assertTrue(scanned>1);assertTrue(answer.contains("QX92"));assertTrue(evidence.contains("QX92"));assertFalse(evidence.contains("ABC99"))
    }
    @Test fun retriesAfterMissingAnswerBeforeScanning() {
        var answers=0;var scans=0
        val (_,evidence)=PostQuestions.answer("La tariffa iniziale era diversa. ".repeat(500)+"La tariffa definitiva è 17 euro.",emptyList(),"tariffa","italiano",{ prompt->
            when {
                prompt.startsWith("Interpreta") -> "TIPO: mirata\nTERMINI: tariffa"
                prompt.startsWith("Trova") -> {scans++;"La tariffa definitiva è 17 euro."}
                else -> { answers++;if(prompt.contains("17 euro")) "17 euro" else "NON_TROVATO" }
            }
        })
        assertTrue(answers>=1);assertTrue(evidence.contains("17 euro"))
    }
    @Test fun globalQuestionInspectsEveryBatch() {
        var scanned=0
        val body="Una proposta comprende il treno. ".repeat(200)+"Una proposta comprende la nave. ".repeat(200)
        val (_,evidence)=PostQuestions.answer(body,emptyList(),"Confronta tutte le proposte","italiano",{ prompt->
            when {
                prompt.startsWith("Interpreta") -> "TIPO: globale\nTERMINI: proposte"
                prompt.startsWith("Trova") -> {scanned++;if(prompt.contains("nave")) "Una proposta comprende la nave." else "Una proposta comprende il treno."}
                else -> "Le proposte includono treno e nave."
            }
        })
        assertEquals(PostRetrieval.batches(PostRetrieval.passages(body,emptyList())).size,scanned)
        assertTrue(evidence.contains("treno"));assertTrue(evidence.contains("nave"))
    }
    @Test fun missingInformationIsReportedOnlyAfterScanning() {
        val (answer,evidence)=PostQuestions.answer("Una breve nota sul mare.",emptyList(),"Quanto costa il biglietto?","italiano",{ prompt->
            if(prompt.startsWith("Interpreta")) "TIPO: mirata\nTERMINI: biglietto" else "NON_TROVATO"
        })
        assertTrue(answer.contains("Non ho trovato"));assertEquals("",evidence)
    }
    @Test fun fallbackKeepsTheTimestampOfTheActualQuote() {
        val segments=listOf(Segment(0,"","Inizio del filmato.",0,-1,"caption"),Segment(0,"","Il luogo di incontro è Milano.",47000,-1,"caption"))
        val (_,evidence)=PostQuestions.answer("",segments,"Dove ci vediamo?","italiano",{ prompt->
            when {
                prompt.startsWith("Interpreta") -> "TIPO: globale\nTERMINI: appuntamento"
                prompt.startsWith("Trova") -> "Il luogo di incontro è Milano."
                else -> "A Milano."
            }
        })
        assertTrue(evidence.contains("00:00:47"));assertFalse(evidence.contains("00:00:00"))
    }
}
