package io.grimoire.extension.en.royalroad

import io.grimoire.api.model.Chapter
import io.grimoire.api.model.Filter
import io.grimoire.api.model.Novel
import io.grimoire.api.model.NovelPage
import io.grimoire.api.model.NovelStatus
import io.grimoire.api.network.HttpSource
import io.grimoire.api.network.richHtml
import io.grimoire.api.source.SourceInfo
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.time.Instant

@SourceInfo(
    id = 12L,
    name = "Royal Road",
    lang = "en",
    baseUrl = "https://www.royalroad.com",
    versionCode = 1,
    novelUpdatesGroups = ["Royal Road"],
)
class RoyalRoad : HttpSource() {

    override val id = 12L
    override val name = "Royal Road"
    override val lang = "en"
    override val baseUrl = "https://www.royalroad.com"

    // Listings ---------------------------------------------------------------
    //
    // Royal Road has no single "popular" ordering; best-rated is the closest
    // stable proxy and matches what the site surfaces as its top fictions.

    override fun popularNovelsRequest(page: Int): Request =
        GET("$baseUrl/fictions/best-rated?page=$page")

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/fictions/latest-updates?page=$page")

    override fun searchNovelsRequest(query: String, page: Int, filters: List<Filter<*>>): Request {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$baseUrl/fictions/search?title=$encoded&page=$page")
    }

    // best-rated / latest-updates / search all render the same fiction-list markup.
    override suspend fun popularNovelsParse(response: Response): List<Novel> = parseFictionList(response)
    override suspend fun latestUpdatesParse(response: Response): List<Novel> = parseFictionList(response)
    override suspend fun searchNovelsParse(response: Response): List<Novel> = parseFictionList(response)

    internal fun parseFictionList(response: Response): List<Novel> =
        parseFictionListDocument(response.asJsoup())

    internal fun parseFictionListDocument(doc: Document): List<Novel> =
        doc.select("div.fiction-list-item").mapNotNull { item ->
            val link = item.selectFirst("h2.fiction-title a") ?: return@mapNotNull null
            Novel(
                url = link.attr("href"),
                title = link.text().trim(),
                thumbnailUrl = item.selectFirst("figure img")?.absUrl("src").cleanCover(),
            )
        }

    // Novel details ----------------------------------------------------------

    override suspend fun novelDetailsParse(response: Response): Novel =
        novelDetailsFromDocument(response.asJsoup(), response.request.url.toString())

    internal fun novelDetailsFromDocument(doc: Document, url: String): Novel {
        val statusText = doc.select("div.fiction-info span.label").joinToString(" ") { it.text() }
        val (rating, ratingCount) = parseAggregateRating(doc)
        return Novel(
            url = url,
            title = doc.selectFirst("div.fic-header h1")?.text()?.trim().orEmpty(),
            thumbnailUrl = doc.selectFirst("div.cover-art-container img")?.absUrl("src").cleanCover(),
            author = doc.selectFirst("div.fic-header h4 a")?.text()?.trim()?.takeIf { it.isNotEmpty() },
            description = doc.selectFirst("div.description")?.text()?.trim()?.takeIf { it.isNotEmpty() },
            genres = doc.select("a.fiction-tag").map { it.text().trim() }.filter { it.isNotEmpty() },
            status = statusText.toNovelStatus(),
            rating = rating,
            ratingCount = ratingCount,
            initialized = true,
        )
    }

    /** Pulls the overall rating (already on a 0..5 scale) from the page's schema.org JSON-LD. */
    private fun parseAggregateRating(doc: Document): Pair<Float?, Int?> {
        val ld = doc.selectFirst("script[type=application/ld+json]")?.data() ?: return null to null
        return runCatching {
            val agg = JSONObject(ld).optJSONObject("aggregateRating") ?: return null to null
            val value = agg.optDouble("ratingValue", Double.NaN)
                .takeIf { !it.isNaN() && it > 0 }?.toFloat()
            val count = agg.optInt("ratingCount", 0).takeIf { it > 0 }
            value to count
        }.getOrDefault(null to null)
    }

    // Chapter list -----------------------------------------------------------
    //
    // The fiction page embeds the full chapter list as a `window.chapters = [...]`
    // JSON array — cleaner and more complete than scraping the (paginated) table,
    // and it carries per-chapter ISO upload dates and the locked/`isUnlocked` flag.

    override suspend fun chapterListParse(response: Response): List<Chapter> =
        chaptersFromHtml(response.body!!.string())

    internal fun chaptersFromHtml(html: String): List<Chapter> {
        val json = CHAPTERS_ARRAY.find(html)?.groupValues?.get(1) ?: return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val ch = arr.getJSONObject(i)
            Chapter(
                url = ch.getString("url"),
                name = ch.optString("title").trim(),
                uploadDate = ch.optString("date").toEpochMillis(),
                // `order` is Royal Road's own 0-based display sequence; +1 gives the
                // host a stable monotonic number to order and track reading position by.
                chapterNumber = (ch.optInt("order", i) + 1).toFloat(),
                // `isUnlocked` is absent/true for free chapters and false for ones
                // gated behind the author's paid subscription tiers.
                locked = !ch.optBoolean("isUnlocked", true),
            )
        }
    }

    // Chapter content --------------------------------------------------------

    override suspend fun pageListParse(response: Response): List<NovelPage> =
        pagesFromDocument(response.asJsoup())

    internal fun pagesFromDocument(doc: Document): List<NovelPage> {
        val content = doc.selectFirst("div.chapter-content") ?: return emptyList()
        stripHoneypots(doc, content)
        return content.select("> p, > hr").mapIndexedNotNull { index, el ->
            if (el.tagName().equals("hr", ignoreCase = true)) {
                return@mapIndexedNotNull NovelPage(index = index, text = "", isSeparator = true)
            }
            val imageUrl = el.selectFirst("img")?.imageUrl()
            if (imageUrl != null) {
                return@mapIndexedNotNull NovelPage(index = index, text = "", imageUrl = imageUrl)
            }
            val text = el.text().trim()
            if (text.isEmpty()) return@mapIndexedNotNull null
            NovelPage(index = index, text = text, formattedText = el.richHtml())
        }
    }

    /**
     * Royal Road defends against scrapers by injecting honeypot nodes — random-class
     * `<p>`/`<span>` elements carrying a theft-warning sentence, hidden from readers by
     * an inline `<style>` rule (`display: none`). Left in, their text bleeds into the
     * chapter mid-sentence. We collect every class hidden by a stylesheet rule and drop
     * those nodes from the content before extracting paragraphs.
     */
    private fun stripHoneypots(doc: Document, content: Element) {
        val hidden = buildSet {
            doc.select("style").forEach { style ->
                HIDDEN_RULE.findAll(style.data()).forEach { add(it.groupValues[1]) }
            }
        }
        hidden.forEach { cls -> content.select(".$cls").remove() }
    }

    override fun getFilterList(): List<Filter<*>> = emptyList()

    // Helpers ----------------------------------------------------------------

    private fun Response.asJsoup(): Document =
        Jsoup.parse(body!!.string(), request.url.toString())

    // Lazy-loaded chapter images park the real URL in data-src; fall back to src.
    private fun Element.imageUrl(): String? =
        sequenceOf("data-src", "src")
            .map { absUrl(it) }
            .firstOrNull { it.isNotBlank() }

    // The placeholder cover is served via the img's onError handler, but a fiction
    // genuinely without art already has nocover in `src` — treat it as "no cover".
    private fun String?.cleanCover(): String? =
        this?.takeIf { it.isNotBlank() && !it.contains("nocover", ignoreCase = true) }

    private fun String.toEpochMillis(): Long =
        runCatching { Instant.parse(this).toEpochMilli() }.getOrDefault(0L)

    private fun String.toNovelStatus(): NovelStatus = when {
        contains("Hiatus", ignoreCase = true) -> NovelStatus.HIATUS
        contains("Completed", ignoreCase = true) -> NovelStatus.COMPLETED
        contains("Dropped", ignoreCase = true) -> NovelStatus.CANCELLED
        contains("Ongoing", ignoreCase = true) -> NovelStatus.ONGOING
        contains("Stub", ignoreCase = true) -> NovelStatus.ONGOING
        else -> NovelStatus.UNKNOWN
    }

    private companion object {
        // window.chapters = [ ... ];  — captures the array literal.
        val CHAPTERS_ARRAY = Regex("""window\.chapters\s*=\s*(\[.*?])\s*;""", RegexOption.DOT_MATCHES_ALL)

        // A stylesheet rule of the form `.someClass { ... display: none ... }`.
        val HIDDEN_RULE = Regex("""\.([A-Za-z0-9_-]+)\s*\{[^}]*?display\s*:\s*none""", RegexOption.IGNORE_CASE)
    }
}
