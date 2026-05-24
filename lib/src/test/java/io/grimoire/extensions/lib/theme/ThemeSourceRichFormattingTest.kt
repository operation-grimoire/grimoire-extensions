package io.grimoire.extensions.lib.theme

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the rich-formatting opt-in baked into the shared base classes. Both
 * `NovelFullThemeSource` and `WPNovelsSource` now populate `NovelPage
 * .formattedText` via `Element.richHtml()` when a paragraph has inline
 * formatting, and leave it null when richHtml() and text() match.
 */
class ThemeSourceRichFormattingTest {

    private class TestNovelFull : NovelFullThemeSource() {
        override val id = 999L
        override val name = "test"
        override val lang = "en"
        override val baseUrl = "https://example.invalid"
    }

    private class TestWPNovels : WPNovelsSource() {
        override val id = 998L
        override val name = "test-wp"
        override val lang = "en"
        override val baseUrl = "https://example-wp.invalid"
    }

    // --- NovelFullThemeSource -------------------------------------------

    @Test
    fun `novelfull plain paragraph leaves formattedText null`() {
        val source = TestNovelFull()
        val el = Jsoup.parseBodyFragment(
            """<div id="chapter-content"><p>Hello world.</p></div>""",
        ).selectFirst("#chapter-content p")!!
        val page = source.pageFromElement(el, 0)
        assertEquals("Hello world.", page.text)
        assertNull(page.formattedText, "no formatting to preserve, expect null")
        assertNull(page.imageUrl)
    }

    @Test
    fun `novelfull italics paragraph populates formattedText`() {
        val source = TestNovelFull()
        val el = Jsoup.parseBodyFragment(
            """<div id="chapter-content"><p>He thought, <em>that's odd.</em></p></div>""",
        ).selectFirst("#chapter-content p")!!
        val page = source.pageFromElement(el, 0)
        assertEquals("He thought, that's odd.", page.text)
        assertEquals("He thought, <i>that's odd.</i>", page.formattedText)
    }

    @Test
    fun `novelfull strong inside em produces nested bold-italic`() {
        val source = TestNovelFull()
        val el = Jsoup.parseBodyFragment(
            """<div id="chapter-content"><p><em>shout <strong>loud</strong> back</em></p></div>""",
        ).selectFirst("#chapter-content p")!!
        val page = source.pageFromElement(el, 0)
        assertEquals("<i>shout <b>loud</b> back</i>", page.formattedText)
    }

    @Test
    fun `novelfull image paragraph keeps imageUrl path`() {
        val source = TestNovelFull()
        val el = Jsoup.parseBodyFragment(
            """<div id="chapter-content"><p><img src="https://example.invalid/i.jpg"/></p></div>""",
        ).selectFirst("#chapter-content p")!!
        val page = source.pageFromElement(el, 0)
        assertEquals("https://example.invalid/i.jpg", page.imageUrl)
        assertEquals("", page.text)
        assertNull(page.formattedText)
    }

    @Test
    fun `novelfull br between sentences becomes a hard line break`() {
        val source = TestNovelFull()
        val el = Jsoup.parseBodyFragment(
            """<div id="chapter-content"><p>Line one.<br>Line two.</p></div>""",
        ).selectFirst("#chapter-content p")!!
        val page = source.pageFromElement(el, 0)
        assertNotNull(page.formattedText, "br should make formattedText differ from text")
        assertTrue(page.formattedText!!.contains("<br>"), "br should appear in formattedText")
    }

    // --- WPNovelsSource -------------------------------------------------

    @Test
    fun `wp plain paragraph leaves formattedText null`() {
        val source = TestWPNovels()
        val el = Jsoup.parseBodyFragment(
            """<div class="reading-content"><p>Just prose.</p></div>""",
        ).selectFirst("div.reading-content p")!!
        val page = source.pageFromElement(el, 0)
        assertEquals("Just prose.", page.text)
        assertNull(page.formattedText)
    }

    @Test
    fun `wp italics paragraph populates formattedText`() {
        val source = TestWPNovels()
        val el = Jsoup.parseBodyFragment(
            """<div class="reading-content"><p>Look, <em>this</em>.</p></div>""",
        ).selectFirst("div.reading-content p")!!
        val page = source.pageFromElement(el, 0)
        assertEquals("Look, this.", page.text)
        assertEquals("Look, <i>this</i>.", page.formattedText)
    }

    @Test
    fun `wp link is preserved with absolutised href`() {
        val source = TestWPNovels()
        val doc = Jsoup.parse(
            """<html><body><div class="reading-content"><p>See <a href="/about">about</a>.</p></div></body></html>""",
            "https://example-wp.invalid/",
        )
        val el = doc.selectFirst("div.reading-content p")!!
        val page = source.pageFromElement(el, 0)
        assertEquals("""See <a href="https://example-wp.invalid/about">about</a>.""", page.formattedText)
    }
}
