package io.grimoire.extension.en.foxaholic

import io.grimoire.api.source.SourceInfo
import io.grimoire.extensions.lib.theme.WPNovelsSource

@SourceInfo(
    id = 5L,
    name = "Foxaholic",
    lang = "en",
    baseUrl = "https://www.foxaholic.com",
    versionCode = 1,
)
class Foxaholic : WPNovelsSource() {
    override val id = 5L
    override val name = "Foxaholic"
    override val lang = "en"
    override val baseUrl = "https://www.foxaholic.com"
}
