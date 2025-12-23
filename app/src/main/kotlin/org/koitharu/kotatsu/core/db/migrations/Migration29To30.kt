package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration29To30 : Migration(29, 30) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE read_chapters ADD COLUMN page INTEGER NOT NULL DEFAULT 0")
	}
}
