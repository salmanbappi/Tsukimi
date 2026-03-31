package org.koitharu.kotatsu.extension.mihon.model

import eu.kanade.tachiyomi.source.CatalogueSource
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource

class MihonMangaSource(
    val catalogueSource: CatalogueSource,
    val pkgName: String,
) : MangaParserSource {
    override val name: String = "MIHON_${catalogueSource.id}"
    
    override val title: String = catalogueSource.name
    override val locale: String = catalogueSource.lang
    override val contentType: ContentType = ContentType.MANGA
    override val isBroken: Boolean = false
    override val isNsfw: Boolean = false
}
