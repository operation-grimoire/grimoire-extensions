package io.grimoire.extensions.lib.theme

import io.grimoire.api.model.filter.Filter
import io.grimoire.api.model.novel.Chapter
import io.grimoire.api.model.novel.Novel
import io.grimoire.api.model.novel.NovelPage
import io.grimoire.api.model.novel.NovelStatus
import io.grimoire.api.model.novel.PageContent
import io.grimoire.api.source.feature.FilterSource
import io.grimoire.api.source.feature.LatestSource
import io.grimoire.api.source.feature.PopularSource
import io.grimoire.api.source.feature.SearchSource
import io.grimoire.api.source.http.ParsedHttpSource
import io.grimoire.api.source.web.PageListSource
import io.grimoire.api.source.web.PaginatedSource
import io.grimoire.api.util.richDescription
import io.grimoire.api.util.richHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Element
import java.net.URLEncoder

abstract class NovelFullThemeSource :
    ParsedHttpSource(),
    PopularSource,
    LatestSource,
    SearchSource,
    FilterSource,
    PaginatedSource,
    PageListSource {

    private val listSelector = ".list-truyen .row:has(.truyen-title)"

    override suspend fun getPopularNovels(page: Int): List<Novel> = withContext(Dispatchers.IO) {
        get("$baseUrl/most-popular?page=$page").asJsoup().select(listSelector).map { it.toNovel() }
    }

    override suspend fun getLatestUpdates(page: Int): List<Novel> = withContext(Dispatchers.IO) {
        get("$baseUrl/latest-release-novel?page=$page").asJsoup().select(listSelector).map { it.toNovel() }
    }

    override suspend fun searchNovels(query: String, page: Int, filters: List<Filter<*>>): List<Novel> =
        withContext(Dispatchers.IO) {
            val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()
                ?.let { it.values.getOrNull(it.state) }
                ?.takeIf { it.isNotEmpty() && !it.equals(ANY, ignoreCase = true) }
            val completedOnly = filters.filterIsInstance<StatusFilter>().firstOrNull()?.state == 1

            val url = when {
                genre != null -> "$baseUrl/genre/${URLEncoder.encode(genre, "UTF-8")}?page=$page"
                completedOnly -> "$baseUrl/completed-novel?page=$page"
                else -> "$baseUrl/search?keyword=${URLEncoder.encode(query, "UTF-8")}&page=$page"
            }
            get(url).asJsoup().select(listSelector).map { it.toNovel() }
        }

    override suspend fun getNovelDetails(novel: Novel): Novel = withContext(Dispatchers.IO) {
        val document = get(resolveUrl(novel.url)).asJsoup()
        // Theme stores rating in `#rateVal` (hidden input) on a 0..10 scale; vote
        // count in `.small em strong:last-of-type span`.
        val rating10 = document.selectFirst("#rateVal")?.attr("value")?.toFloatOrNull()
        val rating = rating10?.let { (it / 2f).coerceIn(0f, 5f) }
        val ratingCount = document.selectFirst(".small em strong:last-of-type span")
            ?.text()?.replace(",", "")?.toIntOrNull()
        Novel(
            url = document.location(),
            title = document.selectFirst("h3.title")!!.text(),
            language = lang,
            thumbnailUrl = document.selectFirst(".book img")?.absUrl("src"),
            author = document.selectFirst(".info a[href*=/author/]")?.text(),
            description = document.selectFirst(".desc-text")?.richDescription()?.takeIf { it.isNotBlank() },
            genres = document.select(".info a[href*=/genre/]").map { it.text() },
            status = document.selectFirst(".info h3:contains(Status) + a")?.text().toNovelStatus(),
            rating = rating,
            ratingCount = ratingCount,
            initialized = true,
        )
    }

    override suspend fun getChapterList(novel: Novel, page: Int): List<Chapter> = withContext(Dispatchers.IO) {
        val doc = get("${resolveUrl(novel.url)}?page=$page").asJsoup()
        // NovelFull clamps an out-of-range ?page to the last page and re-serves it,
        // so a naive page-walk appends the final chapters twice (duplicate Compose
        // keys crash the reader). The pagination's active item reflects the page
        // actually served — if it differs from what we asked for, we've run past
        // the end; return empty to stop the walk.
        val active = doc.selectFirst("ul.pagination li.active a")?.text()?.trim()?.toIntOrNull()
        if (page > 1 && active != null && active != page) {
            emptyList()
        } else {
            doc.select("#list-chapter .row li").map { it.toChapter() }
        }
    }

    override suspend fun getPageList(chapter: Chapter): List<NovelPage> = withContext(Dispatchers.IO) {
        // Drop empty `<p></p>` spacers (no text, no image) that would surface as
        // blank reader pages; keep paragraphs with visible text OR an image.
        get(resolveUrl(chapter.url)).asJsoup()
            .select("#chapter-content p:matches(\\S), #chapter-content p:has(img)")
            .mapIndexed { index, element -> element.toPage(index) }
    }

    // FilterSource — genre list scraped from the homepage sidebar.
    private var loadedGenres: List<String> = emptyList()

    override val hasDynamicFilters: Boolean = true

    override fun getFilterList(): List<Filter<*>> = listOf(
        StatusFilter(),
        GenreFilter(loadedGenres),
    )

    override suspend fun fetchFilterOptions(): List<Filter<*>> = withContext(Dispatchers.IO) {
        val doc = get(baseUrl).asJsoup()
        // The canonical genre list lives in the homepage sidebar `.list-cat`.
        loadedGenres = doc.select(".list-cat a[href*=/genre/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        getFilterList()
    }

    private fun Element.toNovel() = Novel(
        url = selectFirst(".truyen-title a")!!.attr("href"),
        title = selectFirst(".truyen-title a")!!.text(),
        language = lang,
        thumbnailUrl = selectFirst("img.cover")?.absUrl("src"),
    )

    private fun Element.toChapter() = Chapter(
        url = selectFirst("a")!!.attr("href"),
        name = selectFirst("a span.chapter-text")?.text() ?: selectFirst("a")!!.attr("title"),
    )

    // Light-novel titles embed illustrations as <p><img>; emit them as image pages.
    // `html` carries the constrained-HTML rendering; null when it equals the plain
    // text so the offline payload isn't duplicated.
    private fun Element.toPage(index: Int): NovelPage {
        val imageUrl = selectFirst("img")?.imageUrl()
        if (imageUrl != null) return NovelPage(index, PageContent.Image(imageUrl))
        val text = text()
        return NovelPage(index, PageContent.Text(text, richHtml().takeIf { it != text }))
    }

    private fun Element.imageUrl(): String? =
        sequenceOf("data-src", "data-lazy-src", "src")
            .map { absUrl(it) }
            .firstOrNull { it.isNotBlank() }

    private class StatusFilter : Filter.Select<String>("Status", arrayOf("Any", "Completed only"))

    private class GenreFilter(genres: List<String>) :
        Filter.Select<String>("Genre", (listOf(ANY) + genres).toTypedArray())

    private fun String?.toNovelStatus() = when {
        this == null -> NovelStatus.UNKNOWN
        contains("Ongoing", ignoreCase = true) -> NovelStatus.ONGOING
        contains("Completed", ignoreCase = true) -> NovelStatus.COMPLETED
        contains("Hiatus", ignoreCase = true) -> NovelStatus.HIATUS
        contains("Cancelled", ignoreCase = true) -> NovelStatus.CANCELLED
        else -> NovelStatus.UNKNOWN
    }

    companion object {
        private const val ANY = "Any"
    }
}
