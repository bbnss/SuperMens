// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import org.junit.Assert.*
import org.junit.Test

class XPostTest {
    @Test fun linkedArticleCardHasPreviewEvenWithoutPhotos() {
        val (page, quality) = XPost.parse("""{"text":"Read this article", "user":{"name":"Author"},
            "card":{"binding_values":{
              "thumbnail_image":{"image_value":{"url":"https://pbs.twimg.com/card_img/small.jpg"}},
              "photo_image_full_size_original":{"image_value":{"url":"https://pbs.twimg.com/card_img/large.jpg"}},
              "app_icon":{"image_value":{"url":"https://example.org/icon.jpg"}}
            }}}""", "Fallback")
        assertEquals("Author", page.title)
        assertEquals("Read this article", page.text)
        assertEquals(listOf("https://pbs.twimg.com/card_img/large.jpg", "https://pbs.twimg.com/card_img/small.jpg"), page.images)
        assertEquals("unknown", quality)
    }
    @Test fun photosVideoAndQuotedMediaAreRetainedWithoutDuplicates() {
        val (page, _) = XPost.parse("""{"text":"Post",
            "photos":[{"url":"https://pbs.twimg.com/media/photo.jpg"}],
            "mediaDetails":[{"media_url_https":"https://pbs.twimg.com/media/photo.jpg"}],
            "video":{"poster":"https://pbs.twimg.com/video_thumb/poster.jpg"},
            "quoted_tweet":{"photos":[{"url":"https://pbs.twimg.com/media/quote.jpg"}]}}""", "Fallback")
        assertEquals(listOf("https://pbs.twimg.com/media/photo.jpg", "https://pbs.twimg.com/video_thumb/poster.jpg", "https://pbs.twimg.com/media/quote.jpg"), page.images)
    }
    @Test fun privateOrDeletedPostDoesNotInventContent() {
        try { XPost.parse("{}", "Fallback");fail("Expected unavailable text") }
        catch (_:IllegalArgumentException) {}
    }
    @Test fun fullTextQualityAndCanonicalStatusPathsArePreserved() {
        val (page, quality) = XPost.parse("""{"text":"Short…", "note_tweet":{"note_tweet_results":{"result":{"text":"Entire long post"}}}}""", "Fallback")
        assertEquals("Entire long post", page.text); assertEquals("complete", quality)
        assertEquals("123", XPost.id("https://x.com/i/web/status/123?ref=test"))
        assertEquals("456", XPost.id("https://mobile.twitter.com/user/statuses/456"))
    }
}
