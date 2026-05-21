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
import java.io.IOException
import java.net.URLEncoder

/**
 * Webnovel.com (Qidian International) source.
 *
 * Targets the mobile site `m.webnovel.com`, which is a Next.js app: every page
 * server-renders a `<script id="__NEXT_DATA__">` JSON blob holding all the
 * structured data. Parsing that JSON is far more stable than scraping the DOM
 * and — crucially — exposes each chapter's `isAuth`/`isVip` flags, so VIP
 * chapters can be reported as [Chapter.locked]. Chapter text itself is still
 * read from the rendered chapter page.
 *
 * Webnovel gates later chapters behind a paid account, so this also implements
 * [WebViewLoginSource]: the host opens [loginUrl] in a WebView (covering the
 * social-login providers too) and the resulting session cookies are replayed
 * on this source's requests.
 */
@SourceInfo(
    id = 8L,
    name = "Webnovel",
    lang = "en",
    baseUrl = "https://m.webnovel.com",
    versionCode = 4,
)
class WebNovel : HttpSource(), WebViewLoginSource {

    override val id = 8L
    override val name = "Webnovel"
    override val lang = "en"
    override val baseUrl = "https://m.webnovel.com"

    // --- WebViewLoginSource --------------------------------------------------

    override val loginUrl =
        "https://passport.webnovel.com/login.html?appid=900&areaid=1" +
            "&returnurl=https%3A%2F%2Fwww.webnovel.com%2F"

    override val loginSuccessUrl = "https://www.webnovel.com"

    /**
     * Webnovel stores the signed-in user's id in the `uid` cookie; guests have
     * it absent or `0`.
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
        GET("$baseUrl/stories/novel?$PAGE_MARKER=$page")

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/stories/novel?$PAGE_MARKER=$page")

    override fun searchNovelsRequest(query: String, page: Int, filters: List<Filter<*>>): Request {
        val keywords = URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$baseUrl/search?keywords=$keywords&$PAGE_MARKER=$page")
    }

    override suspend fun popularNovelsParse(response: Response): List<Novel> =
        parseListing(response)

    override suspend fun latestUpdatesParse(response: Response): List<Novel> =
        parseListing(response)

    override suspend fun searchNovelsParse(response: Response): List<Novel> =
        parseListing(response)

    override fun getFilterList(): List<Filter<*>> = emptyList()

    /**
     * Webnovel's mobile listing/search pages are not server-paginated, so only
     * the first page carries results; later pages return empty so the host
     * stops paginating.
     */
    private fun parseListing(response: Response): List<Novel> {
        if (response.request.url.queryParameter(PAGE_MARKER).let { it != null && it != "1" }) {
            return emptyList()
        }
        val books = nextData(response.bodyText()).optJSONObject("books")
            ?: return emptyList()
        return books.keys().asSequence()
            .mapNotNull { key -> books.optJSONObject(key)?.toNovel(key) }
            .toList()
    }

    // --- Novel details -------------------------------------------------------

    // The catalog page's __NEXT_DATA__ carries the full book record (and the
    // chapter list), so details and chapters are both read from it.
    override fun novelDetailsRequest(novel: Novel): Request = chapterListRequest(novel)

    override suspend fun novelDetailsParse(response: Response): Novel {
        val html = response.bodyText()
        val id = bookId(response.request.url.toString())
            ?: throw IOException("Webnovel: invalid book URL")
        val book = nextData(html).optJSONObject("books")?.optJSONObject(id)
            ?: throw IOException("Webnovel: book details not found")
        val novel = book.toNovel(id)
            ?: throw IOException("Webnovel: book details not found")
        return novel.copy(status = statusFrom(html), initialized = true)
    }

    // --- Chapter list --------------------------------------------------------

    override fun chapterListRequest(novel: Novel): Request {
        val id = bookId(novel.url)
            ?: throw IOException("Webnovel: could not determine the book id")
        return GET("$baseUrl/book/$id/catalog")
    }

    override suspend fun chapterListParse(response: Response): List<Chapter> {
        val id = bookId(response.request.url.toString())
            ?: throw IOException("Webnovel: could not determine the book id")
        val chapters = nextData(response.bodyText()).optJSONObject("chapter")
            ?: throw IOException("Webnovel: chapter list not found")
        return chapters.keys().asSequence()
            .mapNotNull { cid -> chapters.optJSONObject(cid)?.let { cid to it } }
            .sortedBy { (_, ch) -> ch.optInt("chapterIndex", 0) }
            .map { (cid, ch) ->
                Chapter(
                    url = "/book/$id/$cid",
                    name = ch.optString("chapterName").trim()
                        .ifEmpty { "Chapter ${ch.optInt("chapterIndex")}" },
                    chapterNumber = ch.optInt("chapterIndex", 0).toFloat(),
                    uploadDate = ch.optLong("publishTime", 0L),
                    // isAuth = 0 means this account cannot read the chapter yet.
                    locked = ch.optInt("isAuth", 1) == 0,
                )
            }
            .toList()
    }

    // --- Chapter content -----------------------------------------------------

    override suspend fun pageListParse(response: Response): List<NovelPage> {
        val doc = Jsoup.parse(response.bodyText(), response.request.url.toString())
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

    /**
     * Extracts the data store from a Next.js page's `__NEXT_DATA__` blob.
     * Webnovel keeps its entities (`books`, `chapter`, …) under
     * `props.initialState.entities`. Next.js escapes `<`/`>` inside that JSON,
     * so the first `</script>` after the tag is reliably its terminator.
     */
    private fun nextData(html: String): JSONObject {
        val marker = html.indexOf("id=\"__NEXT_DATA__\"")
        if (marker < 0) throw IOException("Webnovel: page data not found (blocked?)")
        val start = html.indexOf('>', marker) + 1
        val end = html.indexOf("</script>", start)
        if (start <= 0 || end < 0) throw IOException("Webnovel: page data not found")
        val initialState = JSONObject(html.substring(start, end))
            .getJSONObject("props")
            .getJSONObject("initialState")
        return initialState.optJSONObject("entities") ?: initialState
    }

    /** Builds a [Novel] from a `books` entry in the Next.js state. */
    private fun JSONObject.toNovel(fallbackId: String? = null): Novel? {
        val id = optString("bookId").takeIf { it.isNotEmpty() }
            ?: fallbackId
            ?: return null
        val title = optString("bookName").ifEmpty { optString("name") }.trim()
        if (title.isEmpty()) return null
        val tags = optJSONArray("tagInfos")
        val genres = if (tags != null) {
            (0 until tags.length()).mapNotNull {
                tags.optJSONObject(it)?.optString("tagName")?.trim()?.takeIf(String::isNotEmpty)
            }
        } else {
            emptyList()
        }
        return Novel(
            url = "/book/$id",
            title = title,
            thumbnailUrl = "https://book-pic.webnovel.com/bookcover/$id",
            author = optString("authorName").trim().takeIf { it.isNotEmpty() },
            description = optString("description").trim().takeIf { it.isNotEmpty() },
            genres = genres,
        )
    }

    /** The mobile book page prints "Ongoing · N Views" / "Completed · N Views". */
    private fun statusFrom(html: String): NovelStatus = when {
        Regex(""">\s*Completed\s*&middot;|>\s*Completed\s*·""").containsMatchIn(html) ->
            NovelStatus.COMPLETED
        Regex(""">\s*Ongoing\s*&middot;|>\s*Ongoing\s*·""").containsMatchIn(html) ->
            NovelStatus.ONGOING
        else -> NovelStatus.UNKNOWN
    }

    /** Pulls the numeric book id out of any `/book/<id>` style URL. */
    private fun bookId(url: String): String? {
        val segment = url.substringBefore('?')
            .substringAfter("/book/", "")
            .substringBefore('/')
            .takeIf { it.isNotEmpty() } ?: return null
        return segment.substringAfterLast('_').filter { it.isDigit() }
            .takeIf { it.isNotEmpty() }
    }

    private fun cookieValue(rawCookies: String, name: String): String? =
        rawCookies.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')

    companion object {
        private const val PAGE_MARKER = "wnpage"

        // Sign-out clears cookies on every host scope Webnovel may set them on.
        private val COOKIE_DOMAINS = listOf(
            "https://www.webnovel.com",
            "https://webnovel.com",
            "https://m.webnovel.com",
            "https://passport.webnovel.com",
        )
    }
}
