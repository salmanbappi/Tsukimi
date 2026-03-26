package org.koitharu.kotatsu.backups.ui.restore

import android.util.Log
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
            android.util.Log.d("MihonRestore", "Starting decode...")
            val mihonBackup = MihonBackupDecoder.decode(inputStream)
            android.util.Log.d("MihonRestore", "Decoded backup with ${mihonBackup.backupManga.size} manga")
            val mapper = MihonBackupMapper(mihonBackup)
            
            // Extract the mapped data
            val categories = mapper.mapCategories()
            val (mangas, favourites) = mapper.mapMangaAndFavourites()
            val history = mapper.mapHistory()
            val readChapters = mapper.mapReadChapters()
            val scrobbling = mapper.mapScrobbling()
            val sources = mapper.mapSources()

            android.util.Log.d("MihonRestore", "Mapped: ${categories.size} categories, ${favourites.size} favourites, ${readChapters.size} read chapters")

            // Pass this data to BackupRepository. We'll need to modify BackupRepository
            // to accept these pre-mapped lists.
            result += repository.restoreMihonData(
                sections = sections,
                categories = categories,
                mangas = mangas,
                favourites = favourites,
                history = history,
                readChapters = readChapters,
                scrobbling = scrobbling,
                sources = sources,
                progress = progress
            )
            android.util.Log.d("MihonRestore", "Restore completed with result: $result")

        } catch (e: Exception) {
            android.util.Log.e("MihonRestore", "Failed to restore", e)
            e.printStackTrace()
            result += CompositeResult.failure(e)
        }

        return result
    }
}
