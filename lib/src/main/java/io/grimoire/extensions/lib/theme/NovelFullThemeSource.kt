package io.grimoire.extensions.lib.theme

import io.grimoire.api.model.Chapter
import io.grimoire.api.model.Filter
import io.grimoire.api.model.Novel
import io.grimoire.api.model.NovelPage
import io.grimoire.api.model.NovelStatus
import io.grimoire.api.network.ParsedHttpSource
import io.grimoire.api.source.PaginatedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

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

    override fun searchNovelsRequest(query: String, page: Int, filters: List<Filter<*>>) =
        GET("$baseUrl/search?keyword=$query&page=$page")

    override fun searchNovelsSelector() = popularNovelsSelector()
    override fun searchNovelsFromElement(element: Element) = popularNovelsFromElement(element)
    override fun searchNovelsNextPageSelector() = popularNovelsNextPageSelector()

    override fun novelDetailsFromDocument(document: Document) = Novel(
        url = document.location(),
        title = document.selectFirst("h3.title")!!.text(),
        thumbnailUrl = document.selectFirst(".book img")?.absUrl("src"),
        author = document.selectFirst(".info a[href*=/author/]")?.text(),
        description = document.selectFirst(".desc-text")?.text(),
        genres = document.select(".info a[href*=/genre/]").map { it.text() },
        status = document.selectFirst(".info h3:contains(Status) + a")?.text().toNovelStatus(),
        initialized = true,
    )

    override fun chapterListSelector() = "#list-chapter .row li"

    override fun chapterFromElement(element: Element) = Chapter(
        url = element.selectFirst("a")!!.attr("href"),
        name = element.selectFirst("a span.chapter-text")?.text()
            ?: element.selectFirst("a")!!.attr("title"),
    )

    override fun pageListSelector() = "#chapter-content p"

    override fun pageFromElement(element: Element, index: Int) = NovelPage(
        index = index,
        text = element.text(),
    )

    override suspend fun getChapterList(novel: Novel, page: Int): List<Chapter> =
        withContext(Dispatchers.IO) {
            chapterListParse(client.newCall(GET("${resolveUrl(novel.url)}?page=$page")).execute())
        }

    override fun getFilterList() = emptyList<Filter<*>>()

    private fun String?.toNovelStatus() = when {
        this == null -> NovelStatus.UNKNOWN
        contains("Ongoing", ignoreCase = true) -> NovelStatus.ONGOING
        contains("Completed", ignoreCase = true) -> NovelStatus.COMPLETED
        contains("Hiatus", ignoreCase = true) -> NovelStatus.HIATUS
        contains("Cancelled", ignoreCase = true) -> NovelStatus.CANCELLED
        else -> NovelStatus.UNKNOWN
    }
}
