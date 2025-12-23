package org.koitharu.kotatsu.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.koitharu.kotatsu.core.db.entity.ReadChapterEntity

@Dao
interface ReadChaptersDao {

	@Query("SELECT chapter_id FROM read_chapters WHERE manga_id = :mangaId")
	fun observeReadChapterIds(mangaId: Long): Flow<List<Long>>

	@Query("SELECT chapter_id FROM read_chapters WHERE manga_id = :mangaId")
	suspend fun getReadChapterIds(mangaId: Long): List<Long>

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insert(entity: ReadChapterEntity)

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insert(entities: List<ReadChapterEntity>)

	@Query("DELETE FROM read_chapters WHERE manga_id = :mangaId AND chapter_id = :chapterId")
	suspend fun delete(mangaId: Long, chapterId: Long)

	@Query("DELETE FROM read_chapters WHERE manga_id = :mangaId")
	suspend fun deleteByMangaId(mangaId: Long)
}
