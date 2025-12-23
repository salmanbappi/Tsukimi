package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration28To29 : Migration(28, 29) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("CREATE TABLE IF NOT EXISTS `read_chapters` (`manga_id` INTEGER NOT NULL, `chapter_id` INTEGER NOT NULL, `read_at` INTEGER NOT NULL, PRIMARY KEY(`manga_id`, `chapter_id`), FOREIGN KEY(`manga_id`) REFERENCES `manga`(`manga_id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
		// Migrate current progress: mark current chapter and all previous ones as read
		db.execSQL("""
			INSERT OR IGNORE INTO read_chapters (manga_id, chapter_id, read_at)
			SELECT c.manga_id, c.chapter_id, h.updated_at
			FROM chapters c
			JOIN history h ON c.manga_id = h.manga_id
			WHERE c.`index` <= (SELECT `index` FROM chapters WHERE chapter_id = h.chapter_id)
		""")
	}
}
