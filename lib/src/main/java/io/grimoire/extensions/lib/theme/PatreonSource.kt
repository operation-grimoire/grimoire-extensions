package io.grimoire.extensions.lib.theme

import android.webkit.CookieManager
import io.grimoire.api.model.Chapter
import io.grimoire.api.model.Filter
import io.grimoire.api.model.Novel
import io.grimoire.api.model.NovelPage
import io.grimoire.api.model.NovelStatus
import io.grimoire.api.network.HttpSource
import io.grimoire.api.network.richHtml
import io.grimoire.api.source.WebViewLoginSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Base class for a single Patreon creator (campaign), read entirely through
 * Patreon's public JSON:API (`/api/...`). Every creator shares the identical
 * platform, so a concrete source only supplies [campaignId] plus its `id`,
 * `name` and `lang`.
 *
 * The content model maps onto Patreon as:
 *
 *  - **Novel** = a Patreon **collection** (the creator's per-series grouping).
 *    `GET /api/campaigns/{id}?include=collections` returns every collection in
 *    one response (title, post count, description, cover), so browsing/searching
 *    needs no pagination — the whole catalogue arrives at once and is filtered
 *    or re-sorted client-side.
 *  - **Chapter** = a **post** in that collection.
 *    `GET /api/posts?filter[campaign_id]=&filter[collection_id]=&sort=published_at`
 *    lists them oldest-first; the endpoint is cursor-paginated, so
 *    [chapterListParse] follows `links.next` until the collection is exhausted.
 *  - **Page content** = a post's body. Patreon delivers it as a ProseMirror
 *    document in `content_json_string` (and, for some sessions, pre-rendered
 *    HTML in `content`); [pageListParse] prefers the HTML and falls back to
 *    converting the ProseMirror nodes.
 *
 * Most posts are paywalled: an anonymous request sees a post's metadata but its
 * body only materialises when `current_user_can_view` is true, which requires a
 * signed-in (free- or paid-tier) Patreon session. Login is delegated to a
 * WebView ([WebViewLoginSource]); the resulting `session_id` cookie (and any
 * Cloudflare clearance) replays on the API calls via the shared cookie jar so
 * viewable chapters read.
 */
abstract class PatreonSource : HttpSource(), WebViewLoginSource {

    /** Patreon numeric campaign id backing this creator (e.g. "13760222"). */
    protected abstract val campaignId: String

    override val baseUrl: String get() = "https://www.patreon.com"

    // --- Listings -------------------------------------------------------------
    //
    // Popular, Latest and Search all read the same single collections response;
    // they differ only in client-side ordering/filtering. The requested page is
    // carried in the URL fragment (never sent to the server) so the parser can
    // end pagination after the first — the catalogue is un-paginated.

    override fun popularNovelsRequest(page: Int): Request = collectionsRequest(page)

    override suspend fun popularNovelsParse(response: Response): List<Novel> =
        collectionsPage(response) { sortedByDescending { it.numPosts } }

    override fun latestUpdatesRequest(page: Int): Request = collectionsRequest(page)

    override suspend fun latestUpdatesParse(response: Response): List<Novel> =
        collectionsPage(response) { sortedByDescending { it.editedAt } }

    override fun searchNovelsRequest(query: String, page: Int, filters: List<Filter<*>>): Request =
        collectionsRequest(page, query)

    override suspend fun searchNovelsParse(response: Response): List<Novel> {
        val q = response.request.url.queryParameter(QUERY_MARKER).orEmpty().trim()
        return collectionsPage(response) {
            if (q.isEmpty()) this else filter { it.title.contains(q, ignoreCase = true) }
        }
    }

    override fun getFilterList(): List<Filter<*>> = emptyList()

    // `include=collections` on the campaign returns the full collection set in
    // `included`. QUERY_MARKER (a harmless extra query param Patreon ignores)
    // carries the search text; the fragment carries the page so parsing can stop.
    private fun collectionsRequest(page: Int, query: String? = null): Request {
        val b = apiUrl("campaigns/$campaignId").addQueryParameter("include", "collections")
        if (!query.isNullOrBlank()) b.addQueryParameter(QUERY_MARKER, query)
        return GET(b.build().toString() + "#p=$page")
    }

    // Applies [order] to the parsed collections, but only on the first page —
    // later pages return empty so the host stops requesting more.
    private inline fun collectionsPage(
        response: Response,
        order: List<PatreonCollection>.() -> List<PatreonCollection>,
    ): List<Novel> {
        if ((response.request.url.fragment ?: "p=1").substringAfter("p=") != "1") {
            response.close()
            return emptyList()
        }
        return parseCollections(response).order().map { it.toNovel() }
    }

    private fun parseCollections(response: Response): List<PatreonCollection> {
        val included = jsonBody(response).optJSONArray("included") ?: return emptyList()
        return included.objects()
            .filter { it.optString("type") == "collection" }
            .mapNotNull { obj ->
                val id = obj.optString("id").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val attrs = obj.optJSONObject("attributes") ?: JSONObject()
                PatreonCollection(
                    id = id,
                    title = attrs.optString("title").trim().ifEmpty { "Untitled" },
                    numPosts = attrs.optInt("num_posts"),
                    description = attrs.optString("description").trim().takeIf { it.isNotEmpty() },
                    thumbnailUrl = attrs.optJSONObject("thumbnail")?.optString("url")?.takeIf { it.isNotEmpty() },
                    editedAt = parseDate(attrs.optString("edited_at").ifEmpty { attrs.optString("created_at") }),
                )
            }
    }

    private data class PatreonCollection(
        val id: String,
        val title: String,
        val numPosts: Int,
        val description: String?,
        val thumbnailUrl: String?,
        val editedAt: Long,
    )

    private fun PatreonCollection.toNovel() = Novel(
        // A collection has no canonical web URL we need to fetch; encode the id
        // so novelDetails/chapterList can rebuild the API calls. See collectionId().
        url = "$baseUrl/collection/$id",
        title = title,
        thumbnailUrl = thumbnailUrl,
        description = description,
        status = statusFromTitle(title),
    )

    // --- Novel details --------------------------------------------------------
    //
    // Details come from the same collections response (cheap, single request);
    // the target id rides along in the request fragment.

    override fun novelDetailsRequest(novel: Novel): Request =
        GET(apiUrl("campaigns/$campaignId").addQueryParameter("include", "collections")
            .build().toString() + "#c=${collectionId(novel.url)}")

    override suspend fun novelDetailsParse(response: Response): Novel {
        val wantId = (response.request.url.fragment ?: "").substringAfter("c=", "")
        val match = parseCollections(response).firstOrNull { it.id == wantId }
            ?: throw IOException("Patreon: collection $wantId not found in this campaign.")
        return match.toNovel().copy(initialized = true)
    }

    // --- Chapter list ---------------------------------------------------------

    override fun chapterListRequest(novel: Novel): Request {
        val url = apiUrl("posts")
            .addQueryParameter("filter[campaign_id]", campaignId)
            .addQueryParameter("filter[collection_id]", collectionId(novel.url))
            .addQueryParameter("sort", "published_at")
            .addQueryParameter("fields[post]", "title,published_at,url,current_user_can_view")
            .addQueryParameter("page[count]", PAGE_SIZE.toString())
            .build()
        return GET(url.toString())
    }

    override suspend fun chapterListParse(response: Response): List<Chapter> {
        val posts = mutableListOf<JSONObject>()
        var root = jsonBody(response)
        var guard = 0
        while (true) {
            root.optJSONArray("data")?.objects()?.let { posts.addAll(it) }
            val next = root.optJSONObject("links")?.optString("next")?.takeIf { it.isNotEmpty() }
                ?: break
            if (++guard > MAX_PAGES) break
            root = withContext(Dispatchers.IO) {
                client.newCall(GET(next)).execute().use { jsonBody(it) }
            }
        }
        // sort=published_at lists oldest-first, i.e. the natural reading order.
        return posts.mapIndexedNotNull { i, post ->
            val id = post.optString("id").trim().takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val attrs = post.optJSONObject("attributes") ?: JSONObject()
            val title = attrs.optString("title").trim().ifEmpty { "Post $id" }
            Chapter(
                url = contentUrl(id),
                name = title,
                uploadDate = parseDate(attrs.optString("published_at")),
                chapterNumber = (i + 1).toFloat(),
                locked = !attrs.optBoolean("current_user_can_view", false),
            )
        }
    }

    // --- Chapter content ------------------------------------------------------

    override suspend fun pageListParse(response: Response): List<NovelPage> {
        val attrs = jsonBody(response).optJSONObject("data")?.optJSONObject("attributes")
            ?: throw IOException("Patreon: unexpected post response.")
        val canView = attrs.optBoolean("current_user_can_view", false)

        // Prefer pre-rendered HTML when the session is served it; otherwise build
        // the body from the ProseMirror document Patreon ships in every response.
        val html = attrs.optString("content").takeIf { it.isNotBlank() && it != "null" }
        val pages = if (html != null) parseHtml(html) else parseProseMirror(attrs.optString("content_json_string"))

        if (pages.isEmpty()) {
            throw IOException(
                if (!canView) {
                    "Patreon: this chapter is locked — sign in with an account that can view it."
                } else {
                    "Patreon: this chapter has no readable text."
                },
            )
        }
        return pages
    }

    private fun parseHtml(html: String): List<NovelPage> {
        val body = Jsoup.parseBodyFragment(html, baseUrl).body()
        body.select("script, style").remove()
        return body.select("p").mapNotNull { p ->
            val text = p.text().trim()
            if (text.isEmpty()) null else p
        }.mapIndexed { i, p ->
            val text = p.text()
            NovelPage(index = i, text = text, formattedText = p.richHtml().takeIf { it != text })
        }
    }

    // ProseMirror doc: doc -> paragraph -> (text | hardBreak). Text nodes carry
    // inline `marks` (bold/italic/underline/link); render those into the limited
    // HTML subset the reader supports, and drop wholly empty paragraphs.
    private fun parseProseMirror(raw: String): List<NovelPage> {
        if (raw.isBlank() || raw == "null") return emptyList()
        val doc = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val blocks = doc.optJSONArray("content") ?: return emptyList()
        val pages = mutableListOf<NovelPage>()
        for (block in blocks.objects()) {
            val plain = StringBuilder()
            val rich = StringBuilder()
            renderInline(block.optJSONArray("content"), plain, rich)
            val text = plain.toString().trim()
            if (text.isEmpty()) continue
            val html = rich.toString().trim()
            pages.add(
                NovelPage(
                    index = pages.size,
                    text = text,
                    formattedText = html.takeIf { it != text },
                ),
            )
        }
        return pages
    }

    private fun renderInline(nodes: JSONArray?, plain: StringBuilder, rich: StringBuilder) {
        nodes ?: return
        for (node in nodes.objects()) {
            when (node.optString("type")) {
                "hardBreak" -> {
                    plain.append('\n')
                    rich.append("<br>")
                }
                "text" -> {
                    val text = node.optString("text")
                    plain.append(text)
                    rich.append(applyMarks(escapeHtml(text), node.optJSONArray("marks")))
                }
                else -> renderInline(node.optJSONArray("content"), plain, rich)
            }
        }
    }

    private fun applyMarks(escaped: String, marks: JSONArray?): String {
        if (marks == null || marks.length() == 0) return escaped
        var out = escaped
        for (mark in marks.objects()) {
            out = when (mark.optString("type")) {
                "bold", "strong" -> "<b>$out</b>"
                "italic", "em" -> "<i>$out</i>"
                "underline" -> "<u>$out</u>"
                "link" -> {
                    val href = mark.optJSONObject("attrs")?.optString("href").orEmpty()
                    if (href.isEmpty()) out else "<a href=\"${escapeHtml(href)}\">$out</a>"
                }
                else -> out
            }
        }
        return out
    }

    // --- WebViewLoginSource ---------------------------------------------------

    override val loginUrl: String get() = "$baseUrl/login"

    // A successful sign-in redirects to the logged-in home feed. Match that
    // rather than baseUrl itself — baseUrl is a substring of the /login page, so
    // the host could otherwise treat login as complete the instant it opens.
    override val loginSuccessUrl: String get() = "$baseUrl/home"

    // Patreon sets `session_id` only once authenticated — an anonymous visit
    // sets just Cloudflare cookies (__cf_bm / _cfuvid).
    override suspend fun isLoggedIn(): Boolean = withContext(Dispatchers.IO) {
        CookieManager.getInstance().getCookie(baseUrl).orEmpty()
            .split(';').map { it.trim() }
            .any { it.startsWith("session_id", ignoreCase = true) }
    }

    override suspend fun logout(): Unit = withContext(Dispatchers.IO) {
        val cm = CookieManager.getInstance()
        cm.getCookie(baseUrl).orEmpty()
            .split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
            .forEach { cm.setCookie(baseUrl, "$it=; Max-Age=0; Path=/") }
        cm.flush()
    }

    // --- Helpers --------------------------------------------------------------

    private fun apiUrl(path: String): HttpUrl.Builder =
        "$baseUrl/api/$path".toHttpUrl().newBuilder()
            .addQueryParameter("json-api-version", JSON_API_VERSION)

    private fun contentUrl(postId: String): String =
        apiUrl("posts/$postId")
            .addQueryParameter("fields[post]", "content,content_json_string,current_user_can_view")
            .build().toString()

    private fun collectionId(novelUrl: String): String =
        novelUrl.substringAfterLast("/collection/").substringBefore('?').substringBefore('#').trim()

    private fun jsonBody(response: Response): JSONObject =
        response.use { JSONObject(it.body?.string().orEmpty().ifBlank { "{}" }) }

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }

    private fun statusFromTitle(title: String): NovelStatus = when {
        title.contains("complete", ignoreCase = true) -> NovelStatus.COMPLETED
        title.contains("drop", ignoreCase = true) -> NovelStatus.CANCELLED
        title.contains("hiatus", ignoreCase = true) -> NovelStatus.HIATUS
        else -> NovelStatus.UNKNOWN
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // Patreon timestamps are ISO-8601 with an offset, e.g.
    // "2026-06-01T18:12:33.000+00:00". Parse leniently; 0L when absent/unknown.
    private fun parseDate(raw: String): Long {
        val value = raw.trim().takeIf { it.isNotEmpty() && it != "null" } ?: return 0L
        for (pattern in DATE_PATTERNS) {
            runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { timeZone = UTC }.parse(value)?.time
            }.getOrNull()?.let { return it }
        }
        return 0L
    }

    private companion object {
        const val JSON_API_VERSION = "1.0"
        const val QUERY_MARKER = "q"
        const val PAGE_SIZE = 40
        const val MAX_PAGES = 500

        val UTC: TimeZone = TimeZone.getTimeZone("UTC")
        val DATE_PATTERNS = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )
    }
}
