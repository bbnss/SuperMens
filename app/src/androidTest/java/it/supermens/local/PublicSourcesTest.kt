// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local
import org.junit.Assert.*
import org.junit.Test

class PublicSourcesTest {
    private fun live(name:String):String? {
        if(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("sourceChecks")!="true") return null
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        return java.io.File(context.filesDir,"source-checks/$name.txt").readText()
    }
    private fun verifyLiveCover(images:List<String>) {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val cover=images.firstNotNullOfOrNull {url ->runCatching {ImageStorage.downloadPreview(context,java.net.URL(url))}.getOrNull()}
        assertNotNull("At least one actual preview must download and decode",cover)
        try {assertTrue(cover!!.length()>0);assertTrue(ImageStorage.isOptimized(cover))} finally {cover?.delete()}
    }
    @Test fun redditStructuredPostPreservesBodyAndDecodesPreview() {
        val page=PublicPage.redditJson("""[{"data":{"children":[{"data":{"title":"Post title","selftext":"Full body with apostrophes: it's intact.","preview":{"images":[{"source":{"url":"https://preview.redd.it/image.jpg?width=640&amp;format=pjpg"}}]}}}]}}]""")
        assertEquals("Post title\n\nFull body with apostrophes: it's intact.",page.text)
        assertEquals("https://preview.redd.it/image.jpg?width=640&format=pjpg",page.images.single());assertFalse(page.limited)
    }
    @Test fun productStructuredMetadataSuppliesCoverAndDescription() {
        val page=PublicPage.parse("""<title>Amazon</title><script type="application/ld+json">{"@type":"Product","name":"Product fixture","description":"Product description","image":{"url":"https://images.example.org/product.jpg"}}</script>""","https://www.amazon.it/dp/fixture",false)
        assertEquals("Product fixture",page.title);assertEquals("Product description",page.text);assertEquals(listOf("https://images.example.org/product.jpg"),page.images)
        live("amazon")?.let {raw ->
            val actual=PublicPage.parse(raw,"https://www.amazon.it/dp/B0CFPLP3YL",false)
            assertTrue(actual.title.contains("Kindle"));assertTrue(actual.text.isNotBlank());assertTrue(actual.images.isNotEmpty())
            verifyLiveCover(actual.images)
            android.util.Log.i("SuperMensSourceCheck","Amazon: ${actual.text.length} text characters, ${actual.images.size} images")
        }
    }
    @Test fun linkedinStructuredArticleContainsFullText() {
        val page=PublicPage.parse("""<script type="application/ld+json">{"@graph":[{"@type":"Article","headline":"Article title","articleBody":"Full article body","image":["https://images.example.org/cover.jpg"]}]}</script>""","https://www.linkedin.com/posts/fixture",true)
        assertEquals("Full article body",page.text);assertFalse(page.limited);assertEquals(1,page.images.size)
        live("linkedin")?.let {raw ->
            val actual=PublicPage.parse(raw,"https://www.linkedin.com/posts/microsoft_fixture",true)
            assertTrue(actual.text.length>500);assertTrue(actual.images.any {it.contains("licdn.com")})
            verifyLiveCover(actual.images)
            android.util.Log.i("SuperMensSourceCheck","LinkedIn: ${actual.text.length} text characters, ${actual.images.size} images")
        }
    }
    @Test fun deletedRedditTextIsNeverInventedAndInvalidJsonFails() {
        val page=PublicPage.redditJson("""{"data":{"children":[{"data":{"title":"Removed post","selftext":"[removed]","thumbnail":"self"}}]}}""")
        assertEquals("Removed post",page.text);assertTrue(page.images.isEmpty());assertTrue(page.limited)
        assertTrue(runCatching {PublicPage.redditJson("[]")}.isFailure)
        live("reddit")?.let {raw ->
            val actual=PublicPage.parse(raw,"https://www.reddit.com/r/androiddev/comments/13buz3y/",true)
            assertEquals("",actual.text);assertTrue(actual.images.isEmpty())
            android.util.Log.i("SuperMensSourceCheck","Reddit network block correctly rejected")
        }
        live("reddit_old")?.let {raw ->
            val actual=PublicPage.parse(raw,"https://old.reddit.com/r/androiddev/comments/13buz3y/",true)
            assertEquals("",actual.text);assertTrue(actual.images.isEmpty())
            android.util.Log.i("SuperMensSourceCheck","Reddit classic login page correctly rejected")
        }
    }
}
