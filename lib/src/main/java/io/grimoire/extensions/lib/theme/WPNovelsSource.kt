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
import io.grimoire.api.source.web.ChapterListSource
import io.grimoire.api.source.web.PageListSource
import io.grimoire.api.util.richDescription
import io.grimoire.api.util.richHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Base class for sites running the WP Novels WordPress plugin.
 * Subclasses only need to provide baseUrl, name and lang.
 * Override selectors if a site deviates from the standard theme structure.
 */
abstract class WPNovelsSource :
    ParsedHttpSource(),
    PopularSource,
    LatestSource,
    SearchSource,
    FilterSource,
    ChapterListSource,
    PageListSource {

    override suspend fun getPopularNovels(page: Int): List<Novel> = withContext(Dispatchers.IO) {
        get("$baseUrl/novel?m_orderby=views&paged=$page").asJsoup()
            .select("div.page-item-detail").map { it.toListNovel() }
    }

    override suspend fun getLatestUpdates(page: Int): List<Novel> = withContext(Dispatchers.IO) {
        get("$baseUrl/novel?m_orderby=latest&paged=$page").asJsoup()
            .select("div.page-item-detail").map { it.toListNovel() }
    }

    override suspend fun searchNovels(query: String, page: Int, filters: List<Filter<*>>): List<Novel> =
        withContext(Dispatchers.IO) {
            val params = StringBuilder()
            params.append("s=").append(URLEncoder.encode(query, "UTF-8")).append("&post_type=wp-manga")
            for (filter in filters) when (filter) {
                is Filter.Text -> if (filter.state.isNotBlank()) {
                    val param = when (filter.name) {
                        AUTHOR_FILTER_NAME -> "author"
                        TEAM_FILTER_NAME -> "artist"
                        else -> null
                    }
                    if (param != null) {
                        params.append('&').append(param).append('=')
                            .append(URLEncoder.encode(filter.state.trim(), "UTF-8"))
                    }
                }
                is SortFilter -> {
                    val slug = SORT_SLUGS.getOrNull(filter.state).orEmpty()
                    if (slug.isNotEmpty()) params.append("&m_orderby=").append(slug)
                }
                // `op=1` switches Madara's genre match from OR to AND; OR is the empty default.
                is GenresMatchFilter -> if (filter.state == 1) params.append("&op=1")
                is AdultContentFilter -> {
                    val slug = ADULT_SLUGS.getOrNull(filter.state).orEmpty()
                    if (slug.isNotEmpty()) params.append("&adult=").append(slug)
                }
                is StatusGroup -> for ((i, slug) in STATUS_SLUGS.withIndex()) {
                    if (filter.state.getOrNull(i)?.state == true) {
                        params.append("&status%5B%5D=").append(slug)
                    }
                }
                is GenresGroup -> for (cb in filter.state) {
                    if (cb.state) {
                        val slug = filter.slugByLabel[cb.name] ?: continue
                        params.append("&genre%5B%5D=").append(URLEncoder.encode(slug, "UTF-8"))
                    }
                }
                else -> Unit
            }
            val path = if (page > 1) "/page/$page/" else "/"
            get("$baseUrl$path?$params").asJsoup()
                .select("div.c-tabs-item__content, div.page-item-detail")
                .map { it.toSearchNovel() }
        }

    override suspend fun getNovelDetails(novel: Novel): Novel = withContext(Dispatchers.IO) {
        novelFromDocument(get(resolveUrl(novel.url)).asJsoup())
    }

    /** Parse a novel-details page. Subclasses override to tweak per-site fields. */
    protected open fun novelFromDocument(document: Document): Novel {
        // Some Madara novel themes drop the "tab-summary" wrapper; fall back to the
        // whole document so a layout tweak doesn't crash the source.
        val info = document.selectFirst("div.tab-summary") ?: document
        // WP-Manga's rating widget is out of 5 already.
        val ratingValue = document.selectFirst("[itemprop=ratingValue]")?.text()?.trim()?.toFloatOrNull()
            ?: document.selectFirst("#averagerate")?.text()?.trim()?.toFloatOrNull()
        val ratingCount = document.selectFirst("[itemprop=ratingCount]")?.text()?.trim()?.toIntOrNull()
            ?: document.selectFirst("#countrate")?.text()?.trim()?.toIntOrNull()
        return Novel(
            url = document.location(),
            title = (
                document.selectFirst("div.post-title h1")
                    ?: document.selectFirst("div.post-title h3")
                    ?: document.selectFirst("h1.entry-title")
                )?.text()?.trim().orEmpty(),
            language = lang,
            thumbnailUrl = info.selectFirst("div.summary_image")?.lazyImageUrl(),
            author = info.selectFirst("div.author-content a")?.text(),
            description = (
                document.selectFirst("div.summary__content")
                    ?: document.selectFirst("div.description-summary div.summary__content")
                    ?: document.selectFirst("div.manga-excerpt")
                )?.richDescription()?.takeIf { it.isNotBlank() },
            genres = document.select("div.genres-content a").map { it.text() },
            status = document.selectFirst("div.summary-content")?.text().toNovelStatus(),
            rating = ratingValue,
            ratingCount = ratingCount,
            initialized = true,
        )
    }

    // Madara lists chapters newest-first; the reader expects ascending order, so reverse.
    override suspend fun getChapterList(novel: Novel): List<Chapter> = withContext(Dispatchers.IO) {
        get(resolveUrl(novel.url)).asJsoup()
            .select("li.wp-manga-chapter").map { chapterFromElement(it) }.reversed()
    }

    /** Parse one chapter-list row. Subclasses override for locked/dated rows. */
    protected open fun chapterFromElement(element: Element): Chapter = Chapter(
        url = element.selectFirst("a")!!.attr("href"),
        name = element.selectFirst("a")!!.text().trim(),
    )

    override suspend fun getPageList(chapter: Chapter): List<NovelPage> = withContext(Dispatchers.IO) {
        pagesFromDocument(get(resolveUrl(chapter.url)).asJsoup())
    }

    // Madara novel variants put paragraphs in ".reading-content" or a ".text-left" wrapper.
    /** Parse chapter content into pages. Subclasses override for images / other layouts. */
    protected open fun pagesFromDocument(document: Document): List<NovelPage> =
        document.select("div.reading-content p, div.reading-content div.text-left p")
            .mapIndexed { index, element -> element.toPage(index) }

    // --- Filters -------------------------------------------------------------

    override val hasDynamicFilters: Boolean = true

    @Volatile
    private var loadedGenres: List<Pair<String, String>> = emptyList()
    private val filterFetchMutex = Mutex()

    override fun getFilterList(): List<Filter<*>> = buildList {
        add(SortFilter())
        add(Filter.Text(AUTHOR_FILTER_NAME))
        add(Filter.Text(TEAM_FILTER_NAME))
        add(AdultContentFilter())
        add(GenresMatchFilter())
        add(StatusGroup())
        if (loadedGenres.isNotEmpty()) add(GenresGroup(loadedGenres))
    }

    override suspend fun fetchFilterOptions(): List<Filter<*>> = withContext(Dispatchers.IO) {
        if (loadedGenres.isEmpty()) {
            filterFetchMutex.withLock {
                if (loadedGenres.isEmpty()) {
                    runCatching {
                        loadedGenres = parseGenres(get("$baseUrl/?s=&post_type=wp-manga").asJsoup())
                    }
                }
            }
        }
        getFilterList()
    }

    protected open fun parseGenres(doc: Document): List<Pair<String, String>> =
        doc.select("input[name='genre[]']").mapNotNull { input ->
            val slug = input.attr("value").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val id = input.id().takeIf { it.isNotEmpty() }
            val label = (
                id?.let { doc.selectFirst("label[for='$it']") }?.text()
                    ?: input.nextElementSibling()?.text()
                    ?: input.parent()?.text()
                )?.trim()?.takeIf { it.isNotEmpty() } ?: slug
            slug to label
        }.distinctBy { it.first }

    private class SortFilter : Filter.Select<String>(
        "Order by",
        arrayOf("Relevance", "Latest", "A-Z", "Trending", "Most Views", "New"),
    )

    private class GenresMatchFilter : Filter.Select<String>(
        "Genres match",
        arrayOf("OR (any selected)", "AND (all selected)"),
    )

    private class AdultContentFilter : Filter.Select<String>(
        "Adult content",
        arrayOf("All", "None adult content", "Only adult content"),
    )

    private class StatusGroup : Filter.Group<Filter.CheckBox>(
        "Novel Status",
        listOf(
            Filter.CheckBox("OnGoing"),
            Filter.CheckBox("Completed"),
            Filter.CheckBox("Canceled"),
            Filter.CheckBox("On Hold"),
            Filter.CheckBox("Upcoming"),
        ),
    )

    private class GenresGroup(genres: List<Pair<String, String>>) :
        Filter.Group<Filter.CheckBox>("Genres", genres.map { Filter.CheckBox(it.second) }) {
        val slugByLabel: Map<String, String> = genres.associate { it.second to it.first }
    }

    private fun Element.toListNovel() = Novel(
        url = selectFirst("a")!!.attr("href"),
        title = selectFirst("h3 a, h4 a")!!.text(),
        language = lang,
        thumbnailUrl = lazyImageUrl(),
    )

    private fun Element.toSearchNovel() = Novel(
        url = selectFirst("div.post-title a, h3 a, h4 a")!!.attr("href"),
        title = selectFirst("div.post-title a, h3 a, h4 a")!!.text().trim(),
        language = lang,
        thumbnailUrl = lazyImageUrl(),
    )

    private fun Element.toPage(index: Int): NovelPage {
        val text = text()
        return NovelPage(index, PageContent.Text(text, richHtml().takeIf { it != text }))
    }

    // Madara lazy-loads cover images: the real URL lives in a data-* attribute or
    // srcset while src is a placeholder (often a data: URI).
    private fun Element.lazyImageUrl(): String? {
        val img = selectFirst("img") ?: return null
        listOf("data-src", "data-lazy-src", "data-cfsrc", "src").forEach { attr ->
            val value = img.attr(attr).trim()
            if (value.isNotEmpty() && !value.startsWith("data:")) return value
        }
        return img.attr("srcset").trim().substringBefore(" ").takeIf { it.isNotEmpty() }
    }

    private fun String?.toNovelStatus() = when {
        this == null -> NovelStatus.UNKNOWN
        contains("OnGoing", ignoreCase = true) -> NovelStatus.ONGOING
        contains("Completed", ignoreCase = true) -> NovelStatus.COMPLETED
        contains("Hiatus", ignoreCase = true) -> NovelStatus.HIATUS
        contains("Cancelled", ignoreCase = true) -> NovelStatus.CANCELLED
        else -> NovelStatus.UNKNOWN
    }

    private companion object {
        val STATUS_SLUGS = arrayOf("on-going", "end", "canceled", "on-hold", "upcoming")
        val SORT_SLUGS = arrayOf("", "latest", "alphabet", "trending", "views", "new-manga")
        val ADULT_SLUGS = arrayOf("", "0", "1")
        const val AUTHOR_FILTER_NAME = "Author"
        const val TEAM_FILTER_NAME = "Team"
    }
}
