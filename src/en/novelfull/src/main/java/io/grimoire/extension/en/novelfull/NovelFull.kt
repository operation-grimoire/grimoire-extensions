package io.grimoire.extension.en.novelfull

import io.grimoire.api.model.Chapter
import io.grimoire.api.model.Filter
import io.grimoire.api.model.Novel
import io.grimoire.api.model.NovelPage
import io.grimoire.api.model.NovelStatus
import io.grimoire.api.network.ParsedHttpSource
import io.grimoire.api.source.PaginatedSource
import io.grimoire.api.source.SourceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@SourceInfo(
    id = 1L,
    name = "NovelFull",
    lang = "en",
    baseUrl = "https://novelfull.com",
    versionCode = 2,
)
class NovelFull : ParsedHttpSource(), PaginatedSource {

    override val id = 1L
    override val name = "NovelFull"
    override val lang = "en"
    override val baseUrl = "https://novelfull.com"

    // Popular — /most-popular?page=N (page 1 has no param but ?page=1 also works)
    override fun popularNovelsRequest(page: Int): Request =
        GET("$baseUrl/most-popular?page=$page")

    override fun popularNovelsSelector() = ".list-truyen .row:has(.truyen-title)"

    override fun popularNovelsFromElement(element: Element) = Novel(
        url = element.selectFirst(".truyen-title a")!!.attr("href"),
        title = element.selectFirst(".truyen-title a")!!.text(),
        thumbnailUrl = element.selectFirst("img.cover")?.absUrl("src"),
    )

    override fun popularNovelsNextPageSelector() = "ul.pagination li.next a"

    // Latest — /latest-release-novel?page=N
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/latest-release-novel?page=$page")

    override fun latestUpdatesSelector() = popularNovelsSelector()
    override fun latestUpdatesFromElement(element: Element) = popularNovelsFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularNovelsNextPageSelector()

    // Search — /search?keyword=query&page=N
    override fun searchNovelsRequest(query: String, page: Int, filters: List<Filter<*>>): Request =
        GET("$baseUrl/search?keyword=$query&page=$page")

    override fun searchNovelsSelector() = popularNovelsSelector()
    override fun searchNovelsFromElement(element: Element) = popularNovelsFromElement(element)
    override fun searchNovelsNextPageSelector() = popularNovelsNextPageSelector()

    // Novel details
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

    // Chapter list — paginated; #list-chapter .row li each hold one chapter link
    override fun chapterListSelector() = "#list-chapter .row li"

    override fun chapterFromElement(element: Element) = Chapter(
        url = element.selectFirst("a")!!.attr("href"),
        name = element.selectFirst("a span.chapter-text")?.text()
            ?: element.selectFirst("a")!!.attr("title"),
    )

    // Chapter content — #chapter-content p (filter blank paras in text)
    override fun pageListSelector() = "#chapter-content p"

    override fun pageFromElement(element: Element, index: Int) = NovelPage(
        index = index,
        text = element.text(),
    )

    override suspend fun getChapterList(novel: Novel, page: Int): List<Chapter> =
        withContext(Dispatchers.IO) {
            val url = "${resolveUrl(novel.url)}?page=$page"
            chapterListParse(client.newCall(GET(url)).execute())
        }

    override fun getFilterList() = emptyList<Filter<*>>()

    private fun String?.toNovelStatus() = when {
        this == null -> NovelStatus.UNKNOWN
        contains("Ongoing", ignoreCase = true) -> NovelStatus.ONGOING
        contains("Completed", ignoreCase = true) -> NovelStatus.COMPLETED
        else -> NovelStatus.UNKNOWN
    }
}
