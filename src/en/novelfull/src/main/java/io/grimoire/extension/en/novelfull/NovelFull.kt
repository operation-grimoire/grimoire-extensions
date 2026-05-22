package io.grimoire.extension.en.novelfull

import io.grimoire.api.model.NovelPage
import io.grimoire.api.source.SourceInfo
import io.grimoire.extensions.lib.theme.NovelFullThemeSource
import org.jsoup.nodes.Element

@SourceInfo(
    id = 1L,
    name = "NovelFull",
    lang = "en",
    baseUrl = "https://novelfull.com",
    versionCode = 6,
)
class NovelFull : NovelFullThemeSource() {
    override val id = 1L
    override val name = "NovelFull"
    override val lang = "en"
    override val baseUrl = "https://novelfull.com"

    // Light-novel titles embed illustrations as <p><img> inside #chapter-content;
    // emit them as image pages instead of dropping them via .text(). Plain web
    // novels have no images, so this is a no-op for them.
    override fun pageFromElement(element: Element, index: Int): NovelPage {
        val imageUrl = element.selectFirst("img")?.imageUrl()
        if (imageUrl != null) {
            return NovelPage(index = index, text = "", imageUrl = imageUrl)
        }
        return NovelPage(index = index, text = element.text())
    }

    private fun Element.imageUrl(): String? =
        sequenceOf("data-src", "data-lazy-src", "src")
            .map { absUrl(it) }
            .firstOrNull { it.isNotBlank() }
}
