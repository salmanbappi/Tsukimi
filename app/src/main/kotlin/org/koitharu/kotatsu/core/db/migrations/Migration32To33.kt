package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.koitharu.kotatsu.core.db.TABLE_EXTENSION_REPOS

class Migration32To33 : Migration(32, 33) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS `$TABLE_EXTENSION_REPOS` (
				`base_url` TEXT NOT NULL,
				`name` TEXT NOT NULL,
				`short_name` TEXT,
				`website` TEXT NOT NULL,
				`signing_key_fingerprint` TEXT NOT NULL,
				PRIMARY KEY(`base_url`)
			)
			""".trimIndent()
		)
	}
}