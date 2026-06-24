package io.grimoire.extension.en.allnovel

import io.grimoire.api.model.lang.Language
import io.grimoire.api.source.SourceInfo
import io.grimoire.extensions.lib.theme.NovelFullThemeSource

@SourceInfo(
    name = "AllNovel",
    lang = Language.EN,
    baseUrl = "https://allnovel.org",
    versionCode = 9,
)
class AllNovel : NovelFullThemeSource() {
    override val name = "AllNovel"
    override val lang = Language.EN
    override val baseUrl = "https://allnovel.org"
}
