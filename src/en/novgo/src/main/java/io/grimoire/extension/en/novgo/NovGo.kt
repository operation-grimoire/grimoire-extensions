package io.grimoire.extension.en.novgo

import io.grimoire.api.source.SourceInfo
import io.grimoire.extensions.lib.theme.NovelFullThemeSource

@SourceInfo(
    id = 4L,
    name = "NovGo",
    lang = "en",
    baseUrl = "https://novgo.net",
    versionCode = 8,
)
class NovGo : NovelFullThemeSource() {
    override val id = 4L
    override val name = "NovGo"
    override val lang = "en"
    override val baseUrl = "https://novgo.net"
}
