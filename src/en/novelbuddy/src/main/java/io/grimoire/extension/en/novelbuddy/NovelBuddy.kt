package io.grimoire.extension.en.novelbuddy

import io.grimoire.api.model.Chapter
import io.grimoire.api.model.Filter
import io.grimoire.api.model.Novel
import io.grimoire.api.model.NovelPage
import io.grimoire.api.model.NovelStatus
import io.grimoire.api.network.HttpSource
import io.grimoire.api.source.SourceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup

@SourceInfo(id = 2L, name = "NovelBuddy", lang = "en", baseUrl = "https://novelbuddy.com", versionCode = 4)
class NovelBuddy : HttpSource() {

    override val id = 2L
    override val name = "NovelBuddy"
    override val lang = "en"
    override val baseUrl = "https://novelbuddy.com"

    private val apiBase = "https://api.novelbuddy.com"

    // name -> slug for the genre filter. Populated by fetchFilterOptions.
    private var loadedGenres: List<Pair<String, String>> = emptyList()

    override val hasDynamicFilters: Boolean = true

    // Popular — GET /popular?page=N (default request matches site URL)

    override suspend fun popularNovelsParse(response: Response): List<Novel> {
        val items = nextDataPageProps(response.body!!.string()).optJSONArray("items")
            ?: return emptyList()
        return (0 until items.length()).map { itemToNovel(items.getJSONObject(it)) }
    }

    // Latest — GET /latest?page=N

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/latest?page=$page")

    override suspend fun latestUpdatesParse(response: Response): List<Novel> =
        popularNovelsParse(response)

    // Search — JSON API when only a query is supplied, otherwise the site's
    // own /genres/<slug> listing page (the search API ignores genre params).

    override fun searchNovelsRequest(query: String, page: Int, filters: List<Filter<*>>): okhttp3.Request {
        val genreSlug = filters.filterIsInstance<GenreFilter>().firstOrNull()
            ?.let { it.slugs.getOrNull(it.state) }
            ?.takeIf { it.isNotEmpty() }
        return when {
            genreSlug != null -> GET("$baseUrl/genres/$genreSlug?page=$page")
            else -> GET("$apiBase/titles/search?q=${query.trim()}&page=$page&limit=24")
        }
    }

    override suspend fun searchNovelsParse(response: Response): List<Novel> {
        val body = response.body!!.string()
        // /genres/<slug> serves HTML; /titles/search returns JSON.
        return if (body.trimStart().startsWith("{")) {
            val data = JSONObject(body).optJSONObject("data") ?: return emptyList()
            val items = data.optJSONArray("items") ?: return emptyList()
            (0 until items.length()).map { itemToNovel(items.getJSONObject(it)) }
        } else {
            val items = nextDataPageProps(body).optJSONArray("items") ?: return emptyList()
            (0 until items.length()).map { itemToNovel(items.getJSONObject(it)) }
        }
    }

    // Novel details — parse __NEXT_DATA__ from novel page HTML

    override suspend fun novelDetailsParse(response: Response): Novel {
        val html = response.body!!.string()
        val manga = nextDataPageProps(html).getJSONObject("initialManga")
        val authorsArr = manga.optJSONArray("authors")
        val genresArr = manga.optJSONArray("genres")
        val ratingRaw = manga.optDouble("rating", Double.NaN)
        val rating = if (!ratingRaw.isNaN() && ratingRaw > 0) ratingRaw.toFloat() else null
        val ratingCount = manga.optInt("ratingCount", 0).takeIf { it > 0 }
        return Novel(
            url = response.request.url.toString(),
            title = manga.getString("name"),
            thumbnailUrl = manga.optString("cover").takeIf { it.isNotEmpty() },
            author = if (authorsArr != null && authorsArr.length() > 0)
                (0 until authorsArr.length()).joinToString(", ") {
                    authorsArr.getJSONObject(it).getString("name")
                }
            else null,
            description = manga.optString("summary").let { summaryHtml ->
                if (summaryHtml.isEmpty()) null else Jsoup.parse(summaryHtml).text()
            },
            genres = if (genresArr != null)
                (0 until genresArr.length()).map { genresArr.getJSONObject(it).getString("name") }
            else emptyList(),
            status = manga.optString("status").toNovelStatus(),
            rating = rating,
            ratingCount = ratingCount,
            initialized = true,
        )
    }

    // Chapter list — fetch novel page for __NEXT_DATA__, then hit the chapters API

    override suspend fun chapterListParse(response: Response): List<Chapter> {
        val html = response.body!!.string()
        val props = nextDataPageProps(html)
        val manga = props.getJSONObject("initialManga")
        val mangaId = manga.getString("id")
        val cv = manga.optString("cv")
        val slug = manga.optString("slug")
        val chaptersResponse = client.newCall(GET("$apiBase/titles/$mangaId/chapters?cv=$cv")).execute()
        val data = JSONObject(chaptersResponse.body!!.string())
        val chapters = data.optJSONObject("data")?.optJSONArray("chapters") ?: return emptyList()
        return (0 until chapters.length()).map { i ->
            val ch = chapters.getJSONObject(i)
            Chapter(
                url = "/$slug/${ch.getString("slug")}",
                name = ch.optString("name").ifEmpty { "Chapter ${ch.optString("chap")}" },
                chapterNumber = ch.optString("chap").toFloatOrNull() ?: -1f,
                uploadDate = ch.optLong("updatedAt", 0L) * 1000L,
            )
        }.reversed()
    }

    // Page list — parse __NEXT_DATA__ initialChapter.content HTML into pages

    override suspend fun pageListParse(response: Response): List<NovelPage> {
        val contentHtml = nextDataPageProps(response.body!!.string())
            .optJSONObject("initialChapter")
            ?.optString("content")
            ?: return emptyList()
        return Jsoup.parse(contentHtml).select("p")
            .mapIndexedNotNull { index, el ->
                val text = el.text().trim()
                if (text.isEmpty()) null else NovelPage(index, text)
            }
    }

    override fun getFilterList(): List<Filter<*>> = listOf(GenreFilter(loadedGenres))

    override suspend fun fetchFilterOptions(): List<Filter<*>> = withContext(Dispatchers.IO) {
        val body = client.newCall(GET("$apiBase/genres")).execute().use {
            it.body!!.string()
        }
        val items = JSONObject(body).optJSONObject("data")?.optJSONArray("items")
        loadedGenres = if (items != null) {
            (0 until items.length()).map {
                val g = items.getJSONObject(it)
                g.getString("name") to g.getString("slug")
            }
        } else emptyList()
        getFilterList()
    }

    private class GenreFilter(genres: List<Pair<String, String>>) : Filter.Select<String>(
        "Genre",
        (listOf(ANY) + genres.map { it.first }).toTypedArray(),
    ) {
        // Parallel array of slugs lined up with `values`. Index 0 is the "Any" sentinel.
        val slugs: Array<String> = (listOf("") + genres.map { it.second }).toTypedArray()
    }

    companion object {
        private const val ANY = "Any"
    }

    // Helpers

    private fun nextDataPageProps(html: String): JSONObject {
        val marker = """<script id="__NEXT_DATA__" type="application/json">"""
        val start = html.indexOf(marker)
        val contentStart = html.indexOf('>', start) + 1
        val contentEnd = html.indexOf("</script>", contentStart)
        return JSONObject(html.substring(contentStart, contentEnd))
            .getJSONObject("props")
            .getJSONObject("pageProps")
    }

    private fun itemToNovel(item: JSONObject) = Novel(
        url = "/${item.getString("slug")}",
        title = item.getString("name"),
        thumbnailUrl = item.optString("cover").takeIf { it.isNotEmpty() },
    )

    private fun String.toNovelStatus() = when (lowercase()) {
        "ongoing" -> NovelStatus.ONGOING
        "completed" -> NovelStatus.COMPLETED
        "hiatus" -> NovelStatus.HIATUS
        "cancelled" -> NovelStatus.CANCELLED
        else -> NovelStatus.UNKNOWN
    }
}
