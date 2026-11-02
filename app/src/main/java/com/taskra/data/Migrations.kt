package com.taskra.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v1 → v2：内容块新增富文本标记列（加粗/高亮/标题级别），默认空数组，老数据无损。 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `content_blocks` ADD COLUMN `spanJson` TEXT NOT NULL DEFAULT '[]'")
    }
}

/** v2 → v3：录音是独立的内容块，原有图文、草稿和课程关系不变。 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `content_blocks` ADD COLUMN `audioId` TEXT")
        db.execSQL("ALTER TABLE `content_blocks` ADD COLUMN `audioDurationMs` INTEGER NOT NULL DEFAULT 0")
    }
}

/** v3 → v4：课程使用软删除，保留课程、作业与附件关联。 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `courses` ADD COLUMN `deletedAt` INTEGER")
    }
}

/** v4 → v5：模板独立于作业内容，原记录和附件原样保留。 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `courses` ADD COLUMN `templateJson` TEXT")
    }
}
