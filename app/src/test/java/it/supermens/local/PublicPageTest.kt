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
}
