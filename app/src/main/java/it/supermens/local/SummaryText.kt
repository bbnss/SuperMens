// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

/** Strip only standalone/punctuated assistant preambles, never facts inside the summary. */
object SummaryText {
    private val introduction = Regex(
        """(?i)^\s*(?:\*\*|\#\#?\s*)?(?:(?:ecco|qui trovi)\s+(?:(?:il|un)\s+)?riassunto(?:\s+finale)?(?:\s+(?:di|in)\s+\d+(?:\s*[-–]\s*\d+)?\s+frasi)?|(?:here(?:'s| is)|below is)\s+(?:the|a|your)\s+(?:final\s+)?summary(?:\s+(?:in|of)\s+\d+(?:\s*[-–]\s*\d+)?\s+sentences)?|(?:riassunto(?:\s+finale)?|(?:final\s+)?summary))\s*(?:\*\*)?\s*[:：]\s*(?:\*\*)?\s*"""
    )
    fun clean(value: String): String {
        val original = value.trim()
        val cleaned = original.replaceFirst(introduction, "").trim()
        return cleaned.ifBlank { original }
    }
}
