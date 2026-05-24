package io.grimoire.extensions.lib.theme

import io.grimoire.api.model.Chapter
import io.grimoire.api.model.Filter
import io.grimoire.api.model.Novel
import io.grimoire.api.model.NovelPage
import io.grimoire.api.network.ParsedHttpSource
import io.grimoire.api.network.richHtml
import okhttp3.Response
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
    // different wrapper than the /novel listing pages).
    override fun searchNovelsRequest(query: String, page: Int, filters: List<Filter<*>>): okhttp3.Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val path = if (page > 1) "/page/$page/" else "/"
        return GET("$baseUrl$path?s=$encoded&post_type=wp-manga")
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
                )?.text(),
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

    // Default: no filters
    override fun getFilterList() = emptyList<Filter<*>>()

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
