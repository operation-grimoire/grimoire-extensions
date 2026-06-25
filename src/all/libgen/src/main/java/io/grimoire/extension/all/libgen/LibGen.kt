package io.grimoire.extension.all.libgen

import io.grimoire.api.model.filter.Filter
import io.grimoire.api.model.lang.Language
import io.grimoire.api.model.novel.Novel
import io.grimoire.api.model.novel.NovelStatus
import io.grimoire.api.model.pref.ConfigValidationResult
import io.grimoire.api.model.pref.PrefValue
import io.grimoire.api.model.pref.SourcePreference
import io.grimoire.api.network.failoverClient
import io.grimoire.api.source.SourceInfo
import io.grimoire.api.source.epub.EpubSource
import io.grimoire.api.source.feature.ConfigurableSource
import io.grimoire.api.source.feature.MultiHostSource
import io.grimoire.api.source.feature.MultiLanguageSource
import io.grimoire.api.source.feature.SearchSource
import io.grimoire.api.source.http.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
 * Targets the mainline LibGen fork served identically from several mirror hosts.
 * Because the mirrors are byte-for-byte equivalent, this is a [MultiHostSource];
 * a [HostFailoverInterceptor] transparently rotates page requests to the next
 * mirror when one is down. Books are whole-book EPUBs, so this is an [EpubSource]:
 * the host downloads the bytes via [getEpub] and parses them locally.
 */
@SourceInfo(
    name = "Library Genesis",
    lang = Language.MULTI,
    baseUrl = "https://libgen.la",
    versionCode = 6,
)
class LibGen :
    HttpSource(),
    SearchSource,
    ConfigurableSource,
    EpubSource,
    MultiLanguageSource,
    MultiHostSource {

    override val name = "Library Genesis"
    override val lang = Language.MULTI

    @Volatile
    private var userMirror: String? = null

    @Volatile
    private var activeHostBacking: String = DEFAULT_MIRRORS.first()

    // Enabled content languages. Empty = no filter. This fork has no reliable
    // server-side language filter, so it is also applied client-side.
    @Volatile
    private var enabledLanguages: Set<Language> = emptySet()

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

    // Page requests (index.php listings / edition.php details) fail over across
    // mirrors; per-file requests (covers, the key-bound download chain) stay on
    // their host. Every mirror request carries a same-host Referer, and a 200
    // listing with no results is treated as a dead mirror and retried elsewhere.
    override val client: OkHttpClient = failoverClient(
        rotatable = { it.encodedPath.endsWith("/index.php") || it.encodedPath.endsWith("/edition.php") },
        addReferer = true,
        accept = { resp ->
            !resp.request.url.encodedPath.endsWith("/index.php") ||
                "edition.php" in resp.peekBody(PEEK_BYTES).string()
        },
    )

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

    override fun setPreferences(values: Map<String, PrefValue>) {
        userMirror = (values[PREF_BASE_URL] as? PrefValue.Str)?.value
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

    override suspend fun availableLanguages(): List<Language> = LANGUAGE_CODES.keys.toList()

    override fun setEnabledLanguages(languages: Set<Language>) {
        enabledLanguages = languages.filter { it != Language.MULTI && it != Language.UNKNOWN }.toSet()
    }

    // --- Listings (always EPUB-constrained) -----------------------------------

    // This fork exposes no popularity or recency metric, so the source offers
    // only Search (no fake Popular/Latest tabs that would just echo a seeded
    // browse).
    override suspend fun searchNovels(query: String, page: Int, filters: List<Filter<*>>): List<Novel> =
        withContext(Dispatchers.IO) {
            parseResults(execute(searchUrl(query.trim(), page)))
        }

    // Built in LibGen's "Google mode" (gmode=on): `ext:epub` constrains to EPUB
    // and `lang:<iso639-2>` to a language — both server-side. Several enabled
    // languages can't be OR'd in one query, so the request rotates through them
    // by page (each page is one language; paging surfaces the rest).
    private fun searchUrl(query: String, page: Int): Request {
        val lang = languageForPage(page)
        val req = buildList {
            when {
                query.isNotBlank() -> add(query)
                lang == null -> add(BROWSE_SEED)
            }
            add("ext:epub")
            lang?.let { add("lang:$it") }
        }.joinToString(" ")
        val url = baseUrl.toHttpUrlOrNull()!!.newBuilder()
            .addPathSegment("index.php")
            .addQueryParameter("req", req)
            .addQueryParameter("columns[]", "t")
            .addQueryParameter("objects[]", "f")
            .addQueryParameter("topics[]", "f")
            .addQueryParameter("topics[]", "l")
            .addQueryParameter("res", "100")
            .addQueryParameter("gmode", "on")
            .addQueryParameter("covers", "on")
            .apply { if (page > 1) addQueryParameter("page", page.toString()) }
            .build()
        return GET(url.toString())
    }

    // The lang: code to constrain this page to, or null for "all languages".
    private fun languageForPage(page: Int): String? {
        val codes = enabledLanguages.sortedBy { it.code }.mapNotNull { LANGUAGE_CODES[it] }
        if (codes.isEmpty()) return null
        return codes[(page - 1).coerceAtLeast(0) % codes.size]
    }

    // The results live in the one `<table>` whose rows link to `edition.php`.
    // Only EPUB rows with a usable md5 are kept; the md5 rides along on the novel
    // URL so the download needs no extra detail fetch.
    private fun parseResults(response: Response): List<Novel> {
        val doc = response.asJsoup()
        val pageUrl = response.request.url.toString()
        val table = doc.select("table").maxByOrNull { it.select("a[href*=edition.php]").size }
            ?: return emptyList()
        return table.select("tr").mapNotNull { row ->
            val cells = row.select("td")
            if (cells.none { it.text().trim().equals("epub", ignoreCase = true) }) {
                return@mapNotNull null
            }
            val editionLink = row.select("td a[href*=edition.php]")
                .firstOrNull { it.text().isNotBlank() } ?: return@mapNotNull null
            val md5 = row.selectFirst("a[href*=ads.php]")?.attr("href")
                ?.let { Regex("md5=([0-9a-fA-F]+)").find(it)?.groupValues?.get(1) }
                ?: return@mapNotNull null
            val title = editionLink.text().trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            // Trailing columns are stable (… Language, Pages, Size, Ext, Mirrors),
            // so index them from the end — covers=on prepends a cover cell.
            val languageText = cells.getOrNull(cells.size - 5)?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
            // A row can be tagged multi-language ("English;German"); keep it if any
            // of its languages is enabled.
            val rowLangs = languageText?.split(';', ',')
                ?.map { toLanguage(it.trim()) }?.filter { it != Language.UNKNOWN }.orEmpty()
            if (enabledLanguages.isNotEmpty() && rowLangs.none { it in enabledLanguages }) {
                return@mapNotNull null
            }
            val editionUrl = resolveAgainst(pageUrl, editionLink.attr("href").trim())
            Novel(
                url = appendMd5(editionUrl, md5),
                title = title,
                language = rowLangs.firstOrNull() ?: Language.UNKNOWN,
                author = cells.getOrNull(cells.size - 8)?.text()?.trim()
                    ?.removePrefix("by ")?.trim()?.takeIf { it.isNotEmpty() },
                thumbnailUrl = row.selectFirst("img")?.imageUrl(pageUrl),
                status = NovelStatus.COMPLETED,
            )
        }.distinctBy { it.url }
    }

    // --- Novel details --------------------------------------------------------

    override suspend fun getNovelDetails(novel: Novel): Novel = withContext(Dispatchers.IO) {
        novelDetailsParse(execute(GET(resolveUrl(novel.url))))
    }

    private fun novelDetailsParse(response: Response): Novel {
        val pageUrl = response.request.url.toString()
        val doc = response.asJsoup()
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
        val description = listOfNotNull(
            field("publisher")?.let { "Publisher: $it" },
            field("year")?.let { "Year: $it" },
        ).joinToString("\n").takeIf { it.isNotEmpty() }
        val genres = field("tags")?.split(';', ',')
            ?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()
        return Novel(
            url = pageUrl,
            title = title,
            language = toLanguage(field("language")),
            author = field("author")?.removePrefix("by ")?.trim()?.takeIf { it.isNotEmpty() },
            // Prefer the real cover; the /editioncovers/ path is a 0-byte placeholder.
            thumbnailUrl = doc.selectFirst("img[src*=/covers/], img[src*=/fictioncovers/]")
                ?.imageUrl(pageUrl),
            description = description,
            genres = genres,
            status = NovelStatus.COMPLETED,
            initialized = true,
        )
    }

    // --- EpubSource -----------------------------------------------------------

    override suspend fun getEpub(novel: Novel): ByteArray = withContext(Dispatchers.IO) {
        val md5 = novel.url.toHttpUrlOrNull()?.queryParameter("md5")
            ?: Regex("md5=([0-9a-fA-F]+)").find(novel.url)?.groupValues?.get(1)
            ?: throw IOException("LibGen: couldn't find the book id in its URL.")
        var lastError = "unknown error"
        // The get.php key is bound to the host that issued it, so run the whole
        // ads.php -> get.php chain per host, active host first.
        val ordered = (listOf(activeHost) + hosts).distinct()
        for (host in ordered) {
            val bytes = runCatching { downloadEpub(host, md5) { lastError = it } }
                .getOrElse { lastError = it.message ?: "network error"; null }
            if (bytes != null) return@withContext bytes
        }
        throw IOException("LibGen: $lastError.")
    }

    // Runs the two-hop download against one host, retrying transient failures.
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

    // --- Helpers --------------------------------------------------------------

    private suspend fun execute(request: Request): Response =
        withContext(Dispatchers.IO) { client.newCall(request).execute() }

    // Map a LibGen language name (e.g. "English") to a [Language] by its English
    // display name; unknown / blank -> [Language.UNKNOWN].
    private fun toLanguage(name: String?): Language {
        val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return Language.UNKNOWN
        return Language.entries.firstOrNull { it.displayName.equals(n, ignoreCase = true) }
            ?: Language.UNKNOWN
    }

    private fun appendMd5(url: String, md5: String): String {
        val base = url.toHttpUrlOrNull() ?: return url
        return base.newBuilder().setQueryParameter("md5", md5).build().toString()
    }

    // Resolve a (possibly lazy/relative) image URL against [base]; the
    // `/img/blank.png` placeholder is treated as no cover.
    private fun Element.imageUrl(base: String = baseUrl): String? {
        val raw = listOf("data-src", "data-original", "src")
            .map { attr(it).trim() }
            .firstOrNull { it.isNotEmpty() && "blank" !in it }
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

        // Upper bound for sniffing a listing response for a results table.
        private const val PEEK_BYTES = 1L * 1024 * 1024

        // Light keyword for the unfiltered popular/latest browse.
        private const val BROWSE_SEED = "novel"

        // Offered language -> LibGen `lang:` code (ISO 639-2/B, the bibliographic
        // three-letter form the catalogue uses).
        private val LANGUAGE_CODES = mapOf(
            Language.EN to "eng", Language.ES to "spa", Language.PT to "por",
            Language.FR to "fre", Language.DE to "ger", Language.IT to "ita",
            Language.NL to "dut", Language.RU to "rus", Language.UK to "ukr",
            Language.PL to "pol", Language.CS to "cze", Language.RO to "rum",
            Language.EL to "gre", Language.TR to "tur", Language.AR to "ara",
            Language.HE to "heb", Language.HI to "hin", Language.BN to "ben",
            Language.ZH to "chi", Language.JA to "jpn", Language.KO to "kor",
            Language.VI to "vie", Language.TH to "tha", Language.ID to "ind",
            Language.SV to "swe", Language.NO to "nor", Language.DA to "dan",
            Language.FI to "fin", Language.HU to "hun", Language.FA to "fas",
        )

        private val DEFAULT_MIRRORS = listOf(
            "https://libgen.la",
            "https://libgen.vg",
            "https://libgen.bz",
            "https://libgen.gl",
            "https://libgen.li",
        )
    }
}
