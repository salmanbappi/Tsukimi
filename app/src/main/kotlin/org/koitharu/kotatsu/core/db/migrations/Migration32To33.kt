package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration32To33 : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `manga_vectors` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                `manga_id` INTEGER NOT NULL, 
                `embedding` TEXT NOT NULL, 
                `model_version` INTEGER NOT NULL, 
                `last_updated` INTEGER NOT NULL, 
                FOREIGN KEY(`manga_id`) REFERENCES `manga`(`manga_id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_manga_vectors_manga_id` ON `manga_vectors` (`manga_id`)")
    }
}
