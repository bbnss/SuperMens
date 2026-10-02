// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

/** Text shown or copied must not depend on how many segments the UI has loaded. */
object PostContent {
    fun description(segments: List<Segment>): String = segments.filter { it.source == "visione" }
        .joinToString("\n\n") { it.text.trim() }.trim()

    fun textSegments(type: String, segments: List<Segment>): List<Segment> = when (type) {
        "image" -> segments.filter { it.source == "ocr" }
        "audio", "video" -> segments.filter { it.source == "gemma audio" || it.startMs >= 0 }
            .sortedWith(compareBy<Segment> { if(it.startMs >= 0) it.startMs else Long.MAX_VALUE }.thenBy { it.page })
        "youtube" -> segments.filter { it.source.startsWith("caption") || it.source == "import" || it.startMs >= 0 }
        else -> segments.filter { it.source != "visione" }
    }

    fun copyText(item: BrainItem, segments: List<Segment>): String {
        val text = textSegments(item.type, segments).filter { it.text.isNotBlank() }
        if (item.type in setOf("audio", "video") && text.any { it.source == "gemma audio" }) {
            return text.fold("") { all, segment -> TranscriptMerge.join(all, segment.text) }
        }
        if (text.isNotEmpty()) return text.joinToString("\n\n") { it.text.trim() }
        return if (item.type == "image" || (segments.isNotEmpty() && item.type in setOf("audio", "video", "youtube"))) "" else item.body.trim()
    }

    fun preview(item: BrainItem, segments: List<Segment>): String = if(item.type == "image") {
        description(segments).ifBlank { copyText(item, segments) }
    } else item.summary.ifBlank { item.body }

    fun stage(item: BrainItem, language: String="it"): String = when {
        item.status=="acquiring" -> LanguageChoice.text(language,"Acquisizione materiale online","Acquiring source material")
        item.status=="waiting_network" -> LanguageChoice.text(language,"In attesa di Internet","Waiting for Internet")
        item.status=="waiting_charging" -> LanguageChoice.text(language,"In attesa della ricarica","Waiting for charging")
        item.status.startsWith("processing_audio") -> LanguageChoice.text(language,"Trascrizione ","Transcribing ")+item.status.substringAfter(' ', "").trim()
        item.status.startsWith("processing_pdf") -> LanguageChoice.text(language,"Acquisizione PDF ","Extracting PDF ")+item.status.substringAfter(' ', "").trim()
        item.status == "processing_ocr" -> LanguageChoice.text(language,"Lettura del testo OCR","Reading OCR text")
        item.status == "processing_vision" -> LanguageChoice.text(language,"Descrizione della foto","Describing photo")
        item.status == "processing_summary" -> LanguageChoice.text(language,"Generazione riassunto","Generating summary")
        item.status.startsWith("processing") -> LanguageChoice.text(language,"Acquisizione contenuto","Extracting content")
        item.status.startsWith("pending_ai") -> if(item.type == "image") LanguageChoice.text(language,"Descrizione in attesa del modello","Description waiting for the model") else LanguageChoice.text(language,"In attesa del modello locale","Waiting for the local model")
        item.status.startsWith("transcription_failed") -> LanguageChoice.text(language,"Trascrizione interrotta","Transcription interrupted")
        item.status.startsWith("processing_failed") -> LanguageChoice.text(language,"Elaborazione non riuscita","Processing failed")
        item.status.startsWith("extraction_failed") -> LanguageChoice.text(language,"Acquisizione non riuscita","Extraction failed")
        else -> LanguageChoice.text(language,"In attesa di elaborazione","Waiting for processing")
    }

    fun failed(item: BrainItem): Boolean = item.status.startsWith("processing_failed") || item.status.startsWith("extraction_failed") ||
        item.status.startsWith("transcription_failed") || (item.status.startsWith("pending_ai") && item.status.contains(" · "))
}
