package org.koitharu.kotatsu.extension.mihon.model

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SourceStatus

class MihonMangaSource(
    val catalogueSource: CatalogueSource,
    val pkgName: String,
) : MangaSource {
    override val name: String = "MIHON_${catalogueSource.id}"
    override val title: String = catalogueSource.name
    override val locale: String = catalogueSource.lang
    override val contentType: ContentType = ContentType.MANGA
    override val isNsfw: Boolean = false // Should be determined from extension metadata
    override val status: SourceStatus = SourceStatus.WORKING
    override val isBroken: Boolean = false
    override val isLocal: Boolean = false
}
