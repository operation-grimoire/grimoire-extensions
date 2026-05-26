package io.grimoire.extensions.lib.theme

import Filter
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    // --- WPNovelsSource search filters ---------------------------------------

    @Test
    fun `wp search — empty query and no filters hits the Madara wp-manga endpoint`() {
        val url = TestWPNovels()
            .searchNovelsRequest("", 1, emptyList()).url.toString()
        assertEquals("https://example-wp.invalid/?s=&post_type=wp-manga", url)
    }

    @Test
    fun `wp search — page greater than one moves into Madara's /page/N/ path`() {
        val url = TestWPNovels()
            .searchNovelsRequest("love", 3, emptyList()).url.toString()
        assertEquals(
            "https://example-wp.invalid/page/3/?s=love&post_type=wp-manga",
            url,
        )
    }

    @Test
    fun `wp search — author and team feed Madara's author and artist params`() {
        val source = TestWPNovels()
        val filters = source.getFilterList().onEach { f ->
            if (f is Filter.Text) when (f.name) {
                "Author" -> f.state = "Mo Xiang"
                "Team" -> f.state = "Translator Group"
            }
        }
        val url = source.searchNovelsRequest("", 1, filters).url.toString()
        assertTrue(url.contains("&author=Mo+Xiang"), url)
        assertTrue(url.contains("&artist=Translator+Group"), url)
    }

    @Test
    fun `wp search — Sort emits m_orderby slug; Relevance omits the param`() {
        val source = TestWPNovels()
        val filters = source.getFilterList()
        val sort = filters.first { it.name == "Order by" }
        // Index 3 in the SortFilter labels is "Trending" → slug `trending`.
        @Suppress("UNCHECKED_CAST")
        (sort as Filter<Int>).state = 3
        assertTrue(
            source.searchNovelsRequest("", 1, filters).url.toString()
                .contains("&m_orderby=trending"),
        )
        // Index 0 ("Relevance") is the Madara default — emit no param.
        sort.state = 0
        assertFalse(
            source.searchNovelsRequest("", 1, filters).url.toString()
                .contains("m_orderby="),
        )
    }

    @Test
    fun `wp search — Adult content emits adult=0 hide and adult=1 only; All omits it`() {
        val source = TestWPNovels()
        val filters = source.getFilterList()
        val adult = filters.first { it.name == "Adult content" }
        @Suppress("UNCHECKED_CAST")
        val select = adult as Filter<Int>
        select.state = 1
        assertTrue(
            source.searchNovelsRequest("", 1, filters).url.toString()
                .contains("&adult=0"),
        )
        select.state = 2
        assertTrue(
            source.searchNovelsRequest("", 1, filters).url.toString()
                .contains("&adult=1"),
        )
        select.state = 0
        assertFalse(
            source.searchNovelsRequest("", 1, filters).url.toString()
                .contains("adult="),
        )
    }

    @Test
    fun `wp search — AND match emits op=1, OR match omits it`() {
        val source = TestWPNovels()
        val filters = source.getFilterList()
        val match = filters.first { it.name == "Genres match" }
        @Suppress("UNCHECKED_CAST")
        (match as Filter<Int>).state = 1
        assertTrue(
            source.searchNovelsRequest("", 1, filters).url.toString().contains("&op=1"),
        )
        match.state = 0
        assertFalse(
            source.searchNovelsRequest("", 1, filters).url.toString().contains("op="),
        )
    }

    @Test
    fun `wp search — checked statuses emit one status query param per slug`() {
        val source = TestWPNovels()
        val filters = source.getFilterList()
        val statusGroup = filters.first { it.name == "Novel Status" }
        @Suppress("UNCHECKED_CAST")
        val checkboxes = statusGroup.state as List<Filter.CheckBox>
        // OnGoing (0) and Canceled (2) — verifies non-checked rows are skipped.
        checkboxes[0].state = true
        checkboxes[2].state = true
        val url = source.searchNovelsRequest("", 1, filters).url.toString()
        // status[] is encoded as status%5B%5D — both must be present, others absent.
        assertTrue(url.contains("status%5B%5D=on-going"), url)
        assertTrue(url.contains("status%5B%5D=canceled"), url)
        assertFalse(url.contains("status%5B%5D=end"), url)
        assertFalse(url.contains("status%5B%5D=on-hold"), url)
        assertFalse(url.contains("status%5B%5D=upcoming"), url)
    }

    @Test
    fun `wp filters — dynamic Genres group appears only once genres are loaded`() {
        val source = TestWPNovels()
        // Before fetchFilterOptions, the dynamic group is suppressed so the
        // host doesn't show an empty Genres section.
        assertNull(source.getFilterList().firstOrNull { it.name == "Genres" })
    }
}
