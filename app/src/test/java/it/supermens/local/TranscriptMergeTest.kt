// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptMergeTest {
    @Test fun removesRepeatedWordsAtClipBoundary() {
        assertEquals("Oggi andiamo a Roma. Domani torniamo.",TranscriptMerge.join("Oggi andiamo a Roma.","a Roma. Domani torniamo."))
    }

    @Test fun keepsDistinctSpeech() {
        assertEquals("La prima parte continua qui",TranscriptMerge.join("La prima parte","continua qui"))
    }

    @Test fun resumesWithPreviouslyCompletedClip() {
        val saved=listOf("L'audio inizia così", "inizia così e prosegue", "e prosegue fino alla fine")
        assertEquals("L'audio inizia così e prosegue fino alla fine",saved.fold("") { all,clip -> TranscriptMerge.join(all,clip) })
    }

    @Test fun acceptsDifferentItalianApostrophes() {
        assertEquals("Parliamo dell’acqua. Poi cambiamo tema.",TranscriptMerge.join("Parliamo dell’acqua.","dell'acqua. Poi cambiamo tema."))
    }
}
