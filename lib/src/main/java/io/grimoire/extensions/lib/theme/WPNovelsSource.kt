package io.grimoire.extensions.lib.theme

import io.grimoire.api.model.Chapter
import io.grimoire.api.model.Filter
import io.grimoire.api.model.Novel
import io.grimoire.api.model.NovelPage
import io.grimoire.api.network.ParsedHttpSource
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

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
        thumbnailUrl = element.selectFirst("img")?.attr("src"),
    )

    override fun popularNovelsNextPageSelector() = "a.next.page-numbers"

    // Latest updates — /novel?m_orderby=latest
    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/novel?m_orderby=latest&paged=$page")

    override fun latestUpdatesSelector() = popularNovelsSelector()
    override fun latestUpdatesFromElement(element: Element) = popularNovelsFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularNovelsNextPageSelector()

    // Search — /?s=query&post_type=wp-manga
    override fun searchNovelsRequest(query: String, page: Int, filters: List<Filter<*>>) =
        GET("$baseUrl/?s=$query&post_type=wp-manga&paged=$page")

    override fun searchNovelsSelector() = popularNovelsSelector()
    override fun searchNovelsFromElement(element: Element) = popularNovelsFromElement(element)
    override fun searchNovelsNextPageSelector() = popularNovelsNextPageSelector()

    // Novel details
    override fun novelDetailsFromDocument(document: Document): Novel {
        val info = document.selectFirst("div.tab-summary")!!
        // WP-Manga's rating widget is out of 5 already.
        val ratingValue = document.selectFirst("[itemprop=ratingValue]")?.text()?.trim()?.toFloatOrNull()
            ?: document.selectFirst("#averagerate")?.text()?.trim()?.toFloatOrNull()
        val ratingCount = document.selectFirst("[itemprop=ratingCount]")?.text()?.trim()?.toIntOrNull()
            ?: document.selectFirst("#countrate")?.text()?.trim()?.toIntOrNull()
        return Novel(
            url = document.location(),
            title = document.selectFirst("div.post-title h1")!!.text(),
            thumbnailUrl = info.selectFirst("div.summary_image img")?.attr("src"),
            author = info.selectFirst("div.author-content a")?.text(),
            description = document.selectFirst("div.summary__content")?.text(),
            genres = document.select("div.genres-content a").map { it.text() },
            status = document.selectFirst("div.summary-content")?.text().toNovelStatus(),
            rating = ratingValue,
            ratingCount = ratingCount,
            initialized = true,
        )
    }

    // Chapter list
    override fun chapterListSelector() = "li.wp-manga-chapter"

    override fun chapterFromElement(element: Element) = Chapter(
        url = element.selectFirst("a")!!.attr("href"),
        name = element.selectFirst("a")!!.text().trim(),
        uploadDate = 0L,
    )

    // Page list — novel text content
    override fun pageListSelector() = "div.reading-content p"

    override fun pageFromElement(element: Element, index: Int) = NovelPage(
        index = index,
        text = element.text(),
    )

    // Default: no filters
    override fun getFilterList() = emptyList<Filter<*>>()

    private fun String?.toNovelStatus() = when {
        this == null -> io.grimoire.api.model.NovelStatus.UNKNOWN
        contains("OnGoing", ignoreCase = true) -> io.grimoire.api.model.NovelStatus.ONGOING
        contains("Completed", ignoreCase = true) -> io.grimoire.api.model.NovelStatus.COMPLETED
        contains("Hiatus", ignoreCase = true) -> io.grimoire.api.model.NovelStatus.HIATUS
        contains("Cancelled", ignoreCase = true) -> io.grimoire.api.model.NovelStatus.CANCELLED
        else -> io.grimoire.api.model.NovelStatus.UNKNOWN
    }
}
