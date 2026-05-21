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
    versionCode = 5,
)
class WebNovel : HttpSource(), WebViewLoginSource {

    override val id = 8L
    override val name = "Webnovel"
    override val lang = "en"
    override val baseUrl = "https://www.webnovel.com"

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

    override fun popularNovelsRequest(page: Int): Request =
        GET("$RANKING_URL?$PAGE_MARKER=$page")

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$LATEST_URL&$PAGE_MARKER=$page")

    override fun searchNovelsRequest(query: String, page: Int, filters: List<Filter<*>>): Request {
        val keywords = URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$baseUrl/search?keywords=$keywords&pageIndex=$page")
    }

    override suspend fun popularNovelsParse(response: Response): List<Novel> {
        // The ranking/latest listings are a single server page; report page 2+
        // as empty so the host stops paginating.
        if (response.request.url.queryParameter(PAGE_MARKER).let { it != null && it != "1" }) {
            return emptyList()
        }
        val html = response.bodyText()
        // Listing cards render client-side; the embedded JSON is the reliable
        // source. Fall back to scraping in case a page is server-rendered.
        return booksFromNextData(html)
            .ifEmpty { parseBookList(Jsoup.parse(html, response.request.url.toString())) }
    }

    override suspend fun latestUpdatesParse(response: Response): List<Novel> =
        popularNovelsParse(response)

    override suspend fun searchNovelsParse(response: Response): List<Novel> {
        val html = response.bodyText()
        return parseBookList(Jsoup.parse(html, response.request.url.toString()))
            .ifEmpty { booksFromNextData(html) }
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
        val books = runCatching {
            val state = nextDataState(html)
            state.optJSONObject("entities")?.optJSONObject("books")
                ?: state.optJSONObject("books")
        }.getOrNull() ?: return emptyList()
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

    override suspend fun novelDetailsParse(response: Response): Novel {
        val doc = response.asJsoup()
        val pageUrl = response.request.url.toString()
        val id = bookId(pageUrl)
        fun meta(prop: String) = doc.selectFirst(
            "meta[property=og:$prop], meta[name=$prop], meta[name=og:$prop]",
        )?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: meta("title")?.substringBefore(" - ")?.trim()
            ?: "Webnovel book"
        val description = doc.selectFirst(
            ".j_synopsis, [class*=synopsis], .det-content p, .g_txt_over",
        )?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: meta("description")
        val author = doc.selectFirst(
            "a[href*=/profile/], .det-info .c_s a, address .c_s, [class*=author] a",
        )?.text()?.trim()?.removePrefix("Author:")?.trim()?.takeIf { it.isNotEmpty() }
        val genres = doc.select("a[href*=/tags/], a[href*=/category], .m-tags a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() && it.length < 30 }
            .distinct()
            .take(12)
        val status = doc.select(
            ".det-hd-detail, .det-info, ._mn small, [class*=_meta], [class*=status]",
        ).joinToString(" ") { it.text() }.toNovelStatus()

        return Novel(
            url = id?.let { "/book/$it" } ?: pageUrl,
            title = title,
            thumbnailUrl = id?.let { coverUrl(it) } ?: meta("image"),
            author = author,
            description = description,
            genres = genres,
            status = status,
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
        val doc = response.asJsoup()
        val paragraphs = doc.select(
            ".cha-words p, .cha-content p, .j_chapterWrapper p, " +
                "[class*=chapter_content] p, [class*=chapterContent] p, .dib.pr p",
        ).map { it.text().trim() }.filter { it.isNotEmpty() }

        if (paragraphs.isEmpty()) {
            throw IOException(
                "Webnovel: this chapter's content is locked. Sign in with an " +
                    "account that has unlocked it (Source settings → Account).",
            )
        }
        return paragraphs.mapIndexed { i, text -> NovelPage(i, text) }
    }

    // --- Helpers -------------------------------------------------------------

    private fun Response.bodyText(): String = body?.string().orEmpty()

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
        private const val PAGE_MARKER = "wnpage"

        // Webnovel's mobile browse endpoints.
        private const val RANKING_URL =
            "https://m.webnovel.com/ranking/novel/all_time/popular_rank"
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
