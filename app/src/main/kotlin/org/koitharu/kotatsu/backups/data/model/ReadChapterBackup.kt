package org.koitharu.kotatsu.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.core.db.entity.ReadChapterEntity

@Serializable
class ReadChapterBackup(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("chapter_id") val chapterId: Long,
	@SerialName("page") val page: Int,
	@SerialName("read_at") val readAt: Long,
) {

	constructor(entity: ReadChapterEntity) : this(
		mangaId = entity.mangaId,
		chapterId = entity.chapterId,
		page = entity.page,
		readAt = entity.readAt,
	)

	fun toEntity() = ReadChapterEntity(
		mangaId = mangaId,
		chapterId = chapterId,
		page = page,
		readAt = readAt,
	)
}
