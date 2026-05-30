package io.grimoire.extension.all.libgen

import io.grimoire.api.model.Chapter
import io.grimoire.api.model.Filter
import io.grimoire.api.model.Novel
import io.grimoire.api.model.NovelPage
import io.grimoire.api.model.NovelStatus
import io.grimoire.api.network.HttpSource
import io.grimoire.api.network.defaultOkHttpClient
import io.grimoire.api.source.ConfigValidationResult
import io.grimoire.api.source.ConfigurableSource
import io.grimoire.api.source.EpubSource
import io.grimoire.api.source.MultiHostSource
import io.grimoire.api.source.MultiLanguageSource
import io.grimoire.api.source.SourceInfo
import io.grimoire.api.source.SourcePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

/**
 * Library Genesis (LibGen) book source. Only EPUB results are surfaced (the
 * only format the host app reads here): non-EPUB rows are skipped.
 *
 * Targets the mainline LibGen fork served identically from several mirror hosts
 * (`libgen.la`, `libgen.vg`, `libgen.bz`, `libgen.gl`, `libgen.li`). On that fork:
 * `GET /index.php?req=…` returns a results `<table>`, `GET /edition.php?id=…` is
 * the detail page, and the download is a two-hop chain — `GET /ads.php?md5=…`
 * renders a gateway page carrying a keyed `get.php?md5=…&key=…` link that serves
 * the EPUB bytes.
 *
 * Because the mirrors are byte-for-byte equivalent, this is a [MultiHostSource]:
 * it carries the ordered mirror list and a [HostFailoverInterceptor] transparently
 * rotates every request to the next mirror when one is down, so the source keeps
 * working through an outage even before the app pins a host via [setActiveHost].
 * A user-set mirror preference is honoured first.
 *
 * Books are whole-book EPUB files, so this is an [EpubSource]: the host downloads
 * the bytes via [getEpub] and parses them locally; [chapterListParse] /
 * [pageListParse] are intentionally empty.
 */
@SourceInfo(
    id = 9L,
    name = "Library Genesis",
    lang = "all",
    baseUrl = "https://libgen.la",
    versionCode = 3,
)
class LibGen :
    HttpSource(), ConfigurableSource, EpubSource, MultiLanguageSource, MultiHostSource {

    override val id = 9L
    override val name = "Library Genesis"
    override val lang = "all"

    // Optional user override (a mirror the curated list doesn't know about); when
    // set it takes priority over the built-in mirrors.
    @Volatile
    private var userMirror: String? = null

    // The mirror currently serving traffic; advanced by the failover interceptor
    // and pinnable by the app via setActiveHost.
    @Volatile
    private var activeHostBacking: String = DEFAULT_MIRRORS.first()

    // Lowercased enabled content languages. Empty = no filter. This fork has no
    // reliable server-side language filter, so it is applied client-side.
    @Volatile
    private var enabledLanguages: Set<String> = emptySet()

    // --- MultiHostSource ------------------------------------------------------

    override val hosts: List<String>
        get() = buildList {
            userMirror?.let { add(it) }
            addAll(DEFAULT_MIRRORS)
        }.distinct()

    override val activeHost: String
        get() = activeHostBacking

    override fun setActiveHost(host: String) {
        activeHostBacking = host.trim().takeIf { it.isNotEmpty() }
            ?.let { normalizeBaseUrl(it) }
            ?: hosts.first()
    }

    override val baseUrl: String
        get() = activeHostBacking

    // Every request first targets activeHost; the interceptor rotates to the
    // remaining mirrors on a connection error / 5xx and sticks to whichever one
    // answers.
    override val client: OkHttpClient = defaultOkHttpClient().newBuilder()
        .addInterceptor(HostFailoverInterceptor())
        .build()

    // --- ConfigurableSource ---------------------------------------------------

    override fun getPreferences(): List<SourcePreference> = listOf(
        SourcePreference.EditText(
            key = PREF_BASE_URL,
            title = "Mirror domain",
            summary = "Optional LibGen host to try first (default libgen.la, with " +
                "libgen.vg / .bz / .gl / .li as automatic backups). Set this only if " +
                "you want to force a specific or newer mirror.",
            default = "",
        ),
    )

    override fun setPreferences(values: Map<String, String>) {
        userMirror = values[PREF_BASE_URL]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { normalizeBaseUrl(it) }
        // Re-seed the active host from the (possibly new) preference order.
        activeHostBacking = hosts.first()
    }

    override suspend fun validateConfiguration(): ConfigValidationResult =
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(GET("$baseUrl/")).execute().use { it.isSuccessful }
            }.getOrDefault(false).let { ok ->
                if (ok) {
                    ConfigValidationResult(true, "Reached LibGen at $activeHost.")
                } else {
                    ConfigValidationResult(
                        false,
                        "Couldn't reach any LibGen mirror — check the mirror domain.",
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

    // This fork has no popularity metric and no anonymous "browse all", so
    // popular/latest both run one broad query and reuse the results parser. A
    // generic book word (an empty `req` returns nothing, and a stop-word like
    // "the" skews to non-EPUB comics) keeps the EPUB-filtered result set full.
    override fun popularNovelsRequest(page: Int): Request = searchUrl(BROWSE_QUERY, page)

    override fun latestUpdatesRequest(page: Int): Request = searchUrl(BROWSE_QUERY, page)

    override fun searchNovelsRequest(query: String, page: Int, filters: List<Filter<*>>): Request =
        searchUrl(query.trim().ifEmpty { BROWSE_QUERY }, page)

    // Fiction + non-fiction, title column. EPUB is enforced client-side (the
    // search has no dependable extension parameter — every candidate is ignored).
    private fun searchUrl(query: String, page: Int): Request {
        val url = baseUrl.toHttpUrlOrNull()!!.newBuilder()
            .addPathSegment("index.php")
            .addQueryParameter("req", query)
            .addQueryParameter("columns[]", "t")
            .addQueryParameter("objects[]", "f")
            .addQueryParameter("topics[]", "f")
            .addQueryParameter("topics[]", "l")
            .addQueryParameter("res", "100")
            // Renders each row's cover thumbnail (`/covers|fictioncovers/…`),
            // which the listing omits by default.
            .addQueryParameter("covers", "on")
            .apply { if (page > 1) addQueryParameter("page", page.toString()) }
            .build()
        return GET(url.toString())
    }

    override suspend fun popularNovelsParse(response: Response): List<Novel> =
        parseResults(response)

    override suspend fun latestUpdatesParse(response: Response): List<Novel> =
        parseResults(response)

    override suspend fun searchNovelsParse(response: Response): List<Novel> =
        parseResults(response)

    override fun getFilterList(): List<Filter<*>> = emptyList()

    // The results live in the one `<table>` whose rows link to `edition.php`.
    // Each book row is a sequence of <td>: title (edition link), author,
    // publisher, year, language, pages, size, extension, mirrors (the first
    // mirror anchor is `ads.php?md5=…`). Only EPUB rows with a usable md5 are
    // kept; the md5 rides along on the novel URL so the download needs no extra
    // detail fetch.
    private fun parseResults(response: Response): List<Novel> {
        val doc = response.asJsoup()
        val pageUrl = response.request.url.toString()
        val table = doc.select("table").maxByOrNull { it.select("a[href*=edition.php]").size }
            ?: return emptyList()
        return table.select("tr").mapNotNull { row ->
            val cells = row.select("td")
            // Each result row is a single file; keep it only when its extension
            // cell is EPUB (the format the app reads).
            if (cells.none { it.text().trim().equals("epub", ignoreCase = true) }) {
                return@mapNotNull null
            }
            // The title cell holds a cover anchor (empty text) and an ISBN anchor
            // alongside the title anchor — all pointing at edition.php — so take
            // the first edition link that actually has text.
            val editionLink = row.select("td a[href*=edition.php]")
                .firstOrNull { it.text().isNotBlank() } ?: return@mapNotNull null
            val md5 = row.selectFirst("a[href*=ads.php]")?.attr("href")
                ?.let { Regex("md5=([0-9a-fA-F]+)").find(it)?.groupValues?.get(1) }
                ?: return@mapNotNull null
            val title = editionLink.text().trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val language = cells.getOrNull(4)?.text()?.trim()?.takeIf { it.isNotEmpty() }
            if (enabledLanguages.isNotEmpty() &&
                language?.lowercase() !in enabledLanguages
            ) {
                return@mapNotNull null
            }
            val editionUrl = resolveAgainst(pageUrl, editionLink.attr("href").trim())
            Novel(
                // Carry the md5 so getEpub needs no extra round-trip; the server
                // ignores the extra query param on edition.php.
                url = appendMd5(editionUrl, md5),
                title = title,
                author = cells.getOrNull(1)?.text()?.trim()?.removePrefix("by ")?.trim()
                    ?.takeIf { it.isNotEmpty() },
                thumbnailUrl = row.selectFirst("img")?.imageUrl(pageUrl),
                language = language?.replaceFirstChar { it.uppercase() },
                status = NovelStatus.COMPLETED,
            )
        }.distinctBy { it.url }
    }

    // --- Novel details --------------------------------------------------------

    override suspend fun novelDetailsParse(response: Response): Novel {
        val pageUrl = response.request.url.toString()
        val doc = response.asJsoup()
        // The edition page lists its metadata as `<p><strong>Key:</strong> Value</p>`
        // rows (Title / Author(s) / Publisher / Year / Language / Tags / ISBN …).
        // The label sits in the <strong>; the value is the rest of the paragraph.
        val meta = doc.select("p:has(strong)").mapNotNull { p ->
            val label = p.selectFirst("strong")?.text()?.trim().orEmpty()
            if (label.isEmpty() || !label.endsWith(":")) return@mapNotNull null
            val key = label.removeSuffix(":").trim().lowercase()
            val value = p.text().removePrefix(label).trim()
            if (value.isEmpty()) null else key to value
        }.toMap()
        fun field(prefix: String) = meta.entries.firstOrNull { it.key.startsWith(prefix) }?.value
        val title = field("title")
            ?: doc.title().removePrefix("LG+:").substringBefore("{").trim().ifEmpty { "LibGen book" }
        // LibGen exposes no dependable synopsis, so summarise publisher + year.
        val description = listOfNotNull(
            field("publisher")?.let { "Publisher: $it" },
            field("year")?.let { "Year: $it" },
        ).joinToString("\n").takeIf { it.isNotEmpty() }
        val genres = field("tags")?.split(';', ',')
            ?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()
        return Novel(
            url = pageUrl,
            title = title,
            author = field("author")?.removePrefix("by ")?.trim()?.takeIf { it.isNotEmpty() },
            // Prefer the real cover (/covers|fictioncovers/…); the /editioncovers/
            // path exists but is a 0-byte placeholder on this fork.
            thumbnailUrl = doc.selectFirst("img[src*=/covers/], img[src*=/fictioncovers/]")
                ?.imageUrl(pageUrl),
            description = description,
            genres = genres,
            language = field("language")?.replaceFirstChar { it.uppercase() },
            status = NovelStatus.COMPLETED,
            initialized = true,
        )
    }

    // --- Not used: content is delivered as a whole-book EPUB ------------------

    override suspend fun chapterListParse(response: Response): List<Chapter> = emptyList()

    override suspend fun pageListParse(response: Response): List<NovelPage> = emptyList()

    // --- EpubSource -----------------------------------------------------------

    override suspend fun getEpub(novel: Novel): ByteArray = withContext(Dispatchers.IO) {
        val md5 = novel.url.toHttpUrlOrNull()?.queryParameter("md5")
            ?: Regex("md5=([0-9a-fA-F]+)").find(novel.url)?.groupValues?.get(1)
            ?: throw IOException("LibGen: couldn't find the book id in its URL.")
        var lastError = "unknown error"
        // The interceptor can't fail the download over (the get.php key is bound
        // to the host that issued it), so run the whole ads.php -> get.php chain
        // per host instead, active host first, until one serves the file.
        val ordered = (listOf(activeHost) + hosts).distinct()
        for (host in ordered) {
            val bytes = runCatching { downloadEpub(host, md5) { lastError = it } }
                .getOrElse { lastError = it.message ?: "network error"; null }
            if (bytes != null) return@withContext bytes
        }
        throw IOException("LibGen: $lastError.")
    }

    // Runs the two-hop download against one specific host, retrying transient
    // failures. Returns the EPUB bytes or null (reporting the reason via [onError]).
    private fun downloadEpub(host: String, md5: String, onError: (String) -> Unit): ByteArray? {
        repeat(MAX_RETRIES) { attempt ->
            val bytes = runCatching {
                // 1) The gateway page carries a keyed get.php link to the file.
                val getHref = client.newCall(GET("$host/ads.php?md5=$md5")).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        onError("gateway returned HTTP ${resp.code}")
                        return@use null
                    }
                    Jsoup.parse(resp.body!!.string(), resp.request.url.toString())
                        .selectFirst("a[href*=get.php]")?.attr("href")?.trim()
                } ?: run {
                    onError("no download link on the gateway page")
                    return@runCatching null
                }
                // 2) Fetch the actual file on the same host (the key is host-bound).
                val fileUrl = resolveAgainst("$host/ads.php", getHref)
                client.newCall(GET(fileUrl)).execute().use { resp ->
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
                        onError(
                            if (!resp.isSuccessful) "download failed (HTTP ${resp.code})"
                            else "the server returned a web page, not a file",
                        )
                        null
                    }
                }
            }.getOrElse {
                onError(it.message ?: "network error")
                null
            }
            if (bytes != null) return bytes
            if (attempt < MAX_RETRIES - 1) Thread.sleep(1500L * (attempt + 1))
        }
        return null
    }

    // --- Host failover --------------------------------------------------------

    // Transparently rotates a request across the mirror list. Requests aimed at a
    // known mirror host are retried against the remaining mirrors on a connection
    // error or 5xx; the first mirror to answer becomes the new active host. A 4xx
    // is a real answer (same on every mirror), so it is returned as-is. Requests
    // to other hosts (an app-pinned mirror we don't know, a CDN redirect target)
    // pass through untouched.
    private inner class HostFailoverInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val tryHosts = hosts.mapNotNull { it.toHttpUrlOrNull() }
            val knownHosts = tryHosts.map { it.host }.toSet()
            // The download chain (ads.php → get.php) is key-bound to one mirror:
            // the get.php `key` issued by a host's ads.php is rejected elsewhere.
            // Never rotate those requests across hosts — getEpub already runs them
            // against the live active host and retries transient failures.
            val path = original.url.encodedPath
            val isDownload = path.endsWith("/ads.php") || path.endsWith("/get.php")
            if (original.url.host !in knownHosts || isDownload) return chain.proceed(original)

            // Start from the active host, then the rest in declared order.
            val ordered = (listOf(activeHost.toHttpUrlOrNull()).filterNotNull() + tryHosts)
                .distinctBy { it.host }
            var lastError: IOException? = null
            for (host in ordered) {
                val rewritten = original.newBuilder()
                    .url(original.url.newBuilder().scheme(host.scheme).host(host.host).build())
                    .build()
                try {
                    val resp = chain.proceed(rewritten)
                    if (resp.code < 500) {
                        activeHostBacking = "${host.scheme}://${host.host}"
                        return resp
                    }
                    resp.close()
                    lastError = IOException("HTTP ${resp.code} from ${host.host}")
                } catch (e: IOException) {
                    lastError = e
                }
            }
            throw lastError ?: IOException("All LibGen mirrors are unreachable")
        }
    }

    // --- Helpers --------------------------------------------------------------

    private fun appendMd5(url: String, md5: String): String {
        val base = url.toHttpUrlOrNull() ?: return url
        return base.newBuilder().setQueryParameter("md5", md5).build().toString()
    }

    // Resolve a (possibly lazy/relative) image URL against [base].
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
        private const val MAX_RETRIES = 3

        // Seeds the popular/latest browse: a generic book word that returns a
        // large, EPUB-rich result set (stop-words like "the"/"of" return
        // non-EPUB comics or nothing on this fork).
        private const val BROWSE_QUERY = "novel"

        private val DEFAULT_MIRRORS = listOf(
            "https://libgen.la",
            "https://libgen.vg",
            "https://libgen.bz",
            "https://libgen.gl",
            "https://libgen.li",
        )
    }
}
