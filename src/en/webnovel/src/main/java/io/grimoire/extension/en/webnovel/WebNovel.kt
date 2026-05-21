package io.grimoire.extension.en.webnovel

import android.webkit.CookieManager
import io.grimoire.api.model.Chapter
import io.grimoire.api.model.Filter
import io.grimoire.api.model.Novel
import io.grimoire.api.model.NovelPage
import io.grimoire.api.model.NovelStatus
import io.grimoire.api.network.HttpSource
import io.grimoire.api.source.SourceInfo
import io.grimoire.api.source.WebViewLoginSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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
    versionCode = 13,
)
class WebNovel : HttpSource(), WebViewLoginSource {

    override val id = 8L
    override val name = "Webnovel"
    override val lang = "en"
    override val baseUrl = "https://www.webnovel.com"

    // Book urls already returned for the current listing, so paging never
    // repeats one (reset whenever page 1 is requested).
    private val popularSeen = HashSet<String>()
    private val latestSeen = HashSet<String>()
    private val searchSeen = HashSet<String>()

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
        val raw = cm.getCookie(baseUrl).orEmpty()
        raw.split(';')
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() }
            .forEach { name ->
                COOKIE_DOMAINS.forEach { domain ->
                    cm.setCookie(domain, "$name=; Max-Age=0; Path=/")
                }
            }
        cm.flush()
    }

    // --- Listings ------------------------------------------------------------

    // The ranking ignores ?pageIndex; its page number is a trailing path
    // segment (.../power_rank/2). Page 1 keeps the bare, known-good URL.
    override fun popularNovelsRequest(page: Int): Request =
        GET(if (page <= 1) RANKING_URL else "$RANKING_URL/$page")

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$LATEST_URL&pageIndex=$page")

    override fun searchNovelsRequest(query: String, page: Int, filters: List<Filter<*>>): Request {
        val keywords = URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$baseUrl/search?keywords=$keywords&pageIndex=$page")
    }

    override suspend fun popularNovelsParse(response: Response): List<Novel> {
        val html = response.bodyText()
        // Ranking pages emit the ordered list as a JSON-LD ItemList block.
        val books = rankedFromJsonLd(html).ifEmpty {
            val doc = Jsoup.parse(html, response.request.url.toString())
            booksFromNextData(html).ifEmpty { parseBookList(doc) }
        }
        return paginate(popularSeen, requestedPage(response), books)
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
        return runCatching { java.net.URLDecoder.decode(slug, "UTF-8") }.getOrDefault(slug)
            .split('-').filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
            .ifBlank { "Webnovel book" }
    }


    override suspend fun latestUpdatesParse(response: Response): List<Novel> =
        parseSearchListing(response, latestSeen)

    override suspend fun searchNovelsParse(response: Response): List<Novel> =
        parseSearchListing(response, searchSeen)

    private fun parseSearchListing(response: Response, seen: MutableSet<String>): List<Novel> {
        val html = response.bodyText()
        val books = parseBookList(Jsoup.parse(html, response.request.url.toString()))
            .ifEmpty { booksFromNextData(html) }
        return paginate(seen, requestedPage(response), books)
    }

    override fun getFilterList(): List<Filter<*>> = emptyList()

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
                ?.text()?.trim()?.takeIf { it.isNotEmpty() }
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
        val doc = response.asJsoup()
        val bookId = bookId(response.request.url.toString())
            ?: throw IOException("Webnovel: could not determine the book id")
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
        val contents = chapter?.optJSONArray("contents")
        if (contents != null && contents.length() > 0) {
            val pages = (0 until contents.length()).mapNotNull { i ->
                val fragment = contents.optJSONObject(i)?.optString("content").orEmpty()
                Jsoup.parse(fragment).text().trim().takeIf { it.isNotEmpty() }
            }
            if (pages.isNotEmpty()) {
                return pages.mapIndexed { i, text -> NovelPage(i, text) }
            }
        }

        // Fallback for any server-rendered layout.
        val scraped = Jsoup.parse(html, url)
            .select(".cha-words p, .cha-content p, .j_chapterWrapper p")
            .map { it.text().trim() }.filter { it.isNotEmpty() }
        if (scraped.isNotEmpty()) {
            return scraped.mapIndexed { i, text -> NovelPage(i, text) }
        }

        throw IOException(LOCKED_MESSAGE)
    }

    // --- Helpers -------------------------------------------------------------

    private fun Response.bodyText(): String = body?.string().orEmpty()

    private fun requestedPage(response: Response): Int {
        val url = response.request.url
        url.queryParameter("pageIndex")?.toIntOrNull()?.let { return it }
        // Ranking pages carry the page number as the last path segment.
        return url.pathSegments.lastOrNull()?.toIntOrNull() ?: 1
    }

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

    /** Extracts `props.initialState` from a Next.js `__NEXT_DATA__` blob. */
    private fun nextDataState(html: String): JSONObject {
        val marker = html.indexOf("id=\"__NEXT_DATA__\"")
        if (marker < 0) throw IOException("no __NEXT_DATA__")
        val start = html.indexOf('>', marker) + 1
        val end = html.indexOf("</script>", start)
        if (start <= 0 || end < 0) throw IOException("malformed __NEXT_DATA__")
        return JSONObject(html.substring(start, end))
            .getJSONObject("props")
            .getJSONObject("initialState")
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
        private const val LATEST_URL = "https://m.webnovel.com/search?keywords=latest"

        // Sign-out clears cookies on every host scope Webnovel may set them on.
        private val COOKIE_DOMAINS = listOf(
            "https://www.webnovel.com",
            "https://webnovel.com",
            "https://m.webnovel.com",
            "https://passport.webnovel.com",
        )
    }
}
