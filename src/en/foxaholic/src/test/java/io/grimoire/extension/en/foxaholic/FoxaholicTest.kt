package io.grimoire.extension.en.foxaholic

import io.grimoire.api.model.NovelStatus
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxaholicTest {

    private val source = Foxaholic()

    // Real novel-detail page captured from foxaholic.com: Translation=Active,
    // Novel=Completed, with a mix of free and locked chapters.
    private val realPage: Document by lazy {
        val html = checkNotNull(javaClass.getResourceAsStream("/delicate-and-soft-beauty.html")) {
            "missing fixture: /delicate-and-soft-beauty.html"
        }.bufferedReader().use { it.readText() }
        Jsoup.parse(html, "https://www.foxaholic.com/")
    }

    @Test
    fun `real page — novel Completed wins over translation Active`() {
        assertEquals(NovelStatus.COMPLETED, source.novelDetailsFromDocument(realPage).status)
    }

    @Test
    fun `real page — chapter list separates free and locked entries`() {
        val chapters = realPage.select("li.wp-manga-chapter").map(source::chapterFromElement)
        val (locked, unlocked) = chapters.partition { it.locked }
        // Numbers reflect the captured snapshot; if the page is recaptured the
        // counts may shift but the locked/unlocked split should stay non-trivial.
        assertEquals(128, chapters.size)
        assertEquals(25, locked.size)
        assertEquals(103, unlocked.size)
        assertTrue("locked chapters should use the placeholder href", locked.all { it.url == "#" })
        assertTrue(
            "free chapters should have a real URL",
            unlocked.all { it.url.startsWith("https://www.foxaholic.com/") },
        )
    }

    @Test
    fun `status — dropped translation overrides ongoing novel`() {
        val doc = sidebar(translation = "Dropped", novel = "OnGoing")
        assertEquals(NovelStatus.CANCELLED, source.novelDetailsFromDocument(doc).status)
    }

    @Test
    fun `status — teaser translation maps to hiatus`() {
        val doc = sidebar(translation = "Teaser", novel = "OnGoing")
        assertEquals(NovelStatus.HIATUS, source.novelDetailsFromDocument(doc).status)
    }

    @Test
    fun `status — active translation with ongoing novel is ongoing`() {
        val doc = sidebar(translation = "Active", novel = "OnGoing")
        assertEquals(NovelStatus.ONGOING, source.novelDetailsFromDocument(doc).status)
    }

    @Test
    fun `status — finished translation falls back to completed when novel field is missing`() {
        val doc = sidebar(translation = "Finished", novel = null)
        assertEquals(NovelStatus.COMPLETED, source.novelDetailsFromDocument(doc).status)
    }

    @Test
    fun `status — no fields parses as unknown`() {
        val doc = Jsoup.parse("<html><body></body></html>")
        assertEquals(NovelStatus.UNKNOWN, source.novelDetailsFromDocument(doc).status)
    }

    @Test
    fun `chapter — free-chap element keeps URL and is unlocked`() {
        val li = parseChapterLi(
            """
            <li class="wp-manga-chapter free-chap ">
              <span class="coin free"> </span>
              <a href="https://www.foxaholic.com/novel/x/chapter-1/"> Chapter 1 - Intro </a>
              <span class="chapter-release-date"><i>September 3, 2024</i></span>
            </li>
            """.trimIndent(),
        )
        val chapter = source.chapterFromElement(li)
        assertEquals("https://www.foxaholic.com/novel/x/chapter-1/", chapter.url)
        assertEquals("Chapter 1 - Intro", chapter.name)
        assertFalse(chapter.locked)
    }

    @Test
    fun `chapter — premium-block element is locked with placeholder href`() {
        val li = parseChapterLi(
            """
            <li class="wp-manga-chapter premium coin-5 data-chapter-63868 premium-block ">
              <span class="coin"><i class="fas fa-coins"></i>5</span>
              <a href="#"> Chapter 94 - After We Get Married <i class="fas fa-lock"></i> </a>
              <span class="chapter-release-date"><i>September 27, 2024</i></span>
            </li>
            """.trimIndent(),
        )
        val chapter = source.chapterFromElement(li)
        assertEquals("#", chapter.url)
        assertEquals("Chapter 94 - After We Get Married", chapter.name)
        assertTrue(chapter.locked)
    }

    private fun parseChapterLi(html: String) =
        Jsoup.parseBodyFragment(html).selectFirst("li.wp-manga-chapter")!!

    private fun sidebar(translation: String?, novel: String?): Document {
        fun row(heading: String, value: String?) = value?.let {
            """
            <div class="post-content_item">
              <div class="summary-heading"><h5><i class="fas fa-briefcase"></i> $heading</h5></div>
              <div class="summary-content"> $it</div>
            </div>
            """.trimIndent()
        } ?: ""
        return Jsoup.parse(
            """
            <html><body>
              <div class="post-status">
                ${row("Translation", translation)}
                ${row("Novel", novel)}
              </div>
            </body></html>
            """.trimIndent(),
        )
    }
}
