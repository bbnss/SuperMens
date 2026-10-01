package it.supermens.local

import org.junit.Assert.*
import org.junit.Test

class PostRetrievalTest {
    @Test fun findsAnswerAfterTheOldBodyLimit() {
        val body="Paragrafo introduttivo senza informazioni rilevanti. ".repeat(500)+"Il codice segreto del progetto è ZX91."
        val passages=PostRetrieval.passages(body,emptyList())
        val selected=PostRetrieval.select(passages,PostRetrieval.rank(passages,"Qual è il codice segreto?"))
        assertTrue(PostRetrieval.evidence(selected).contains("ZX91"))
        assertTrue(PostRetrieval.evidence(selected).length<=PostRetrieval.contextChars)
    }
    @Test fun searchesBeyondTheBeginningOfALongPage() {
        val segment=Segment(0,"id","Introduzione generale. ".repeat(200)+"Il preventivo definitivo ammonta a 712 euro.",-1,9,"pdf")
        val passages=PostRetrieval.passages("",listOf(segment))
        val evidence=PostRetrieval.evidence(PostRetrieval.select(passages,PostRetrieval.rank(passages,"Qual è il preventivo definitivo?")))
        assertTrue(evidence.contains("712 euro"))
        assertTrue(evidence.contains("pagina 9"))
    }
    @Test fun expansionsFindSynonymsAndOriginalWordsRemainUseful() {
        val passages=PostRetrieval.passages("",listOf(
            Segment(0,"","Le automobili elettriche costano 25000 euro.",-1,1,"pdf"),
            Segment(0,"","Il prezzo delle biciclette è 800 euro.",-1,2,"pdf")))
        assertTrue(PostRetrieval.rank(passages,"Quanto costano le vetture?","automobili; prezzo").first().passage.text.contains("automobili"))
        assertTrue(PostRetrieval.rank(passages,"biciclette","automobili").any { it.passage.text.contains("biciclette") })
    }
    @Test fun preservesShortNamesNumbersAndAccentsWithoutSubstringMatches() {
        assertTrue(PostRetrieval.terms("UE AI 7 città").containsAll(listOf("ue","ai","7","citta")))
        val passages=PostRetrieval.passages("",listOf(Segment(0,"","Scienza sociale",-1,1,"pdf"),Segment(0,"","Il documento UE è qui",-1,2,"pdf")))
        assertEquals(1,PostRetrieval.rank(passages,"UE").size)
    }
    @Test fun includesNearbyTimestampedSentences() {
        val segments=listOf(Segment(0,"","Quando parliamo di batterie,",10000,-1,"caption"),Segment(0,"","la durata è di dieci ore.",12000,-1,"caption"))
        val passages=PostRetrieval.passages("",segments)
        val evidence=PostRetrieval.evidence(PostRetrieval.select(passages,PostRetrieval.rank(passages,"batterie")))
        assertTrue(evidence.contains("dieci ore"))
        assertTrue(evidence.contains("00:00:12"))
    }
    @Test fun everyCharacterCanBeFoundInThePassagesEvenAcrossBoundaries() {
        val body=(1..1600).joinToString(" ") { "parola$it" }
        val passages=PostRetrieval.passages(body,emptyList())
        (1..1600).forEach { assertTrue("Missing parola$it",passages.any { passage->Regex("\\bparola$it\\b").containsMatchIn(passage.text) }) }
        assertTrue(PostRetrieval.batches(passages).all { PostRetrieval.evidence(it).length<=PostRetrieval.contextChars })
    }
    @Test fun retrySelectsNewEvidence() {
        val passages=PostRetrieval.passages("Elemento budget: 100. ".repeat(400),emptyList())
        val hits=PostRetrieval.rank(passages,"budget")
        val first=PostRetrieval.select(passages,hits)
        val second=PostRetrieval.select(passages,hits,first.map { it.index }.toSet())
        assertTrue(second.isNotEmpty())
        assertTrue(second.none { it in first })
    }
    @Test fun nonLatinTextIsBudgetedByBytesAndDoesNotSplitEmoji() {
        val passages=PostRetrieval.passages("東京の交通機関について説明します。".repeat(500),emptyList())
        assertTrue(PostRetrieval.batches(passages).all { PostRetrieval.contextSize(PostRetrieval.evidence(it))<=PostRetrieval.contextChars })
        val limited=PostRetrieval.limitContext("😀😀😀",5)
        assertEquals("😀",limited)
    }
}
