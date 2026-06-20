package io.grimoire.extension.en.allnovel

import io.grimoire.api.source.SourceInfo
import io.grimoire.extensions.lib.theme.NovelFullThemeSource

@SourceInfo(
    name = "AllNovel",
    lang = "en",
    baseUrl = "https://allnovel.org",
    versionCode = 9,
)
class AllNovel : NovelFullThemeSource() {
    override val name = "AllNovel"
    override val lang = "en"
    override val baseUrl = "https://allnovel.org"
}
