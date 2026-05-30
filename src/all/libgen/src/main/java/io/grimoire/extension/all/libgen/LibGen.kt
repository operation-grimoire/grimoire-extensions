package io.grimoire.extension.all.libgen

import io.grimoire.api.model.Chapter
import io.grimoire.api.model.Filter
import io.grimoire.api.model.Novel
import io.grimoire.api.model.NovelPage
import io.grimoire.api.model.NovelStatus
import io.grimoire.api.network.HttpSource
import io.grimoire.api.source.ConfigValidationResult
import io.grimoire.api.source.ConfigurableSource
import io.grimoire.api.source.EpubSource
import io.grimoire.api.source.MultiLanguageSource
import io.grimoire.api.source.SourceInfo
import io.grimoire.api.source.SourcePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

/**
 * Library Genesis (LibGen) book source. Only EPUB results are surfaced (the
 * only format the host app reads here): every search is constrained to
 * `extensions[]=EPUB` and non-EPUB results are skipped defensively.
 *
 * Everything runs against a single host (default `libgen.com.im`): the homepage
 * renders a "Most Popular" shelf, `GET /search.php` serves results as
 * `.book-item` cards, `GET /book.php?md5=…` is the detail page and
 * `GET /download.php?md5=…` 302-redirects through the LibGen download gateway
 * to a CDN that serves the EPUB bytes directly. Books are whole-book EPUB
 * files, so this is an [EpubSource]: the host downloads the bytes via [getEpub]
 * and parses them locally; [chapterListParse] / [pageListParse] are
 * intentionally empty.
 *
 * Downloads are anonymous — no account, no quota gate observed. The site does
 * inject a browser-only ad/redirect script, but it never fires for a plain
 * (no-JS) OkHttp request, so server responses are the real content.
 *
 * Note: LibGen rotates mirror domains, so the host is a user-editable
 * preference, and shelf/search/detail parsing and the download chain are each
 * isolated below so they can be adjusted against live responses without
 * touching the rest of the source.
 */
@SourceInfo(
    id = 9L,
    name = "Library Genesis",
    lang = "all",
    baseUrl = "https://libgen.com.im",
    versionCode = 1,
)
class LibGen : HttpSource(), ConfigurableSource, EpubSource, MultiLanguageSource {

    override val id = 9L
    override val name = "Library Genesis"
    override val lang = "all"

    // LibGen rotates mirrors, so the host is user-editable.
    private val defaultMirror = "https://libgen.com.im"

    @Volatile
    private var mirror: String = defaultMirror

    // Lowercased enabled content languages. Empty = no filter. LibGen filters
    // server-side via `languages[]`, so the set is forwarded into the query.
    @Volatile
    private var enabledLanguages: Set<String> = emptySet()

    override val baseUrl: String
        get() = mirror

    // --- ConfigurableSource ---------------------------------------------------

    override fun getPreferences(): List<SourcePreference> = listOf(
        SourcePreference.EditText(
            key = PREF_BASE_URL,
            title = "Mirror domain",
            summary = "LibGen host used for browsing, search and downloads " +
                "(default libgen.com.im). Change it if the default mirror is " +
                "blocked.",
            default = defaultMirror,
        ),
    )

    override fun setPreferences(values: Map<String, String>) {
        mirror = values[PREF_BASE_URL]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { normalizeBaseUrl(it) }
            ?: defaultMirror
    }

    override suspend fun validateConfiguration(): ConfigValidationResult =
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(GET(baseUrl)).execute().use { it.isSuccessful }
            }.getOrDefault(false).let { ok ->
                if (ok) {
                    ConfigValidationResult(true, "Reached LibGen at $baseUrl.")
                } else {
                    ConfigValidationResult(
                        false,
                        "Couldn't reach $baseUrl — check the mirror domain.",
                    )
                }
            }
        }

    // --- MultiLanguageSource --------------------------------------------------

    override fun availableLanguages(): List<String> = listOf(
        "English", "Spanish", "Portuguese", "French", "German", "Italian",
        "Dutch", "Russian", "Ukrainian", "Polish", "Czech", "Romanian",
        "Greek", "Turkish", "Arabic", "Hebrew", "Hindi", "Bengali",
        "Chinese", "Japanese", "Korean", "Vietnamese", "Thai", "Indonesian",
        "Swedish", "Norwegian", "Danish", "Finnish", "Hungarian", "Persian",
    )

    override fun setEnabledLanguages(languages: Set<String>) {
        enabledLanguages = languages.map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }.toSet()
    }

    // --- Listings (always EPUB-constrained) -----------------------------------

    // LibGen has no anonymous "browse all" endpoint, so popular/latest both read
    // the homepage's server-rendered "Most Popular" shelf (single page, no
    // pagination). The page marker lets the parser return an empty second page
    // so the host stops paginating instead of looping the same shelf forever.
    override fun popularNovelsRequest(page: Int): Request =
        GET("$baseUrl/?$PAGE_MARKER=$page")

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/?$PAGE_MARKER=$page")

    override fun searchNovelsRequest(query: String, page: Int, filters: List<Filter<*>>): Request {
        val url = baseUrl.toHttpUrlOrNull()!!.newBuilder()
            .addPathSegment("search.php")
            .addQueryParameter("mode", "general")
            .addQueryParameter("q", query.trim())
            .addQueryParameter("extensions[]", "EPUB")
            .apply {
                enabledLanguages.forEach { addQueryParameter("languages[]", it) }
                if (page > 1) addQueryParameter("page", page.toString())
            }
            .build()
        return GET(url.toString())
    }

    override suspend fun popularNovelsParse(response: Response): List<Novel> =
        parseShelf(response)

    override suspend fun latestUpdatesParse(response: Response): List<Novel> =
        parseShelf(response)

    // Each search hit is a `.book-item`: the title/url come from
    // `.book-title a`, author/cover/language from the surrounding card.
    override suspend fun searchNovelsParse(response: Response): List<Novel> {
        val doc = response.asJsoup()
        val pageUrl = response.request.url.toString()
        return doc.select("div.book-item").mapNotNull { item ->
            val titleLink = item.selectFirst(".book-title a, a.book-thumb")
                ?: return@mapNotNull null
            val href = titleLink.attr("href").trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val title = item.selectFirst(".book-title a")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val language = item.select(".book-details span")
                .firstOrNull { it.text().contains("Language", ignoreCase = true) }
                ?.selectFirst("strong")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            Novel(
                url = href,
                title = title,
                author = item.selectFirst(".book-author")?.text()?.trim()
                    ?.removePrefix("by ")?.trim()?.takeIf { it.isNotEmpty() },
                thumbnailUrl = item.selectFirst("img")?.imageUrl(pageUrl),
                language = language?.replaceFirstChar { it.uppercase() },
                status = NovelStatus.COMPLETED,
            )
        }
    }

    override fun getFilterList(): List<Filter<*>> = emptyList()

    private fun parseShelf(response: Response): List<Novel> {
        val pageParam = response.request.url.queryParameter(PAGE_MARKER)
        if (pageParam != null && pageParam != "1") return emptyList()
        val doc = response.asJsoup()
        val pageUrl = response.request.url.toString()
        // Each shelf entry is `<a class="book-card" href="book.php?md5=…">` with
        // the cover in `img.book-cover-img[data-src]` and title/author in
        // `.placeholder-title` / `.placeholder-author`. Fall back to the img alt
        // for the title. De-dup by URL in case the shelf repeats a book.
        return doc.select("a.book-card[href]").mapNotNull { link ->
            // Keep the full href (the ?md5=… query identifies the book).
            val href = link.attr("href").trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val title = link.selectFirst(".placeholder-title")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: link.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            Novel(
                url = href,
                title = title,
                author = link.selectFirst(".placeholder-author")?.text()?.trim()
                    ?.takeIf { it.isNotEmpty() },
                thumbnailUrl = link.selectFirst("img")?.imageUrl(pageUrl),
                status = NovelStatus.COMPLETED,
            )
        }.distinctBy { it.url }
    }

    // --- Novel details --------------------------------------------------------

    override suspend fun novelDetailsParse(response: Response): Novel {
        val pageUrl = response.request.url.toString()
        val doc = response.asJsoup()
        val title = doc.selectFirst("h1.book-title")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: doc.title().substringBefore(" - LibGen").trim()
        // Detail metadata is a key/value table (Publisher / Year / Language /
        // ISBN / MD5). LibGen exposes no synopsis, so build a short description
        // from publisher + year rather than leaving it blank.
        val meta = doc.select("table tr").mapNotNull { row ->
            val key = row.selectFirst("th")?.text()?.trim()?.lowercase()
            val value = row.selectFirst("td")?.text()?.trim()
            if (key != null && !value.isNullOrEmpty()) key to value else null
        }.toMap()
        val description = listOfNotNull(
            meta["publisher"]?.let { "Publisher: $it" },
            meta["year"]?.let { "Year: $it" },
        ).joinToString("\n").takeIf { it.isNotEmpty() }
        return Novel(
            url = pageUrl,
            title = title,
            author = doc.selectFirst(".book-author")?.text()?.trim()
                ?.removePrefix("by ")?.trim()?.takeIf { it.isNotEmpty() },
            thumbnailUrl = doc.selectFirst("img[src*=cover.php], .book-cover img")
                ?.imageUrl(pageUrl),
            description = description,
            language = meta["language"]?.replaceFirstChar { it.uppercase() },
            status = NovelStatus.COMPLETED,
            initialized = true,
        )
    }

    // --- Not used: content is delivered as a whole-book EPUB ------------------

    override suspend fun chapterListParse(response: Response): List<Chapter> = emptyList()

    override suspend fun pageListParse(response: Response): List<NovelPage> = emptyList()

    // --- EpubSource -----------------------------------------------------------

    override suspend fun getEpub(novel: Novel): ByteArray = withContext(Dispatchers.IO) {
        // The book URL is `book.php?md5=<hash>`; the download gateway keys off
        // the same md5 and 302/307-redirects to a CDN that serves the bytes.
        val md5 = novel.url.toHttpUrlOrNull()?.queryParameter("md5")
            ?: Regex("md5=([0-9a-fA-F]+)").find(novel.url)?.groupValues?.get(1)
            ?: throw IOException("LibGen: couldn't find the book id in its URL.")
        val dlUrl = "$baseUrl/download.php?md5=$md5"
        var lastError = "unknown error"
        repeat(MAX_RETRIES) { attempt ->
            val result = runCatching {
                // OkHttp follows the 302 → 307 redirect chain to the CDN.
                client.newCall(GET(dlUrl)).execute().use { resp ->
                    val ct = resp.header("Content-Type").orEmpty().lowercase()
                    val cd = resp.header("Content-Disposition").orEmpty()
                    val isFile = !ct.startsWith("text/html") && (
                        ct.contains("epub") || ct.contains("octet-stream") ||
                            ct.contains("zip") || cd.contains("attachment", ignoreCase = true) ||
                            cd.contains(".epub", ignoreCase = true)
                        )
                    if (resp.isSuccessful && isFile) {
                        resp.body?.bytes()?.takeIf { it.isNotEmpty() }
                    } else {
                        lastError = if (!resp.isSuccessful) {
                            "download failed (HTTP ${resp.code})"
                        } else {
                            "the server returned a web page, not a file"
                        }
                        null
                    }
                }
            }.getOrElse {
                lastError = it.message ?: "network error"
                null
            }
            if (result != null) return@withContext result
            if (attempt < MAX_RETRIES - 1) Thread.sleep(1500L * (attempt + 1))
        }
        throw IOException("LibGen: $lastError.")
    }

    // --- Helpers --------------------------------------------------------------

    // Resolve a (possibly lazy/relative) image URL against [base]. LibGen covers
    // are root-relative `cover.php?md5=…` paths on the same host.
    private fun Element.imageUrl(base: String = baseUrl): String? {
        val raw = listOf("data-src", "data-original", "src")
            .map { attr(it).trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        return when {
            raw.startsWith("http") -> raw
            raw.startsWith("//") -> "https:$raw"
            else -> resolveAgainst(base, raw)
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
        private const val PREF_BASE_URL = "base_url"
        private const val PAGE_MARKER = "lgpage"
        private const val MAX_RETRIES = 3
    }
}
