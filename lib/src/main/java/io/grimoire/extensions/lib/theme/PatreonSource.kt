package io.grimoire.extensions.lib.theme

import android.webkit.CookieManager
import io.grimoire.api.model.novel.Chapter
import io.grimoire.api.model.novel.Novel
import io.grimoire.api.model.novel.NovelPage
import io.grimoire.api.model.novel.NovelStatus
import io.grimoire.api.model.novel.PageContent
import io.grimoire.api.source.feature.LatestSource
import io.grimoire.api.source.feature.PopularSource
import io.grimoire.api.source.feature.SearchSource
import io.grimoire.api.source.feature.WebViewLoginSource
import io.grimoire.api.source.http.HttpSource
import io.grimoire.api.source.web.ChapterListSource
import io.grimoire.api.source.web.PageListSource
import io.grimoire.api.model.filter.Filter
import io.grimoire.api.util.richHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
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
 * Patreon's public JSON:API (`/api/...`). A concrete source supplies [campaignId]
 * plus its `name` and `lang`.
 *
 * Content model: a **Novel** is a Patreon collection, a **Chapter** is a post, and
 * page content is a post body (pre-rendered HTML when served, else the ProseMirror
 * document). Most posts are paywalled — [WebViewLoginSource] signs in so the
 * `session_id` cookie replays on the API calls.
 */
abstract class PatreonSource :
    HttpSource(),
    PopularSource,
    LatestSource,
    SearchSource,
    ChapterListSource,
    PageListSource,
    WebViewLoginSource {

    /** Patreon numeric campaign id backing this creator (e.g. "13760222"). */
    protected abstract val campaignId: String

    override val baseUrl: String get() = "https://www.patreon.com"

    // --- Listings: all read the same single collections response, ordered/filtered client-side.

    override suspend fun getPopularNovels(page: Int): List<Novel> = withContext(Dispatchers.IO) {
        if (page != 1) emptyList()
        else collections().sortedByDescending { it.numPosts }.map { it.toNovel() }
    }

    override suspend fun getLatestUpdates(page: Int): List<Novel> = withContext(Dispatchers.IO) {
        if (page != 1) emptyList()
        else collections().sortedByDescending { it.editedAt }.map { it.toNovel() }
    }

    override suspend fun searchNovels(query: String, page: Int, filters: List<Filter<*>>): List<Novel> =
        withContext(Dispatchers.IO) {
            if (page != 1) {
                emptyList()
            } else {
                val q = query.trim()
                collections()
                    .filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) }
                    .map { it.toNovel() }
            }
        }

    override suspend fun getNovelDetails(novel: Novel): Novel = withContext(Dispatchers.IO) {
        val wantId = collectionId(novel.url)
        collections().firstOrNull { it.id == wantId }?.toNovel()?.copy(initialized = true)
            ?: throw IOException("Patreon: collection $wantId not found in this campaign.")
    }

    override suspend fun getChapterList(novel: Novel): List<Chapter> = withContext(Dispatchers.IO) {
        val posts = mutableListOf<JSONObject>()
        var root = jsonBody(get(chapterListUrl(novel)))
        var guard = 0
        while (true) {
            root.optJSONArray("data")?.objects()?.let { posts.addAll(it) }
            val next = root.optJSONObject("links")?.optString("next")?.takeIf { it.isNotEmpty() } ?: break
            if (++guard > MAX_PAGES) break
            root = jsonBody(get(next))
        }
        // sort=published_at lists oldest-first, i.e. the natural reading order.
        posts.mapIndexedNotNull { i, post ->
            val id = post.optString("id").trim().takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val attrs = post.optJSONObject("attributes") ?: JSONObject()
            Chapter(
                url = contentUrl(id),
                name = attrs.optString("title").trim().ifEmpty { "Post $id" },
                uploadDate = parseDate(attrs.optString("published_at")),
                chapterNumber = (i + 1).toFloat(),
                locked = !attrs.optBoolean("current_user_can_view", false),
            )
        }
    }

    override suspend fun getPageList(chapter: Chapter): List<NovelPage> = withContext(Dispatchers.IO) {
        val attrs = jsonBody(get(chapter.url)).optJSONObject("data")?.optJSONObject("attributes")
            ?: throw IOException("Patreon: unexpected post response.")
        val canView = attrs.optBoolean("current_user_can_view", false)

        val html = attrs.optString("content").takeIf { it.isNotBlank() && it != "null" }
        val pages = if (html != null) parseHtml(html) else parseProseMirror(attrs.optString("content_json_string"))

        if (pages.isEmpty()) {
            throw IOException(
                if (!canView) "Patreon: this chapter is locked — sign in with an account that can view it."
                else "Patreon: this chapter has no readable text.",
            )
        }
        pages
    }

    // --- Collections fetch/parse ---------------------------------------------

    private suspend fun collections(): List<PatreonCollection> {
        val url = apiUrl("campaigns/$campaignId").addQueryParameter("include", "collections").build().toString()
        val included = jsonBody(get(url)).optJSONArray("included") ?: return emptyList()
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
        url = "$baseUrl/collection/$id",
        title = title,
        language = lang,
        thumbnailUrl = thumbnailUrl,
        description = description,
        status = statusFromTitle(title),
    )

    private fun chapterListUrl(novel: Novel): String =
        apiUrl("posts")
            .addQueryParameter("filter[campaign_id]", campaignId)
            .addQueryParameter("filter[collection_id]", collectionId(novel.url))
            .addQueryParameter("sort", "published_at")
            .addQueryParameter("fields[post]", "title,published_at,url,current_user_can_view")
            .addQueryParameter("page[count]", PAGE_SIZE.toString())
            .build().toString()

    // --- Page content parsing -------------------------------------------------

    private fun parseHtml(html: String): List<NovelPage> {
        val body = Jsoup.parseBodyFragment(html, baseUrl).body()
        body.select("script, style").remove()
        return body.select("p")
            .filter { it.text().trim().isNotEmpty() }
            .mapIndexed { i, p ->
                val text = p.text()
                NovelPage(i, PageContent.Text(text, p.richHtml().takeIf { it != text }))
            }
    }

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
            pages.add(NovelPage(pages.size, PageContent.Text(text, html.takeIf { it != text })))
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
    override val loginSuccessUrl: String get() = "$baseUrl/home"

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
