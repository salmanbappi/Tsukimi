package org.koitharu.kotatsu.extension.mihon.model

import eu.kanade.tachiyomi.source.CatalogueSource
import org.koitharu.kotatsu.parsers.model.MangaSource

class MihonMangaSource(
    val catalogueSource: CatalogueSource,
    val pkgName: String,
) : MangaSource {
    override val name: String = "MIHON_${catalogueSource.id}"
    
    // Internal properties for mapping
    val title: String = catalogueSource.name
    val locale: String = catalogueSource.lang
}
