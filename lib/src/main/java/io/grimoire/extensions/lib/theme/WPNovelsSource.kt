package io.grimoire.extensions.lib.theme

import io.grimoire.api.model.Chapter
import io.grimoire.api.model.Filter
import io.grimoire.api.model.Novel
import io.grimoire.api.model.NovelPage
import io.grimoire.api.network.ParsedHttpSource
import io.grimoire.api.network.richDescription
import io.grimoire.api.network.richHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Base class for sites running the WP Novels WordPress plugin.
 * Subclasses only need to provide baseUrl, name, id, and lang.
 * Override selectors if a site deviates from the standard theme structure.
 */
abstract class WPNovelsSource : ParsedHttpSource() {

    // Popular novels — /novel?m_orderby=views
    override fun popularNovelsRequest(page: Int) =
        GET("$baseUrl/novel?m_orderby=views&paged=$page")

    override fun popularNovelsSelector() = "div.page-item-detail"

    override fun popularNovelsFromElement(element: Element) = Novel(
        url = element.selectFirst("a")!!.attr("href"),
        title = element.selectFirst("h3 a, h4 a")!!.text(),
        thumbnailUrl = element.lazyImageUrl(),
    )

    override fun popularNovelsNextPageSelector() = "a.next.page-numbers"

    // Latest updates — /novel?m_orderby=latest
    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/novel?m_orderby=latest&paged=$page")

    override fun latestUpdatesSelector() = popularNovelsSelector()
    override fun latestUpdatesFromElement(element: Element) = popularNovelsFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularNovelsNextPageSelector()

    // Search — /?s=query&post_type=wp-manga (Madara renders results with a
    // different wrapper than the /novel listing pages). Selected filters are
    // appended to the same URL via Madara's `author`, `artist`, `m_orderby`,
    // `op`, `adult`, `genre[]` and `status[]` query params; see the filter
    // section near the bottom of this file.
    override fun searchNovelsRequest(query: String, page: Int, filters: List<Filter<*>>): okhttp3.Request {
        val params = StringBuilder()
        params.append("s=").append(URLEncoder.encode(query, "UTF-8"))
            .append("&post_type=wp-manga")
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
            // `op=1` switches Madara's genre match from OR (any of) to AND
            // (all of). The OR default is the empty string, so emit nothing.
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
        return GET("$baseUrl$path?$params")
    }

    override fun searchNovelsSelector() = "div.c-tabs-item__content, div.page-item-detail"

    override fun searchNovelsFromElement(element: Element) = Novel(
        url = element.selectFirst("div.post-title a, h3 a, h4 a")!!.attr("href"),
        title = element.selectFirst("div.post-title a, h3 a, h4 a")!!.text().trim(),
        thumbnailUrl = element.lazyImageUrl(),
    )

    override fun searchNovelsNextPageSelector() =
        "div.wp-pagenavi a.nextpostslink, a.next.page-numbers"

    // Novel details
    override fun novelDetailsFromDocument(document: Document): Novel {
        // Some Madara novel themes drop the "tab-summary" wrapper; fall back to
        // the whole document so a layout tweak doesn't crash the whole source.
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

    // Chapter list. Madara lists chapters newest-first; the reader expects
    // ascending order (chapter 1 first), so reverse the parsed list.
    override fun chapterListSelector() = "li.wp-manga-chapter"

    override fun chapterFromElement(element: Element) = Chapter(
        url = element.selectFirst("a")!!.attr("href"),
        name = element.selectFirst("a")!!.text().trim(),
        uploadDate = 0L,
    )

    override suspend fun chapterListParse(response: Response): List<Chapter> =
        super.chapterListParse(response).reversed()

    // Page list — novel text content. Madara novel variants put paragraphs in
    // either ".reading-content" or a ".text-left" inner wrapper.
    override fun pageListSelector() = "div.reading-content p, div.reading-content div.text-left p"

    // `formattedText` preserves the WP-Novels markup the reader can render
    // with AnnotatedString.fromHtml — italics, bold, links, in-paragraph
    // <br> breaks. When richHtml() matches text() (a paragraph with no
    // inline formatting at all), leave formattedText null so plain prose
    // pages don't double their size in the offline download.
    override fun pageFromElement(element: Element, index: Int): NovelPage {
        val text = element.text()
        val formatted = element.richHtml().takeIf { it != text }
        return NovelPage(index = index, text = text, formattedText = formatted)
    }

    // --- Filters -------------------------------------------------------------
    //
    // Mirrors Madara's advanced search at `/?s=&post_type=wp-manga`: a Sort
    // dropdown (the `m_orderby` selector shown on the search results page),
    // Author / Team text inputs, an OR/AND Genres-match selector, an Adult
    // content selector, a multi-select Novel Status group, and a multi-select
    // Genres group whose options are scraped from the advanced-search form so
    // the list stays in step with whatever the site exposes today.

    override val hasDynamicFilters: Boolean = true

    // `(slug, label)` pairs for the genre taxonomy on this site, populated
    // lazily on the first filter-sheet open. Volatile + double-checked Mutex
    // so a burst of opens hits the network once.
    @Volatile
    private var loadedGenres: List<Pair<String, String>> = emptyList()
    private val filterFetchMutex = Mutex()

    override fun getFilterList(): List<Filter<*>> = buildList {
        add(SortFilter())
        // Filter.Text and Filter.CheckBox are final in the API so they
        // cannot be subclassed; dispatch on `name` in searchNovelsRequest.
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
                        val body = client.newCall(GET("$baseUrl/?s=&post_type=wp-manga"))
                            .execute().use { it.body?.string().orEmpty() }
                        loadedGenres = parseGenres(Jsoup.parse(body, baseUrl))
                    }
                }
            }
        }
        getFilterList()
    }

    // Madara's advanced search renders each genre as a checkbox sibling to a
    // `<label for="...">`; fall back to the surrounding text when the form
    // template omits the for/id pairing. Subclasses can override to support
    // a site that ships its genre taxonomy elsewhere.
    protected open fun parseGenres(doc: Document): List<Pair<String, String>> =
        doc.select("input[name='genre[]']").mapNotNull { input ->
            val slug = input.attr("value").trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val id = input.id().takeIf { it.isNotEmpty() }
            val label = (
                id?.let { doc.selectFirst("label[for='$it']") }?.text()
                    ?: input.nextElementSibling()?.text()
                    ?: input.parent()?.text()
                )?.trim()?.takeIf { it.isNotEmpty() } ?: slug
            slug to label
        }.distinctBy { it.first }

    // Mirrors the `m_orderby` dropdown above the search results — order
    // matches the visible labels and indexes into SORT_SLUGS. Relevance is
    // Madara's empty default (no `m_orderby` param), so leave that slug blank.
    private class SortFilter : Filter.Select<String>(
        "Order by",
        arrayOf("Relevance", "Latest", "A-Z", "Trending", "Most Views", "New"),
    )

    private class GenresMatchFilter : Filter.Select<String>(
        "Genres match",
        arrayOf("OR (any selected)", "AND (all selected)"),
    )

    // Madara `adult` param: empty = All (default), `0` = hide adult, `1` = only
    // adult. Skipped at request time when "All" is selected.
    private class AdultContentFilter : Filter.Select<String>(
        "Adult content",
        arrayOf("All", "None adult content", "Only adult content"),
    )

    // Standard wp-manga `_wp_manga_status` taxonomy slugs — "Completed"
    // really is `end` upstream, not `completed`.
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
        Filter.Group<Filter.CheckBox>(
            "Genres",
            genres.map { Filter.CheckBox(it.second) },
        ) {
        // Lookup from the visible label (the only thing a CheckBox carries)
        // back to the slug Madara expects in `genre[]`.
        val slugByLabel: Map<String, String> = genres.associate { it.second to it.first }
    }

    private companion object {
        // Order mirrors the StatusGroup checkbox order above.
        val STATUS_SLUGS = arrayOf("on-going", "end", "canceled", "on-hold", "upcoming")

        // Order mirrors the SortFilter labels; index 0 is the empty default.
        val SORT_SLUGS = arrayOf("", "latest", "alphabet", "trending", "views", "new-manga")

        // Order mirrors the AdultContentFilter labels; index 0 is "All".
        val ADULT_SLUGS = arrayOf("", "0", "1")

        // Filter.Text is final in the API so author/team filters can't be
        // tagged by type — dispatch on these constants instead.
        const val AUTHOR_FILTER_NAME = "Author"
        const val TEAM_FILTER_NAME = "Team"
    }

    // Madara lazy-loads cover images: the real URL lives in a data-* attribute
    // or srcset while src is a placeholder (often a data: URI).
    private fun Element.lazyImageUrl(): String? {
        val img = selectFirst("img") ?: return null
        listOf("data-src", "data-lazy-src", "data-cfsrc", "src").forEach { attr ->
            val value = img.attr(attr).trim()
            if (value.isNotEmpty() && !value.startsWith("data:")) return value
        }
        return img.attr("srcset").trim().substringBefore(" ").takeIf { it.isNotEmpty() }
    }

    private fun String?.toNovelStatus() = when {
        this == null -> io.grimoire.api.model.NovelStatus.UNKNOWN
        contains("OnGoing", ignoreCase = true) -> io.grimoire.api.model.NovelStatus.ONGOING
        contains("Completed", ignoreCase = true) -> io.grimoire.api.model.NovelStatus.COMPLETED
        contains("Hiatus", ignoreCase = true) -> io.grimoire.api.model.NovelStatus.HIATUS
        contains("Cancelled", ignoreCase = true) -> io.grimoire.api.model.NovelStatus.CANCELLED
        else -> io.grimoire.api.model.NovelStatus.UNKNOWN
    }
}
