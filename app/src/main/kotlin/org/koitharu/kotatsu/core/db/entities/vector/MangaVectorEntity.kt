package org.koitharu.kotatsu.core.db.entities.vector

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.koitharu.kotatsu.core.db.entity.MangaEntity

@Entity(
    tableName = "manga_vectors",
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["manga_id"],
            childColumns = ["manga_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["manga_id"], unique = true)]
)
data class MangaVectorEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "manga_id")
    val mangaId: Long,

    @ColumnInfo(name = "embedding")
    val embedding: String, // Stored as JSON string "[0.1, 0.5, ...]" or comma-separated for now

    @ColumnInfo(name = "model_version")
    val modelVersion: Int = 1,
    
    @ColumnInfo(name = "last_updated")
    val lastUpdated: Long = System.currentTimeMillis()
)
