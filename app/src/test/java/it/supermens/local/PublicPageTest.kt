// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local
import org.junit.Assert.*
import org.junit.Test
class PublicPageTest {
    @Test fun quotedApostrophesAndEntitiesArePreserved() {
        val page=PublicPage.parse("""<meta property="og:description" content="L'app conserva tutto il testo &amp; l'originale"><title>Demo</title>""","https://example.org",true)
        assertEquals("L'app conserva tutto il testo & l'originale",page.text)
    }
    @Test fun githubPreviewAndRedirectedRelativeFallbacksAreResolved() {
        val page=PublicPage.parse("""<meta name='twitter:image' content='../preview.png'><meta property='og:image' content='https://opengraph.githubassets.com/hash/openai/codex'>""","https://github.com/openai/codex",false)
        assertEquals(listOf("https://opengraph.githubassets.com/hash/openai/codex","https://github.com/preview.png"),page.images)
    }
    @Test fun socialLoginPageDoesNotBecomePostText() {
        val page=PublicPage.parse("<title>Login • Instagram</title><meta name='description' content='Log in to Instagram'>","https://instagram.com/p/test",true)
        assertEquals("",page.text);assertEquals("",page.title)
    }
    @Test fun articleExcludesNavigationAndScriptsWithoutTruncatingLongText() {
        val body="Full text paragraph ".repeat(12000)
        val page=PublicPage.parse("<nav>Navigation</nav><article>$body<script>secret</script></article>","https://example.org",false)
        assertEquals(body.trim(),page.text);assertFalse(page.limited)
    }
    @Test fun redditBodyBeatsTruncatedDescription() {
        val page=PublicPage.parse("<title>Reddit</title><meta property='og:description' content='short'><shreddit-post post-title='A public post'><div slot='text-body'>The complete post body</div></shreddit-post>","https://www.reddit.com/r/test/comments/123/title/",true)
        assertEquals("A public post",page.title);assertEquals("The complete post body",page.text);assertFalse(page.limited)
    }
    @Test fun linkedinPostAndCoverAreReadWithoutNavigation() {
        val page=PublicPage.parse("<title>Post</title><nav>Sign in</nav><div class='share-update-card__update-text'>Public LinkedIn text</div><div class='share-update-card'><img src='/cover.jpg'></div>","https://www.linkedin.com/posts/example",true)
        assertEquals("Public LinkedIn text",page.text);assertEquals(listOf("https://www.linkedin.com/cover.jpg"),page.images)
    }
    @Test fun amazonProductImageHasPriorityOverSiteLogo() {
        val page=PublicPage.parse("<meta property='og:image' content='/logo.png'><span id='productTitle'>Product title</span><ul id='feature-bullets'><li>Product feature</li></ul><img id='landingImage' src='/small.jpg' data-old-hires='/large.jpg'>","https://www.amazon.it/dp/test",false)
        assertEquals("Product title",page.title);assertTrue(page.text.contains("Product feature"));assertFalse(page.images.any {it.contains("logo")});assertTrue(page.images.contains("https://www.amazon.it/large.jpg"))
    }
    @Test fun blockedPageIsNotStoredAsArticleText() {
        val page=PublicPage.parse("<title>Robot Check</title><article>Enter captcha</article>","https://www.amazon.it/dp/test",false)
        assertEquals("",page.text);assertEquals("",page.title)
        val login=PublicPage.parse("<title>Welcome to Reddit</title><main>Log in to continue</main>","https://www.reddit.com/login/",true)
        assertEquals("",login.text);assertTrue(login.images.isEmpty())
    }
}
