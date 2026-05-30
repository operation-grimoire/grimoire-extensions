package io.grimoire.extensions.lib.theme

import io.grimoire.api.model.Chapter
import io.grimoire.api.model.Filter
import io.grimoire.api.model.Novel
import io.grimoire.api.model.NovelPage
import io.grimoire.api.model.NovelStatus
import io.grimoire.api.network.ParsedHttpSource
import io.grimoire.api.network.richHtml
import io.grimoire.api.source.PaginatedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

abstract class NovelFullThemeSource : ParsedHttpSource(), PaginatedSource {

    override fun popularNovelsRequest(page: Int) =
        GET("$baseUrl/most-popular?page=$page")

    override fun popularNovelsSelector() = ".list-truyen .row:has(.truyen-title)"

    override fun popularNovelsFromElement(element: Element) = Novel(
        url = element.selectFirst(".truyen-title a")!!.attr("href"),
        title = element.selectFirst(".truyen-title a")!!.text(),
        thumbnailUrl = element.selectFirst("img.cover")?.absUrl("src"),
    )

    override fun popularNovelsNextPageSelector() = "ul.pagination li.next a"

    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/latest-release-novel?page=$page")

    override fun latestUpdatesSelector() = popularNovelsSelector()
    override fun latestUpdatesFromElement(element: Element) = popularNovelsFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularNovelsNextPageSelector()

    override fun searchNovelsRequest(query: String, page: Int, filters: List<Filter<*>>): okhttp3.Request {
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()
            ?.let { it.values.getOrNull(it.state) }
            ?.takeIf { it.isNotEmpty() && !it.equals(ANY, ignoreCase = true) }
        val completedOnly = filters.filterIsInstance<StatusFilter>().firstOrNull()?.state == 1

        val encoded = URLEncoder.encode(query, "UTF-8")
        return when {
            genre != null -> GET("$baseUrl/genre/${URLEncoder.encode(genre, "UTF-8")}?page=$page")
            completedOnly -> GET("$baseUrl/completed-novel?page=$page")
            else -> GET("$baseUrl/search?keyword=$encoded&page=$page")
        }
    }

    override fun searchNovelsSelector() = popularNovelsSelector()
    override fun searchNovelsFromElement(element: Element) = popularNovelsFromElement(element)
    override fun searchNovelsNextPageSelector() = popularNovelsNextPageSelector()

    override fun novelDetailsFromDocument(document: Document): Novel {
        // Theme stores rating in `#rateVal` (hidden input) on a 0..10 scale.
        // Vote count lives in `.small em strong:last-of-type span`, formatted
        // like `<strong><span>47498</span> ratings</strong>`.
        val rating10 = document.selectFirst("#rateVal")?.attr("value")?.toFloatOrNull()
        val rating = rating10?.let { (it / 2f).coerceIn(0f, 5f) }
        val ratingCount = document.selectFirst(".small em strong:last-of-type span")
            ?.text()?.replace(",", "")?.toIntOrNull()
        return Novel(
            url = document.location(),
            title = document.selectFirst("h3.title")!!.text(),
            thumbnailUrl = document.selectFirst(".book img")?.absUrl("src"),
            author = document.selectFirst(".info a[href*=/author/]")?.text(),
            description = document.selectFirst(".desc-text")?.text(),
            genres = document.select(".info a[href*=/genre/]").map { it.text() },
            status = document.selectFirst(".info h3:contains(Status) + a")?.text().toNovelStatus(),
            rating = rating,
            ratingCount = ratingCount,
            initialized = true,
        )
    }

    override fun chapterListSelector() = "#list-chapter .row li"

    override fun chapterFromElement(element: Element) = Chapter(
        url = element.selectFirst("a")!!.attr("href"),
        name = element.selectFirst("a span.chapter-text")?.text()
            ?: element.selectFirst("a")!!.attr("title"),
    )

    // Drop empty `<p></p>` spacers the theme sprinkles into #chapter-content:
    // they carry no text and no image, so they'd otherwise surface as blank
    // reader pages. Keep any paragraph with visible text OR an image.
    override fun pageListSelector() =
        "#chapter-content p:matches(\\S), #chapter-content p:has(img)"

    // Light-novel titles embed illustrations as <p><img> in #chapter-content;
    // emit them as image pages instead of dropping them via .text(). Plain web
    // novels have no images, so this branch is a no-op for them.
    //
    // `formattedText` carries the constrained-HTML version the reader renders
    // with AnnotatedString.fromHtml (italics, bold, links, in-paragraph line
    // breaks). When the paragraph has no formatting at all, richHtml() and
    // text() produce the same string, so leave it null to avoid duplicating
    // every paragraph in the offline download payload.
    override fun pageFromElement(element: Element, index: Int): NovelPage {
        val imageUrl = element.selectFirst("img")?.imageUrl()
        if (imageUrl != null) {
            return NovelPage(index = index, text = "", imageUrl = imageUrl)
        }
        val text = element.text()
        val formatted = element.richHtml().takeIf { it != text }
        return NovelPage(index = index, text = text, formattedText = formatted)
    }

    private fun Element.imageUrl(): String? =
        sequenceOf("data-src", "data-lazy-src", "src")
            .map { absUrl(it) }
            .firstOrNull { it.isNotBlank() }

    override suspend fun getChapterList(novel: Novel, page: Int): List<Chapter> =
        withContext(Dispatchers.IO) {
            val body = client.newCall(GET("${resolveUrl(novel.url)}?page=$page"))
                .execute().use { it.body?.string().orEmpty() }
            val doc = Jsoup.parse(body, baseUrl)
            // NovelFull clamps an out-of-range ?page to the last page and
            // re-serves it (the mobile pagination can even advertise a "next"
            // past the end). A naive page-walk then appends the final chapters
            // twice, and duplicate chapter urls become duplicate Compose keys
            // that crash the reader list. The pagination's active item reflects
            // the page actually served, so if it differs from the page we asked
            // for we've run past the end — return empty to stop the walk.
            val active = doc.selectFirst("ul.pagination li.active a")
                ?.text()?.trim()?.toIntOrNull()
            if (page > 1 && active != null && active != page) {
                return@withContext emptyList()
            }
            doc.select(chapterListSelector()).map { chapterFromElement(it) }
        }

    private var loadedGenres: List<String> = emptyList()

    override val hasDynamicFilters: Boolean = true

    override fun getFilterList(): List<Filter<*>> = listOf(
        StatusFilter(),
        GenreFilter(loadedGenres),
    )

    override suspend fun fetchFilterOptions(): List<Filter<*>> = withContext(Dispatchers.IO) {
        val body = client.newCall(GET(baseUrl)).execute().use { it.body?.string().orEmpty() }
        val doc = Jsoup.parse(body, baseUrl)
        // The full canonical genre list lives in the homepage sidebar `.list-cat`.
        // Other `/genre/` links on the page are per-novel badges (a small subset)
        // and would also leak a blank from icon-only anchors.
        loadedGenres = doc.select(".list-cat a[href*=/genre/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        getFilterList()
    }

    private class StatusFilter : Filter.Select<String>("Status", arrayOf("Any", "Completed only"))

    private class GenreFilter(genres: List<String>) :
        Filter.Select<String>("Genre", (listOf(ANY) + genres).toTypedArray())

    companion object {
        private const val ANY = "Any"
    }

    private fun String?.toNovelStatus() = when {
        this == null -> NovelStatus.UNKNOWN
        contains("Ongoing", ignoreCase = true) -> NovelStatus.ONGOING
        contains("Completed", ignoreCase = true) -> NovelStatus.COMPLETED
        contains("Hiatus", ignoreCase = true) -> NovelStatus.HIATUS
        contains("Cancelled", ignoreCase = true) -> NovelStatus.CANCELLED
        else -> NovelStatus.UNKNOWN
    }
}
