// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local
import org.junit.Assert.*
import org.junit.Test

class SummaryTextTest {
    @Test fun italianPreambleIsRemovedWithoutChangingFacts() {
        assertEquals("Il progetto costa 40 euro.",SummaryText.clean("Ecco riassunto finale di 3-4 frasi: Il progetto costa 40 euro."))
        assertEquals("Il progetto costa 40 euro.",SummaryText.clean("Ecco un riassunto finale di 3-4 frasi: Il progetto costa 40 euro."))
        assertEquals("Il video descrive Roma.",SummaryText.clean("**Ecco il riassunto finale:**\nIl video descrive Roma."))
    }
    @Test fun englishPreambleIsRemoved() {
        assertEquals("The team launched three products.",SummaryText.clean("Here is the final summary in 4–6 sentences:\nThe team launched three products."))
        assertEquals("Facts remain.",SummaryText.clean("Here's a summary: Facts remain."))
    }
    @Test fun realContentAndArchivedTextAreNotAggressivelyTrimmed() {
        for(text in listOf("Il riassunto finale del libro contiene quattro capitoli.","The summary explains the experiment.","L'autore scrive: Ecco il riassunto finale:","Summary:")) assertEquals(text,SummaryText.clean(text))
    }
}
