package io.grimoire.extension.en.zlibrary

import android.webkit.CookieManager
import io.grimoire.api.model.filter.Filter
import io.grimoire.api.model.lang.Language
import io.grimoire.api.model.novel.Novel
import io.grimoire.api.model.novel.NovelStatus
import io.grimoire.api.model.pref.ConfigValidationResult
import io.grimoire.api.model.pref.PrefValue
import io.grimoire.api.model.pref.SourcePreference
import io.grimoire.api.source.SourceInfo
import io.grimoire.api.source.epub.EpubSource
import io.grimoire.api.source.feature.ConfigurableSource
import io.grimoire.api.source.feature.LatestSource
import io.grimoire.api.source.feature.MultiLanguageSource
import io.grimoire.api.source.feature.PopularSource
import io.grimoire.api.source.feature.SearchSource
import io.grimoire.api.source.feature.WebViewLoginSource
import io.grimoire.api.source.http.HttpSource
import io.grimoire.api.util.richDescription
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
 * Z-Library book source. Only EPUB results are surfaced (the only format the
 * host app reads here): every search is constrained to `extensions[]=EPUB` and
 * non-EPUB results are skipped defensively.
 *
 * Everything runs against a single host (default `z-library.im`): the homepage
 * renders the "Most Popular" shelf, `GET /s/<query>` serves search results as
 * `<z-bookcard>` HTML, and `/book/…` + `/dl/…` serve details and downloads.
 * (An earlier split routed search through the `z-library.bz` landing domain's
 * `POST /api/search` JSON, but that endpoint 404s on the per-user mirror while
 * `/s/` is served everywhere — so one host now covers all of it.) Books are
 * whole-book EPUB files, so this is an [EpubSource]: the host downloads the
 * bytes via [getEpub] and parses them locally; [chapterListParse] /
 * [pageListParse] are intentionally empty.
 *
 * Downloads work anonymously within Z-Library's daily quota; signing in raises
 * or removes that limit. Login is delegated to a WebView ([WebViewLoginSource]):
 * the host opens [loginUrl], the user signs in on Z-Library's own page (which
 * also clears Cloudflare), and the resulting session cookies are replayed on
 * this source's OkHttp requests — no credentials are stored by the extension.
 *
 * Note: Z-Library rotates mirror domains and tweaks its markup, so the host is
 * a user-editable preference, and shelf/search parsing, download-link
 * resolution and login are each isolated below so they can be adjusted against
 * live responses without touching the rest of the source.
 */
@SourceInfo(
    name = "Z-Library",
    lang = Language.MULTI,
    baseUrl = "https://z-library.im",
    versionCode = 26,
)
class ZLibrary :
    HttpSource(),
    PopularSource,
    LatestSource,
    SearchSource,
    ConfigurableSource,
    EpubSource,
    MultiLanguageSource,
    WebViewLoginSource {

    override val name = "Z-Library"
    override val lang = Language.MULTI

    // Single host for everything (shelf, /s/ search, /book details, /dl
    // downloads, /login). Z-Library rotates mirrors, so this is user-editable.
    private val defaultMirror = "https://z-library.im"

    @Volatile
    private var mirror: String = defaultMirror

    // Enabled content languages. Empty = no filter. Z-Library's search ignores
    // any language parameter (verified), so this is applied client-side by
    // dropping non-matching results.
    @Volatile
    private var enabledLanguages: Set<Language> = emptySet()

    override val baseUrl: String
        get() = mirror

    // --- ConfigurableSource (mirror domain only — login is via WebView) -------

    override fun getPreferences(): List<SourcePreference> = listOf(
        SourcePreference.EditText(
            key = PREF_BASE_URL,
            title = "Mirror domain",
            summary = "Z-Library host used for everything — browsing, search, " +
                "sign-in and downloads (default z-library.im). Change it if the " +
                "default mirror is blocked.",
            default = defaultMirror,
        ),
    )

    override fun setPreferences(values: Map<String, PrefValue>) {
        mirror = (values[PREF_BASE_URL] as? PrefValue.Str)?.value
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
                    ConfigValidationResult(true, "Reached Z-Library at $baseUrl.")
                } else {
                    ConfigValidationResult(
                        false,
                        "Couldn't reach $baseUrl — check the mirror domain.",
                    )
                }
            }
        }

    // --- WebViewLoginSource ---------------------------------------------------

    // Z-Library's sign-in page; loading it in a WebView also clears Cloudflare.
    // Both track the configured mirror so a changed host still logs in/downloads
    // against the same domain.
    override val loginUrl: String
        get() = "$baseUrl/login"

    // A successful sign-in redirects off /login back to the homepage.
    override val loginSuccessUrl: String
        get() = baseUrl

    // Z-Library issues `remix_userid` (+ `remix_userkey`) cookies on sign-in;
    // a logged-out jar has neither (or `remix_userid=0`).
    override suspend fun isLoggedIn(): Boolean = withContext(Dispatchers.IO) {
        val raw = CookieManager.getInstance().getCookie(baseUrl).orEmpty()
        val userId = cookieValue(raw, "remix_userid")
        (!userId.isNullOrBlank() && userId != "0") ||
            !cookieValue(raw, "remix_userkey").isNullOrBlank()
    }

    override suspend fun logout(): Unit = withContext(Dispatchers.IO) {
        val cm = CookieManager.getInstance()
        val host = baseUrl.toHttpUrlOrNull()?.host
        val scopes = buildList {
            add(baseUrl)
            host?.let { add("https://$it"); add("https://www.$it") }
        }.distinct()
        // Domain attributes to expire against. Z-Library's auth cookies are
        // domain cookies (Domain=.z-library.im); a host-only expiry cookie does
        // NOT override a domain cookie, so each name must also be expired with
        // the matching Domain or the login survives logout.
        val domains = buildList {
            host?.let {
                add(it); add(".$it")
                val parts = it.split('.')
                if (parts.size > 2) {
                    val parent = parts.drop(1).joinToString(".")
                    add(parent); add(".$parent")
                }
            }
        }.distinct()
        // Expire every cookie currently set on the mirror — the session cookie
        // name varies by deployment, so clearing all of them is the safe option.
        val names = cm.getCookie(baseUrl).orEmpty()
            .split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
        for (name in names) for (scope in scopes) {
            cm.setCookie(scope, "$name=; $COOKIE_EXPIRY")
            for (domain in domains) cm.setCookie(scope, "$name=; Domain=$domain; $COOKIE_EXPIRY")
        }
        cm.flush()
    }

    // --- MultiLanguageSource --------------------------------------------------

    override suspend fun availableLanguages(): List<Language> = listOf(
        Language.EN, Language.ES, Language.PT, Language.FR, Language.DE, Language.IT,
        Language.NL, Language.RU, Language.UK, Language.PL, Language.CS, Language.RO,
        Language.EL, Language.TR, Language.AR, Language.HE, Language.HI, Language.BN,
        Language.ZH, Language.JA, Language.KO, Language.VI, Language.TH, Language.ID,
        Language.SV, Language.NO, Language.DA, Language.FI, Language.HU, Language.FA,
    )

    override fun setEnabledLanguages(languages: Set<Language>) {
        enabledLanguages = languages.filter { it != Language.MULTI && it != Language.UNKNOWN }.toSet()
    }

    // --- Listings (always EPUB-constrained) -----------------------------------

    // Z-Library has no anonymous "browse all" endpoint and the search API
    // rejects an empty query, so popular/latest both read the landing page's
    // server-rendered "Most Popular" shelf (single page, no pagination). The
    // page marker lets the parser return an empty second page so the host stops
    // paginating instead of looping the same shelf forever.
    // Z-Library has no anonymous "browse all" endpoint, so popular/latest both
    // read the landing page's "Most Popular" shelf (single page). The page marker
    // lets the parser return an empty second page so the host stops paginating.
    override suspend fun getPopularNovels(page: Int): List<Novel> = withContext(Dispatchers.IO) {
        parseShelf(execute(GET("$baseUrl/?$PAGE_MARKER=$page")))
    }

    override suspend fun getLatestUpdates(page: Int): List<Novel> = withContext(Dispatchers.IO) {
        parseShelf(execute(GET("$baseUrl/?$PAGE_MARKER=$page")))
    }

    // Search is the server-rendered `GET /s/<query>` results page.
    // `extensions[]=EPUB` constrains to EPUB; `page` paginates.
    override suspend fun searchNovels(query: String, page: Int, filters: List<Filter<*>>): List<Novel> =
        withContext(Dispatchers.IO) {
            val url = baseUrl.toHttpUrlOrNull()!!.newBuilder()
                .addPathSegment("s")
                .addPathSegment(query.trim())
                .addQueryParameter("extensions[]", "EPUB")
                .apply { if (page > 1) addQueryParameter("page", page.toString()) }
                .build()
            searchNovelsParse(execute(GET(url.toString())))
        }

    // The `/s/` results page renders each hit as a `<z-bookcard>` custom element
    // carrying href/extension/language as attributes, with title/author in
    // `[slot]` children and the (absolute CDN) cover in the nested `<img>`.
    private fun searchNovelsParse(response: Response): List<Novel> {
        val doc = response.asJsoup()
        return doc.select("z-bookcard").mapNotNull { card ->
            if (!card.attr("extension").equals("epub", ignoreCase = true)) return@mapNotNull null
            val href = card.attr("href").trim().substringBefore('?').takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            // Z-Library's search ignores any language param, so filter client-side.
            val language = toLanguage(card.attr("language").trim())
            if (enabledLanguages.isNotEmpty() && language != Language.UNKNOWN &&
                language !in enabledLanguages
            ) {
                return@mapNotNull null
            }
            val title = card.selectFirst("[slot=title]")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Novel(
                url = href,
                title = title,
                language = language,
                author = card.selectFirst("[slot=author]")?.text()?.trim()
                    ?.takeIf { it.isNotEmpty() },
                thumbnailUrl = card.selectFirst("img")?.imageUrl(response.request.url.toString()),
                status = NovelStatus.COMPLETED,
            )
        }
    }

    private fun parseShelf(response: Response): List<Novel> {
        val pageParam = response.request.url.queryParameter(PAGE_MARKER)
        if (pageParam != null && pageParam != "1") return emptyList()
        val doc = response.asJsoup()
        // Each shelf entry is `<a href="…/book/<id>/<slug>.html"><z-cover
        // author="…" title="…"><img src="<absolute CDN>" alt="Author — Title"/>
        // </z-cover></a>`. Prefer the z-cover attributes, falling back to the
        // img alt ("Author — Title", em dash). Some books appear twice (with and
        // without `?dsource=mostpopular`); strip the query and de-dup by URL.
        return doc.select("a[href*=/book/]").mapNotNull { link ->
            val img = link.selectFirst("img") ?: return@mapNotNull null
            val href = link.attr("href").trim().substringBefore('?').takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val cover = link.selectFirst("z-cover")
            val alt = img.attr("alt").trim().ifEmpty { img.attr("title").trim() }
            val altAuthor: String?
            val altTitle: String
            if (" — " in alt) {
                val parts = alt.split(" — ", limit = 2)
                altAuthor = parts[0].trim().takeIf { it.isNotEmpty() }
                altTitle = parts[1].trim()
            } else {
                altAuthor = null
                altTitle = alt
            }
            val title = cover?.attr("title")?.trim()?.takeIf { it.isNotEmpty() } ?: altTitle
            if (title.isEmpty()) return@mapNotNull null
            Novel(
                url = href,
                title = title,
                language = Language.UNKNOWN,
                author = cover?.attr("author")?.trim()?.takeIf { it.isNotEmpty() } ?: altAuthor,
                thumbnailUrl = img.imageUrl(response.request.url.toString()),
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
        fun meta(prop: String) =
            doc.selectFirst("meta[property=og:$prop], meta[name=$prop]")
                ?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
        val ogTitle = meta("title").orEmpty()
        // Without the browser session token the mirror redirects book URLs to
        // its homepage. Rather than error, degrade gracefully: derive a title
        // from the URL slug so the screen is usable and the (more robust)
        // download path can still run.
        val path = response.request.url.encodedPath.trim('/')
        if (path.isEmpty() || ogTitle.contains("largest e-book library", ignoreCase = true)) {
            return Novel(
                url = pageUrl,
                title = titleFromUrl(pageUrl),
                language = Language.UNKNOWN,
                status = NovelStatus.COMPLETED,
                initialized = true,
            )
        }
        // og:title is "<Title> | <Author> | download on Z-Library"; prefer the
        // page heading, else take the part before the first separator.
        val title = doc.selectFirst("h1[itemprop=name], h1")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: ogTitle.substringBefore(" | ").trim().takeIf { it.isNotEmpty() }
            ?: ogTitle
        val description = doc.selectFirst("#bookDescriptionBox, [itemprop=description]")
            ?.richDescription()?.takeIf { it.isNotBlank() }
            ?: meta("description")
        val author = doc.selectFirst("a.color1[title], [itemprop=author], .book-property__author a")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val cover = doc.selectFirst(
            ".details-book-cover-container img, z-cover img, .z-book-cover img, img.cover",
        )?.imageUrl(pageUrl) ?: meta("image")
        val genres = doc.select(".property_categories a, a[href*=/category/]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val language = toLanguage(
            doc.selectFirst(
                ".property_language .property_value, [itemprop=inLanguage], " +
                    ".bookProperty.property_language .property_value",
            )?.text()?.trim(),
        )
        return Novel(
            url = response.request.url.toString(),
            title = title,
            language = language,
            thumbnailUrl = cover,
            author = author,
            description = description,
            genres = genres,
            status = NovelStatus.COMPLETED,
            initialized = true,
        )
    }

    // --- EpubSource -----------------------------------------------------------

    override suspend fun getEpub(novel: Novel): ByteArray = withContext(Dispatchers.IO) {
        val bookUrl = resolveUrl(novel.url)
        // The session is carried by the WebView's cookie jar (see
        // [WebViewLoginSource]); requests here ride those cookies automatically.
        // The book page is Cloudflare gated; the default client solves the
        // challenge transparently, but clearance is cookie-based and can need a
        // follow-up request to land, so retry a few times before giving up.
        val bookHtml = fetchWithRetry(bookUrl)
            ?: throw IOException(
                "Z-Library: couldn't load the book page — Cloudflare protection " +
                    "on the mirror could not be cleared. Try again in a moment.",
            )
        // The real download link is NOT the anchor's href — that's a decoy token
        // that always answers /dl with 204 No Content (anti-scraping). The working
        // token is split across a JS array in an inline click handler and only
        // assembled at click time; assemble it ourselves. Fall back to the anchor
        // href for older markup.
        val downloadHref = realDownloadHref(bookHtml)
            ?: resolveDownloadHref(Jsoup.parse(bookHtml, bookUrl))
            ?: throw IOException(
                "Z-Library: no EPUB download link found. Sign in via the source's " +
                    "account login, or the daily download limit may have been reached.",
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
                // A 204 No Content here means the token was refused even though we
                // assembled the real one (see realDownloadHref) — typically the
                // daily download limit, or the book needs a signed-in account.
                if (resp.code == 204 ||
                    (resp.isSuccessful && resp.body?.contentLength() == 0L)
                ) {
                    return DlResult.Fatal(
                        "no file returned (HTTP ${resp.code}) — sign in via the " +
                            "source's account login, or the daily download limit may " +
                            "have been reached",
                    )
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
                val pageTitle = Jsoup.parse(html).title().trim()
                val title = pageTitle.takeIf { it.isNotEmpty() }?.let { " (page: \"$it\")" } ?: ""
                val finalUrl = resp.request.url
                // Z-Library serves its online reader (not the file) when the
                // request lacks its first-party browser session (a JS-issued
                // token alongside Cloudflare clearance). This is not an account
                // issue — anonymous downloads work once that session exists.
                val isReader = finalUrl.host.startsWith("reader.") ||
                    finalUrl.encodedPath.contains("/read/") ||
                    pageTitle.contains("reader", ignoreCase = true)
                return when {
                    QUOTA_MARKERS.any { lower.contains(it) } -> DlResult.Fatal(
                        "the daily download limit has been reached — sign in via " +
                            "the source's account login to raise it",
                    )
                    isReader -> DlResult.Fatal(
                        "Z-Library returned its online reader instead of the file. " +
                            "Its download session (a browser token) wasn't " +
                            "established — open the source once via “Open in " +
                            "WebView” to warm it up, then retry the download",
                    )
                    LOGIN_MARKERS.any { lower.contains(it) } -> DlResult.Fatal(
                        "this book requires a signed-in account — sign in via the " +
                            "source's account login$title",
                    )
                    else -> DlResult.Retry(
                        "the server returned a web page, not a file$title",
                    )
                }
            }
        }
        return DlResult.Retry("too many download redirects")
    }

    /**
     * Extract a meta-refresh / JS / anchor target that leads to the file.
     * Online-reader targets are intentionally not followed so the caller can
     * report the unauthorised-session state instead of chasing the reader.
     */
    private fun nextDownloadLink(html: String, baseUrl: String): String? {
        fun usable(raw: String): String? {
            val abs = absolute(baseUrl, raw)
            val low = abs.lowercase()
            return if (low.contains("/read/") || low.contains("reader.")) null else abs
        }
        val doc = Jsoup.parse(html, baseUrl)
        doc.selectFirst("meta[http-equiv=refresh]")?.attr("content")
            ?.let { Regex("url=([^;]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            ?.trim()?.trim('\'', '"')?.takeIf { it.isNotEmpty() }
            ?.let { usable(it)?.let { u -> return u } }
        Regex("""(?:window\.location(?:\.href)?|location\.href)\s*=\s*['"]([^'"]+)['"]""")
            .find(html)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
            ?.let { usable(it)?.let { u -> return u } }
        doc.selectFirst(
            "a.addDownloadedBook, a.dlButton, a[href*=/dl/], " +
                "a[href$=.epub], a.btn[href*=download]",
        )?.attr("href")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { usable(it)?.let { u -> return u } }
        return null
    }

    private fun absolute(base: String, href: String): String =
        if (href.startsWith("http")) href else resolveAgainst(base, href)

    /**
     * The working download path, reconstructed from the inline click handler the
     * site attaches to the download button:
     *
     * ```
     * a.addDownloadedBook ... addEventListener('click', function(event){
     *     event.preventDefault();
     *     const location = ["/dl","/nK","71b","7A1","9o"], split='';
     *     window.location.href = location.join(split);
     * })
     * ```
     *
     * The anchor's own `href` is a decoy that always returns 204; only the token
     * assembled here (`location.join(split)`) hits the real /dl that 302-redirects
     * to the file CDN. Returns null when the handler isn't present (older markup).
     */
    private fun realDownloadHref(html: String): String? {
        val block = Regex(
            "addDownloadedBook'\\)\\.addEventListener\\('click'[\\s\\S]*?location\\.join\\(split\\)",
        ).find(html)?.value ?: return null
        val arr = Regex("location\\s*=\\s*\\[([^\\]]*)]").find(block)?.groupValues?.get(1) ?: return null
        val split = Regex("split\\s*=\\s*'([^']*)'").find(block)?.groupValues?.get(1) ?: ""
        val parts = Regex("\"((?:\\\\.|[^\"\\\\])*)\"").findAll(arr)
            .map { it.groupValues[1].replace("\\/", "/") }
            .toList()
        return parts.joinToString(split).takeIf { it.startsWith("/dl/") }
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

    // The real download button is `a.addDownloadedBook` (href=/dl/...).
    // `a.dlButton.reader-link` is the *Read Online* link — never follow it.
    private fun resolveDownloadHref(doc: Document): String? {
        doc.selectFirst("a.addDownloadedBook[href]")
            ?.attr("href")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return doc.select("a[href*=/dl/]")
            .firstOrNull {
                !it.hasClass("reader-link") && !it.hasClass("dlButton") &&
                    !it.attr("href").contains("/read/") &&
                    !it.attr("href").contains("reader.")
            }
            ?.attr("href")?.trim()?.takeIf { it.isNotEmpty() }
    }

    // --- Helpers --------------------------------------------------------------

    private suspend fun execute(request: Request): Response =
        withContext(Dispatchers.IO) { client.newCall(request).execute() }

    // Map a Z-Library language name (e.g. "English") to a [Language] by its
    // English display name; unknown / blank -> [Language.UNKNOWN].
    private fun toLanguage(name: String?): Language {
        val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return Language.UNKNOWN
        return Language.entries.firstOrNull { it.displayName.equals(n, ignoreCase = true) }
            ?: Language.UNKNOWN
    }

    private fun cookieValue(rawCookies: String, name: String): String? =
        rawCookies.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() }

    // Resolve a (possibly lazy/relative) image URL. Homepage shelf covers are
    // root-relative (/covers150/...) but live on the book's mirror host, not
    // the landing domain, so callers pass the owning page/book URL as [base].
    private fun Element.imageUrl(base: String = baseUrl): String? {
        val raw = listOf("data-src", "data-flickity-lazyload", "data-original", "src")
            .map { attr(it).trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        return when {
            raw.startsWith("http") -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> resolveAgainst(base, raw)
            else -> raw
        }
    }

    private fun resolveAgainst(pageUrl: String, href: String): String {
        if (href.startsWith("http")) return href
        val base = pageUrl.toHttpUrlOrNull() ?: return resolveUrl(href)
        val origin = "${base.scheme}://${base.host}"
        return if (href.startsWith("/")) "$origin$href" else "$origin/$href"
    }

    /** Best-effort human title from a /book/<id>/<slug>.html URL. */
    private fun titleFromUrl(url: String): String {
        val slug = url.substringBefore('?').substringAfterLast('/')
            .removeSuffix(".html").replace('-', ' ').replace('_', ' ').trim()
        return slug.split(' ').filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            .ifBlank { "Z-Library book" }
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
        private const val PAGE_MARKER = "zlpage"
        private const val COOKIE_EXPIRY =
            "Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=/"
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
