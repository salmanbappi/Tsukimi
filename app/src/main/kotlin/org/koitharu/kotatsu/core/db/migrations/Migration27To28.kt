package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration27To28 : Migration(27, 28) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE history ADD COLUMN max_percent REAL NOT NULL DEFAULT 0")
		// Initialize max_percent with current percent for existing entries
		db.execSQL("UPDATE history SET max_percent = percent")
	}
}
