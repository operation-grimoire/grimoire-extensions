package io.grimoire.extension.en.foxaholic

import io.grimoire.api.model.lang.Language
import io.grimoire.api.model.novel.Chapter
import io.grimoire.api.model.novel.Novel
import io.grimoire.api.model.novel.NovelPage
import io.grimoire.api.model.novel.NovelStatus
import io.grimoire.api.model.novel.PageContent
import io.grimoire.api.source.AdultContent
import io.grimoire.api.source.SourceInfo
import io.grimoire.api.util.richHtml
import io.grimoire.extensions.lib.theme.WPNovelsSource
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

@SourceInfo(
    name = "Foxaholic",
    lang = Language.EN,
    baseUrl = "https://www.foxaholic.com",
    versionCode = 11,
    novelUpdatesGroups = ["Foxaholic"],
    adultContent = AdultContent.PARTIAL,
)
class Foxaholic : WPNovelsSource() {
    override val name = "Foxaholic"
    override val lang = Language.EN
    override val baseUrl = "https://www.foxaholic.com"

    // Foxaholic exposes two status fields under non-standard headings
    // ("Translation" and "Novel") instead of Madara's "Status". Translation
    // status reflects what's actually available to read, so it takes priority;
    // "Novel" is consulted only when translation is missing/unrecognized.
    public override fun novelFromDocument(document: Document): Novel {
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
        return super.novelFromDocument(document).copy(status = status)
    }

    private fun Document.statusContentFor(heading: String): String? =
        selectFirst("div.post-content_item:has(div.summary-heading h5:contains($heading)) div.summary-content")
            ?.text()?.trim()

    // Paid chapters carry `premium-block` + a `href="#"` placeholder. Mark them
    // locked and give each a unique URL (from the `data-chapter-XXXX` class) so
    // 25+ locked rows don't collapse onto the same `#` key and crash the list.
    public override fun chapterFromElement(element: Element): Chapter {
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

    private fun parseReleaseDate(element: Element): Long {
        val raw = element.selectFirst("span.chapter-release-date")?.text()?.trim() ?: return 0L
        return runCatching {
            LocalDate.parse(raw, RELEASE_DATE_FORMATTER)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        }.getOrElse {
            if (it is DateTimeParseException) 0L else throw it
        }
    }

    // Content mixes prose <p> with illustrations wrapped as <p><img>/<figure><img>;
    // walk both in order. Keep an image only when served from the site's media
    // library (/wp-content/uploads/), excluding injected ad-network images.
    override fun pagesFromDocument(document: Document): List<NovelPage> {
        val content = document.selectFirst("div.reading-content") ?: return emptyList()
        return content.select("p, figure").mapIndexedNotNull { index, element ->
            val imageUrl = element.selectFirst("img")?.contentImageUrl()
            if (imageUrl != null) {
                NovelPage(index, PageContent.Image(imageUrl))
            } else {
                val text = element.text().trim()
                if (text.isEmpty()) return@mapIndexedNotNull null
                NovelPage(index, PageContent.Text(text, element.richHtml().takeIf { it != text }))
            }
        }
    }

    private fun Element.contentImageUrl(): String? =
        sequenceOf("data-src", "data-lazy-src", "src")
            .map { absUrl(it) }
            .firstOrNull { it.contains("/wp-content/uploads/", ignoreCase = true) }

    private companion object {
        val RELEASE_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
    }
}
