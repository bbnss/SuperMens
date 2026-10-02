// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

object TranscriptMerge {
    private val word=Regex("[\\p{L}\\p{N}'’]+")
    private fun normalize(value:String)=value.lowercase().replace('’','\'')
    fun join(previous:String,next:String):String {
        if(previous.isBlank()) return next.trim()
        if(next.isBlank()) return previous.trim()
        val left=word.findAll(previous).toList()
        val right=word.findAll(next).toList()
        val max=minOf(12,left.size,right.size)
        for(count in max downTo 1) {
            val tail=left.takeLast(count).map { normalize(it.value) }
            val head=right.take(count).map { normalize(it.value) }
            if(tail==head) {
                val remainder=next.substring(right[count-1].range.last+1).trimStart(' ',',','.',':',';','!','?')
                return if(remainder.isBlank()) previous.trim() else "${previous.trimEnd()} $remainder"
            }
        }
        return "${previous.trimEnd()} ${next.trimStart()}"
    }
}
