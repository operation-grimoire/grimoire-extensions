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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder

/**
 * Webnovel.com (Qidian International) source.
 *
 * Webnovel gates later chapters behind a paid account ("privilege"/coins), so
 * this source implements [WebViewLoginSource]: the host opens [loginUrl] in a
 * WebView — which also covers Webnovel's social sign-in providers — and the
 * session cookies it sets are replayed automatically on this source's OkHttp
 * calls. VIP chapters are reported with [Chapter.locked] = `true` so the host
 * can show them disabled instead of failing when they are opened.
 *
 * Webnovel's listing, book, catalog and chapter pages are all server-rendered,
 * so everything is scraped with Jsoup. The site occasionally reshuffles its
 * markup; parsing here is deliberately defensive (broad selectors with
 * fallbacks) so it degrades rather than crashes when a selector drifts.
 */
@SourceInfo(
    id = 8L,
    name = "Webnovel",
    lang = "en",
    baseUrl = "https://www.webnovel.com",
    versionCode = 1,
)
class WebNovel : HttpSource(), WebViewLoginSource {

    override val id = 8L
    override val name = "Webnovel"
    override val lang = "en"
    override val baseUrl = "https://www.webnovel.com"

    // --- WebViewLoginSource ---------------------------------------------------

    // Webnovel's passport login page; it hosts the email/password form as well
    // as the social-provider buttons (Google/Facebook), which redirect back to
    // www.webnovel.com once sign-in completes.
    override val loginUrl =
        "https://passport.webnovel.com/login.html?appid=900&areaid=1" +
            "&returnurl=https%3A%2F%2Fwww.webnovel.com%2F"

    override val loginSuccessUrl = "https://www.webnovel.com"

    /**
     * Webnovel stores the signed-in user's id in the `uid` cookie; guests have
     * it absent or `0`. Cookie inspection keeps this cheap (no network call) so
     * the host can re-check login state freely. If Webnovel renames the cookie
     * this is the single spot to adjust.
     */
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
        GET("$baseUrl/stories/novel?pageIndex=$page")

    // orderBy=5 is Webnovel's "most recently updated" ordering.
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/stories/novel?orderBy=5&pageIndex=$page")

    override fun searchNovelsRequest(query: String, page: Int, filters: List<Filter<*>>): Request {
        val keywords = URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$baseUrl/search?keywords=$keywords&pageIndex=$page")
    }

    override suspend fun popularNovelsParse(response: Response): List<Novel> =
        parseBookList(response.asJsoup())

    override suspend fun latestUpdatesParse(response: Response): List<Novel> =
        parseBookList(response.asJsoup())

    override suspend fun searchNovelsParse(response: Response): List<Novel> =
        parseBookList(response.asJsoup())

    override fun getFilterList(): List<Filter<*>> = emptyList()

    /**
     * Generic book-card extraction: every Webnovel listing/search page links to
     * books via `/book/<slug>_<id>` anchors, so we collect those, de-duplicate
     * by book id and pull a title + cover from the surrounding card. This is
     * resilient to the exact list container class changing.
     */
    private fun parseBookList(doc: Document): List<Novel> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Novel>()
        for (anchor in doc.select("a[href*=/book/]")) {
            val id = bookId(anchor.attr("href")) ?: continue
            if (!seen.add(id)) continue
            val card = anchor.ancestorLi() ?: anchor.parent() ?: anchor
            val title = card.selectFirst("h2, h3, h4")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
                ?: anchor.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
                ?: continue
            if (title.length < 2) continue
            out += Novel(
                url = "/book/$id",
                title = title,
                thumbnailUrl = card.selectFirst("img")?.let { imageUrl(it) },
            )
        }
        return out
    }

    // --- Novel details -------------------------------------------------------

    override suspend fun novelDetailsParse(response: Response): Novel {
        val doc = response.asJsoup()
        val pageUrl = response.request.url.toString()
        fun meta(prop: String) = doc.selectFirst(
            "meta[property=og:$prop], meta[name=$prop], meta[name=og:$prop]",
        )?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

        // og:title is "<Title> - <Genre> - Webnovel"; prefer the page heading.
        val title = doc.selectFirst("h1, .pt4.pb4.oh h1, .det-info h1")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: meta("title")?.substringBefore(" - ")?.trim()
            ?: "Webnovel book"
        val description = doc.selectFirst(".j_synopsis, .det-content p, .g_txt_over")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: meta("description")
        val author = doc.selectFirst(
            ".det-info .c_s a, address .c_s, a[href*=/profile/], .det-info .pt4 a",
        )?.text()?.trim()?.removePrefix("Author:")?.trim()?.takeIf { it.isNotEmpty() }
        val cover = doc.selectFirst(".det-hd-img img, .g_thumb img, .det-info img")
            ?.let { imageUrl(it) }
            ?: meta("image")
        val genres = doc.select(".det-hd-detail a[href*=/category], .m-tags a, .det-tag a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val status = doc.select(".det-hd-detail, .det-info, ._mn small, .det-hd-detail strong")
            .joinToString(" ") { it.text() }
            .toNovelStatus()

        return Novel(
            url = bookId(pageUrl)?.let { "/book/$it" } ?: pageUrl,
            title = title,
            thumbnailUrl = cover,
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
        var index = 0
        // Catalog rows are <li> entries containing a chapter <a>. Volume headers
        // and other <li>s without a /book/ link are skipped.
        return doc.select("li:has(a[href*=/book/])").mapNotNull { li ->
            val anchor = li.selectFirst("a[href*=/book/]") ?: return@mapNotNull null
            val href = anchor.attr("href").trim().substringBefore('?')
            if (href.isEmpty() || href.endsWith("/catalog")) return@mapNotNull null
            // Skip anchors that point back at the book itself rather than a chapter.
            if (bookId != null && href.trimEnd('/').endsWith("/book/$bookId")) {
                return@mapNotNull null
            }
            val name = (anchor.attr("title").trim().takeIf { it.isNotEmpty() }
                ?: anchor.text().trim())
                .takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Chapter(
                url = href,
                name = name,
                chapterNumber = parseChapterNumber(name, index++),
                locked = li.isLockedChapter(),
            )
        }
    }

    // --- Chapter content -----------------------------------------------------

    override suspend fun pageListParse(response: Response): List<NovelPage> {
        val doc = response.asJsoup()
        val paragraphs = doc.select(
            ".cha-words p, .cha-content p, .j_chapterWrapper p, " +
                "._content p, .chapter_content p, .dib.pr p",
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

    private fun Response.asJsoup(): Document =
        Jsoup.parse(body?.string().orEmpty(), request.url.toString())

    /** Nearest enclosing `<li>` ancestor, or null. */
    private fun Element.ancestorLi(): Element? =
        generateSequence(parent()) { it.parent() }.firstOrNull { it.tagName() == "li" }

    /**
     * Webnovel marks VIP/locked catalog rows with a lock glyph — an inline
     * `<svg><use href="#i-lock…">` or an element whose class mentions "lock".
     */
    private fun Element.isLockedChapter(): Boolean {
        val lockedSvg = select("svg use").any { use ->
            (use.attr("xlink:href") + " " + use.attr("href")).contains("lock", ignoreCase = true)
        }
        if (lockedSvg) return true
        return select("i, span, em, svg").any {
            it.className().contains("lock", ignoreCase = true)
        }
    }

    /** Pulls the numeric book id out of any `/book/<slug>_<id>` style URL. */
    private fun bookId(url: String): String? {
        val segment = url.substringBefore('?')
            .substringAfter("/book/", "")
            .substringBefore('/')
            .takeIf { it.isNotEmpty() } ?: return null
        return segment.substringAfterLast('_').filter { it.isDigit() }
            .takeIf { it.isNotEmpty() }
    }

    private fun imageUrl(img: Element): String? {
        val raw = listOf("data-original", "data-src", "src")
            .map { img.attr(it).trim() }
            .firstOrNull { it.isNotEmpty() } ?: return null
        return when {
            raw.startsWith("http") -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "$baseUrl$raw"
            else -> raw
        }
    }

    private fun parseChapterNumber(name: String, fallbackIndex: Int): Float {
        val match = CHAPTER_NUMBER.find(name)?.groupValues?.get(1)
        return match?.toFloatOrNull() ?: (fallbackIndex + 1).toFloat()
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
        private val CHAPTER_NUMBER =
            Regex("""(?:chapter|chap|ch|episode|ep)\.?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

        // Sign-out clears cookies on every host scope Webnovel may have set them on.
        private val COOKIE_DOMAINS = listOf(
            "https://www.webnovel.com",
            "https://webnovel.com",
            "https://passport.webnovel.com",
        )
    }
}
