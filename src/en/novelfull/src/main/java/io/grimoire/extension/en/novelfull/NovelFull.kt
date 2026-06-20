package io.grimoire.extension.en.novelfull

import io.grimoire.api.source.SourceInfo
import io.grimoire.extensions.lib.theme.NovelFullThemeSource

@SourceInfo(
    name = "NovelFull",
    lang = "en",
    baseUrl = "https://novelfull.com",
    versionCode = 9,
)
class NovelFull : NovelFullThemeSource() {
    override val name = "NovelFull"
    override val lang = "en"
    override val baseUrl = "https://novelfull.com"
}
