package it.supermens.local

import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln

/** Searches the full source before limiting the context sent to the model. */
object PostRetrieval {
    const val contextChars = 5200
    // UTF-8 bytes are a conservative proxy for text size across different scripts.
    fun contextSize(text: String): Int = text.toByteArray(Charsets.UTF_8).size
    fun limitContext(text: String, budget: Int = contextChars): String {
        var low=0;var high=text.length
        while(low<high) { val mid=(low+high+1)/2;if(contextSize(text.take(mid))<=budget) low=mid else high=mid-1 }
        if(low>0 && low<text.length && text[low-1].isHighSurrogate()) low--
        return text.take(low)
    }
    private const val chunkChars = 1050
    private const val overlap = 180
    data class Passage(val index: Int, val text: String, val evidence: String)
    data class Hit(val passage: Passage, val score: Double)
    private val stop = ("a al alla alle allo ai agli anche che chi come con da dal dalla delle dei del di e è era gli i il in la le lo ma nel nella non o per più quale quali quando quanto quante quanti se si sono su tra un una uno dove cosa mi puoi puoi potresti dimmi spiegami " +
        "the a an and are as at be by can could did do does for from how i in is it me of on or please tell that this to was were what when where which who why with would you your").split(' ').map { normalize(it) }.toSet()
    fun normalize(text: String): String = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    fun terms(text: String): Set<String> = Regex("[\\p{L}\\p{N}]+").findAll(text).map { it.value }
        .filter { raw -> (normalize(raw) !in stop || (raw.length in 2..4 && raw.all(Char::isUpperCase))) && (raw.length>=2 || raw.all(Char::isDigit)) }
        .map(::normalize).toSet()
    private fun stem(word: String): String = if(word.length>5 && word.last() in "aeio") word.dropLast(1) else word

    fun passages(body: String, segments: List<Segment>): List<Passage> {
        val sources = segments.filter { it.text.isNotBlank() }.ifEmpty { listOf(Segment(0,"",body,-1,-1,"testo")) }
        val result = mutableListOf<Passage>()
        var text = ""
        var evidence = ""
        fun flush() { if(text.isNotBlank()) result += Passage(result.size,text,evidence); text=""; evidence="" }
        sources.forEach { source ->
            val marker = when {
                source.startMs>=0 -> "%02d:%02d:%02d".format(Locale.ROOT,source.startMs/3600000,(source.startMs/60000)%60,(source.startMs/1000)%60)
                source.page>=0 -> "pagina ${source.page}"
                else -> source.source.ifBlank { "testo" }.take(60)
            }
            var start = 0
            while(start<source.text.length) {
                var end = (start+chunkChars).coerceAtMost(source.text.length)
                if(end<source.text.length) {
                    val boundary = source.text.lastIndexOf(' ',end)
                    if(boundary>start+chunkChars-150) end=boundary
                }
                val part = source.text.substring(start,end).trim()
                if(text.length+part.length>chunkChars || evidence.length+part.length+marker.length+25>chunkChars+180) flush()
                val labelled = "[$marker · blocco ${result.size+1}] $part"
                text += if(text.isEmpty()) part else "\n$part"
                evidence += if(evidence.isEmpty()) labelled else "\n$labelled"
                if(end==source.text.length) break
                flush()
                start=(end-overlap).coerceAtLeast(start+1)
            }
        }
        flush()
        return result
    }

    fun rank(passages: List<Passage>, question: String, expansion: String = ""): List<Hit> {
        val original = terms(question).map(::stem).toSet()
        val extra = terms(expansion.take(600)).map(::stem).toSet()-original
        val query = original+extra
        if(query.isEmpty() || passages.isEmpty()) return emptyList()
        val tokens = passages.map { termsWithRepeats(it.text).map(::stem).groupingBy { it }.eachCount() }
        val lengths = tokens.map { it.values.sum().coerceAtLeast(1) }
        val average = lengths.average()
        val idf = query.associateWith { word -> ln(1.0+(passages.size-tokens.count { word in it }+0.5)/(tokens.count { word in it }+0.5)) }
        return passages.mapIndexed { index, passage ->
            var score = 0.0
            query.forEach { word ->
                val frequency=(tokens[index][word] ?: 0).toDouble()
                score += (if(word in original) 2.0 else 1.0)*idf.getValue(word)*frequency*2.2/(frequency+1.2*(0.25+0.75*lengths[index]/average))
            }
            Hit(passage,score)
        }.filter { it.score>0 }.sortedByDescending { it.score }
    }
    private fun termsWithRepeats(text: String): List<String> = Regex("[\\p{L}\\p{N}]+").findAll(normalize(text)).map { it.value }.toList()

    fun select(passages: List<Passage>, hits: List<Hit>, excluded: Set<Int> = emptySet(), budget: Int = contextChars): List<Passage> {
        val chosen = linkedMapOf<Int,Passage>()
        var used = 0
        fun add(p: Passage?) {
            if(p!=null && p.index !in excluded && p.index !in chosen && used+contextSize(p.evidence)+2<=budget) {
                chosen[p.index]=p; used+=contextSize(p.evidence)+2
            }
        }
        // Keep neighbouring context for the strongest result, then prefer other relevant passages.
        hits.firstOrNull { it.passage.index !in excluded }?.let { hit ->
            add(hit.passage); add(passages.getOrNull(hit.passage.index-1)); add(passages.getOrNull(hit.passage.index+1))
        }
        hits.forEach { add(it.passage) }
        return chosen.values.sortedBy { it.index }
    }
    fun evidence(passages: List<Passage>): String = passages.joinToString("\n\n") { it.evidence }
    fun batches(passages: List<Passage>, budget: Int = contextChars): List<List<Passage>> {
        val batches=mutableListOf<List<Passage>>()
        var batch=mutableListOf<Passage>();var used=0
        passages.forEach { p ->
            if(batch.isNotEmpty() && used+contextSize(p.evidence)+2>budget) { batches+=batch;batch=mutableListOf();used=0 }
            batch+=p;used+=contextSize(p.evidence)+2
        }
        if(batch.isNotEmpty()) batches+=batch
        return batches
    }
}
