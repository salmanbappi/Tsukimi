package org.koitharu.kotatsu.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.koitharu.kotatsu.core.db.TABLE_EXTENSION_REPOS

@Entity(tableName = TABLE_EXTENSION_REPOS)
data class ExtensionRepoEntity(
	@PrimaryKey
	@ColumnInfo(name = "base_url") val baseUrl: String,
	@ColumnInfo(name = "name") val name: String,
	@ColumnInfo(name = "short_name") val shortName: String?,
	@ColumnInfo(name = "website") val website: String,
	@ColumnInfo(name = "signing_key_fingerprint") val signingKeyFingerprint: String
)