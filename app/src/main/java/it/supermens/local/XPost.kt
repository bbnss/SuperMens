// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

import org.json.JSONObject

/** Public syndication has separate image fields for photos, videos, cards and quoted posts. */
internal object XPost {
    fun id(url: String): String? = Regex("/(?:status|statuses)/(\\d+)").find(url)?.groupValues?.get(1)
    fun parse(json: String, fallbackTitle: String): Pair<PublicPage.Content, String> {
        val obj = JSONObject(json)
        val note = obj.optJSONObject("note_tweet")
        val full = note?.optString("text").orEmpty()
            .ifBlank { note?.optJSONObject("note_tweet_results")?.optJSONObject("result")?.optString("text").orEmpty() }
            .ifBlank { obj.optString("full_text") }
        val text = full.ifBlank { obj.optString("text") }
        require(text.isNotBlank()) { "Public post text unavailable" }
        val quality = if (full.isNotBlank()) "complete" else if (text.endsWith("…") || text.endsWith("...")) "limited" else "unknown"
        return PublicPage.Content(obj.optJSONObject("user")?.optString("name").orEmpty().ifBlank { fallbackTitle },
            text, images(obj), quality != "complete") to quality
    }
    fun images(obj: JSONObject): List<String> = buildList {
        fun addUrl(value: String?) { if (value?.startsWith("https://") == true) add(value) }
        fun media(post: JSONObject) {
            for (key in listOf("photos", "mediaDetails")) {
                val entries = post.optJSONArray(key) ?: continue
                for (i in 0 until entries.length()) entries.optJSONObject(i)?.let { entry ->
                    for (field in listOf("media_url_https", "url", "thumbnail_url")) addUrl(entry.optString(field))
                }
            }
            post.optJSONObject("video")?.let { addUrl(it.optString("poster")) }
        }
        fun card(post: JSONObject) {
            val bindings = post.optJSONObject("card")?.optJSONObject("binding_values") ?: return
            // Prefer large content previews and skip avatars/logos/palette metadata.
            val preferred = listOf("photo_image_full_size_original", "summary_photo_image_original",
                "thumbnail_image_original", "photo_image_full_size_large", "summary_photo_image_large",
                "thumbnail_image_large", "photo_image_full_size", "summary_photo_image", "thumbnail_image", "player_image")
            for (key in preferred) addUrl(bindings.optJSONObject(key)?.optJSONObject("image_value")?.optString("url"))
        }
        media(obj)
        card(obj)
        obj.optJSONObject("quoted_tweet")?.let { media(it); card(it) }
    }.distinct()
}
