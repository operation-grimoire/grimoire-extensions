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
    versionCode = 3,
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

    @Volatile
    private var loggedIn: Boolean = false

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
            loggedIn = false
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
        ensureLoggedIn()

        val bookUrl = resolveUrl(novel.url)
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
            client.newCall(GET(dlUrl)).execute().use { resp ->
                val contentType = resp.header("Content-Type").orEmpty().lowercase()
                val disposition = resp.header("Content-Disposition").orEmpty()
                val looksLikeEpub = contentType.contains("epub") ||
                    contentType.contains("octet-stream") ||
                    contentType.contains("application/zip") ||
                    disposition.contains(".epub", ignoreCase = true)
                when {
                    resp.isSuccessful && looksLikeEpub && !contentType.startsWith("text/html") ->
                        return@withContext resp.body?.bytes()
                            ?: throw IOException("Z-Library: empty download response")
                    // Cloudflare interstitial / transient gate — back off and retry.
                    resp.code == 503 || resp.code == 403 ->
                        lastError = "blocked by Cloudflare (HTTP ${resp.code})"
                    else ->
                        lastError = "download was blocked — sign in via Source " +
                            "settings, or the daily download limit has been reached"
                }
            }
            if (attempt < MAX_RETRIES - 1) Thread.sleep(1500L * (attempt + 1))
        }
        throw IOException("Z-Library: $lastError.")
    }

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

    private fun ensureLoggedIn() {
        if (loggedIn || email.isEmpty() || password.isEmpty()) return
        synchronized(this) {
            if (loggedIn) return
            runCatching {
                val body = FormBody.Builder()
                    .add("email", email)
                    .add("password", password)
                    .add("action", "login")
                    .add("gg_json_mode", "1")
                    .build()
                val request = Request.Builder()
                    .url("$baseUrl/rpc.php")
                    .post(body)
                    .build()
                client.newCall(request).execute().use { it.body?.string() }
            }
            // Best-effort: even if the parse of the login response fails we mark
            // it attempted so we don't retry on every download; the cookie jar
            // shared with the WebView carries any issued session cookie.
            loggedIn = true
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
    }
}
