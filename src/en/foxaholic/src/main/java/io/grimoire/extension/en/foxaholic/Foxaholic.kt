package io.grimoire.extension.en.foxaholic

import io.grimoire.api.model.NovelPage
import io.grimoire.api.source.SourceInfo
import io.grimoire.extensions.lib.theme.WPNovelsSource
import okhttp3.Response
import org.jsoup.nodes.Element

@SourceInfo(
    id = 5L,
    name = "Foxaholic",
    lang = "en",
    baseUrl = "https://www.foxaholic.com",
    versionCode = 3,
)
class Foxaholic : WPNovelsSource() {
    override val id = 5L
    override val name = "Foxaholic"
    override val lang = "en"
    override val baseUrl = "https://www.foxaholic.com"

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
}
