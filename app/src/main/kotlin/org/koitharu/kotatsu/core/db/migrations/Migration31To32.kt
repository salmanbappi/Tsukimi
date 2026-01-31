package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration31To32 : Migration(31, 32) {

	override fun migrate(db: SupportSQLiteDatabase) {
		try {
			db.execSQL("ALTER TABLE preferences ADD COLUMN cf_denoising REAL NOT NULL DEFAULT 0")
		} catch (e: Exception) {
			e.printStackTrace()
		}
	}
}
