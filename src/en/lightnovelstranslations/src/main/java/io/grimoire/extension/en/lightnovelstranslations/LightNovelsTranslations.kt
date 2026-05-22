package io.grimoire.extension.en.lightnovelstranslations

import io.grimoire.api.model.Chapter
import io.grimoire.api.model.Filter
import io.grimoire.api.model.Novel
import io.grimoire.api.model.NovelPage
import io.grimoire.api.model.NovelStatus
import io.grimoire.api.network.ParsedHttpSource
import io.grimoire.api.source.SourceInfo
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

@SourceInfo(
    id = 7L,
    name = "Light Novels Translations",
    lang = "en",
    baseUrl = "https://lightnovelstranslations.com",
    versionCode = 3,
)
class LightNovelsTranslations : ParsedHttpSource() {

    override val id = 7L
    override val name = "Light Novels Translations"
    override val lang = "en"
    override val baseUrl = "https://lightnovelstranslations.com"

    // The site exposes a single catalogue at /read with no separate "popular"
    // ordering, so popular and latest both page through that list.
    private fun readListRequest(page: Int): Request =
        GET(if (page > 1) "$baseUrl/read/page/$page/" else "$baseUrl/read/")

    override fun popularNovelsRequest(page: Int) = readListRequest(page)
    override fun latestUpdatesRequest(page: Int) = readListRequest(page)

    override fun popularNovelsSelector() = "div.read_list-story-item"

    override fun popularNovelsFromElement(element: Element): Novel {
        val link = element.selectFirst("div.read_list-story-item--title a")!!
        return Novel(
            url = link.attr("href"),
            title = link.text().trim(),
            thumbnailUrl = element.selectFirst("div.item_thumb img")?.absUrl("src"),
        )
    }

    override fun popularNovelsNextPageSelector() = "a.next.page-numbers"

    override fun latestUpdatesSelector() = popularNovelsSelector()
    override fun latestUpdatesFromElement(element: Element) = popularNovelsFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularNovelsNextPageSelector()

    // WordPress default search; results are rendered as <article class="novels">.
    override fun searchNovelsRequest(query: String, page: Int, filters: List<Filter<*>>): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET(if (page > 1) "$baseUrl/page/$page/?s=$encoded" else "$baseUrl/?s=$encoded")
    }

    override fun searchNovelsSelector() = "article.novels"

    override fun searchNovelsFromElement(element: Element): Novel {
        val link = element.selectFirst("div.entry-summary a")!!
        val img = link.selectFirst("img")
        return Novel(
            url = link.attr("href"),
            title = link.attr("title").ifBlank { img?.attr("alt").orEmpty() }.trim(),
            thumbnailUrl = img?.absUrl("src"),
        )
    }

    override fun searchNovelsNextPageSelector() = "a.next.page-numbers"

    override fun novelDetailsFromDocument(document: Document): Novel {
        val author = document.select("div.novel_detail_info li")
            .firstOrNull { it.selectFirst("span")?.text()?.startsWith("Author", ignoreCase = true) == true }
            ?.ownText()?.trim()?.takeIf { it.isNotEmpty() }

        return Novel(
            url = document.location(),
            title = document.selectFirst("div.novel_title h3")?.text()?.trim().orEmpty(),
            thumbnailUrl = document.selectFirst("div.novel-image img")?.absUrl("src"),
            author = author,
            description = document.selectFirst("div.novel_text")?.text()?.trim(),
            genres = document.select("div.novel_tags_item span").map { it.text().trim() }
                .filter { it.isNotEmpty() },
            status = document.selectFirst("div.novel_status")?.text().toNovelStatus(),
            initialized = true,
        )
    }

    // Chapters are listed oldest-first, grouped by volume — the order the
    // reader expects, so no reversing is needed.
    override fun chapterListSelector() = "div.novel_list_chapter li.chapter-item"

    override fun chapterFromElement(element: Element): Chapter {
        val link = element.selectFirst("a")!!
        return Chapter(
            url = link.attr("href"),
            name = link.text().trim(),
            uploadDate = 0L,
            // VIP chapters carry a `lock` class on the <li>; free ones `unlock`.
            locked = element.hasClass("lock"),
        )
    }

    override fun pageListSelector() = "div.text_story p"

    override fun pageFromElement(element: Element, index: Int) = NovelPage(
        index = index,
        text = element.text(),
    )

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
