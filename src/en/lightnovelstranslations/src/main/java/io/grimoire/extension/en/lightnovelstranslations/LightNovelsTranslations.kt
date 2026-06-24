package io.grimoire.extension.en.lightnovelstranslations

import io.grimoire.api.model.filter.Filter
import io.grimoire.api.model.lang.Language
import io.grimoire.api.model.novel.Chapter
import io.grimoire.api.model.novel.Novel
import io.grimoire.api.model.novel.NovelPage
import io.grimoire.api.model.novel.NovelStatus
import io.grimoire.api.model.novel.PageContent
import io.grimoire.api.source.SourceInfo
import io.grimoire.api.source.feature.LatestSource
import io.grimoire.api.source.feature.PopularSource
import io.grimoire.api.source.feature.SearchSource
import io.grimoire.api.source.http.ParsedHttpSource
import io.grimoire.api.source.web.ChapterListSource
import io.grimoire.api.source.web.PageListSource
import io.grimoire.api.util.richDescription
import io.grimoire.api.util.richHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Element
import java.net.URLEncoder

@SourceInfo(
    name = "Light Novels Translations",
    lang = Language.EN,
    baseUrl = "https://lightnovelstranslations.com",
    versionCode = 9,
    novelUpdatesGroups = ["Light Novels Translations"],
)
class LightNovelsTranslations :
    ParsedHttpSource(),
    PopularSource,
    LatestSource,
    SearchSource,
    ChapterListSource,
    PageListSource {

    override val name = "Light Novels Translations"
    override val lang = Language.EN
    override val baseUrl = "https://lightnovelstranslations.com"

    // Single catalogue at /read with no separate "popular" ordering.
    private fun readListUrl(page: Int) = if (page > 1) "$baseUrl/read/page/$page/" else "$baseUrl/read/"

    override suspend fun getPopularNovels(page: Int): List<Novel> = withContext(Dispatchers.IO) {
        get(readListUrl(page)).asJsoup().select("div.read_list-story-item").map { it.toListNovel() }
    }

    override suspend fun getLatestUpdates(page: Int): List<Novel> = withContext(Dispatchers.IO) {
        get(readListUrl(page)).asJsoup().select("div.read_list-story-item").map { it.toListNovel() }
    }

    override suspend fun searchNovels(query: String, page: Int, filters: List<Filter<*>>): List<Novel> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = if (page > 1) "$baseUrl/page/$page/?s=$encoded" else "$baseUrl/?s=$encoded"
            get(url).asJsoup().select("article.novels").map { it.toSearchNovel() }
        }

    override suspend fun getNovelDetails(novel: Novel): Novel = withContext(Dispatchers.IO) {
        val url = resolveUrl(novel.url)
        val document = get(url).asJsoup()
        val author = document.select("div.novel_detail_info li")
            .firstOrNull { it.selectFirst("span")?.text()?.startsWith("Author", ignoreCase = true) == true }
            ?.ownText()?.trim()?.takeIf { it.isNotEmpty() }
        Novel(
            url = url,
            title = document.selectFirst("div.novel_title h3")?.text()?.trim().orEmpty(),
            language = lang,
            thumbnailUrl = document.selectFirst("div.novel-image img")?.absUrl("src"),
            author = author,
            description = document.selectFirst("div.novel_text")?.richDescription()?.takeIf { it.isNotBlank() },
            genres = document.select("div.novel_tags_item span").map { it.text().trim() }.filter { it.isNotEmpty() },
            status = document.selectFirst("div.novel_status")?.text().toNovelStatus(),
            initialized = true,
        )
    }

    // Chapters are listed oldest-first (reader order); no reversing needed.
    override suspend fun getChapterList(novel: Novel): List<Chapter> = withContext(Dispatchers.IO) {
        get(resolveUrl(novel.url)).asJsoup()
            .select("div.novel_list_chapter li.chapter-item").map { it.toChapter() }
    }

    // Flat list of <p> in .text_story interleaved with <hr/> scene breaks;
    // illustrations are <p>-wrapped <img>.
    override suspend fun getPageList(chapter: Chapter): List<NovelPage> = withContext(Dispatchers.IO) {
        get(resolveUrl(chapter.url)).asJsoup()
            .select(pageListSelector())
            .mapIndexed { index, element -> pageFromElement(element, index) }
    }

    // Exposed for unit tests, which parse captured chapter fixtures directly.
    internal fun pageListSelector(): String = "div.text_story > p, div.text_story > hr"

    internal fun pageFromElement(element: Element, index: Int): NovelPage = element.toPage(index)

    private fun Element.toListNovel(): Novel {
        val link = selectFirst("div.read_list-story-item--title a")!!
        return Novel(
            url = link.attr("href"),
            title = link.text().trim(),
            language = lang,
            thumbnailUrl = selectFirst("div.item_thumb img")?.absUrl("src"),
        )
    }

    private fun Element.toSearchNovel(): Novel {
        val link = selectFirst("div.entry-summary a")!!
        val img = link.selectFirst("img")
        return Novel(
            url = link.attr("href"),
            title = link.attr("title").ifBlank { img?.attr("alt").orEmpty() }.trim(),
            language = lang,
            thumbnailUrl = img?.absUrl("src"),
        )
    }

    private fun Element.toChapter(): Chapter {
        val link = selectFirst("a")!!
        return Chapter(
            url = link.attr("href"),
            name = link.text().trim(),
            // VIP chapters carry a `lock` class on the <li>; free ones `unlock`.
            locked = hasClass("lock"),
        )
    }

    private fun Element.toPage(index: Int): NovelPage {
        if (tagName().equals("hr", ignoreCase = true)) return NovelPage(index, PageContent.Separator())
        val imageUrl = selectFirst("img")?.imageUrl()
        if (imageUrl != null) return NovelPage(index, PageContent.Image(imageUrl))
        return NovelPage(index, PageContent.Text(text(), richHtml()))
    }

    private fun Element.imageUrl(): String? =
        sequenceOf("data-src", "data-lazy-src", "src")
            .map { absUrl(it) }
            .firstOrNull { it.isNotBlank() }

    private fun String?.toNovelStatus() = when {
        this == null -> NovelStatus.UNKNOWN
        contains("Ongoing", ignoreCase = true) -> NovelStatus.ONGOING
        contains("Completed", ignoreCase = true) -> NovelStatus.COMPLETED
        contains("Hiatus", ignoreCase = true) -> NovelStatus.HIATUS
        contains("Cancelled", ignoreCase = true) -> NovelStatus.CANCELLED
        else -> NovelStatus.UNKNOWN
    }
}
