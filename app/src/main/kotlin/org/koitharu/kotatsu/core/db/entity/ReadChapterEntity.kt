package org.koitharu.kotatsu.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import org.koitharu.kotatsu.core.db.TABLE_READ_CHAPTERS

@Entity(
	tableName = TABLE_READ_CHAPTERS,
	primaryKeys = ["manga_id", "chapter_id"],
	foreignKeys = [
		ForeignKey(
			entity = MangaEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
)
data class ReadChapterEntity(
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "chapter_id") val chapterId: Long,
	@ColumnInfo(name = "read_at") val readAt: Long = System.currentTimeMillis(),
)
