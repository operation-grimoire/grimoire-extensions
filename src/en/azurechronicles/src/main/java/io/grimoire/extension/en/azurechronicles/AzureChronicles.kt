package io.grimoire.extension.en.azurechronicles

import android.webkit.CookieManager
import io.grimoire.api.model.filter.Filter
import io.grimoire.api.model.lang.Language
import io.grimoire.api.model.novel.Chapter
import io.grimoire.api.model.novel.Novel
import io.grimoire.api.model.novel.NovelPage
import io.grimoire.api.model.novel.NovelStatus
import io.grimoire.api.model.novel.PageContent
import io.grimoire.api.source.SourceInfo
import io.grimoire.api.source.feature.FilterSource
import io.grimoire.api.source.feature.LatestSource
import io.grimoire.api.source.feature.PopularSource
import io.grimoire.api.source.feature.SearchSource
import io.grimoire.api.source.feature.WebViewLoginSource
import io.grimoire.api.source.http.HttpSource
import io.grimoire.api.source.web.ChapterListSource
import io.grimoire.api.source.web.PageListSource
import io.grimoire.api.util.richDescription
import io.grimoire.api.util.richHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

/**
 * Azure Chronicles (azurechronicles.com) — a translated web-novel site on a
 * bespoke WordPress theme. Browsing scrapes the archive; keyword search and
 * advanced filtering hit the theme's admin-ajax endpoints; premium chapters
 * unlock behind a WebView login.
 */
@SourceInfo(
    name = "Azure Chronicles",
    lang = Language.EN,
    baseUrl = "https://azurechronicles.com",
    versionCode = 6,
    novelUpdatesGroups = ["Azure Chronicles"],
)
class AzureChronicles :
    HttpSource(),
    PopularSource,
    LatestSource,
    SearchSource,
    FilterSource,
    ChapterListSource,
    PageListSource,
    WebViewLoginSource {

    override val name = "Azure Chronicles"
    override val lang = Language.EN
    override val baseUrl = "https://azurechronicles.com"

    @Volatile
    private var nonce: String? = null

    @Volatile
    private var cachedFilters: List<Filter<*>> = emptyList()
    private val filterMutex = Mutex()

    // --- Listings -------------------------------------------------------------

    // Popular is the homepage "Trending" rail, a single un-paginated set.
    override suspend fun getPopularNovels(page: Int): List<Novel> = withContext(Dispatchers.IO) {
        if (page != 1) emptyList()
        else parseCardLinks(get("$baseUrl/").asJsoup(), "article.ac-trending-card a.ac-tc-cover")
    }

    override suspend fun getLatestUpdates(page: Int): List<Novel> = withContext(Dispatchers.IO) {
        val url = if (page <= 1) "$baseUrl/latest/" else "$baseUrl/latest/page/$page/"
        parseCardLinks(get(url).asJsoup(), ".hc-card a.hc-card-cover")
    }

    // Routing: no query/filters -> browse archive (paginated); plain query ->
    // ac_search_series JSON; any filter -> ac_archive_filter_novels (single page).
    override suspend fun searchNovels(query: String, page: Int, filters: List<Filter<*>>): List<Novel> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            val genres = selectedGroup(filters, "genre[]")
            val tags = selectedGroup(filters, "tag[]")
            val sort = selectedSelect(filters, "sort")
            val status = selectedSelect(filters, "status")
            val translator = selectedSelect(filters, "translator")
            val anyFilter = genres.isNotEmpty() || tags.isNotEmpty() ||
                sort != null || status != null || translator != null

            when {
                !anyFilter && q.isEmpty() -> {
                    val url = if (page <= 1) "$baseUrl/type/novel/" else "$baseUrl/type/novel/page/$page/"
                    parseCards(get(url).asJsoup())
                }
                // The ajax endpoints are single-page.
                page != 1 -> emptyList()
                !anyFilter -> {
                    val url = adminAjax()
                        .addQueryParameter("action", "ac_search_series")
                        .addQueryParameter("q", q)
                        .addQueryParameter(PAGE_MARKER, page.toString())
                        .build()
                    parseSearchJson(getBody(url.toString()))
                }
                else -> {
                    val b = adminAjax()
                        .addQueryParameter("action", "ac_archive_filter_novels")
                        .addQueryParameter("nonce", ensureNonce())
                        .addQueryParameter("type", "novel")
                        .addQueryParameter("q", q)
                        .addQueryParameter(PAGE_MARKER, page.toString())
                    genres.forEach { b.addQueryParameter("genre[]", it) }
                    tags.forEach { b.addQueryParameter("tag[]", it) }
                    sort?.let { b.addQueryParameter("sort", it) }
                    status?.let { b.addQueryParameter("status", it) }
                    translator?.let { b.addQueryParameter("translator", it) }
                    // A rejected/expired nonce answers "-1"; degrade to no results.
                    val json = runCatching { JSONObject(getBody(b.build().toString())) }.getOrNull()
                        ?: return@withContext emptyList()
                    parseCards(Jsoup.parse(json.optJSONObject("data")?.optString("html").orEmpty(), baseUrl))
                }
            }
        }

    private fun parseCardLinks(doc: Document, selector: String): List<Novel> =
        doc.select(selector).mapNotNull { link ->
            val href = link.attr("href").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = link.attr("title").trim()
                .ifEmpty { link.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
                .ifEmpty { link.text().trim() }
                .takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Novel(url = absoluteUrl(href), title = title, language = lang, thumbnailUrl = coverUrl(link))
        }.distinctBy { it.url }

    private fun parseCards(doc: Document): List<Novel> =
        parseCardLinks(doc, "article.ac-grid2-card a.ac-grid2-cover-link")

    private fun parseSearchJson(raw: String): List<Novel> {
        val json = runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrNull() ?: return emptyList()
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            val href = item.optString("url").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = item.optString("title").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Novel(
                url = absoluteUrl(href),
                title = title,
                language = lang,
                thumbnailUrl = item.optString("cover").trim().takeIf { it.isNotEmpty() },
                genres = item.optString("genres").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                status = statusOf(item.optString("status_slug").ifEmpty { item.optString("status") }),
            )
        }
    }

    // --- Dynamic filters ------------------------------------------------------

    override val hasDynamicFilters: Boolean = true

    override fun getFilterList(): List<Filter<*>> = cachedFilters.ifEmpty {
        listOf(Filter.Header("Open filters to load genres, tags, translators and sorting."))
    }

    override suspend fun fetchFilterOptions(): List<Filter<*>> = withContext(Dispatchers.IO) {
        if (cachedFilters.isEmpty()) {
            filterMutex.withLock {
                if (cachedFilters.isEmpty()) {
                    runCatching {
                        val html = getBody("$baseUrl/type/novel/")
                        extractNonce(html)?.let { nonce = it }
                        cachedFilters = buildFilters(Jsoup.parse(html, baseUrl))
                    }
                }
            }
        }
        getFilterList()
    }

    private fun buildFilters(doc: Document): List<Filter<*>> = buildList {
        fun options(id: String) = doc.select("#$id option").map { it.attr("value").trim() to it.text().trim() }
        options("ac-archive-filter-sort").takeIf { it.size > 1 }?.let {
            add(SelectFilter("Sort by", "sort", it.map { o -> o.first }, it.map { o -> o.second }))
        }
        options("ac-archive-filter-status").takeIf { it.size > 1 }?.let {
            add(SelectFilter("Status", "status", it.map { o -> o.first }, it.map { o -> o.second }))
        }
        options("ac-archive-filter-translator").takeIf { it.size > 1 }?.let {
            add(SelectFilter("Translator", "translator", it.map { o -> o.first }, it.map { o -> o.second }))
        }
        options("ac-archive-filter-genre").filter { it.first.isNotEmpty() }.takeIf { it.isNotEmpty() }
            ?.let { add(CheckGroupFilter("Genres", "genre[]", it)) }
        options("ac-archive-filter-tag").filter { it.first.isNotEmpty() }.takeIf { it.isNotEmpty() }
            ?.let { tags -> add(CheckGroupFilter("Tags", "tag[]", tags.map { it.first to it.second.removePrefix("#") })) }
    }

    private fun selectedGroup(filters: List<Filter<*>>, param: String): List<String> =
        filters.filterIsInstance<CheckGroupFilter>().firstOrNull { it.param == param }
            ?.let { group -> group.state.filter { it.state }.mapNotNull { group.slugByLabel[it.name] } }
            .orEmpty()

    private fun selectedSelect(filters: List<Filter<*>>, param: String): String? =
        filters.filterIsInstance<SelectFilter>().firstOrNull { it.param == param }
            ?.let { it.slugs.getOrNull(it.state)?.takeIf { slug -> slug.isNotEmpty() } }

    private class SelectFilter(
        name: String,
        val param: String,
        val slugs: List<String>,
        labels: List<String>,
    ) : Filter.Select<String>(name, labels.toTypedArray())

    private class CheckGroupFilter(
        name: String,
        val param: String,
        items: List<Pair<String, String>>,
    ) : Filter.Group<Filter.CheckBox>(name, items.map { Filter.CheckBox(it.second) }) {
        val slugByLabel: Map<String, String> = items.associate { it.second to it.first }
    }

    // --- Novel details --------------------------------------------------------

    override suspend fun getNovelDetails(novel: Novel): Novel = withContext(Dispatchers.IO) {
        val pageUrl = resolveUrl(novel.url)
        val response = get(pageUrl)
        if (!response.isSuccessful) {
            response.close()
            throw IOException("Azure Chronicles: novel not found (HTTP ${response.code}) — it may have been removed.")
        }
        val doc = response.asJsoup()
        fun meta(prop: String) = doc.selectFirst("meta[property=og:$prop], meta[name=$prop]")
            ?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: meta("title")?.substringBefore(" - ")?.trim()
            ?: "Azure Chronicles novel"
        val author = doc.selectFirst("#ac-meta-translator a, #ac-meta-translator .ac-badge-value")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.select(".ac-meta-chip").getOrNull(2)?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val status = doc.select(".ac-meta-chip").firstNotNullOfOrNull { chip -> statusWord(chip.text()) }
            ?: NovelStatus.UNKNOWN
        Novel(
            url = pageUrl,
            title = title,
            language = lang,
            thumbnailUrl = doc.selectFirst("#ac-cover-img")?.let { bgImage(it) }
                ?: doc.selectFirst("#ac-cover-img img, .ac-cover img")?.imageUrl()
                ?: meta("image"),
            author = author,
            description = (
                doc.selectFirst("#synopsis-content")
                    ?: doc.selectFirst("[id*=synopsis]")
                    ?: doc.selectFirst("[class*=synopsis]")
                )?.richDescription()?.takeIf { it.isNotBlank() },
            genres = doc.select("#ac-genres-row a[href*=/genre/]")
                .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct(),
            status = status,
            initialized = true,
        )
    }

    // --- Chapter list ---------------------------------------------------------

    override suspend fun getChapterList(novel: Novel): List<Chapter> = withContext(Dispatchers.IO) {
        val doc = get(resolveUrl(novel.url)).asJsoup()
        val chapters = doc.select("#chapters a.chapter-el").mapNotNull { link ->
            val href = link.attr("data-ac-href").trim().ifEmpty { link.attr("href").trim() }
                .takeIf { it.isNotEmpty() && "/chapter-" in it } ?: return@mapNotNull null
            val label = link.attr("data-ac-chapter-label").trim()
                .ifEmpty { link.selectFirst("span")?.text()?.trim().orEmpty() }
                .ifEmpty { link.ownText().trim() }
                .takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val number = Regex("""[\d.]+""").find(label)?.value?.toFloatOrNull()
                ?: link.parent()?.attr("data-num")?.toFloatOrNull()
                ?: -1f
            val locked = link.attr("data-ac-locked").trim() == "1" ||
                (link.attr("data-ac-cost").trim().toIntOrNull() ?: 0) > 0
            val dateText = link.attr("data-ac-chapter-date").trim()
                .ifEmpty { link.select("span").lastOrNull()?.text()?.trim().orEmpty() }
            Chapter(
                url = absoluteUrl(href),
                name = label,
                chapterNumber = number,
                uploadDate = parseDate(dateText),
                locked = locked,
            )
        }.distinctBy { it.url }
        // The list renders newest-first; the reader expects ascending order.
        val ordered = if (chapters.any { it.chapterNumber >= 0f }) {
            chapters.sortedBy { it.chapterNumber }
        } else {
            chapters.reversed()
        }
        ordered.mapIndexed { i, ch ->
            if (ch.chapterNumber >= 0f) ch else ch.copy(chapterNumber = (i + 1).toFloat())
        }
    }

    private fun parseDate(raw: String): Long {
        val value = raw.trim().takeIf { it.isNotEmpty() } ?: return 0L
        if (value.equals("just now", ignoreCase = true)) return System.currentTimeMillis()
        if ('/' in value) {
            runCatching {
                java.text.SimpleDateFormat("M/d/yyyy", java.util.Locale.US).parse(value)?.time
            }.getOrNull()?.let { return it }
        }
        val m = Regex("""(\d+)\s*(mo|month|min|yr|sec|s|m|h|hr|hour|d|w|y)""", RegexOption.IGNORE_CASE)
            .find(value) ?: return 0L
        val n = m.groupValues[1].toLong()
        val unitMs = when (m.groupValues[2].lowercase()) {
            "mo", "month" -> 30L * 86_400_000
            "y", "yr" -> 365L * 86_400_000
            "w" -> 7L * 86_400_000
            "d" -> 86_400_000L
            "h", "hr", "hour" -> 3_600_000L
            "m", "min" -> 60_000L
            else -> 1_000L
        }
        return System.currentTimeMillis() - n * unitMs
    }

    // --- Chapter content ------------------------------------------------------

    override suspend fun getPageList(chapter: Chapter): List<NovelPage> = withContext(Dispatchers.IO) {
        val doc = get(resolveUrl(chapter.url)).asJsoup()
        val body = doc.selectFirst("#ac-r-body, .ac-r-body")
            ?: throw IOException("Azure Chronicles: couldn't find the chapter content.")
        body.select("h1.ac-r-chapter-name, h1, script, style, .ac-r-ads, [class*=unlock]").remove()
        val paragraphs = body.select("p").mapNotNull { p ->
            val text = p.text().trim()
            if (text.isEmpty()) null else p to text
        }
        if (paragraphs.isEmpty()) {
            throw IOException(
                "Azure Chronicles: this chapter has no readable text — it may be a " +
                    "locked premium chapter that requires unlocking on the site.",
            )
        }
        paragraphs.mapIndexed { i, (el, text) ->
            NovelPage(i, PageContent.Text(text, el.richHtml().takeIf { it != text }))
        }
    }

    // --- WebViewLoginSource ---------------------------------------------------

    override val loginUrl: String = "$baseUrl/login/"
    override val loginSuccessUrl: String = baseUrl

    override suspend fun isLoggedIn(): Boolean = withContext(Dispatchers.IO) {
        CookieManager.getInstance().getCookie(baseUrl).orEmpty()
            .split(';').map { it.trim() }
            .any { it.startsWith("wordpress_logged_in", ignoreCase = true) }
    }

    override suspend fun logout(): Unit = withContext(Dispatchers.IO) {
        val cm = CookieManager.getInstance()
        cm.getCookie(baseUrl).orEmpty()
            .split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
            .forEach { cm.setCookie(baseUrl, "$it=; Max-Age=0; Path=/") }
        cm.flush()
    }

    // --- Helpers --------------------------------------------------------------

    private suspend fun getBody(url: String): String = get(url).use { it.body?.string().orEmpty() }

    private fun adminAjax() = "$baseUrl/wp-admin/admin-ajax.php".toHttpUrlOrNull()!!.newBuilder()

    private suspend fun ensureNonce(): String {
        nonce?.let { return it }
        val fetched = runCatching { extractNonce(getBody("$baseUrl/type/novel/")) }.getOrNull()
        if (fetched != null) nonce = fetched
        return fetched.orEmpty()
    }

    private fun extractNonce(html: String): String? =
        Regex("""\bconst\s+nonce\s*=\s*["']([a-f0-9]+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""\bnonce\s*=\s*["']([a-f0-9]+)["']""").find(html)?.groupValues?.get(1)

    private fun absoluteUrl(href: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> "$baseUrl$href"
        else -> "$baseUrl/$href"
    }

    private fun coverUrl(link: Element): String? {
        link.selectFirst("img")?.imageUrl()?.let { return it }
        (sequenceOf(link) + link.select("[style*=background-image]").asSequence())
            .firstNotNullOfOrNull { bgImage(it) }?.let { return it }
        val lazyBg = listOf("data-bg", "data-background", "data-bg-image", "data-lazy-bg", "data-src")
        (sequenceOf(link) + link.allElements.asSequence()).forEach { el ->
            lazyBg.forEach { name ->
                val v = el.attr(name).trim()
                if ((v.startsWith("http") || v.startsWith("/")) && !v.startsWith("data:")) {
                    return absoluteUrl(v)
                }
            }
        }
        return null
    }

    private fun bgImage(el: Element): String? =
        Regex("""background-image\s*:\s*url\((['"]?)(.*?)\1\)""")
            .find(el.attr("style"))?.groupValues?.get(2)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { absoluteUrl(it) }

    private fun Element.imageUrl(): String? {
        for (name in listOf("data-src", "data-original", "data-lazy-src", "src")) {
            val value = attr(name).trim()
            if (value.isNotEmpty() && !value.startsWith("data:")) return absoluteUrl(value)
        }
        return attr("srcset").trim().substringBefore(" ").takeIf { it.isNotEmpty() }
            ?.let { absoluteUrl(it) }
    }

    private fun statusWord(text: String): NovelStatus? = when {
        text.contains("Completed", ignoreCase = true) -> NovelStatus.COMPLETED
        text.contains("Hiatus", ignoreCase = true) -> NovelStatus.HIATUS
        text.contains("Ongoing", ignoreCase = true) -> NovelStatus.ONGOING
        else -> null
    }

    private fun statusOf(slug: String): NovelStatus = statusWord(slug) ?: NovelStatus.UNKNOWN

    private fun Response.asJsoup(): Document = Jsoup.parse(body!!.string(), request.url.toString())

    companion object {
        private const val PAGE_MARKER = "acpage"
    }
}
