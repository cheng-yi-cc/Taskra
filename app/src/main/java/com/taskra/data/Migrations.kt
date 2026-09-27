package com.taskra.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v1 → v2：内容块新增富文本标记列（加粗/高亮/标题级别），默认空数组，老数据无损。 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `content_blocks` ADD COLUMN `spanJson` TEXT NOT NULL DEFAULT '[]'")
    }
}
