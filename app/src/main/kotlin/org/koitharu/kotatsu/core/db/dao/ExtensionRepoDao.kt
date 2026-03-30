package org.koitharu.kotatsu.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.koitharu.kotatsu.core.db.entity.ExtensionRepoEntity

@Dao
interface ExtensionRepoDao {

	@Query("SELECT * FROM extension_repos")
	fun observeAll(): Flow<List<ExtensionRepoEntity>>

	@Query("SELECT * FROM extension_repos")
	suspend fun getAll(): List<ExtensionRepoEntity>

	@Query("SELECT * FROM extension_repos WHERE base_url = :baseUrl")
	suspend fun get(baseUrl: String): ExtensionRepoEntity?

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun insert(repo: ExtensionRepoEntity)

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun insertAll(repos: List<ExtensionRepoEntity>)

	@Query("DELETE FROM extension_repos WHERE base_url = :baseUrl")
	suspend fun delete(baseUrl: String)
}