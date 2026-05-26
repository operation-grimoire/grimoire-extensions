package io.grimoire.extension.en.foxaholic

import io.grimoire.api.model.Filter
import io.grimoire.api.model.NovelStatus
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset

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
    fun `real page — translation Active drives status even with Completed novel`() {
        assertEquals(NovelStatus.ONGOING, source.novelDetailsFromDocument(realPage).status)
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
        assertTrue(
            locked.all { it.url.startsWith("#locked-") },
            "locked chapters should expose a synthetic per-chapter URL",
        )
        assertTrue(
            unlocked.all { it.url.startsWith("https://www.foxaholic.com/") },
            "free chapters should have a real URL",
        )
        // The whole list must contain unique URLs — Compose's chapter LazyColumn
        // keys by URL, so duplicate keys (the original `#` href on every locked
        // entry) crash the screen on scroll. This is the regression we're guarding.
        val urls = chapters.map { it.url }
        assertEquals(urls.size, urls.toSet().size, "every chapter URL should be unique")
        // Both free and locked rows carry a span.chapter-release-date in the
        // captured page, so every chapter should have a non-zero upload date.
        assertTrue(
            chapters.all { it.uploadDate > 0L },
            "every chapter should have a parsed upload date",
        )
    }

    @Test
    fun `status — active translation overrides completed novel`() {
        val doc = sidebar(translation = "Active", novel = "Completed")
        assertEquals(NovelStatus.ONGOING, source.novelDetailsFromDocument(doc).status)
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
    fun `status — finished translation maps to completed`() {
        val doc = sidebar(translation = "Finished", novel = "Completed")
        assertEquals(NovelStatus.COMPLETED, source.novelDetailsFromDocument(doc).status)
    }

    @Test
    fun `status — falls back to novel field when translation is absent`() {
        val doc = sidebar(translation = null, novel = "Completed")
        assertEquals(NovelStatus.COMPLETED, source.novelDetailsFromDocument(doc).status)
    }

    @Test
    fun `status — falls back to novel field for unrecognized translation value`() {
        val doc = sidebar(translation = "Pending", novel = "OnGoing")
        assertEquals(NovelStatus.ONGOING, source.novelDetailsFromDocument(doc).status)
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
        assertEquals(epochMillisUtc(2024, 9, 3), chapter.uploadDate)
        assertFalse(chapter.locked)
    }

    @Test
    fun `chapter — premium-block element is locked with per-chapter synthetic URL`() {
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
        assertEquals("#locked-63868", chapter.url)
        assertEquals("Chapter 94 - After We Get Married", chapter.name)
        assertEquals(epochMillisUtc(2024, 9, 27), chapter.uploadDate)
        assertTrue(chapter.locked)
    }

    @Test
    fun `chapter — missing chapter-release-date returns 0L`() {
        val li = parseChapterLi(
            """
            <li class="wp-manga-chapter free-chap">
              <a href="https://www.foxaholic.com/novel/x/chapter-2/"> Chapter 2 </a>
            </li>
            """.trimIndent(),
        )
        assertEquals(0L, source.chapterFromElement(li).uploadDate)
    }

    @Test
    fun `chapter — unparseable date returns 0L without throwing`() {
        val li = parseChapterLi(
            """
            <li class="wp-manga-chapter free-chap">
              <a href="https://www.foxaholic.com/novel/x/chapter-3/"> Chapter 3 </a>
              <span class="chapter-release-date"><i>3 days ago</i></span>
            </li>
            """.trimIndent(),
        )
        assertEquals(0L, source.chapterFromElement(li).uploadDate)
    }

    @Test
    fun `chapter — locked entry without a data-chapter class falls back to the raw href`() {
        val li = parseChapterLi(
            """
            <li class="wp-manga-chapter premium premium-block">
              <a href="#"> Chapter X </a>
            </li>
            """.trimIndent(),
        )
        val chapter = source.chapterFromElement(li)
        assertEquals("#", chapter.url)
        assertTrue(chapter.locked)
    }

    @Test
    fun `search — empty query and no filters hits the Madara wp-manga endpoint`() {
        val url = source.searchNovelsRequest("", 1, emptyList()).url.toString()
        assertEquals("https://www.foxaholic.com/?s=&post_type=wp-manga", url)
    }

    @Test
    fun `search — page greater than one moves into Madara's /page/N/ path`() {
        val url = source.searchNovelsRequest("love", 3, emptyList()).url.toString()
        assertEquals("https://www.foxaholic.com/page/3/?s=love&post_type=wp-manga", url)
    }

    @Test
    fun `search — author and team feed Madara's author and artist query params`() {
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
    fun `search — AND match emits op=1, OR match omits it`() {
        val filters = source.getFilterList().toMutableList()
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
    fun `search — checked statuses emit one status query param per slug`() {
        val filters = source.getFilterList()
        val statusGroup = filters.first { it.name == "Novel Status" }
        @Suppress("UNCHECKED_CAST")
        val checkboxes = statusGroup.state as List<Filter.CheckBox>
        // OnGoing (0) and Canceled (2) only — verifies non-checked rows are skipped.
        checkboxes[0].state = true
        checkboxes[2].state = true
        val url = source.searchNovelsRequest("", 1, filters).url.toString()
        // status[] is encoded as status%5B%5D — both must be present and others absent.
        assertTrue(url.contains("status%5B%5D=on-going"), url)
        assertTrue(url.contains("status%5B%5D=canceled"), url)
        assertFalse(url.contains("status%5B%5D=end"), url)
        assertFalse(url.contains("status%5B%5D=on-hold"), url)
        assertFalse(url.contains("status%5B%5D=upcoming"), url)
    }

    private fun parseChapterLi(html: String) =
        Jsoup.parseBodyFragment(html).selectFirst("li.wp-manga-chapter")!!

    private fun epochMillisUtc(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

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
