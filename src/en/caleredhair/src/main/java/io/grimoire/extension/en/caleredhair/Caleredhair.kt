package io.grimoire.extension.en.caleredhair

import io.grimoire.api.model.lang.Language
import io.grimoire.api.source.SourceInfo
import io.grimoire.extensions.lib.theme.PatreonSource

/**
 * Cale (patreon.com/cw/caleredhair) — a webnovel translator who publishes
 * chapter batches as Patreon posts grouped into per-series collections. All
 * behaviour lives in [PatreonSource]; this only pins the campaign id.
 */
@SourceInfo(
    name = "Cale",
    lang = Language.EN,
    baseUrl = "https://www.patreon.com",
    versionCode = 2,
    novelUpdatesGroups = ["Cale Red Hair"],
)
class Caleredhair : PatreonSource() {

    override val name = "Cale"
    override val lang = Language.EN

    override val campaignId = "13760222"
}
