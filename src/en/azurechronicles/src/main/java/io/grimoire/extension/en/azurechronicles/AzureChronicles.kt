package io.grimoire.extension.en.azurechronicles

import android.webkit.CookieManager
import io.grimoire.api.model.Chapter
import io.grimoire.api.model.Filter
import io.grimoire.api.model.Novel
import io.grimoire.api.model.NovelPage
import io.grimoire.api.model.NovelStatus
import io.grimoire.api.network.HttpSource
import io.grimoire.api.network.richHtml
import io.grimoire.api.source.SourceInfo
import io.grimoire.api.source.WebViewLoginSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

/**
 * Azure Chronicles (azurechronicles.com) — a translated web-novel site on a
 * bespoke WordPress theme (`azurechronicles-21`, custom `ac_novel` post type),
 * so everything is scraped rather than served by a shared base class.
 *
 * - Browsing reads the `/type/novel/` archive (paginated `/page/N/`); cards are
 *   `article.ac-grid2-card`.
 * - Search hits the theme's `ac_search_series` admin-ajax endpoint, which
 *   returns clean JSON.
 * - A novel page server-renders its details and its full chapter list under
 *   `#chapters` (no AJAX pagination), and chapter pages hold the prose in
 *   `#ac-r-body`.
 *
 * The site sets cosmetic "soft protection" (disabled selection/right-click) and
 * has a coin-based unlock system for premium chapters; locked chapters render
 * an unlock prompt instead of prose, which [pageListParse] reports as an error.
 * Login is delegated to a WebView ([WebViewLoginSource]) — signing in lets the
 * session cookies replay on chapter requests so unlocked premium chapters read.
 *
 * Advanced filtering uses the theme's `ac_archive_filter_novels` admin-ajax
 * endpoint (status / translator / sort / genre[] / tag[]); its taxonomy and the
 * required ajax nonce are scraped from the archive page on first filter open.
 */
@SourceInfo(
    id = 10L,
    name = "Azure Chronicles",
    lang = "en",
    baseUrl = "https://azurechronicles.com",
    versionCode = 1,
)
class AzureChronicles : HttpSource(), WebViewLoginSource {

    override val id = 10L
    override val name = "Azure Chronicles"
    override val lang = "en"
    override val baseUrl = "https://azurechronicles.com"

    // The ajax nonce required by ac_archive_filter_novels and the dynamic filter
    // taxonomy, both scraped from the archive page on first filter-sheet open.
    @Volatile
    private var nonce: String? = null

    @Volatile
    private var cachedFilters: List<Filter<*>> = emptyList()
    private val filterMutex = Mutex()

    // --- Listings -------------------------------------------------------------

    // Popular is the homepage "Trending" rail (a single, un-paginated set).
    override fun popularNovelsRequest(page: Int): Request = GET("$baseUrl/?$PAGE_MARKER=$page")

    override suspend fun popularNovelsParse(response: Response): List<Novel> {
        if (otherPage(response)) return emptyList()
        return parseCardLinks(response.asJsoup(), "article.ac-trending-card a.ac-tc-cover")
    }

    // Latest is the dedicated /latest/ feed, paginated by /latest/page/N/.
    override fun latestUpdatesRequest(page: Int): Request =
        if (page <= 1) GET("$baseUrl/latest/") else GET("$baseUrl/latest/page/$page/")

    override suspend fun latestUpdatesParse(response: Response): List<Novel> =
        parseCardLinks(response.asJsoup(), ".hc-card a.hc-card-cover")

    // The /type/novel/ archive backs the filter endpoint's card fragment and the
    // unfiltered-search fallback.
    private fun browseRequest(page: Int): Request =
        if (page <= 1) GET("$baseUrl/type/novel/") else GET("$baseUrl/type/novel/page/$page/")

    // Cards across the site share the shape "<a href title> with a cover img or
    // background-image"; only the wrapping selector differs, so callers pass it.
    private fun parseCardLinks(doc: Document, selector: String): List<Novel> =
        doc.select(selector).mapNotNull { link ->
            val href = link.attr("href").trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val title = link.attr("title").trim()
                .ifEmpty { link.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
                .ifEmpty { link.text().trim() }
                .takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Novel(
                url = absUrl(href),
                title = title,
                // Cover is an <img> (trending), a background-image on the link
                // itself (latest), or on a child wrapper (archive grid cards).
                thumbnailUrl = link.selectFirst("img")?.imageUrl()
                    ?: bgImage(link)
                    ?: link.selectFirst("[style*=background-image]")?.let { bgImage(it) },
            )
        }.distinctBy { it.url }

    // The /type/novel/ archive + filter endpoint use `article.ac-grid2-card`.
    private fun parseCards(doc: Document): List<Novel> =
        parseCardLinks(doc, "article.ac-grid2-card a.ac-grid2-cover-link, article.ac-grid2-card a[href*=/novel/]")

    private fun otherPage(response: Response): Boolean =
        response.request.url.queryParameter(PAGE_MARKER).let { it != null && it != "1" }

    // A plain query (no active filters) uses the simple, nonce-free
    // `ac_search_series` endpoint. As soon as any filter is set, the request
    // switches to `ac_archive_filter_novels`, which supports status / translator
    // / sort / genre[] / tag[] but requires the ajax nonce. Both return a single
    // un-paginated result set, so [PAGE_MARKER] lets the parser stop after page 1.
    override fun searchNovelsRequest(query: String, page: Int, filters: List<Filter<*>>): Request {
        val q = query.trim()
        if (filters.none(::isFilterActive)) {
            if (q.isEmpty()) return browseRequest(page)
            val url = adminAjax()
                .addQueryParameter("action", "ac_search_series")
                .addQueryParameter("q", q)
                .addQueryParameter(PAGE_MARKER, page.toString())
                .build()
            return GET(url.toString())
        }
        val b = adminAjax()
            .addQueryParameter("action", "ac_archive_filter_novels")
            .addQueryParameter("nonce", ensureNonce())
            .addQueryParameter("type", "novel")
            .addQueryParameter("q", q)
            .addQueryParameter(PAGE_MARKER, page.toString())
        for (filter in filters) when (filter) {
            is SelectFilter -> filter.slugs.getOrNull(filter.state)
                ?.takeIf { it.isNotEmpty() }
                ?.let { b.addQueryParameter(filter.param, it) }
            is CheckGroupFilter -> for (cb in filter.state) {
                if (cb.state) filter.slugByLabel[cb.name]?.let { b.addQueryParameter(filter.param, it) }
            }
            else -> Unit
        }
        return GET(b.build().toString())
    }

    override suspend fun searchNovelsParse(response: Response): List<Novel> {
        val url = response.request.url
        if (url.queryParameter(PAGE_MARKER).let { it != null && it != "1" }) return emptyList()
        val raw = response.body?.string().orEmpty()
        return when {
            // Filter endpoint: cards live in the JSON's data.html fragment. A
            // rejected/expired nonce answers "-1" (or 403) rather than JSON —
            // this happens for anonymous sessions, so degrade to no results
            // instead of crashing. Sign in (the nonce becomes valid) to filter.
            "ac_archive_filter_novels" in url.toString() -> {
                val json = runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrNull()
                    ?: return emptyList()
                val html = json.optJSONObject("data")?.optString("html").orEmpty()
                parseCards(Jsoup.parse(html, baseUrl))
            }
            // Keyword search: a flat JSON array of series objects.
            "ac_search_series" in url.toString() -> parseSearchJson(raw)
            // Fallback: a browse listing page (blank query, no filters).
            else -> parseCards(Jsoup.parse(raw, url.toString()))
        }
    }

    private fun parseSearchJson(raw: String): List<Novel> {
        val json = runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrNull() ?: return emptyList()
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            val href = item.optString("url").trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val title = item.optString("title").trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            Novel(
                url = absUrl(href),
                title = title,
                thumbnailUrl = item.optString("cover").trim().takeIf { it.isNotEmpty() },
                genres = item.optString("genres").split(',')
                    .map { it.trim() }.filter { it.isNotEmpty() },
                status = statusOf(item.optString("status_slug").ifEmpty { item.optString("status") }),
            )
        }
    }

    // --- Dynamic filters ------------------------------------------------------

    override val hasDynamicFilters: Boolean = true

    override fun getFilterList(): List<Filter<*>> = cachedFilters.ifEmpty {
        listOf(Filter.Header("Open filters to load genres, tags, translators and sorting."))
    }

    // Double-checked lock: a burst of filter-sheet opens hits the network once.
    // Caches both the filter taxonomy and the ajax nonce from the archive page.
    override suspend fun fetchFilterOptions(): List<Filter<*>> = withContext(Dispatchers.IO) {
        if (cachedFilters.isEmpty()) {
            filterMutex.withLock {
                if (cachedFilters.isEmpty()) {
                    runCatching {
                        val html = client.newCall(GET("$baseUrl/type/novel/"))
                            .execute().use { it.body?.string().orEmpty() }
                        extractNonce(html)?.let { nonce = it }
                        cachedFilters = buildFilters(Jsoup.parse(html, baseUrl))
                    }
                }
            }
        }
        getFilterList()
    }

    private fun buildFilters(doc: Document): List<Filter<*>> = buildList {
        fun options(id: String) = doc.select("#$id option")
            .map { it.attr("value").trim() to it.text().trim() }
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

    private fun isFilterActive(filter: Filter<*>): Boolean = when (filter) {
        is SelectFilter -> filter.state > 0
        is CheckGroupFilter -> filter.state.any { it.state }
        else -> false
    }

    /** A single-choice filter backed by a query parameter; index 0 is the
     *  "all/default" option whose value is blank and therefore skipped. */
    private class SelectFilter(
        name: String,
        val param: String,
        val slugs: List<String>,
        labels: List<String>,
    ) : Filter.Select<String>(name, labels.toTypedArray())

    /** A multi-select group emitting one `param` entry per checked box;
     *  CheckBox is final in the API, so the slug is recovered from the label. */
    private class CheckGroupFilter(
        name: String,
        val param: String,
        items: List<Pair<String, String>>,
    ) : Filter.Group<Filter.CheckBox>(name, items.map { Filter.CheckBox(it.second) }) {
        val slugByLabel: Map<String, String> = items.associate { it.second to it.first }
    }

    // --- Novel details --------------------------------------------------------

    override suspend fun novelDetailsParse(response: Response): Novel {
        val pageUrl = response.request.url.toString()
        val doc = response.asJsoup()
        fun meta(prop: String) = doc.selectFirst("meta[property=og:$prop], meta[name=$prop]")
            ?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: meta("title")?.substringBefore(" - ")?.trim()
            ?: "Azure Chronicles novel"
        // The translator badge doubles as the author/team; the meta chips carry
        // the publication status (Ongoing / Completed / Hiatus).
        val author = doc.selectFirst("#ac-meta-translator a, #ac-meta-translator .ac-badge-value")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.select(".ac-meta-chip").getOrNull(2)?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val status = doc.select(".ac-meta-chip")
            .firstNotNullOfOrNull { chip -> statusWord(chip.text()) }
            ?: NovelStatus.UNKNOWN
        return Novel(
            url = pageUrl,
            title = title,
            thumbnailUrl = doc.selectFirst("#ac-cover-img")?.let { bgImage(it) }
                ?: doc.selectFirst("#ac-cover-img img, .ac-cover img")?.imageUrl()
                ?: meta("image"),
            author = author,
            description = doc.selectFirst(".ac-synopsis-wrap, #ac-synopsis, [class*=synopsis]")
                ?.text()?.trim()?.takeIf { it.isNotEmpty() },
            genres = doc.select("#ac-genres-row a[href*=/genre/]")
                .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct(),
            status = status,
            initialized = true,
        )
    }

    // --- Chapter list ---------------------------------------------------------

    override suspend fun chapterListParse(response: Response): List<Chapter> {
        val doc = response.asJsoup()
        // `#chapters` server-renders the full list; each `a.chapter-el` carries
        // its metadata on data-* attributes: data-ac-locked ("1" for premium),
        // data-ac-cost (coin price), data-ac-chapter-label ("Chapter N"),
        // data-ac-chapter-date (M/D/YYYY). The `.chapter-row` parent's data-num
        // is a fallback number.
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
            // A chapter is locked when the site flags it premium / priced.
            val locked = link.attr("data-ac-locked").trim() == "1" ||
                (link.attr("data-ac-cost").trim().toIntOrNull() ?: 0) > 0
            Chapter(
                url = absUrl(href),
                name = label,
                chapterNumber = number,
                uploadDate = parseDate(link.attr("data-ac-chapter-date")),
                locked = locked,
            )
        }.distinctBy { it.url }
        // The list renders newest-first; the reader expects ascending order.
        val ordered = if (chapters.any { it.chapterNumber >= 0f }) {
            chapters.sortedBy { it.chapterNumber }
        } else {
            chapters.reversed()
        }
        return ordered.mapIndexed { i, ch ->
            if (ch.chapterNumber >= 0f) ch else ch.copy(chapterNumber = (i + 1).toFloat())
        }
    }

    // data-ac-chapter-date is "M/D/YYYY"; 0L when absent/unparseable.
    private fun parseDate(raw: String): Long {
        val value = raw.trim().takeIf { it.isNotEmpty() } ?: return 0L
        return runCatching {
            java.text.SimpleDateFormat("M/d/yyyy", java.util.Locale.US)
                .parse(value)?.time ?: 0L
        }.getOrDefault(0L)
    }

    // --- Chapter content ------------------------------------------------------

    override suspend fun pageListParse(response: Response): List<NovelPage> {
        val doc = response.asJsoup()
        val body = doc.selectFirst("#ac-r-body, .ac-r-body")
            ?: throw IOException("Azure Chronicles: couldn't find the chapter content.")
        // Drop the rendered chapter-title heading; keep the prose paragraphs.
        body.select("h1.ac-r-chapter-name, h1, script, style, .ac-r-ads, [class*=unlock]").remove()
        val paragraphs = body.select("p").mapNotNull { p ->
            val text = p.text().trim()
            if (text.isEmpty()) null else p to text
        }
        if (paragraphs.isEmpty()) {
            // No prose: a premium/locked chapter renders an unlock prompt instead.
            throw IOException(
                "Azure Chronicles: this chapter has no readable text — it may be a " +
                    "locked premium chapter that requires unlocking on the site.",
            )
        }
        return paragraphs.mapIndexed { i, (el, text) ->
            NovelPage(index = i, text = text, formattedText = el.richHtml().takeIf { it != text })
        }
    }

    // --- WebViewLoginSource ---------------------------------------------------

    // Azure Chronicles is WordPress; /login/ is a normal email/password (+Google)
    // page, and a successful sign-in redirects back to the site. The WebView's
    // cookie jar then carries the WP session onto chapter requests so unlocked
    // premium chapters render.
    override val loginUrl: String = "$baseUrl/login/"
    override val loginSuccessUrl: String = baseUrl

    // WordPress sets a `wordpress_logged_in_<hash>` cookie when authenticated.
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

    private fun adminAjax() = "$baseUrl/wp-admin/admin-ajax.php".toHttpUrlOrNull()!!.newBuilder()

    // The filter endpoint rejects a missing/blank nonce (HTTP 403). The nonce is
    // normally cached by fetchFilterOptions before any filtered search; this is
    // the fallback when a filtered request arrives without it.
    private fun ensureNonce(): String {
        nonce?.let { return it }
        val fetched = runCatching {
            client.newCall(GET("$baseUrl/type/novel/")).execute()
                .use { extractNonce(it.body?.string().orEmpty()) }
        }.getOrNull()
        if (fetched != null) nonce = fetched
        return fetched.orEmpty()
    }

    private fun extractNonce(html: String): String? =
        Regex("""nonce:\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            ?.takeIf { it.isNotEmpty() }

    private fun absUrl(href: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> "$baseUrl$href"
        else -> "$baseUrl/$href"
    }

    /** Pulls the URL out of an inline `background-image:url('…')` style. */
    private fun bgImage(el: Element): String? =
        Regex("""background-image\s*:\s*url\((['"]?)(.*?)\1\)""")
            .find(el.attr("style"))?.groupValues?.get(2)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { absUrl(it) }

    private fun Element.imageUrl(): String? {
        val raw = listOf("data-src", "data-original", "src")
            .map { attr(it).trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        return absUrl(raw)
    }

    /** Maps a status word found anywhere in [text] to a [NovelStatus], or null. */
    private fun statusWord(text: String): NovelStatus? = when {
        text.contains("Completed", ignoreCase = true) -> NovelStatus.COMPLETED
        text.contains("Hiatus", ignoreCase = true) -> NovelStatus.HIATUS
        text.contains("Ongoing", ignoreCase = true) -> NovelStatus.ONGOING
        else -> null
    }

    private fun statusOf(slug: String): NovelStatus =
        statusWord(slug) ?: NovelStatus.UNKNOWN

    private fun Response.asJsoup(): Document =
        Jsoup.parse(body!!.string(), request.url.toString())

    companion object {
        private const val PAGE_MARKER = "acpage"
    }
}
