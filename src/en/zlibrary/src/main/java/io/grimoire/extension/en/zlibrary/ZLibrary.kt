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
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder

/**
 * Z-Library book source. Only EPUB results are surfaced (the only format the
 * host app reads here): every listing request is constrained to
 * `extensions[]=EPUB` and non-EPUB cards are skipped defensively.
 *
 * Books are whole-book EPUB files, so this is an [EpubSource]: the host
 * downloads the bytes via [getEpub] and parses them locally; [chapterListParse]
 * / [pageListParse] are intentionally empty.
 *
 * Downloads work anonymously within Z-Library's daily quota; optional account
 * credentials ([ConfigurableSource]) raise/remove that limit. Cloudflare is
 * handled transparently by the default OkHttp client.
 *
 * Note: Z-Library rotates mirror domains and tweaks its markup; the card
 * parsing, download-link resolution and login are each isolated below so they
 * can be adjusted against live HTML without touching the rest of the source.
 */
@SourceInfo(
    id = 6L,
    name = "Z-Library",
    lang = "en",
    baseUrl = "https://z-library.bz",
    versionCode = 1,
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

    override fun popularNovelsRequest(page: Int): Request =
        GET(searchUrl(query = "", page = page, order = "popular"))

    override fun latestUpdatesRequest(page: Int): Request =
        GET(searchUrl(query = "", page = page, order = "date"))

    override fun searchNovelsRequest(query: String, page: Int, filters: List<Filter<*>>): Request =
        GET(searchUrl(query = query, page = page, order = null))

    override suspend fun popularNovelsParse(response: Response): List<Novel> =
        parseBookCards(response)

    override suspend fun latestUpdatesParse(response: Response): List<Novel> =
        parseBookCards(response)

    override suspend fun searchNovelsParse(response: Response): List<Novel> =
        parseBookCards(response)

    override fun getFilterList(): List<Filter<*>> = emptyList()

    private fun searchUrl(query: String, page: Int, order: String?): String {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val orderParam = order?.let { "&order=$it" }.orEmpty()
        return "$baseUrl/s/?q=$q&extensions%5B%5D=EPUB$orderParam&page=$page"
    }

    private fun parseBookCards(response: Response): List<Novel> {
        val doc = response.asJsoup()
        return doc.select("z-bookcard").mapNotNull { card ->
            val extension = card.attr("extension").trim().lowercase()
            if (extension.isNotEmpty() && extension != "epub") return@mapNotNull null
            val href = card.attr("href").trim().ifEmpty { return@mapNotNull null }
            val title = card.selectFirst("[slot=title]")?.text()?.trim()
                ?: card.attr("title").trim()
            if (title.isEmpty()) return@mapNotNull null
            Novel(
                url = href,
                title = title,
                author = card.selectFirst("[slot=author]")?.text()?.trim()
                    ?.takeIf { it.isNotEmpty() },
                thumbnailUrl = card.selectFirst("img")?.imageUrl(),
            )
        }
    }

    // --- Novel details --------------------------------------------------------

    override suspend fun novelDetailsParse(response: Response): Novel {
        val doc = response.asJsoup()
        val title = doc.selectFirst("h1[itemprop=name]")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: ""
        val author = doc.selectFirst("a.color1[title], [itemprop=author], .book-property__author a")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val description = doc.selectFirst("#bookDescriptionBox, [itemprop=description]")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val cover = doc.selectFirst(
            ".details-book-cover-container img, z-cover img, .z-book-cover img, img.cover",
        )?.imageUrl()
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

        val bookPage = client.newCall(GET(resolveUrl(novel.url))).execute()
        val downloadHref = bookPage.use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Z-Library: failed to open book page (HTTP ${resp.code})")
            }
            resolveDownloadHref(resp.asJsoup())
        } ?: throw IOException(
            "Z-Library: no EPUB download link found. Sign in via Source settings " +
                "or the daily download limit may have been reached.",
        )

        val dlResponse = client.newCall(GET(resolveUrl(downloadHref))).execute()
        dlResponse.use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Z-Library: download failed (HTTP ${resp.code})")
            }
            val contentType = resp.header("Content-Type").orEmpty().lowercase()
            val isEpub = contentType.contains("epub") ||
                contentType.contains("octet-stream") ||
                contentType.contains("application/zip") ||
                resp.header("Content-Disposition").orEmpty().contains(".epub", ignoreCase = true)
            if (!isEpub || contentType.startsWith("text/html")) {
                throw IOException(
                    "Z-Library: download was blocked. Sign in via Source settings " +
                        "or the daily download limit has been reached.",
                )
            }
            resp.body?.bytes() ?: throw IOException("Z-Library: empty download response")
        }
    }

    private fun resolveDownloadHref(doc: Document): String? =
        doc.selectFirst(
            "a.addDownloadedBook, a.dlButton, a.btn-primary[href*=/dl/], a[href*=/dl/]",
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
    }
}
