package io.grimoire.extension.en.novgo

import io.grimoire.api.model.lang.Language
import io.grimoire.api.source.SourceInfo
import io.grimoire.extensions.lib.theme.NovelFullThemeSource

@SourceInfo(
    name = "NovGo",
    lang = Language.EN,
    baseUrl = "https://novgo.net",
    versionCode = 9,
)
class NovGo : NovelFullThemeSource() {
    override val name = "NovGo"
    override val lang = Language.EN
    override val baseUrl = "https://novgo.net"
}
