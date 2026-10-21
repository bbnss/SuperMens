// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

data class PostSummary(val title: String, val summary: String)
data class ImageDescription(val title: String, val description: String)

/** A small, explicit output format that can be checked before publishing or checkpointing. */
internal object PostTitles {
    fun title(value: String): String {
        val title = value.trim().trim('"', '“', '”').trim()
        require(title.isNotBlank() && title.length <= 240 && !title.contains('\n') && !title.contains('<')) { "Invalid descriptive title" }
        require(!Regex("(?<=[.!?])\\s+(?=[A-ZÀ-ÖØ-Þ])").containsMatchIn(title)) { "The title must be one sentence" }
        return title
    }

    fun parse(raw: String, contentTag: String): Pair<String, String> {
        val text = raw.trim().removePrefix("```xml").removePrefix("```").removeSuffix("```").trim()
        val match = Regex("<title>(.*?)</title>\\s*<$contentTag>(.*?)</$contentTag>", RegexOption.DOT_MATCHES_ALL).matchEntire(text)
            ?: error("Expected title and $contentTag")
        val body = match.groupValues[2].trim()
        require(body.isNotBlank()) { "Empty $contentTag" }
        return title(match.groupValues[1]) to body
    }

    fun generate(prompt: String, check: () -> Unit = {}, read: () -> String? = { null }, write: (String) -> Unit = {},
                 parse: (String) -> Pair<String, String>, generate: (String) -> String): Pair<String, String> {
        check()
        read()?.let { cached -> runCatching { parse(cached) }.getOrNull()?.let { return it } }
        var last: IllegalArgumentException? = null
        repeat(2) { attempt ->
            check()
            val raw = generate(prompt + if (attempt == 0) "" else "\nReturn exactly the requested tags and one short title sentence, with no other text.")
            val parsed = runCatching { parse(raw) }
            if (parsed.isSuccess) {
                check();write(raw)
                return parsed.getOrThrow()
            }
            last = IllegalArgumentException("Invalid model title/content response", parsed.exceptionOrNull())
        }
        throw last!!
    }
}
