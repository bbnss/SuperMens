// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

import java.io.File

/** Image analysis finishes at OCR + vision. It deliberately has no summarization stage. */
internal object ImageAnalysis {
    fun enrich(store: BrainStore, item: BrainItem, modelReady: Boolean, language: String="it", complete:((()->Unit)->Unit)={it()}, force:Boolean=false, describe: (File)->ImageDescription) {
        if(!force && PostContent.description(store.segments(item.id)).isNotBlank()) {
            complete {store.update(item.id,status="ready")}
            return
        }
        if(!modelReady) {
            store.update(item.id,status="pending_ai")
            return
        }
        store.update(item.id,status="processing_vision")
        val result=describe(File(item.attachment))
        val description=result.description.trim()
        val title=PostTitles.title(result.title)
        require(description.isNotBlank()) { LanguageChoice.text(language,"Il modello non ha restituito una descrizione","The model returned no description") }
        complete {
        store.addSegments(item.id,store.segments(item.id).filter { it.source!="visione" } + Segment(0,item.id,description,-1,-1,"visione"))
        store.update(item.id,title=title,titleState="generated",status="ready")
        }
    }
}
