package io.grimoire.extension.en.webnovel

import android.webkit.CookieManager
import io.grimoire.api.model.Chapter
import io.grimoire.api.model.Filter
import io.grimoire.api.model.Novel
import io.grimoire.api.model.NovelPage
import io.grimoire.api.model.NovelStatus
import io.grimoire.api.network.HttpSource
import io.grimoire.api.network.richDescription
import io.grimoire.api.network.richHtml
import io.grimoire.api.source.ConfigurableSource
import io.grimoire.api.source.SourceInfo
import io.grimoire.api.source.SourcePreference
import io.grimoire.api.source.WebViewLoginSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.IOException
import java.net.URLEncoder

/**
 * Webnovel.com (Qidian International) source.
 *
 * Book, catalog and chapter pages are server-rendered, so they are scraped
 * with Jsoup. Listing pages render their cards client-side, so browsing falls
 * back to the Next.js `__NEXT_DATA__` JSON the site also embeds.
 *
 * Webnovel gates later chapters behind a paid account, so this implements
 * [WebViewLoginSource]: the host opens [loginUrl] in a WebView (covering the
 * social-login providers) and the resulting session cookies are replayed on
 * this source's requests. Catalog rows for VIP chapters carry a lock glyph;
 * those are reported with [Chapter.locked] = `true`.
 */
@SourceInfo(
    id = 8L,
    name = "Webnovel",
    lang = "en",
    baseUrl = "https://www.webnovel.com",
    versionCode = 26,
    novelUpdatesGroups = ["Webnovel"],
)
class WebNovel : HttpSource(), WebViewLoginSource, ConfigurableSource {

    override val id = 8L
    override val name = "Webnovel"
    override val lang = "en"
    override val baseUrl = "https://www.webnovel.com"

    // Which power-ranking section "Popular" shows. Defaults to the male-lead
    // list (webnovel.com's main ranking); a preference flips it to female-lead.
    @Volatile
    private var rankingSex: Int = SEX_MALE

    // --- ConfigurableSource --------------------------------------------------

    override fun getPreferences(): List<SourcePreference> = listOf(
        SourcePreference.Switch(
            key = PREF_FEMALE_RANKING,
            title = "Female-lead Popular ranking",
            summary = "Show the female-lead power ranking on the Popular tab " +
                "instead of the male-lead one.",
            default = false,
        ),
    )

    override fun setPreferences(values: Map<String, String>) {
        rankingSex = if (values[PREF_FEMALE_RANKING] == "true") SEX_FEMALE else SEX_MALE
    }

    // Book urls already returned for the current listing, so paging never
    // repeats one (reset whenever page 1 is requested).
    private val popularSeen = HashSet<String>()
    private val latestSeen = HashSet<String>()
    private val searchSeen = HashSet<String>()

    // Query string for the ranking scroll API, captured from the first
    // ranking page's __NEXT_DATA__; null until that page has been parsed.
    @Volatile
    private var rankApiQuery: String? = null

    // Advanced-search tag taxonomy, fetched once per session by
    // fetchFilterOptions() and cached for the rest of the session. The tag map
    // also records each tag's section id (sex=1 or sex=2) so the search can
    // target the section it belongs to.
    @Volatile
    private var tagGroups: List<Pair<String, List<Pair<String, String>>>> = emptyList()

    @Volatile
    private var tagNameToId: Map<String, Pair<String, Int>> = emptyMap()

    private val tagMutex = Mutex()

    // --- WebViewLoginSource --------------------------------------------------

    override val loginUrl =
        "https://passport.webnovel.com/login.html?appid=900&areaid=1" +
            "&returnurl=https%3A%2F%2Fwww.webnovel.com%2F"

    override val loginSuccessUrl = "https://www.webnovel.com"

    /** Webnovel stores the signed-in user's id in the `uid` cookie. */
    override suspend fun isLoggedIn(): Boolean = withContext(Dispatchers.IO) {
        val raw = CookieManager.getInstance().getCookie(baseUrl).orEmpty()
        val uid = cookieValue(raw, "uid")
        !uid.isNullOrBlank() && uid != "0"
    }

    override suspend fun logout(): Unit = withContext(Dispatchers.IO) {
        val cm = CookieManager.getInstance()
        // Collect cookie names across every host Webnovel sets them on — the
        // session cookies (uid, _csrfToken, …) are spread over the passport and
        // m. subdomains, not just baseUrl.
        val names = COOKIE_DOMAINS
            .flatMap { url -> cm.getCookie(url).orEmpty().split(';') }
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        // Webnovel's auth cookies are domain cookies (Domain=.webnovel.com). A
        // host-only expiry cookie does NOT override a domain cookie, so each
        // name must be expired both host-only and with the matching Domain
        // attribute, or the login survives logout.
        names.forEach { name ->
            COOKIE_DOMAINS.forEach { url ->
                cm.setCookie(url, "$name=; $COOKIE_EXPIRY")
                COOKIE_PARENT_DOMAINS.forEach { domain ->
                    cm.setCookie(url, "$name=; Domain=$domain; $COOKIE_EXPIRY")
                }
            }
        }
        cm.flush()
    }

    // --- Listings ------------------------------------------------------------

    // Page 1 is the server-rendered ranking page. Webnovel ignores ?pageIndex
    // there for ordinary requests, so deeper pages are fetched from the
    // ranking's own scroll API, parameterised from page 1's __NEXT_DATA__.
    override fun popularNovelsRequest(page: Int): Request {
        val query = rankApiQuery
        if (page <= 1 || query == null) {
            // The power ranking is split into a male-lead (sex=1) and
            // female-lead (sex=2) section; with no sex param the mobile site
            // defaults to sex=2, so "Popular" would otherwise be the
            // romance-heavy female list. Pin the section explicitly (user
            // choice via getPreferences, default male) so the default matches
            // webnovel.com's main ranking.
            return GET("$RANKING_URL?sex=$rankingSex&pageIndex=$page")
        }
        val csrf = runCatching {
            cookieValue(CookieManager.getInstance().getCookie(RANKING_URL).orEmpty(), "_csrfToken")
        }.getOrNull()
        val url = buildString {
            append(RANKING_API).append('?').append(query).append("&pageIndex=").append(page)
            if (!csrf.isNullOrBlank()) append("&_csrfToken=").append(csrf)
        }
        return Request.Builder()
            .url(url)
            .header("Referer", "$RANKING_URL?pageIndex=$page")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json")
            .build()
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$LATEST_URL&pageIndex=$page")

    // A bare query runs Webnovel's keyword search. Once a tag is set the
    // request switches to the advanced tag search, which ignores keywords.
    override fun searchNovelsRequest(query: String, page: Int, filters: List<Filter<*>>): Request {
        if (filters.none(::filterActive)) {
            val keywords = query.trim()
            // The advanced search needs a tag and the keyword search needs a
            // keyword; with neither, browse the ranking so filtering without a
            // tag still shows books instead of an empty screen.
            if (keywords.isEmpty()) return popularNovelsRequest(page)
            val encoded = URLEncoder.encode(keywords, "UTF-8")
            return GET("$baseUrl/search?keywords=$encoded&pageIndex=$page")
        }
        val include = mutableListOf<String>()
        val exclude = mutableListOf<String>()
        val extras = StringBuilder()
        // Webnovel's advanced search is scoped to one section (sex=1 male,
        // sex=2 female); derive it from the selected tags so a Romance pick
        // searches the female section. Default to sex=1 when only excludes or
        // non-tag filters are set.
        var section: Int? = null
        for (filter in filters) when (filter) {
            is ParamFilter -> filter.codes[filter.state].takeIf { it != OMIT }
                ?.let { extras.append('&').append(filter.param).append('=').append(it) }
            is Filter.Group<*> -> for (tag in triStatesOf(filter)) {
                val info = tagNameToId[tag.name] ?: continue
                when (tag.state) {
                    Filter.TriState.STATE_INCLUDE -> {
                        include += info.first
                        if (section == null) section = info.second
                    }
                    Filter.TriState.STATE_EXCLUDE -> exclude += info.first
                }
            }
            else -> Unit
        }
        val params = StringBuilder("sex=").append(section ?: 1).append(extras)
        if (include.isNotEmpty()) params.append("&tagId=").append(include.joinToString(","))
        if (exclude.isNotEmpty()) params.append("&negTagId=").append(exclude.joinToString(","))
        params.append("&pageIndex=").append(page)
        return apiRequest("$SEARCH_API?$params")
    }

    override suspend fun popularNovelsParse(response: Response): List<Novel> =
        parseRanking(response, popularSeen)

    private fun parseRanking(response: Response, seen: MutableSet<String>): List<Novel> {
        val body = response.bodyText()
        // Deeper pages are the JSON scroll API; page 1 is the rendered page.
        if (response.request.url.toString().contains("getRankList")) {
            return paginate(seen, requestedPage(response), apiBooks(body))
        }
        // The desktop ranking page renders its rows server-side, in rank order,
        // with a clean title on each book anchor — parse those first. The
        // JSON-LD ItemList and __NEXT_DATA__ stay as fallbacks for the mobile
        // layout / future markup changes.
        val doc = Jsoup.parse(body, response.request.url.toString())
        val books = parseBookList(doc)
            .ifEmpty { rankedFromJsonLd(body) }
            .ifEmpty { booksFromNextData(body) }
        captureRankApiQuery(body)
        return paginate(seen, requestedPage(response), books)
    }

    /** Books from a Webnovel JSON list API (getRankList / get-search-list). */
    private fun apiBooks(json: String): List<Novel> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val items = findBookArray(root) ?: return emptyList()
        val out = mutableListOf<Novel>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("bookId").ifEmpty { item.optString("id") }
                .filter(Char::isDigit)
            if (id.isEmpty()) continue
            val title = item.optString("bookName").ifEmpty { item.optString("name") }.trim()
            if (title.length < 2) continue
            out += Novel(url = "/book/$id", title = title, thumbnailUrl = coverUrl(id))
        }
        return out
    }

    /**
     * Captures the ranking scroll-API parameters from the first ranking
     * page's `__NEXT_DATA__`. The page records the exact query it rendered
     * with, so the parameters never need to be hard-coded.
     */
    private fun captureRankApiQuery(html: String) {
        val root = runCatching { nextDataRoot(html) }.getOrNull() ?: return
        val query = findObject(root) {
            it.has("rankId") && it.has("timeType") && it.has("signStatus")
        } ?: return
        val params = StringBuilder()
        for (key in query.keys()) {
            if (key == "pageIndex") continue
            val value = query.opt(key)
            if (value == null || value is JSONObject || value is JSONArray) continue
            if (params.isNotEmpty()) params.append('&')
            params.append(key).append('=')
                .append(URLEncoder.encode(value.toString(), "UTF-8"))
        }
        // getRankList also sends sourceType, mirroring bookType.
        query.opt("bookType")?.let {
            params.append("&sourceType=").append(URLEncoder.encode(it.toString(), "UTF-8"))
        }
        if (params.isNotEmpty()) rankApiQuery = params.toString()
    }

    /**
     * Ranking pages list their books, in rank order, as a schema.org JSON-LD
     * `ItemList`. Titles are filled from the page's entity store when present,
     * otherwise derived from the book URL slug.
     */
    private fun rankedFromJsonLd(html: String): List<Novel> {
        val titles = nextDataBooks(html)
        val ranked = mutableListOf<Pair<Int, Novel>>()
        var from = 0
        while (true) {
            val tag = html.indexOf("application/ld+json", from)
            if (tag < 0) break
            val open = html.indexOf('>', tag) + 1
            val close = html.indexOf("</script>", open)
            if (open <= 0 || close < 0) break
            from = close + 1
            val items = runCatching {
                JSONObject(html.substring(open, close)).optJSONArray("itemListElement")
            }.getOrNull() ?: continue
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val bookUrl = item.optString("url")
                val id = bookId(bookUrl) ?: continue
                val title = titles?.optJSONObject(id)
                    ?.let { it.optString("bookName").ifEmpty { it.optString("name") } }
                    ?.trim()?.takeIf { it.isNotEmpty() }
                    ?: titleFromBookUrl(bookUrl)
                ranked += item.optInt("position", i + 1) to
                    Novel(url = "/book/$id", title = title, thumbnailUrl = coverUrl(id))
            }
        }
        return ranked.sortedBy { it.first }.map { it.second }.distinctBy { it.url }
    }

    /** Best-effort title from a `/book/<slug>_<id>` URL when no better source. */
    private fun titleFromBookUrl(url: String): String {
        val slug = url.substringBefore('?').substringAfterLast("/book/").substringBeforeLast('_')
        val decoded = runCatching { java.net.URLDecoder.decode(slug, "UTF-8") }.getOrDefault(slug)
        // Ranking JSON-LD urls keep HTML entities in the slug (alpha&apos;s-…),
        // so unescape before title-casing or the entity leaks into the title.
        return Parser.unescapeEntities(decoded, false)
            .split('-').filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
            .ifBlank { "Webnovel book" }
    }


    override suspend fun latestUpdatesParse(response: Response): List<Novel> =
        parseSearchListing(response, latestSeen)

    override suspend fun searchNovelsParse(response: Response): List<Novel> {
        val url = response.request.url.toString()
        // The advanced tag search returns JSON; keyword search returns a page.
        if (url.contains("get-search-list")) {
            return paginate(searchSeen, requestedPage(response), apiBooks(response.bodyText()))
        }
        // A no-tag, no-keyword filter falls back to the ranking listing.
        if (url.contains("getRankList") || url.contains("/ranking/")) {
            return parseRanking(response, searchSeen)
        }
        return parseSearchListing(response, searchSeen)
    }

    private fun parseSearchListing(response: Response, seen: MutableSet<String>): List<Novel> {
        val html = response.bodyText()
        val books = parseBookList(Jsoup.parse(html, response.request.url.toString()))
            .ifEmpty { booksFromNextData(html) }
        return paginate(seen, requestedPage(response), books)
    }

    // --- Filters -------------------------------------------------------------

    override val hasDynamicFilters: Boolean = true

    override fun getFilterList(): List<Filter<*>> = buildList {
        add(
            Filter.Header(
                "Select one or more tags to search. Sort, Status and the " +
                    "other filters only refine a tag search.",
            ),
        )
        add(
            ParamFilter(
                "Content type", "categoryType",
                arrayOf("Web Novel", "Fanfic"), intArrayOf(1, 4),
            ),
        )
        add(
            ParamFilter(
                "Sort by", "orderBy",
                arrayOf("Popular", "Collection", "Updated"), intArrayOf(1, 2, 3),
            ),
        )
        add(
            ParamFilter(
                "Status", "bookStatus",
                arrayOf("Any", "Ongoing", "Completed"), intArrayOf(0, 1, 2),
            ),
        )
        add(
            ParamFilter(
                "Chapters", "chapterNum",
                arrayOf("Any", "Fewer than 300", "300 - 1000", "More than 1000"),
                intArrayOf(OMIT, 1, 2, 3),
            ),
        )
        add(
            ParamFilter(
                "Updated", "newChapterTime",
                arrayOf("Any", "Within 7 days", "Within 15 days", "Within 30 days"),
                intArrayOf(0, 7, 15, 30),
            ),
        )
        if (tagGroups.isNotEmpty()) add(Filter.Header("Tags"))
        for ((group, tags) in tagGroups) {
            add(
                TagGroup(
                    group.replaceFirstChar(Char::uppercase),
                    tags.map { (tagName, _) -> Filter.TriState(tagName) },
                ),
            )
        }
    }

    // Double-checked lock: a burst of filter-sheet opens hits the network once.
    override suspend fun fetchFilterOptions(): List<Filter<*>> = withContext(Dispatchers.IO) {
        if (tagGroups.isEmpty()) {
            tagMutex.withLock {
                if (tagGroups.isEmpty()) {
                    // Webnovel splits its tag taxonomy across the male (sex=1)
                    // and female (sex=2) sections; each call returns only half
                    // the tags, so fetch both and merge.
                    val merged = LinkedHashMap<String, LinkedHashMap<String, Pair<String, Int>>>()
                    for (section in intArrayOf(1, 2)) runCatching {
                        val body = client.newCall(
                            apiRequest("$TAG_LIST_API?categoryType=1&sex=$section"),
                        ).execute().use { it.bodyText() }
                        val groups = mutableListOf<Pair<String, List<Pair<String, String>>>>()
                        collectTagGroups(JSONObject(body), groups)
                        for ((groupName, tags) in groups) {
                            val into = merged.getOrPut(groupName) { LinkedHashMap() }
                            for ((tagName, tagId) in tags) {
                                into.putIfAbsent(tagName, tagId to section)
                            }
                        }
                    }
                    if (merged.isNotEmpty()) {
                        tagGroups = merged.map { (name, tags) ->
                            name to tags.entries
                                .sortedBy { it.key.lowercase() }
                                .map { it.key to it.value.first }
                        }
                        tagNameToId = merged.values
                            .flatMap { it.entries }
                            .associate { it.key to it.value }
                    }
                }
            }
        }
        getFilterList()
    }

    /** Recursively collects `(groupName, [(tagName, tagId)])` from a tag-list response. */
    private fun collectTagGroups(
        node: Any?,
        out: MutableList<Pair<String, List<Pair<String, String>>>>,
    ) {
        when (node) {
            is JSONObject -> {
                for (key in node.keys()) {
                    val array = node.optJSONArray(key) ?: continue
                    // Tag objects carry `tagName`; group objects carry `name`.
                    if (array.optJSONObject(0)?.has("tagName") != true) continue
                    val tags = (0 until array.length()).mapNotNull { i ->
                        val tag = array.optJSONObject(i) ?: return@mapNotNull null
                        val id = tag.optString("tagId").ifEmpty { tag.optString("id") }
                        val name = tag.optString("tagName").ifEmpty { tag.optString("name") }
                        if (id.isEmpty() || name.isEmpty()) null else name.trim() to id
                    }
                    if (tags.isNotEmpty()) {
                        val name = sequenceOf("categoryName", "groupName", "name", "title")
                            .map { node.optString(it).trim() }
                            .firstOrNull { it.isNotEmpty() } ?: key
                        out += name to tags
                    }
                }
                for (key in node.keys()) collectTagGroups(node.opt(key), out)
            }
            is JSONArray -> for (i in 0 until node.length()) collectTagGroups(node.opt(i), out)
        }
    }

    private fun triStatesOf(group: Filter.Group<*>): List<Filter.TriState> =
        (group.state as? List<*>).orEmpty().filterIsInstance<Filter.TriState>()

    // The advanced search returns nothing without a tag, so only a tag
    // selection switches search out of plain keyword mode. The other filters
    // refine a tag search but cannot drive one on their own.
    private fun filterActive(filter: Filter<*>): Boolean =
        filter is Filter.Group<*> &&
            triStatesOf(filter).any { it.state != Filter.TriState.STATE_IGNORE }

    /** Builds a JSON-API request with the headers Webnovel's XHR endpoints expect. */
    private fun apiRequest(url: String): Request {
        val csrf = runCatching {
            cookieValue(
                CookieManager.getInstance().getCookie("https://m.webnovel.com").orEmpty(),
                "_csrfToken",
            )
        }.getOrNull()
        val full = if (csrf.isNullOrBlank()) {
            url
        } else {
            "$url${if ('?' in url) '&' else '?'}_csrfToken=$csrf"
        }
        return Request.Builder()
            .url(full)
            .header("Referer", "https://m.webnovel.com/search/advancedResult")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json")
            .build()
    }

    /** A single-choice filter backed by an integer query parameter. */
    private class ParamFilter(
        name: String,
        val param: String,
        labels: Array<String>,
        val codes: IntArray,
    ) : Filter.Select<String>(name, labels)

    /** A searchable group of advanced-search tag include/exclude toggles. */
    private class TagGroup(name: String, tags: List<Filter<*>>) :
        Filter.Group<Filter<*>>(name, tags)

    /** Book-card extraction from a server-rendered listing page. */
    private fun parseBookList(doc: Document): List<Novel> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Novel>()
        // Cards expose the book id via the href or a data-* attribute.
        for (anchor in doc.select("a[href*=/book/], a[data-report-bid], a[data-bid]")) {
            val id = bookId(anchor.attr("href"))
                ?: anchor.attr("data-report-bid").filter(Char::isDigit).takeIf { it.isNotEmpty() }
                ?: anchor.attr("data-bid").filter(Char::isDigit).takeIf { it.isNotEmpty() }
                ?: continue
            if (!seen.add(id)) continue
            val card = anchor.ancestorLi() ?: anchor.parent() ?: anchor
            val title = sequenceOf(
                anchor.attr("title"),
                card.selectFirst("h2, h3, h4, [class*=title], [class*=name]")?.text(),
                anchor.selectFirst("img")?.attr("alt"),
            ).mapNotNull { it?.trim() }.firstOrNull { it.length >= 2 } ?: continue
            out += Novel(url = "/book/$id", title = title, thumbnailUrl = coverUrl(id))
        }
        return out
    }

    /** Book extraction from a Next.js page's `__NEXT_DATA__` entity store. */
    private fun booksFromNextData(html: String): List<Novel> {
        val books = nextDataBooks(html) ?: return emptyList()
        val out = mutableListOf<Novel>()
        for (key in books.keys()) {
            val book = books.optJSONObject(key) ?: continue
            val id = book.optString("bookId").takeIf { it.isNotEmpty() } ?: key
            if (id.isEmpty() || id.any { !it.isDigit() }) continue
            val title = book.optString("bookName").ifEmpty { book.optString("name") }.trim()
            if (title.length < 2) continue
            out += Novel(
                url = "/book/$id",
                title = title,
                thumbnailUrl = coverUrl(id),
                author = book.optString("authorName").trim().takeIf { it.isNotEmpty() },
                description = book.optString("description").trim().takeIf { it.isNotEmpty() },
            )
        }
        return out
    }

    // --- Novel details -------------------------------------------------------

    // The catalog page embeds the full book record (description, tags, score)
    // in its __NEXT_DATA__, so novel details are sourced from there.
    override fun novelDetailsRequest(novel: Novel): Request = chapterListRequest(novel)

    override suspend fun novelDetailsParse(response: Response): Novel {
        val html = response.bodyText()
        val pageUrl = response.request.url.toString()
        val id = bookId(pageUrl)

        val book = id?.let { nextDataBook(html, it) }
        if (book != null) {
            val tags = book.optJSONArray("tagInfos")
            val genres = buildList {
                book.optString("categoryName").trim().takeIf { it.isNotEmpty() }?.let { add(it) }
                if (tags != null) for (i in 0 until tags.length()) {
                    tags.optJSONObject(i)?.optString("tagName")?.trim()
                        ?.takeIf { t -> t.isNotEmpty() }?.let { add(it) }
                }
            }.distinct()
            val score = book.optDouble("totalScore", Double.NaN)
            return Novel(
                url = "/book/$id",
                title = book.optString("bookName").ifEmpty { book.optString("name") }.trim()
                    .ifEmpty { "Webnovel book" },
                thumbnailUrl = coverUrl(id),
                author = book.optString("authorName").trim().takeIf { it.isNotEmpty() },
                description = book.optString("description").trim().takeIf { it.isNotEmpty() },
                genres = genres,
                status = statusFromHtml(html),
                rating = score.takeIf { !it.isNaN() && it > 0 }?.toFloat(),
                ratingCount = book.optInt("voters", 0).takeIf { it > 0 },
                initialized = true,
            )
        }

        // Fallback: scrape the page if the embedded data is unavailable.
        val doc = Jsoup.parse(html, pageUrl)
        fun meta(prop: String) = doc.selectFirst(
            "meta[property=og:$prop], meta[name=$prop], meta[name=og:$prop]",
        )?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
        return Novel(
            url = id?.let { "/book/$it" } ?: pageUrl,
            title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: meta("title")?.substringBefore(" - ")?.trim()
                ?: "Webnovel book",
            thumbnailUrl = id?.let { coverUrl(it) } ?: meta("image"),
            author = doc.selectFirst("a[href*=/profile/], [class*=author] a")
                ?.text()?.trim()?.removePrefix("Author:")?.trim()?.takeIf { it.isNotEmpty() },
            description = doc.selectFirst(".j_synopsis, [class*=synopsis], [class*=description]")
                ?.richDescription()?.takeIf { it.isNotBlank() }
                ?: meta("description"),
            genres = doc.select("a[href*=/tags/], a[href*=/category]")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() && it.length < 30 }
                .distinct()
                .take(12),
            status = statusFromHtml(html),
            initialized = true,
        )
    }

    // --- Chapter list --------------------------------------------------------

    // The book page does not list chapters; the dedicated catalog page does.
    override fun chapterListRequest(novel: Novel): Request {
        val id = bookId(novel.url)
            ?: throw IOException("Webnovel: could not determine the book id")
        return GET("$baseUrl/book/$id/catalog")
    }

    override suspend fun chapterListParse(response: Response): List<Chapter> {
        val html = response.bodyText()
        val url = response.request.url.toString()
        val doc = Jsoup.parse(html, url)
        val bookId = bookId(url)
            ?: throw IOException("Webnovel: could not determine the book id")
        // Publish times are not in the rendered rows; they live in the page's
        // __NEXT_DATA__, keyed by chapter id.
        val publishTimes = chapterPublishTimes(html)
        // A real chapter link is /book/<bookId>/<chapterId>. Restricting to this
        // book's id keeps "recommended" / sidebar book links out of the list.
        return doc.select("a[href*=/book/$bookId/]").mapNotNull { anchor ->
            val href = anchor.attr("href").trim().substringBefore('?').trimEnd('/')
            val chapterSegment = href.substringAfter("/book/$bookId/", "")
            if (chapterSegment.isEmpty() || chapterSegment.contains('/')) {
                return@mapNotNull null
            }
            val name = (anchor.selectFirst("strong")?.text()?.trim()
                ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
                ?: anchor.ownText().trim())
                .takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Chapter(
                url = href,
                name = name,
                uploadDate = publishTimes[chapterSegment] ?: 0L,
                // VIP/locked rows carry a lock <svg>; free rows have none.
                locked = anchor.selectFirst("svg") != null,
            )
        }
            .distinctBy { it.url }
            // The catalog lists chapters in reading order; number them by that
            // position. Webnovel titles are arbitrary, so they cannot be parsed
            // for a chapter number.
            .mapIndexed { i, ch -> ch.copy(chapterNumber = (i + 1).toFloat()) }
    }

    /** Chapter publish times (epoch millis) keyed by chapter id, from the
     *  catalog page's __NEXT_DATA__ `entities.chapter` map. */
    private fun chapterPublishTimes(html: String): Map<String, Long> {
        val chapters = runCatching {
            nextDataState(html).getJSONObject("entities").getJSONObject("chapter")
        }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, Long>()
        for (id in chapters.keys()) {
            val time = chapters.optJSONObject(id)?.optLong("publishTime") ?: 0L
            if (time > 0L) out[id] = time
        }
        return out
    }

    // --- Chapter content -----------------------------------------------------

    override suspend fun pageListParse(response: Response): List<NovelPage> {
        val html = response.bodyText()
        val url = response.request.url.toString()
        val chapter = nextDataChapter(html, url)

        // A locked VIP chapter still ships a short teaser in `contents`. Refuse
        // it so the reader shows the locked notice instead of a misleading
        // preview that just stops a few paragraphs in.
        if (chapter != null && chapter.optInt("isAuth", 1) == 0) {
            throw IOException(LOCKED_MESSAGE)
        }

        // Chapter text lives in __NEXT_DATA__ at entities.chapter[id].contents:
        // an array of paragraphs whose `content` is a small HTML fragment.
        // We parse the fragment as a body, then keep both the plain text (for
        // TTS/search) and a richHtml() version (for the reader's italics/bold/
        // links rendering) — when they match, formattedText is left null so
        // plain prose paragraphs don't double their size on disk.
        val contents = chapter?.optJSONArray("contents")
        if (contents != null && contents.length() > 0) {
            val pages = (0 until contents.length()).mapNotNull { i ->
                val fragment = contents.optJSONObject(i)?.optString("content").orEmpty()
                val body = Jsoup.parseBodyFragment(fragment).body()
                val text = body.text().trim()
                if (text.isEmpty()) null else {
                    val formatted = body.richHtml().takeIf { it != text }
                    NovelPage(index = i, text = text, formattedText = formatted)
                }
            }
            if (pages.isNotEmpty()) return pages
        }

        // Fallback for any server-rendered layout.
        val scraped = Jsoup.parse(html, url)
            .select(".cha-words p, .cha-content p, .j_chapterWrapper p")
            .mapNotNull { el ->
                val text = el.text().trim()
                if (text.isEmpty()) null else el to text
            }
        if (scraped.isNotEmpty()) {
            return scraped.mapIndexed { i, (el, text) ->
                val formatted = el.richHtml().takeIf { it != text }
                NovelPage(index = i, text = text, formattedText = formatted)
            }
        }

        throw IOException(LOCKED_MESSAGE)
    }

    // --- Helpers -------------------------------------------------------------

    private fun Response.bodyText(): String = body?.string().orEmpty()

    private fun requestedPage(response: Response): Int =
        response.request.url.queryParameter("pageIndex")?.toIntOrNull() ?: 1

    /**
     * Keeps only books not already returned for this listing. Webnovel listing
     * pages re-serve page 1 when they do not support the page param, so this
     * both de-duplicates and lets an exhausted/repeated page end pagination.
     */
    private fun paginate(seen: MutableSet<String>, page: Int, novels: List<Novel>): List<Novel> =
        synchronized(seen) {
            if (page <= 1) seen.clear()
            novels.filter { seen.add(it.url) }
        }

    private fun Response.asJsoup(): Document =
        Jsoup.parse(bodyText(), request.url.toString())

    /** Covers have a deterministic CDN URL keyed by book id. */
    private fun coverUrl(bookId: String): String =
        "https://book-pic.webnovel.com/bookcover/$bookId"

    /** Parses the raw `__NEXT_DATA__` JSON blob of a Next.js page. */
    private fun nextDataRoot(html: String): JSONObject {
        val marker = html.indexOf("id=\"__NEXT_DATA__\"")
        if (marker < 0) throw IOException("no __NEXT_DATA__")
        val start = html.indexOf('>', marker) + 1
        val end = html.indexOf("</script>", start)
        if (start <= 0 || end < 0) throw IOException("malformed __NEXT_DATA__")
        return JSONObject(html.substring(start, end))
    }

    /** Extracts `props.initialState` from a Next.js `__NEXT_DATA__` blob. */
    private fun nextDataState(html: String): JSONObject =
        nextDataRoot(html).getJSONObject("props").getJSONObject("initialState")

    /** Depth-first search for a nested object satisfying [match]. */
    private fun findObject(node: Any?, match: (JSONObject) -> Boolean): JSONObject? {
        when (node) {
            is JSONObject -> {
                if (match(node)) return node
                for (key in node.keys()) findObject(node.opt(key), match)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                findObject(node.opt(i), match)?.let { return it }
            }
        }
        return null
    }

    /** Depth-first search for the first array of book records. */
    private fun findBookArray(node: Any?): JSONArray? {
        when (node) {
            is JSONArray -> {
                val first = node.optJSONObject(0)
                if (first != null &&
                    (first.has("bookId") || first.has("id")) &&
                    (first.has("bookName") || first.has("name"))
                ) {
                    return node
                }
                for (i in 0 until node.length()) findBookArray(node.opt(i))?.let { return it }
            }
            is JSONObject -> for (key in node.keys()) {
                findBookArray(node.opt(key))?.let { return it }
            }
        }
        return null
    }

    /** The `books` entity map (id -> book record) from a page's `__NEXT_DATA__`. */
    private fun nextDataBooks(html: String): JSONObject? = runCatching {
        val state = nextDataState(html)
        state.optJSONObject("entities")?.optJSONObject("books")
            ?: state.optJSONObject("books")
    }.getOrNull()

    /** The single book record for [id] from a page's `__NEXT_DATA__`. */
    private fun nextDataBook(html: String, id: String): JSONObject? =
        nextDataBooks(html)?.optJSONObject(id)

    /** The chapter record for the chapter in [url] from a page's `__NEXT_DATA__`. */
    private fun nextDataChapter(html: String, url: String): JSONObject? = runCatching {
        val chapters = nextDataState(html)
            .optJSONObject("entities")?.optJSONObject("chapter") ?: return null
        val cid = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        chapters.optJSONObject(cid)
            ?: chapters.keys().asSequence()
                .mapNotNull { chapters.optJSONObject(it) }
                .firstOrNull { it.optJSONArray("contents") != null }
    }.getOrNull()

    /** Webnovel prints "Ongoing · N Views" / "Completed · N Views" in the header. */
    private fun statusFromHtml(html: String): NovelStatus =
        Regex(""">\s*(Completed|Ongoing|Hiatus)\s*[·•]""").find(html)
            ?.groupValues?.get(1)?.toNovelStatus()
            ?: NovelStatus.UNKNOWN

    /** Nearest enclosing `<li>` ancestor, or null. */
    private fun Element.ancestorLi(): Element? =
        generateSequence(parent()) { it.parent() }.firstOrNull { it.tagName() == "li" }

    /** Pulls the numeric book id out of any `/book/<slug>_<id>` style URL. */
    private fun bookId(url: String): String? {
        val segment = url.substringBefore('?')
            .substringAfter("/book/", "")
            .substringBefore('/')
            .takeIf { it.isNotEmpty() } ?: return null
        return segment.substringAfterLast('_').filter { it.isDigit() }
            .takeIf { it.isNotEmpty() }
    }

    private fun String.toNovelStatus(): NovelStatus = when {
        contains("Completed", ignoreCase = true) -> NovelStatus.COMPLETED
        contains("Hiatus", ignoreCase = true) -> NovelStatus.HIATUS
        contains("Ongoing", ignoreCase = true) -> NovelStatus.ONGOING
        else -> NovelStatus.UNKNOWN
    }

    private fun cookieValue(rawCookies: String, name: String): String? =
        rawCookies.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')

    companion object {
        private const val LOCKED_MESSAGE =
            "Webnovel: this chapter is locked — its full content requires a " +
                "signed-in account that has unlocked it (Source settings → Account)."

        // Webnovel's mobile browse endpoints.
        private const val RANKING_URL =
            "https://m.webnovel.com/ranking/novel/bi_annual/power_rank"

        // Ranking section codes (also reused by the advanced search's `sex`).
        private const val SEX_MALE = 1
        private const val SEX_FEMALE = 2

        // Preference: which lead the Popular ranking shows.
        private const val PREF_FEMALE_RANKING = "popular_female_lead"
        private const val RANKING_API =
            "https://m.webnovel.com/go/pcm/category/getRankList"
        private const val SEARCH_API =
            "https://m.webnovel.com/go/pcm/search/get-search-list"
        private const val TAG_LIST_API =
            "https://m.webnovel.com/go/pcm/search/get-tag-list"
        private const val LATEST_URL = "https://m.webnovel.com/search?keywords=latest"

        // ParamFilter code meaning "leave this query parameter out entirely".
        private const val OMIT = -1

        // Sign-out clears cookies on every host scope Webnovel may set them on.
        private val COOKIE_DOMAINS = listOf(
            "https://www.webnovel.com",
            "https://webnovel.com",
            "https://m.webnovel.com",
            "https://passport.webnovel.com",
        )

        // Domain attributes to expire each cookie against. Webnovel's auth
        // cookies are registrable-domain cookies (Domain=.webnovel.com); the
        // leading-dot and bare forms cover both how a browser may store them.
        private val COOKIE_PARENT_DOMAINS = listOf(
            ".webnovel.com",
            "webnovel.com",
        )

        private const val COOKIE_EXPIRY =
            "Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=/"
    }
}
