package org.koitharu.kotatsu.core.db.dao.vector

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.koitharu.kotatsu.core.db.entities.vector.MangaVectorEntity

@Dao
interface MangaVectorDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vector: MangaVectorEntity)

    @Query("SELECT * FROM manga_vectors WHERE manga_id = :mangaId")
    suspend fun getVector(mangaId: Long): MangaVectorEntity?

    @Query("SELECT * FROM manga_vectors WHERE model_version = :version")
    suspend fun getAllVectors(version: Int): List<MangaVectorEntity>
    
    @Query("DELETE FROM manga_vectors WHERE manga_id = :mangaId")
    suspend fun delete(mangaId: Long)
}
