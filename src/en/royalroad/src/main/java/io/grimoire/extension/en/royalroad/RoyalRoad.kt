package io.grimoire.extension.en.royalroad

import io.grimoire.api.model.Chapter
import io.grimoire.api.model.Filter
import io.grimoire.api.model.Novel
import io.grimoire.api.model.NovelPage
import io.grimoire.api.model.NovelStatus
import io.grimoire.api.network.HttpSource
import io.grimoire.api.network.richDescription
import io.grimoire.api.network.richHtml
import io.grimoire.api.source.SourceInfo
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Instant

@SourceInfo(
    id = 12L,
    name = "Royal Road",
    lang = "en",
    baseUrl = "https://www.royalroad.com",
    versionCode = 2,
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

    // The advanced-search endpoint accepts a free-text title together with the
    // full filter set, so search + filters compose in a single request.
    override val supportsSearchWithFilters: Boolean = true

    override fun searchNovelsRequest(query: String, page: Int, filters: List<Filter<*>>): Request {
        val url = "$baseUrl/fictions/search".toHttpUrl().newBuilder()
        url.addQueryParameter("globalFilters", "false")
        if (query.isNotBlank()) url.addQueryParameter("title", query.trim())

        filters.forEach { filter ->
            when (filter) {
                is StatusFilter -> STATUS.getOrNull(filter.state)
                    ?.takeIf { it.param != "ALL" }
                    ?.let { url.addQueryParameter("status", it.param) }

                is TypeFilter -> TYPE.getOrNull(filter.state)
                    ?.takeIf { it.param != "ALL" }
                    ?.let { url.addQueryParameter("type", it.param) }

                is SortByFilter -> ORDER_BY.getOrNull(filter.state)
                    ?.let { url.addQueryParameter("orderBy", it.param) }

                is OrderFilter -> url.addQueryParameter("dir", if (filter.state == 1) "asc" else "desc")

                // Tags + content warnings both submit through tagsAdd/tagsRemove;
                // a tri-state per entry maps INCLUDE -> add, EXCLUDE -> remove.
                is TagGroup -> filter.state.forEachIndexed { i, child ->
                    val value = filter.tags[i].value
                    when (child.state) {
                        Filter.TriState.STATE_INCLUDE -> url.addQueryParameter("tagsAdd", value)
                        Filter.TriState.STATE_EXCLUDE -> url.addQueryParameter("tagsRemove", value)
                    }
                }

                is Filter.Text -> {
                    val value = filter.state.trim()
                    val param = TEXT_PARAMS[filter.name]
                    if (value.isNotEmpty() && param != null) url.addQueryParameter(param, value)
                }

                else -> Unit
            }
        }

        url.addQueryParameter("page", page.toString())
        return GET(url.build().toString())
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
            description = doc.selectFirst("div.description")?.richDescription()?.takeIf { it.isNotBlank() },
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

    // Filters ----------------------------------------------------------------
    //
    // Royal Road's tag/status/sort taxonomy is a fixed set baked into the search
    // form, so it's hardcoded here (no dynamic fetch / "load filters" gate). Tags
    // and content warnings are tri-state (include -> tagsAdd, exclude -> tagsRemove).

    override fun getFilterList(): List<Filter<*>> = listOf(
        SortByFilter(),
        OrderFilter(),
        StatusFilter(),
        TypeFilter(),
        TagGroup("Genres & Tags", TAGS),
        TagGroup("Content Warnings", CONTENT_WARNINGS),
        Filter.Separator(),
        // Free-text refinements grouped at the bottom so they don't read like
        // three stacked search bars above the categorical filters.
        Filter.Header("Advanced"),
        Filter.Text(KEYWORD),
        Filter.Text(AUTHOR),
        Filter.Text(MIN_PAGES),
        Filter.Text(MAX_PAGES),
        Filter.Text(MIN_RATING),
        Filter.Text(MAX_RATING),
    )

    internal data class Tag(val value: String, val label: String)

    private class StatusFilter : Filter.Select<String>("Status", STATUS.labels())
    private class TypeFilter : Filter.Select<String>("Type", TYPE.labels())
    // Sort is two compact dropdowns rather than Filter.Sort, whose always-expanded
    // 10-row radio list would dominate the sheet and dwarf the other controls.
    private class SortByFilter : Filter.Select<String>("Sort by", ORDER_BY.labels())
    private class OrderFilter : Filter.Select<String>("Order", arrayOf("Descending", "Ascending"))

    /** A tri-state group; child `state` order lines up with [tags] by index. */
    internal class TagGroup(name: String, val tags: List<Tag>) :
        Filter.Group<Filter.TriState>(name, tags.map { Filter.TriState(it.label) })

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

// ---- Royal Road filter taxonomy (static; mirrors /fictions/search) ----------

private data class Opt(val param: String, val label: String)

private fun List<Opt>.labels(): Array<String> = map { it.label }.toTypedArray()

// Free-text filter labels and the search params they map to.
private const val KEYWORD = "Keyword (searches content)"
private const val AUTHOR = "Author"
private const val MIN_PAGES = "Min pages"
private const val MAX_PAGES = "Max pages"
private const val MIN_RATING = "Min rating (0.5–5)"
private const val MAX_RATING = "Max rating (0.5–5)"

private val TEXT_PARAMS = mapOf(
    KEYWORD to "keyword",
    AUTHOR to "author",
    MIN_PAGES to "minPages",
    MAX_PAGES to "maxPages",
    MIN_RATING to "minRating",
    MAX_RATING to "maxRating",
)

// First entry ("All"/index 0) is the no-op default; param "ALL" is dropped.
private val STATUS = listOf(
    Opt("ALL", "All"),
    Opt("ONGOING", "Ongoing"),
    Opt("HIATUS", "Hiatus"),
    Opt("COMPLETED", "Completed"),
    Opt("DROPPED", "Dropped"),
    Opt("STUB", "Stub"),
    Opt("INACTIVE", "Inactive"),
)

private val TYPE = listOf(
    Opt("ALL", "All"),
    Opt("fanfiction", "Fan Fiction"),
    Opt("original", "Original"),
)

private val ORDER_BY = listOf(
    Opt("relevance", "Relevance"),
    Opt("popularity", "Popularity"),
    Opt("rating", "Average Rating"),
    Opt("last_update", "Last Update"),
    Opt("release_date", "Release Date"),
    Opt("followers", "Followers"),
    Opt("length", "Number of Pages"),
    Opt("views", "Views"),
    Opt("title", "Title"),
    Opt("author", "Author"),
)

private val CONTENT_WARNINGS = listOf(
    RoyalRoad.Tag("profanity", "Profanity"),
    RoyalRoad.Tag("sexuality", "Sexual Content"),
    RoyalRoad.Tag("graphic_violence", "Graphic Violence"),
    RoyalRoad.Tag("sensitive", "Sensitive Content"),
    RoyalRoad.Tag("ai_assisted", "AI-Assisted Content"),
    RoyalRoad.Tag("ai_generated", "AI-Generated Content"),
)

// The full genre/tag list from the advanced-search form. Stable enough to
// hardcode; a new tag is a one-line addition + version bump.
private val TAGS = listOf(
    RoyalRoad.Tag("anti-hero_lead", "Anti-Hero Lead"),
    RoyalRoad.Tag("antivillain_lead", "Anti-Villain Lead"),
    RoyalRoad.Tag("apocalypse", "Apocalypse"),
    RoyalRoad.Tag("artificial_intelligence", "Artificial Intelligence"),
    RoyalRoad.Tag("attractive_lead", "Attractive Lead"),
    RoyalRoad.Tag("chivalry", "Chivalry"),
    RoyalRoad.Tag("competing_love", "Competing Love Interest"),
    RoyalRoad.Tag("cozy", "Cozy"),
    RoyalRoad.Tag("crafting", "Crafting"),
    RoyalRoad.Tag("cultivation", "Cultivation"),
    RoyalRoad.Tag("cyberpunk", "Cyberpunk"),
    RoyalRoad.Tag("deck_building", "Deck Building"),
    RoyalRoad.Tag("dungeon_core", "Dungeon Core"),
    RoyalRoad.Tag("dungeon_crawler", "Dungeon Crawler"),
    RoyalRoad.Tag("dystopia", "Dystopia"),
    RoyalRoad.Tag("female_lead", "Female Lead"),
    RoyalRoad.Tag("first_contact", "First Contact"),
    RoyalRoad.Tag("gamelit", "GameLit"),
    RoyalRoad.Tag("gender_bender", "Gender Bender"),
    RoyalRoad.Tag("genetically_engineered", "Genetically Engineered"),
    RoyalRoad.Tag("grimdark", "Grimdark"),
    RoyalRoad.Tag("hard_sci-fi", "Hard Sci-fi"),
    RoyalRoad.Tag("high_fantasy", "High Fantasy"),
    RoyalRoad.Tag("kingdom_building", "Kingdom Building"),
    RoyalRoad.Tag("lesbian_romance", "Lesbian Romance"),
    RoyalRoad.Tag("litrpg", "LitRPG"),
    RoyalRoad.Tag("local_protagonist", "Local Protagonist"),
    RoyalRoad.Tag("low_fantasy", "Low Fantasy"),
    RoyalRoad.Tag("magic", "Magic"),
    RoyalRoad.Tag("magical_girl", "Magical Girl"),
    RoyalRoad.Tag("magitech", "Magitech"),
    RoyalRoad.Tag("gay_romance", "Male Gay Romance"),
    RoyalRoad.Tag("male_lead", "Male Lead"),
    RoyalRoad.Tag("martial_arts", "Martial Arts"),
    RoyalRoad.Tag("mecha", "Mecha"),
    RoyalRoad.Tag("modern_knowledge", "Modern Knowledge"),
    RoyalRoad.Tag("monster_evolution", "Monster Evolution"),
    RoyalRoad.Tag("multiple_lead", "Multiple Lead Characters"),
    RoyalRoad.Tag("harem", "Multiple Lovers"),
    RoyalRoad.Tag("mythos", "Mythos"),
    RoyalRoad.Tag("non-human_lead", "Non-Human Lead"),
    RoyalRoad.Tag("nonhumanoid_lead", "Non-Humanoid Lead"),
    RoyalRoad.Tag("otome", "Otome"),
    RoyalRoad.Tag("summoned_hero", "Portal Fantasy / Isekai"),
    RoyalRoad.Tag("post_apocalyptic", "Post Apocalyptic"),
    RoyalRoad.Tag("progression", "Progression"),
    RoyalRoad.Tag("reader_interactive", "Reader Interactive"),
    RoyalRoad.Tag("reincarnation", "Reincarnation"),
    RoyalRoad.Tag("romance", "Romance Subplot"),
    RoyalRoad.Tag("ruling_class", "Ruling Class"),
    RoyalRoad.Tag("school_life", "School Life"),
    RoyalRoad.Tag("secret_identity", "Secret Identity"),
    RoyalRoad.Tag("slice_of_life", "Slice of Life"),
    RoyalRoad.Tag("soft_sci-fi", "Soft Sci-fi"),
    RoyalRoad.Tag("space_opera", "Space Opera"),
    RoyalRoad.Tag("sports", "Sports"),
    RoyalRoad.Tag("steampunk", "Steampunk"),
    RoyalRoad.Tag("strategy", "Strategy"),
    RoyalRoad.Tag("strong_lead", "Strong Lead"),
    RoyalRoad.Tag("super_heroes", "Super Heroes"),
    RoyalRoad.Tag("supernatural", "Supernatural"),
    RoyalRoad.Tag("survival", "Survival"),
    RoyalRoad.Tag("system_invasion", "System Invasion"),
    RoyalRoad.Tag("technologically_engineered", "Technologically Engineered"),
    RoyalRoad.Tag("loop", "Time Loop"),
    RoyalRoad.Tag("time_travel", "Time Travel"),
    RoyalRoad.Tag("tower", "Tower"),
    RoyalRoad.Tag("urban_fantasy", "Urban Fantasy"),
    RoyalRoad.Tag("villainous_lead", "Villainous Lead"),
    RoyalRoad.Tag("virtual_reality", "Virtual Reality"),
    RoyalRoad.Tag("war_and_military", "War and Military"),
    RoyalRoad.Tag("wuxia", "Wuxia"),
)
