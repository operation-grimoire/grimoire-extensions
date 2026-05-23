package io.grimoire.extension.en.foxaholic

import io.grimoire.api.model.Chapter
import io.grimoire.api.model.Novel
import io.grimoire.api.model.NovelPage
import io.grimoire.api.model.NovelStatus
import io.grimoire.api.source.SourceInfo
import io.grimoire.extensions.lib.theme.WPNovelsSource
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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
    versionCode = 5,
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
                element.text().trim().takeIf { it.isNotEmpty() }?.let { NovelPage(index, it) }
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

    private companion object {
        // ENGLISH locale required so month names parse regardless of device locale.
        val RELEASE_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
    }
}
