package org.koitharu.kotatsu.backups.ui.restore

import kotlinx.coroutines.flow.FlowCollector
import org.koitharu.kotatsu.backups.data.BackupRepository
import org.koitharu.kotatsu.backups.domain.BackupSection
import org.koitharu.kotatsu.backups.domain.mihon.MihonBackupDecoder
import org.koitharu.kotatsu.backups.domain.mihon.MihonBackupMapper
import org.koitharu.kotatsu.core.util.CompositeResult
import org.koitharu.kotatsu.core.util.progress.Progress
import java.io.InputStream

object MihonRestoreHandler {
    suspend fun restore(
        inputStream: InputStream,
        repository: BackupRepository,
        sections: Set<BackupSection>,
        progress: FlowCollector<Progress>?
    ): CompositeResult {
        progress?.emit(Progress.INDETERMINATE)
        var result = CompositeResult.EMPTY

        try {
            val mihonBackup = MihonBackupDecoder.decode(inputStream)
            val mapper = MihonBackupMapper(mihonBackup)
            
            // Extract the mapped data
            val categories = mapper.mapCategories()
            val (mangas, favourites) = mapper.mapMangaAndFavourites()
            val history = mapper.mapHistory()

            // Pass this data to BackupRepository. We'll need to modify BackupRepository
            // to accept these pre-mapped lists.
            result += repository.restoreMihonData(
                sections = sections,
                categories = categories,
                mangas = mangas,
                favourites = favourites,
                history = history,
                progress = progress
            )

        } catch (e: Exception) {
            e.printStackTrace()
            result += CompositeResult.failure(e)
        }

        return result
    }
}
