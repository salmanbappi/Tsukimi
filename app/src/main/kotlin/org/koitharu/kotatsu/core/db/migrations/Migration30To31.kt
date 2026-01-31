package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration30To31 : Migration(30, 31) {

	override fun migrate(db: SupportSQLiteDatabase) {
		try {
			db.execSQL("ALTER TABLE preferences ADD COLUMN cf_sharpening REAL NOT NULL DEFAULT 0")
		} catch (e: Exception) {
			// Column might already exist from a previous failed attempt
			e.printStackTrace()
		}
	}
}
