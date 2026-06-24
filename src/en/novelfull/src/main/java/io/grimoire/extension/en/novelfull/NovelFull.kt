package io.grimoire.extension.en.novelfull

import io.grimoire.api.model.lang.Language
import io.grimoire.api.source.SourceInfo
import io.grimoire.extensions.lib.theme.NovelFullThemeSource

@SourceInfo(
    name = "NovelFull",
    lang = Language.EN,
    baseUrl = "https://novelfull.com",
    versionCode = 9,
)
class NovelFull : NovelFullThemeSource() {
    override val name = "NovelFull"
    override val lang = Language.EN
    override val baseUrl = "https://novelfull.com"
}
