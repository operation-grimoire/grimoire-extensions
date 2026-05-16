package io.grimoire.extension.en.zlibrary

import io.grimoire.api.model.Chapter
import io.grimoire.api.model.Filter
import io.grimoire.api.model.Novel
import io.grimoire.api.model.NovelPage
import io.grimoire.api.model.NovelStatus
import io.grimoire.api.network.HttpSource
import io.grimoire.api.source.ConfigurableSource
import io.grimoire.api.source.EpubSource
import io.grimoire.api.source.SourceInfo
import io.grimoire.api.source.SourcePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

/**
 * Z-Library book source. Only EPUB results are surfaced (the only format the
 * host app reads here): every search is constrained to `extensions[]=EPUB` and
 * non-EPUB results are skipped defensively.
 *
 * Z-Library serves search results from a JSON endpoint (`POST /api/search`) and
 * renders its "Most Popular" shelf as plain HTML on the landing page — the old
 * `z-bookcard` scraping never matched, which is why search/popular/latest were
 * empty. Books are whole-book EPUB files, so this is an [EpubSource]: the host
 * downloads the bytes via [getEpub] and parses them locally; [chapterListParse]
 * / [pageListParse] are intentionally empty.
 *
 * Downloads work anonymously within Z-Library's daily quota; optional account
 * credentials ([ConfigurableSource]) raise/remove that limit. Cloudflare is
 * handled transparently by the default OkHttp client.
 *
 * Note: Z-Library rotates mirror domains (the landing domain proxies to a
 * per-user mirror that book/download links point at) and tweaks its markup, so
 * search/popular parsing, download-link resolution and login are each isolated
 * below so they can be adjusted against live responses without touching the
 * rest of the source.
 */
@SourceInfo(
    id = 6L,
    name = "Z-Library",
    lang = "en",
    baseUrl = "https://z-library.bz",
    versionCode = 5,
)
class ZLibrary : HttpSource(), ConfigurableSource, EpubSource {

    override val id = 6L
    override val name = "Z-Library"
    override val lang = "en"

    private val defaultBaseUrl = "https://z-library.bz"

    @Volatile
    private var mirror: String = defaultBaseUrl

    @Volatile
    private var email: String = ""

    @Volatile
    private var password: String = ""

    // Z-Library uses a single-login that issues per-host session cookies, and
    // the download host (the per-user mirror in a book's URL) differs from the
    // landing domain, so login state is tracked per host.
    private val loggedInHosts = java.util.Collections.synchronizedSet(HashSet<String>())

    override val baseUrl: String
        get() = mirror

    // --- ConfigurableSource ---------------------------------------------------

    override fun getPreferences(): List<SourcePreference> = listOf(
        SourcePreference.EditText(
            key = PREF_EMAIL,
            title = "Email",
            summary = "Z-Library account email (optional — raises the daily download limit)",
        ),
        SourcePreference.EditText(
            key = PREF_PASSWORD,
            title = "Password",
            summary = "Z-Library account password",
            isPassword = true,
        ),
        SourcePreference.EditText(
            key = PREF_BASE_URL,
            title = "Mirror domain",
            summary = "Change if the default domain is blocked",
            default = defaultBaseUrl,
        ),
    )

    override fun setPreferences(values: Map<String, String>) {
        val newEmail = values[PREF_EMAIL].orEmpty().trim()
        val newPassword = values[PREF_PASSWORD].orEmpty()
        if (newEmail != email || newPassword != password) {
            loggedInHosts.clear()
        }
        email = newEmail
        password = newPassword
        mirror = values[PREF_BASE_URL]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { normalizeBaseUrl(it) }
            ?: defaultBaseUrl
    }

    // --- Listings (always EPUB-constrained) -----------------------------------

    // Z-Library has no anonymous "browse all" endpoint and the search API
    // rejects an empty query, so popular/latest both read the landing page's
    // server-rendered "Most Popular" shelf (single page, no pagination). The
    // page marker lets the parser return an empty second page so the host stops
    // paginating instead of looping the same shelf forever.
    override fun popularNovelsRequest(page: Int): Request =
        GET("$baseUrl/?$PAGE_MARKER=$page")

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/?$PAGE_MARKER=$page")

    override fun searchNovelsRequest(query: String, page: Int, filters: List<Filter<*>>): Request {
        val body = FormBody.Builder()
            .add("q", query.trim())
            .add("page", page.toString())
            .add("limit", "30")
            .add("order", "")
            .add("extensions[]", "EPUB")
            .build()
        return Request.Builder().url("$baseUrl/api/search").post(body).build()
    }

    override suspend fun popularNovelsParse(response: Response): List<Novel> =
        parseShelf(response)

    override suspend fun latestUpdatesParse(response: Response): List<Novel> =
        parseShelf(response)

    override suspend fun searchNovelsParse(response: Response): List<Novel> {
        val json = JSONObject(response.body?.string().orEmpty())
        if (json.optInt("success", 0) != 1) return emptyList()
        val books = json.optJSONArray("books") ?: return emptyList()
        return (0 until books.length()).mapNotNull { i ->
            val b = books.optJSONObject(i) ?: return@mapNotNull null
            if (!b.optString("extension").equals("epub", ignoreCase = true)) return@mapNotNull null
            val href = b.optString("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = b.optString("title").trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            Novel(
                url = href,
                title = title,
                author = b.optString("author").trim().takeIf { it.isNotEmpty() },
                description = b.optString("description").trim()
                    .let { Jsoup.parse(it).text().trim() }
                    .takeIf { it.isNotEmpty() },
                thumbnailUrl = b.optString("cover").trim().takeIf { it.isNotEmpty() },
                status = NovelStatus.COMPLETED,
            )
        }
    }

    override fun getFilterList(): List<Filter<*>> = emptyList()

    private fun parseShelf(response: Response): List<Novel> {
        val pageParam = response.request.url.queryParameter(PAGE_MARKER)
        if (pageParam != null && pageParam != "1") return emptyList()
        val doc = response.asJsoup()
        return doc.select("z-cover").mapNotNull { cover ->
            val link = generateSequence(cover.parent()) { it.parent() }
                .firstOrNull { it.tagName() == "a" && it.hasAttr("href") }
            val href = link?.attr("href")?.trim()?.substringBefore('?')?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val title = cover.attr("title").trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            Novel(
                url = href,
                title = title,
                author = cover.attr("author").trim().takeIf { it.isNotEmpty() },
                thumbnailUrl = cover.selectFirst("img")?.imageUrl(),
                status = NovelStatus.COMPLETED,
            )
        }
    }

    // --- Novel details --------------------------------------------------------

    override suspend fun novelDetailsParse(response: Response): Novel {
        val doc = response.asJsoup()
        fun meta(prop: String) =
            doc.selectFirst("meta[property=og:$prop], meta[name=$prop]")
                ?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
        val title = meta("title")
            ?: doc.selectFirst("h1[itemprop=name], h1")?.text()?.trim()
            ?: ""
        val description = doc.selectFirst("#bookDescriptionBox, [itemprop=description]")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: meta("description")
        val author = doc.selectFirst("a.color1[title], [itemprop=author], .book-property__author a")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val cover = doc.selectFirst(
            ".details-book-cover-container img, z-cover img, .z-book-cover img, img.cover",
        )?.imageUrl() ?: meta("image")
        val genres = doc.select(".property_categories a, a[href*=/category/]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return Novel(
            url = response.request.url.toString(),
            title = title,
            thumbnailUrl = cover,
            author = author,
            description = description,
            genres = genres,
            status = NovelStatus.COMPLETED,
            initialized = true,
        )
    }

    // --- Not used: content is delivered as a whole-book EPUB ------------------

    override suspend fun chapterListParse(response: Response): List<Chapter> = emptyList()

    override suspend fun pageListParse(response: Response): List<NovelPage> = emptyList()

    // --- EpubSource -----------------------------------------------------------

    override suspend fun getEpub(novel: Novel): ByteArray = withContext(Dispatchers.IO) {
        val bookUrl = resolveUrl(novel.url)
        val host = bookUrl.toHttpUrlOrNull()?.host
            ?: throw IOException("Z-Library: invalid book URL")
        // Authenticate on the download host before fetching the (logged-in)
        // book page so the page exposes the real download link and the cookie
        // jar carries the session to the /dl request.
        ensureLoggedIn(host)

        // The book lives on Z-Library's per-user mirror, which is Cloudflare
        // gated. The default client solves the challenge transparently, but
        // clearance is cookie-based and can need a follow-up request to land,
        // so retry a few times before giving up.
        val bookHtml = fetchWithRetry(bookUrl)
            ?: throw IOException(
                "Z-Library: couldn't load the book page — Cloudflare protection " +
                    "on the mirror could not be cleared. Try again in a moment.",
            )
        val downloadHref = resolveDownloadHref(Jsoup.parse(bookHtml, bookUrl))
            ?: throw IOException(
                "Z-Library: no EPUB download link found. Sign in via Source " +
                    "settings, or the daily download limit may have been reached.",
            )

        // Download links are relative to the book's own mirror host (the
        // per-user domain in novel.url), not the landing domain.
        val dlUrl = resolveAgainst(bookUrl, downloadHref)
        var lastError = "unknown error"
        repeat(MAX_RETRIES) { attempt ->
            when (val r = attemptDownload(dlUrl, bookUrl)) {
                is DlResult.File -> return@withContext r.bytes
                is DlResult.Retry -> lastError = r.reason
                is DlResult.Fatal -> throw IOException("Z-Library: ${r.reason}.")
            }
            if (attempt < MAX_RETRIES - 1) Thread.sleep(1500L * (attempt + 1))
        }
        throw IOException("Z-Library: $lastError.")
    }

    private sealed interface DlResult {
        class File(val bytes: ByteArray) : DlResult
        class Retry(val reason: String) : DlResult
        class Fatal(val reason: String) : DlResult
    }

    /**
     * Follows the download chain from [startUrl]. Z-Library's download button
     * can point at an interstitial that meta-refreshes or links to the real
     * file, so HTML responses are followed (up to [MAX_HOPS]) before being
     * classified. The page `<title>` is included in failure reasons so an
     * unexpected page is diagnosable instead of an opaque "blocked".
     */
    private fun attemptDownload(startUrl: String, referer: String): DlResult {
        var url = startUrl
        var ref = referer
        repeat(MAX_HOPS) {
            val request = Request.Builder().url(url).header("Referer", ref).build()
            client.newCall(request).execute().use { resp ->
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                val cd = resp.header("Content-Disposition").orEmpty()
                val isHtml = ct.startsWith("text/html") || ct.isEmpty() && resp.body == null
                val looksLikeFile = !ct.startsWith("text/html") && (
                    ct.contains("epub") || ct.contains("octet-stream") ||
                        ct.contains("zip") || ct.contains("force-download") ||
                        ct.contains("application/download") ||
                        cd.contains("attachment", ignoreCase = true) ||
                        cd.contains(".epub", ignoreCase = true)
                    )
                if (resp.isSuccessful && looksLikeFile) {
                    val bytes = resp.body?.bytes()
                    return if (bytes != null && bytes.isNotEmpty()) {
                        DlResult.File(bytes)
                    } else {
                        DlResult.Retry("empty download response")
                    }
                }
                if (resp.code == 503 || resp.code == 403) {
                    return DlResult.Retry("blocked by Cloudflare (HTTP ${resp.code})")
                }
                if (!isHtml) {
                    return DlResult.Retry("download failed (HTTP ${resp.code})")
                }
                val html = resp.peekBody(96 * 1024).string()
                val lower = html.lowercase()
                val next = nextDownloadLink(html, resp.request.url.toString())
                if (next != null && next != url) {
                    ref = url
                    url = next
                    return@use // follow the chain
                }
                val title = Jsoup.parse(html).title().trim()
                    .takeIf { it.isNotEmpty() }?.let { " (page: \"$it\")" } ?: ""
                return when {
                    QUOTA_MARKERS.any { lower.contains(it) } -> DlResult.Fatal(
                        "the daily download limit has been reached for this account",
                    )
                    email.isEmpty() && LOGIN_MARKERS.any { lower.contains(it) } -> DlResult.Fatal(
                        "this book requires a signed-in account — add your " +
                            "Z-Library credentials in Source settings",
                    )
                    LOGIN_MARKERS.any { lower.contains(it) } -> DlResult.Fatal(
                        "the account session was not accepted — re-check the " +
                            "email/password in Source settings$title",
                    )
                    else -> DlResult.Retry(
                        "the server returned a web page, not a file$title",
                    )
                }
            }
        }
        return DlResult.Retry("too many download redirects")
    }

    /** Extract a meta-refresh / JS / anchor target that leads to the file. */
    private fun nextDownloadLink(html: String, baseUrl: String): String? {
        val doc = Jsoup.parse(html, baseUrl)
        doc.selectFirst("meta[http-equiv=refresh]")?.attr("content")
            ?.let { Regex("url=([^;]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            ?.trim()?.trim('\'', '"')?.takeIf { it.isNotEmpty() }
            ?.let { return absolute(baseUrl, it) }
        Regex("""(?:window\.location(?:\.href)?|location\.href)\s*=\s*['"]([^'"]+)['"]""")
            .find(html)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
            ?.let { return absolute(baseUrl, it) }
        doc.selectFirst(
            "a.addDownloadedBook, a.dlButton, a[href*=/dl/], a[href*=/d/], " +
                "a[href$=.epub], a.btn[href*=download]",
        )?.attr("href")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return absolute(baseUrl, it) }
        return null
    }

    private fun absolute(base: String, href: String): String =
        if (href.startsWith("http")) href else resolveAgainst(base, href)

    private fun fetchWithRetry(url: String): String? {
        repeat(MAX_RETRIES) { attempt ->
            runCatching {
                client.newCall(GET(url)).execute().use { resp ->
                    if (resp.isSuccessful) return resp.body?.string()
                }
            }
            if (attempt < MAX_RETRIES - 1) Thread.sleep(1500L * (attempt + 1))
        }
        return null
    }

    private fun resolveDownloadHref(doc: Document): String? =
        doc.selectFirst(
            "a.addDownloadedBook, a.dlButton, a.btn-default[href*=/dl/], " +
                "a.btn-primary[href*=/dl/], a[href*=/dl/]",
        )?.attr("href")?.trim()?.takeIf { it.isNotEmpty() }

    // --- Optional account login ----------------------------------------------

    /**
     * Logs the account in on [host] (the host that will serve the download).
     * Z-Library's single-login issues `remix_userid` / `remix_userkey` cookies
     * scoped to the host they were requested on, so logging in on the landing
     * domain does not authorise downloads on a per-user mirror. No-op when no
     * credentials are configured (anonymous, quota-limited). Throws only on an
     * explicit credential rejection so a bad password is reported clearly
     * rather than surfacing later as a generic "download blocked".
     */
    private fun ensureLoggedIn(host: String) {
        if (email.isEmpty() || password.isEmpty()) return
        if (loggedInHosts.contains(host)) return
        synchronized(loggedInHosts) {
            if (loggedInHosts.contains(host)) return
            val body = FormBody.Builder()
                .add("isModal", "true")
                .add("email", email)
                .add("password", password)
                .add("site_mode", "books")
                .add("action", "login")
                .add("isSingleLogin", "1")
                .add("redirectUrl", "")
                .add("gg_json_mode", "1")
                .build()
            val request = Request.Builder()
                .url("https://$host/rpc.php")
                .header("Referer", "https://$host/")
                .header("X-Requested-With", "XMLHttpRequest")
                .post(body)
                .build()
            val payload = runCatching {
                client.newCall(request).execute().use { it.body?.string().orEmpty() }
            }.getOrDefault("")
            // Explicit rejection -> surface immediately. Anything else is
            // treated as best-effort: the shared cookie jar carries whatever
            // session cookie was issued.
            if (payload.contains("\"validationError\":true", ignoreCase = true) ||
                payload.contains("Incorrect email", ignoreCase = true) ||
                payload.contains("wrong email or password", ignoreCase = true)
            ) {
                throw IOException(
                    "Z-Library: sign-in failed — check the email/password in " +
                        "Source settings.",
                )
            }
            loggedInHosts.add(host)
        }
    }

    // --- Helpers --------------------------------------------------------------

    private fun Element.imageUrl(): String? {
        val raw = listOf("data-src", "data-flickity-lazyload", "data-original", "src")
            .map { attr(it).trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        return when {
            raw.startsWith("http") -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "$baseUrl$raw"
            else -> raw
        }
    }

    private fun resolveAgainst(pageUrl: String, href: String): String {
        if (href.startsWith("http")) return href
        val base = pageUrl.toHttpUrlOrNull() ?: return resolveUrl(href)
        val origin = "${base.scheme}://${base.host}"
        return if (href.startsWith("/")) "$origin$href" else "$origin/$href"
    }

    private fun Response.asJsoup(): Document =
        Jsoup.parse(body!!.string(), request.url.toString())

    private fun normalizeBaseUrl(value: String): String {
        val trimmed = value.removeSuffix("/")
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    companion object {
        private const val PREF_EMAIL = "email"
        private const val PREF_PASSWORD = "password"
        private const val PREF_BASE_URL = "base_url"
        private const val PAGE_MARKER = "zlpage"
        private const val MAX_RETRIES = 3
        private const val MAX_HOPS = 4

        private val QUOTA_MARKERS = listOf(
            "daily limit", "download limit", "limit reached", "reached the limit",
            "downloads today", "limit of", "exceeded", "no more downloads",
        )
        private val LOGIN_MARKERS = listOf(
            "log in", "sign in", "/login", "you need to log in",
            "please log in", "authorization required", "registration",
        )
    }
}
