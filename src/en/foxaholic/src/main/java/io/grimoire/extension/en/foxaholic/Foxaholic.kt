package io.grimoire.extension.en.foxaholic

import io.grimoire.api.model.Chapter
import io.grimoire.api.model.Filter
import io.grimoire.api.model.Novel
import io.grimoire.api.model.NovelPage
import io.grimoire.api.model.NovelStatus
import io.grimoire.api.network.richHtml
import io.grimoire.api.source.SourceInfo
import io.grimoire.extensions.lib.theme.WPNovelsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

@SourceInfo(
    id = 5L,
    name = "Foxaholic",
    lang = "en",
    baseUrl = "https://www.foxaholic.com",
    versionCode = 7,
)
class Foxaholic : WPNovelsSource() {
    override val id = 5L
    override val name = "Foxaholic"
    override val lang = "en"
    override val baseUrl = "https://www.foxaholic.com"

    // Foxaholic's sidebar exposes two status fields under non-standard headings
    // ("Translation" — Active/Dropped/Finished/Teaser, and "Novel" — OnGoing/
    // Completed) instead of the Madara default "Status", so the base parser
    // falls through to UNKNOWN. Translation status reflects what is actually
    // available to read on the host, so it takes priority: a Completed novel
    // whose translation is still Active means more chapters are coming, and
    // that is what a reader cares about. "Novel" is consulted only when the
    // translation field is missing or unrecognized.
    override fun novelDetailsFromDocument(document: Document): Novel {
        val translation = document.statusContentFor("Translation")?.lowercase()
        val novel = document.statusContentFor("Novel")?.lowercase()
        val byTranslation = when (translation) {
            "dropped" -> NovelStatus.CANCELLED
            "teaser" -> NovelStatus.HIATUS
            "finished" -> NovelStatus.COMPLETED
            "active" -> NovelStatus.ONGOING
            else -> null
        }
        val byNovel = when {
            novel?.contains("completed") == true -> NovelStatus.COMPLETED
            novel?.contains("ongoing") == true -> NovelStatus.ONGOING
            else -> null
        }
        val status = byTranslation ?: byNovel ?: NovelStatus.UNKNOWN
        return super.novelDetailsFromDocument(document).copy(status = status)
    }

    private fun Document.statusContentFor(heading: String): String? =
        selectFirst("div.post-content_item:has(div.summary-heading h5:contains($heading)) div.summary-content")
            ?.text()?.trim()

    // Paid chapters carry the `premium-block` class and a `href="#"` placeholder.
    // Mark them locked so the host won't attempt to fetch them, and replace the
    // shared `#` URL with the per-chapter `data-chapter-XXXX` sidecar class —
    // the chapter list is keyed by URL in the app, and 25+ locked entries
    // sharing the same `#` crashes Compose's LazyColumn on scroll.
    override fun chapterFromElement(element: Element): Chapter {
        val isLocked = element.hasClass("premium-block")
        val anchor = element.selectFirst("a")!!
        val url = if (isLocked) {
            val chapterId = element.classNames()
                .firstOrNull { it.startsWith("data-chapter-") }
                ?.removePrefix("data-chapter-")
            if (chapterId != null) "#locked-$chapterId" else anchor.attr("href")
        } else {
            anchor.attr("href")
        }
        return Chapter(
            url = url,
            name = anchor.text().trim(),
            uploadDate = parseReleaseDate(element),
            locked = isLocked,
        )
    }

    // Chapter dates are rendered as e.g. "September 3, 2024" inside the
    // `chapter-release-date` sidecar — both free and locked rows carry one.
    // Parse as a UTC LocalDate; fall back to 0 (the model's "unknown" sentinel)
    // when the field is missing or formatted unexpectedly so the chapter still
    // appears in the list.
    private fun parseReleaseDate(element: Element): Long {
        val raw = element.selectFirst("span.chapter-release-date")?.text()?.trim()
            ?: return 0L
        return runCatching {
            LocalDate.parse(raw, RELEASE_DATE_FORMATTER)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        }.getOrElse {
            if (it is DateTimeParseException) 0L else throw it
        }
    }

    // Chapter content mixes prose <p> with illustrations the WordPress editor
    // wraps as <p><img> or <figure><img>; walk both in document order. An image
    // is kept only when served from the site's own media library
    // (/wp-content/uploads/), which excludes injected ad-network images.
    override suspend fun pageListParse(response: Response): List<NovelPage> {
        val content = response.asJsoup().selectFirst("div.reading-content")
            ?: return emptyList()
        return content.select("p, figure").mapIndexedNotNull { index, element ->
            val imageUrl = element.selectFirst("img")?.contentImageUrl()
            if (imageUrl != null) {
                NovelPage(index = index, text = "", imageUrl = imageUrl)
            } else {
                // `formattedText` preserves italics/bold/br/links that
                // element.text() would flatten. Leave it null when there's
                // no inline formatting so plain prose doesn't double its
                // size in the offline download payload.
                val text = element.text().trim()
                if (text.isEmpty()) return@mapIndexedNotNull null
                val formatted = element.richHtml().takeIf { it != text }
                NovelPage(index = index, text = text, formattedText = formatted)
            }
        }
    }

    // WordPress lazy-loads images via data-src/data-lazy-src; the resolved URL
    // must live under /wp-content/uploads/ to count as a chapter illustration
    // rather than an ad-network image.
    private fun Element.contentImageUrl(): String? =
        sequenceOf("data-src", "data-lazy-src", "src")
            .map { absUrl(it) }
            .firstOrNull { it.contains("/wp-content/uploads/", ignoreCase = true) }

    // --- Filters -------------------------------------------------------------
    //
    // Mirrors Madara's advanced search at `/?s=&post_type=wp-manga`: Author /
    // Team text inputs, a multi-select Status group, an OR/AND Genres-match
    // selector and a multi-select Genres group whose options are scraped from
    // the advanced-search form so they stay in step with whatever the site
    // currently exposes. Selected filters are appended as query params on the
    // existing Madara search URL (`genre[]`, `op`, `status[]`, `author`,
    // `artist`).

    override val hasDynamicFilters: Boolean = true

    // `(slug, label)` pairs for the genre taxonomy on this site, populated
    // lazily on the first filter-sheet open. Volatile + double-checked Mutex
    // so a burst of opens hits the network once.
    @Volatile
    private var loadedGenres: List<Pair<String, String>> = emptyList()
    private val filterFetchMutex = Mutex()

    override fun getFilterList(): List<Filter<*>> = buildList {
        add(AuthorFilter())
        add(TeamFilter())
        add(GenresMatchFilter())
        add(StatusGroup())
        if (loadedGenres.isNotEmpty()) add(GenresGroup(loadedGenres))
    }

    override suspend fun fetchFilterOptions(): List<Filter<*>> = withContext(Dispatchers.IO) {
        if (loadedGenres.isEmpty()) {
            filterFetchMutex.withLock {
                if (loadedGenres.isEmpty()) {
                    runCatching {
                        val body = client.newCall(GET("$baseUrl/?s=&post_type=wp-manga"))
                            .execute().use { it.body?.string().orEmpty() }
                        loadedGenres = parseGenres(Jsoup.parse(body, baseUrl))
                    }
                }
            }
        }
        getFilterList()
    }

    // Madara's advanced search renders each genre as a checkbox sibling to a
    // `<label for="...">`; fall back to the surrounding text when the form
    // template omits the for/id pairing.
    private fun parseGenres(doc: Document): List<Pair<String, String>> =
        doc.select("input[name='genre[]']").mapNotNull { input ->
            val slug = input.attr("value").trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val id = input.id().takeIf { it.isNotEmpty() }
            val label = (
                id?.let { doc.selectFirst("label[for='$it']") }?.text()
                    ?: input.nextElementSibling()?.text()
                    ?: input.parent()?.text()
                )?.trim()?.takeIf { it.isNotEmpty() } ?: slug
            slug to label
        }.distinctBy { it.first }

    override fun searchNovelsRequest(
        query: String,
        page: Int,
        filters: List<Filter<*>>,
    ): Request {
        val params = StringBuilder()
        params.append("s=").append(URLEncoder.encode(query, "UTF-8"))
            .append("&post_type=wp-manga")
        for (filter in filters) when (filter) {
            is AuthorFilter -> if (filter.state.isNotBlank()) {
                params.append("&author=").append(URLEncoder.encode(filter.state.trim(), "UTF-8"))
            }
            is TeamFilter -> if (filter.state.isNotBlank()) {
                params.append("&artist=").append(URLEncoder.encode(filter.state.trim(), "UTF-8"))
            }
            // `op=1` switches Madara's genre match from OR (any of) to AND
            // (all of). The OR default is the empty string, so emit nothing
            // then.
            is GenresMatchFilter -> if (filter.state == 1) params.append("&op=1")
            is StatusGroup -> for ((i, slug) in STATUS_SLUGS.withIndex()) {
                if (filter.state.getOrNull(i)?.state == true) {
                    params.append("&status%5B%5D=").append(slug)
                }
            }
            is GenresGroup -> for (cb in filter.state) {
                if (cb.state) {
                    val slug = filter.slugByLabel[cb.name] ?: continue
                    params.append("&genre%5B%5D=").append(URLEncoder.encode(slug, "UTF-8"))
                }
            }
            else -> Unit
        }
        val path = if (page > 1) "/page/$page/" else "/"
        return GET("$baseUrl$path?$params")
    }

    private class AuthorFilter : Filter.Text("Author")
    private class TeamFilter : Filter.Text("Team")

    // Foxaholic's "Genres condition" dropdown: OR (default) or AND.
    private class GenresMatchFilter : Filter.Select<String>(
        "Genres match",
        arrayOf("OR (any selected)", "AND (all selected)"),
    )

    // Standard wp-manga `_wp_manga_status` taxonomy slugs — "Completed"
    // really is `end` upstream, not `completed`.
    private class StatusGroup : Filter.Group<Filter.CheckBox>(
        "Novel Status",
        listOf(
            Filter.CheckBox("OnGoing"),
            Filter.CheckBox("Completed"),
            Filter.CheckBox("Canceled"),
            Filter.CheckBox("On Hold"),
            Filter.CheckBox("Upcoming"),
        ),
    )

    private class GenresGroup(genres: List<Pair<String, String>>) :
        Filter.Group<Filter.CheckBox>(
            "Genres",
            genres.map { Filter.CheckBox(it.second) },
        ) {
        // Lookup from the visible label (which is all the CheckBox carries)
        // back to the slug Madara expects in `genre[]`.
        val slugByLabel: Map<String, String> = genres.associate { it.second to it.first }
    }

    private companion object {
        // ENGLISH locale required so month names parse regardless of device locale.
        val RELEASE_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)

        // Order mirrors the StatusGroup checkbox order above.
        val STATUS_SLUGS = arrayOf("on-going", "end", "canceled", "on-hold", "upcoming")
    }
}
