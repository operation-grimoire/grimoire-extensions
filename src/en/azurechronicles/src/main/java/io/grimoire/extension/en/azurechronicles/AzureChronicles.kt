package io.grimoire.extension.en.azurechronicles

import io.grimoire.api.model.Chapter
import io.grimoire.api.model.Filter
import io.grimoire.api.model.Novel
import io.grimoire.api.model.NovelPage
import io.grimoire.api.model.NovelStatus
import io.grimoire.api.network.HttpSource
import io.grimoire.api.network.richHtml
import io.grimoire.api.source.SourceInfo
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

/**
 * Azure Chronicles (azurechronicles.com) — a translated web-novel site on a
 * bespoke WordPress theme (`azurechronicles-21`, custom `ac_novel` post type),
 * so everything is scraped rather than served by a shared base class.
 *
 * - Browsing reads the `/type/novel/` archive (paginated `/page/N/`); cards are
 *   `article.ac-grid2-card`.
 * - Search hits the theme's `ac_search_series` admin-ajax endpoint, which
 *   returns clean JSON.
 * - A novel page server-renders its details and its full chapter list under
 *   `#chapters` (no AJAX pagination), and chapter pages hold the prose in
 *   `#ac-r-body`.
 *
 * The site sets cosmetic "soft protection" (disabled selection/right-click) and
 * has a coin-based unlock system for premium chapters; locked chapters render
 * an unlock prompt instead of prose, which [pageListParse] reports as an error.
 */
@SourceInfo(
    id = 10L,
    name = "Azure Chronicles",
    lang = "en",
    baseUrl = "https://azurechronicles.com",
    versionCode = 1,
)
class AzureChronicles : HttpSource() {

    override val id = 10L
    override val name = "Azure Chronicles"
    override val lang = "en"
    override val baseUrl = "https://azurechronicles.com"

    // --- Listings -------------------------------------------------------------

    // The archive has no exposed sort, so popular and latest both read the
    // `/type/novel/` listing (newest first), paginated by `/page/N/`.
    override fun popularNovelsRequest(page: Int): Request = browseRequest(page)

    override fun latestUpdatesRequest(page: Int): Request = browseRequest(page)

    private fun browseRequest(page: Int): Request =
        if (page <= 1) GET("$baseUrl/type/novel/") else GET("$baseUrl/type/novel/page/$page/")

    override suspend fun popularNovelsParse(response: Response): List<Novel> =
        parseListing(response)

    override suspend fun latestUpdatesParse(response: Response): List<Novel> =
        parseListing(response)

    private fun parseListing(response: Response): List<Novel> {
        val doc = response.asJsoup()
        return doc.select("article.ac-grid2-card").mapNotNull { card ->
            val link = card.selectFirst("a.ac-grid2-cover-link, a[href*=/novel/]")
                ?: return@mapNotNull null
            val href = link.attr("href").trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val title = link.attr("title").trim()
                .ifEmpty { card.selectFirst(".ac-grid2-title, h2, h3")?.text()?.trim().orEmpty() }
                .takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Novel(
                url = absUrl(href),
                title = title,
                thumbnailUrl = card.selectFirst(".ac-grid2-cover, [style*=background-image]")
                    ?.let { bgImage(it) }
                    ?: card.selectFirst("img")?.imageUrl(),
            )
        }.distinctBy { it.url }
    }

    // Search is the theme's admin-ajax endpoint; it answers with
    // `{ success, data: [{ id, title, url, cover, status, genres }] }`.
    override fun searchNovelsRequest(query: String, page: Int, filters: List<Filter<*>>): Request {
        val url = "$baseUrl/wp-admin/admin-ajax.php".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("action", "ac_search_series")
            .addQueryParameter("q", query.trim())
            // ac_search_series returns one un-paginated result set; carry the
            // requested page so the parser can end pagination after page 1.
            .addQueryParameter(PAGE_MARKER, page.toString())
            .build()
        return GET(url.toString())
    }

    override suspend fun searchNovelsParse(response: Response): List<Novel> {
        if (response.request.url.queryParameter(PAGE_MARKER).let { it != null && it != "1" }) {
            return emptyList()
        }
        val json = JSONObject(response.body?.string().orEmpty().ifBlank { "{}" })
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            val href = item.optString("url").trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val title = item.optString("title").trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            Novel(
                url = absUrl(href),
                title = title,
                thumbnailUrl = item.optString("cover").trim().takeIf { it.isNotEmpty() },
                genres = item.optString("genres").split(',')
                    .map { it.trim() }.filter { it.isNotEmpty() },
                status = statusOf(item.optString("status_slug").ifEmpty { item.optString("status") }),
            )
        }
    }

    override fun getFilterList(): List<Filter<*>> = emptyList()

    // --- Novel details --------------------------------------------------------

    override suspend fun novelDetailsParse(response: Response): Novel {
        val pageUrl = response.request.url.toString()
        val doc = response.asJsoup()
        fun meta(prop: String) = doc.selectFirst("meta[property=og:$prop], meta[name=$prop]")
            ?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: meta("title")?.substringBefore(" - ")?.trim()
            ?: "Azure Chronicles novel"
        // The translator badge doubles as the author/team; the meta chips carry
        // the publication status (Ongoing / Completed / Hiatus).
        val author = doc.selectFirst("#ac-meta-translator a, #ac-meta-translator .ac-badge-value")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.select(".ac-meta-chip").getOrNull(2)?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val status = doc.select(".ac-meta-chip")
            .firstNotNullOfOrNull { chip -> statusWord(chip.text()) }
            ?: NovelStatus.UNKNOWN
        return Novel(
            url = pageUrl,
            title = title,
            thumbnailUrl = doc.selectFirst("#ac-cover-img")?.let { bgImage(it) }
                ?: doc.selectFirst("#ac-cover-img img, .ac-cover img")?.imageUrl()
                ?: meta("image"),
            author = author,
            description = doc.selectFirst(".ac-synopsis-wrap, #ac-synopsis, [class*=synopsis]")
                ?.text()?.trim()?.takeIf { it.isNotEmpty() },
            genres = doc.select("#ac-genres-row a[href*=/genre/]")
                .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct(),
            status = status,
            initialized = true,
        )
    }

    // --- Chapter list ---------------------------------------------------------

    override suspend fun chapterListParse(response: Response): List<Chapter> {
        val doc = response.asJsoup()
        // `#chapters` server-renders every chapter as `.chapter-row > a.chapter-el`;
        // `data-num` is the chapter number, the inner span is its title.
        val chapters = doc.select("#chapters a.chapter-el[href]").mapNotNull { link ->
            val href = link.attr("href").trim().takeIf { it.isNotEmpty() && "/chapter-" in it }
                ?: return@mapNotNull null
            val name = link.selectFirst("span")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: link.text().trim()
            val num = link.parent()?.attr("data-num")?.toFloatOrNull() ?: -1f
            Chapter(url = absUrl(href), name = name, chapterNumber = num)
        }.distinctBy { it.url }
        // The list renders newest-first; the reader expects ascending order.
        val ordered = if (chapters.any { it.chapterNumber >= 0f }) {
            chapters.sortedBy { it.chapterNumber }
        } else {
            chapters.reversed()
        }
        return ordered.mapIndexed { i, ch ->
            if (ch.chapterNumber >= 0f) ch else ch.copy(chapterNumber = (i + 1).toFloat())
        }
    }

    // --- Chapter content ------------------------------------------------------

    override suspend fun pageListParse(response: Response): List<NovelPage> {
        val doc = response.asJsoup()
        val body = doc.selectFirst("#ac-r-body, .ac-r-body")
            ?: throw IOException("Azure Chronicles: couldn't find the chapter content.")
        // Drop the rendered chapter-title heading; keep the prose paragraphs.
        body.select("h1.ac-r-chapter-name, h1, script, style, .ac-r-ads, [class*=unlock]").remove()
        val paragraphs = body.select("p").mapNotNull { p ->
            val text = p.text().trim()
            if (text.isEmpty()) null else p to text
        }
        if (paragraphs.isEmpty()) {
            // No prose: a premium/locked chapter renders an unlock prompt instead.
            throw IOException(
                "Azure Chronicles: this chapter has no readable text — it may be a " +
                    "locked premium chapter that requires unlocking on the site.",
            )
        }
        return paragraphs.mapIndexed { i, (el, text) ->
            NovelPage(index = i, text = text, formattedText = el.richHtml().takeIf { it != text })
        }
    }

    // --- Helpers --------------------------------------------------------------

    private fun absUrl(href: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> "$baseUrl$href"
        else -> "$baseUrl/$href"
    }

    /** Pulls the URL out of an inline `background-image:url('…')` style. */
    private fun bgImage(el: Element): String? =
        Regex("""background-image\s*:\s*url\((['"]?)(.*?)\1\)""")
            .find(el.attr("style"))?.groupValues?.get(2)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { absUrl(it) }

    private fun Element.imageUrl(): String? {
        val raw = listOf("data-src", "data-original", "src")
            .map { attr(it).trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        return absUrl(raw)
    }

    /** Maps a status word found anywhere in [text] to a [NovelStatus], or null. */
    private fun statusWord(text: String): NovelStatus? = when {
        text.contains("Completed", ignoreCase = true) -> NovelStatus.COMPLETED
        text.contains("Hiatus", ignoreCase = true) -> NovelStatus.HIATUS
        text.contains("Ongoing", ignoreCase = true) -> NovelStatus.ONGOING
        else -> null
    }

    private fun statusOf(slug: String): NovelStatus =
        statusWord(slug) ?: NovelStatus.UNKNOWN

    private fun Response.asJsoup(): Document =
        Jsoup.parse(body!!.string(), request.url.toString())

    companion object {
        private const val PAGE_MARKER = "acpage"
    }
}
