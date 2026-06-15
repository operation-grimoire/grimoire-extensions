package io.grimoire.extension.en.royalroad

import io.grimoire.api.model.Filter
import io.grimoire.api.model.NovelStatus
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoyalRoadTest {

    private val source = RoyalRoad()
    private val baseUrl = "https://www.royalroad.com/"

    private fun doc(html: String) = Jsoup.parse(html, baseUrl)

    // --- Fiction list -------------------------------------------------------

    @Test
    fun `fiction list extracts title, relative url and absolute cover`() {
        val novels = source.parseFictionListDocument(
            doc(
                """
                <div class="fiction-list" id="result">
                  <div class="fiction-list-item row">
                    <figure class="col-sm-2">
                      <a href="/fiction/21220/mother-of-learning">
                        <img class="img-responsive" data-type="cover"
                             src="https://www.royalroadcdn.com/public/covers-full/21220-mother-of-learning.jpg?time=1"/>
                      </a>
                    </figure>
                    <div class="col-sm-10">
                      <h2 class="fiction-title">
                        <a href="/fiction/21220/mother-of-learning" class="font-red-sunglo bold">Mother of Learning</a>
                      </h2>
                    </div>
                  </div>
                </div>
                """.trimIndent(),
            ),
        )
        assertEquals(1, novels.size)
        val novel = novels.single()
        assertEquals("Mother of Learning", novel.title)
        assertEquals("/fiction/21220/mother-of-learning", novel.url)
        assertEquals(
            "https://www.royalroadcdn.com/public/covers-full/21220-mother-of-learning.jpg?time=1",
            novel.thumbnailUrl,
        )
    }

    @Test
    fun `nocover placeholder is treated as no thumbnail`() {
        val novels = source.parseFictionListDocument(
            doc(
                """
                <div class="fiction-list-item row">
                  <figure><img src="/dist/img/nocover-new-min.png"/></figure>
                  <div><h2 class="fiction-title"><a href="/fiction/1/x">Stub Story</a></h2></div>
                </div>
                """.trimIndent(),
            ),
        )
        assertNull(novels.single().thumbnailUrl)
    }

    // --- Novel details ------------------------------------------------------

    @Test
    fun `novel details parse title, author, status, genres and rating`() {
        val novel = source.novelDetailsFromDocument(
            doc(
                """
                <html><head>
                <script type="application/ld+json">
                {"@context":"https://schema.org","@type":"Book","name":"Mother of Learning",
                 "aggregateRating":{"@type":"AggregateRating","bestRating":5,"ratingValue":4.83,
                 "worstRating":0.5,"ratingCount":16946}}
                </script>
                </head><body>
                <div class="row fic-header">
                  <div class="cover-art-container">
                    <img class="thumbnail" src="https://www.royalroadcdn.com/public/covers-full/21220.jpg"/>
                  </div>
                  <div class="col">
                    <h1 class="font-white">Mother of Learning</h1>
                    <h4 class="font-white"><span class="small">by </span>
                      <span><a href="/profile/100374" class="font-white">nobody103</a></span>
                    </h4>
                  </div>
                </div>
                <div class="fiction-info">
                  <span class="label">Original</span>
                  <span class="label">COMPLETED</span>
                  <span class="tags">
                    <a class="fiction-tag" href="/fictions/search?tagsAdd=loop">Time Loop</a>
                    <a class="fiction-tag" href="/fictions/search?tagsAdd=magic">Magic</a>
                  </span>
                </div>
                <div class="description"><div class="hidden-content"><p>Zorian is a teenage mage.</p></div></div>
                </body></html>
                """.trimIndent(),
            ),
            "https://www.royalroad.com/fiction/21220/mother-of-learning",
        )
        assertEquals("Mother of Learning", novel.title)
        assertEquals("nobody103", novel.author)
        assertEquals(NovelStatus.COMPLETED, novel.status)
        assertEquals(listOf("Time Loop", "Magic"), novel.genres)
        assertEquals(4.83f, novel.rating)
        assertEquals(16946, novel.ratingCount)
        assertTrue(novel.description!!.contains("teenage mage"))
        assertTrue(novel.initialized)
    }

    @Test
    fun `description keeps paragraph breaks, inline formatting and links`() {
        val novel = source.novelDetailsFromDocument(
            doc(
                """
                <html><body>
                <div class="row fic-header"><div class="col"><h1>X</h1></div></div>
                <div class="description"><div class="hidden-content">
                  <p>First <strong>paragraph</strong> of the synopsis.</p>
                  <p>Second one with a <a href="https://example.com/more">link</a>.</p>
                </div></div>
                </body></html>
                """.trimIndent(),
            ),
            "https://www.royalroad.com/fiction/1/x",
        )
        val desc = novel.description!!
        // Paragraphs are separated for fromHtml, inline formatting + link preserved.
        assertTrue(desc.contains("<br><br>"))
        assertTrue(desc.contains("<b>paragraph</b>"))
        assertTrue(desc.contains("""<a href="https://example.com/more">link</a>"""))
        // No collapsed run of three-or-more breaks.
        assertFalse(desc.contains("<br><br><br>"))
    }

    @Test
    fun `ongoing status is recognised over the Original type label`() {
        val novel = source.novelDetailsFromDocument(
            doc(
                """
                <div class="row fic-header"><div class="col"><h1 class="font-white">X</h1></div></div>
                <div class="fiction-info">
                  <span class="label">Fanfiction</span>
                  <span class="label">ONGOING</span>
                </div>
                """.trimIndent(),
            ),
            "https://www.royalroad.com/fiction/2/x",
        )
        assertEquals(NovelStatus.ONGOING, novel.status)
    }

    // --- Chapter list (window.chapters JSON) --------------------------------

    @Test
    fun `chapters parse from window dot chapters in source order with dates and lock state`() {
        val html = """
            <script>
            window.chapters = [
              {"id":301778,"title":"1. Good Morning Brother","date":"2018-10-28T21:34:43Z",
               "order":0,"isUnlocked":true,"url":"/fiction/21220/mother-of-learning/chapter/301778/1-good-morning-brother"},
              {"id":301781,"title":"2. Patreon Bonus","date":"2018-10-29T10:00:00Z",
               "order":1,"isUnlocked":false,"url":"/fiction/21220/mother-of-learning/chapter/301781/2-patreon-bonus"}
            ];
            window.fictionId = 21220;
            </script>
        """.trimIndent()

        val chapters = source.chaptersFromHtml(html)
        assertEquals(2, chapters.size)
        assertEquals("1. Good Morning Brother", chapters[0].name)
        assertEquals(
            "/fiction/21220/mother-of-learning/chapter/301778/1-good-morning-brother",
            chapters[0].url,
        )
        assertFalse(chapters[0].locked)
        // 2018-10-28T21:34:43Z in epoch millis.
        assertEquals(1540762483000L, chapters[0].uploadDate)
        // order 0/1 => chapterNumber 1f/2f for stable host ordering.
        assertEquals(1f, chapters[0].chapterNumber)
        assertEquals(2f, chapters[1].chapterNumber)
        // isUnlocked=false => a paid/locked chapter.
        assertTrue(chapters[1].locked)
    }

    @Test
    fun `missing window dot chapters yields empty list`() {
        assertTrue(source.chaptersFromHtml("<html><body>no chapters here</body></html>").isEmpty())
    }

    // --- Chapter content (honeypot stripping) -------------------------------

    @Test
    fun `hidden honeypot span is stripped from chapter pages`() {
        val pages = source.pagesFromDocument(
            doc(
                """
                <html><head>
                <style>.cjcz1234{ display: none; speak: never; }</style>
                </head><body>
                <div class="chapter-inner chapter-content">
                  <p>Zorian opened his eyes.</p>
                  <span class="cjcz1234"><br>Enjoying the story? Read it on the official site.<br></span>
                  <p>He sat up in bed.</p>
                </div>
                </body></html>
                """.trimIndent(),
            ),
        )
        assertEquals(2, pages.size)
        assertEquals("Zorian opened his eyes.", pages[0].text)
        assertEquals("He sat up in bed.", pages[1].text)
        assertTrue(pages.none { it.text.contains("official site") })
    }

    @Test
    fun `honeypot hidden as a paragraph is also stripped`() {
        val pages = source.pagesFromDocument(
            doc(
                """
                <head><style>.stolen99 {display:none}</style></head>
                <div class="chapter-content">
                  <p>Real opening line.</p>
                  <p class="stolen99">This tale has been illicitly lifted; report it.</p>
                  <p>Real closing line.</p>
                </div>
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("Real opening line.", "Real closing line."), pages.map { it.text })
    }

    @Test
    fun `hr becomes a separator and formatting is preserved`() {
        val pages = source.pagesFromDocument(
            doc(
                """
                <div class="chapter-content">
                  <p>Before the break.</p>
                  <hr/>
                  <p><em>A whispered thought.</em></p>
                </div>
                """.trimIndent(),
            ),
        )
        assertEquals(3, pages.size)
        assertEquals("Before the break.", pages[0].text)
        assertTrue(pages[1].isSeparator)
        assertEquals("", pages[1].text)
        assertEquals("A whispered thought.", pages[2].text)
        assertEquals("<i>A whispered thought.</i>", pages[2].formattedText)
    }

    @Test
    fun `image paragraph maps to imageUrl`() {
        val pages = source.pagesFromDocument(
            doc(
                """
                <div class="chapter-content">
                  <p><img src="https://www.royalroadcdn.com/illustration.jpg"/></p>
                </div>
                """.trimIndent(),
            ),
        )
        assertEquals("https://www.royalroadcdn.com/illustration.jpg", pages.single().imageUrl)
        assertEquals("", pages.single().text)
    }

    // --- Filters ------------------------------------------------------------

    private fun named(filters: List<Filter<*>>, name: String) = filters.first { it.name == name }

    private fun RoyalRoad.TagGroup.set(value: String, state: Int) {
        val i = tags.indexOfFirst { it.value == value }
        require(i >= 0) { "unknown tag $value" }
        @Suppress("UNCHECKED_CAST")
        (this.state as List<Filter.TriState>)[i].state = state
    }

    @Test
    fun `getFilterList exposes the full static filter set`() {
        val filters = source.getFilterList()
        assertTrue(filters.any { it is Filter.Select<*> && it.name == "Sort by" })
        assertTrue(filters.any { it is Filter.Select<*> && it.name == "Order" })
        assertTrue(filters.any { it is Filter.Select<*> && it.name == "Status" })
        assertTrue(filters.any { it is Filter.Select<*> && it.name == "Type" })
        // Free-text refinements are present.
        listOf("Author", "Min pages", "Max pages", "Min rating (0.5–5)", "Max rating (0.5–5)")
            .forEach { name -> assertTrue(filters.any { it is Filter.Text && it.name == name }, "missing $name") }
        val groups = filters.filterIsInstance<RoyalRoad.TagGroup>()
        assertEquals(2, groups.size)
        val tags = groups.first { it.name == "Genres & Tags" }
        assertEquals(72, tags.tags.size)
        assertTrue(tags.tags.any { it.value == "litrpg" && it.label == "LitRPG" })
        // Static filters require no network fetch.
        assertFalse(source.hasDynamicFilters)
        assertTrue(source.supportsSearchWithFilters)
    }

    @Test
    fun `tri-state tags map to tagsAdd and tagsRemove`() {
        val filters = source.getFilterList()
        val genres = filters.filterIsInstance<RoyalRoad.TagGroup>().first { it.name == "Genres & Tags" }
        genres.set("litrpg", Filter.TriState.STATE_INCLUDE)
        genres.set("magic", Filter.TriState.STATE_INCLUDE)
        genres.set("harem", Filter.TriState.STATE_EXCLUDE)
        // A content warning excluded through the same mechanism.
        val warnings = filters.filterIsInstance<RoyalRoad.TagGroup>().first { it.name == "Content Warnings" }
        warnings.set("ai_generated", Filter.TriState.STATE_EXCLUDE)

        val url = source.searchNovelsRequest("dungeon", 2, filters).url
        assertEquals(listOf("litrpg", "magic"), url.queryParameterValues("tagsAdd"))
        assertEquals(listOf("harem", "ai_generated"), url.queryParameterValues("tagsRemove"))
        assertEquals("dungeon", url.queryParameter("title"))
        assertEquals("2", url.queryParameter("page"))
        assertEquals("false", url.queryParameter("globalFilters"))
    }

    @Test
    fun `status, type, sort and numeric filters map to query params`() {
        val filters = source.getFilterList()
        (named(filters, "Status") as Filter.Select<*>).state = 1 // Ongoing
        (named(filters, "Type") as Filter.Select<*>).state = 2 // Original
        (named(filters, "Sort by") as Filter.Select<*>).state = 2 // Average Rating
        (named(filters, "Order") as Filter.Select<*>).state = 1 // Ascending
        named(filters, "Min pages").let { (it as Filter.Text).state = "200" }
        named(filters, "Author").let { (it as Filter.Text).state = "  nobody103  " }

        val url = source.searchNovelsRequest("", 1, filters).url
        assertEquals("ONGOING", url.queryParameter("status"))
        assertEquals("original", url.queryParameter("type"))
        assertEquals("rating", url.queryParameter("orderBy"))
        assertEquals("asc", url.queryParameter("dir"))
        assertEquals("200", url.queryParameter("minPages"))
        assertEquals("nobody103", url.queryParameter("author")) // trimmed
        // No query => no title param.
        assertNull(url.queryParameter("title"))
    }

    @Test
    fun `untouched filters omit ALL-valued selects and blank text`() {
        val url = source.searchNovelsRequest("world", 1, source.getFilterList()).url
        // Status/Type default to "ALL" and are dropped; no tags selected.
        assertNull(url.queryParameter("status"))
        assertNull(url.queryParameter("type"))
        assertTrue(url.queryParameterValues("tagsAdd").isEmpty())
        assertNull(url.queryParameter("minPages"))
        assertEquals("world", url.queryParameter("title"))
    }
}
