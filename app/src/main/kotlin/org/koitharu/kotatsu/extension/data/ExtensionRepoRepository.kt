package org.koitharu.kotatsu.extension.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.ExtensionRepoEntity
import org.koitharu.kotatsu.extension.model.ExtensionRepo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExtensionRepoRepository @Inject constructor(
	private val db: MangaDatabase,
) {
	private val dao = db.getExtensionRepoDao()

	fun observeAll(): Flow<List<ExtensionRepo>> {
		return dao.observeAll().map { list ->
			list.map { it.toModel() }
		}
	}

	suspend fun getAll(): List<ExtensionRepo> {
		return dao.getAll().map { it.toModel() }
	}

	suspend fun get(baseUrl: String): ExtensionRepo? {
		return dao.get(baseUrl)?.toModel()
	}

	suspend fun add(repo: ExtensionRepo) {
		dao.insert(repo.toEntity())
	}

	suspend fun delete(baseUrl: String) {
		dao.delete(baseUrl)
	}

	private fun ExtensionRepoEntity.toModel() = ExtensionRepo(
		baseUrl = baseUrl,
		name = name,
		shortName = shortName,
		website = website,
		signingKeyFingerprint = signingKeyFingerprint
	)

	private fun ExtensionRepo.toEntity() = ExtensionRepoEntity(
		baseUrl = baseUrl,
		name = name,
		shortName = shortName,
		website = website,
		signingKeyFingerprint = signingKeyFingerprint
	)
}