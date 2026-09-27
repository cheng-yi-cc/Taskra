package com.taskra.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        Semester::class, Course::class, Homework::class, ContentBlock::class,
        ImageAsset::class, EditorDraft::class, ReviewSession::class, ReviewItem::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun semesterDao(): SemesterDao
    abstract fun courseDao(): CourseDao
    abstract fun homeworkDao(): HomeworkDao
    abstract fun blockDao(): ContentBlockDao
    abstract fun imageDao(): ImageAssetDao
    abstract fun draftDao(): DraftDao
    abstract fun reviewDao(): ReviewDao
}
