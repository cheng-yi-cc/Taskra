package com.taskra.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// 注意：全部写入统一使用 @Upsert（更新或插入），禁止使用
// @Insert(onConflict = REPLACE)，因为 REPLACE 在主键冲突时是“先删除再插入”，
// 会触发外键级联（删除作业内容块、置空课程归属），导致更新操作丢失数据。

@Dao
interface SemesterDao {
    @Query("SELECT * FROM semesters ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<Semester>>
    @Query("SELECT * FROM semesters ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun listAll(): List<Semester>
    @Query("SELECT * FROM semesters WHERE id=:id")
    suspend fun get(id: String): Semester?
    @Upsert
    suspend fun upsert(s: Semester)
    @Update
    suspend fun update(s: Semester)
    @Query("DELETE FROM semesters WHERE id=:id")
    suspend fun delete(id: String)
}

@Dao
interface CourseDao {
    @Query("SELECT * FROM courses WHERE semesterId=:semesterId ORDER BY createdAt ASC")
    fun observeBySemester(semesterId: String): Flow<List<Course>>
    @Query("SELECT * FROM courses ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<Course>>
    @Query("SELECT * FROM courses WHERE id=:id")
    suspend fun get(id: String): Course?
    @Query("SELECT * FROM courses WHERE semesterId=:semesterId AND name=:name LIMIT 1")
    suspend fun findByName(semesterId: String, name: String): Course?
    @Query("SELECT * FROM courses WHERE semesterId=:semesterId ORDER BY createdAt ASC")
    suspend fun listBySemester(semesterId: String): List<Course>
    @Query("SELECT * FROM courses ORDER BY createdAt ASC")
    suspend fun listAll(): List<Course>
    @Upsert
    suspend fun upsert(c: Course)
    @Update
    suspend fun update(c: Course)
    @Query("SELECT COUNT(*) FROM homework WHERE courseId=:courseId AND deletedAt IS NULL")
    suspend fun homeworkCount(courseId: String): Int
    @Query("SELECT COUNT(*) FROM editor_drafts WHERE courseId=:courseId")
    suspend fun draftCount(courseId: String): Int
    @Query("SELECT COUNT(*) FROM homework WHERE courseId=:courseId AND deletedAt IS NOT NULL")
    suspend fun trashCount(courseId: String): Int
}

@Dao
interface HomeworkDao {
    @Query("SELECT * FROM homework WHERE deletedAt IS NULL AND status='TODO' ORDER BY createdAt ASC")
    fun observeTodoAll(): Flow<List<Homework>>
    @Query("SELECT * FROM homework WHERE id=:id")
    fun observeById(id: String): Flow<Homework?>
    @Query("SELECT * FROM homework WHERE id=:id")
    suspend fun get(id: String): Homework?
    @Query("SELECT * FROM homework WHERE deletedAt IS NULL ORDER BY createdAt ASC")
    suspend fun listActive(): List<Homework>
    @Query("SELECT * FROM homework WHERE courseId=:courseId AND deletedAt IS NULL ORDER BY createdAt ASC")
    fun observeByCourse(courseId: String): Flow<List<Homework>>
    @Query("SELECT * FROM homework WHERE courseId=:courseId AND deletedAt IS NULL ORDER BY createdAt ASC")
    suspend fun listByCourse(courseId: String): List<Homework>
    @Query("SELECT * FROM homework WHERE semesterId=:semesterId AND courseId IS NULL AND deletedAt IS NULL ORDER BY createdAt ASC")
    fun observeUncat(semesterId: String): Flow<List<Homework>>
    @Query("SELECT * FROM homework WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<Homework>>
    @Query("SELECT * FROM homework WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    suspend fun listTrash(): List<Homework>
    @Upsert
    suspend fun upsert(h: Homework)
    @Update
    suspend fun update(h: Homework)
    @Query("DELETE FROM homework WHERE id=:id")
    suspend fun deleteHard(id: String)
    @Query("SELECT COUNT(*) FROM homework WHERE deletedAt IS NULL AND status='TODO'")
    fun observeTodoCount(): Flow<Int>
}

@Dao
interface ContentBlockDao {
    @Query("SELECT * FROM content_blocks WHERE homeworkId=:homeworkId ORDER BY region ASC, position ASC")
    suspend fun listByHomework(homeworkId: String): List<ContentBlock>
    @Query("SELECT * FROM content_blocks WHERE homeworkId=:homeworkId ORDER BY region ASC, position ASC")
    fun observeByHomework(homeworkId: String): Flow<List<ContentBlock>>
    @Query("DELETE FROM content_blocks WHERE homeworkId=:homeworkId")
    suspend fun deleteByHomework(homeworkId: String)
    @Upsert
    suspend fun upsertAll(blocks: List<ContentBlock>)
    @Query("SELECT DISTINCT imageId FROM content_blocks WHERE imageId IS NOT NULL")
    suspend fun allReferencedImageIds(): List<String>
}

@Dao
interface ImageAssetDao {
    @Query("SELECT * FROM image_assets WHERE id=:id")
    suspend fun get(id: String): ImageAsset?
    @Query("SELECT * FROM image_assets")
    suspend fun listAll(): List<ImageAsset>
    @Upsert
    suspend fun upsert(a: ImageAsset)
    @Query("DELETE FROM image_assets WHERE id=:id")
    suspend fun delete(id: String)
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM editor_drafts WHERE id=:id")
    fun observe(id: String): Flow<EditorDraft?>
    @Query("SELECT * FROM editor_drafts WHERE id=:id")
    suspend fun get(id: String): EditorDraft?
    @Query("SELECT * FROM editor_drafts ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<EditorDraft>>
    @Query("SELECT * FROM editor_drafts ORDER BY updatedAt DESC")
    suspend fun listAll(): List<EditorDraft>
    @Upsert
    suspend fun upsert(d: EditorDraft)
    @Query("DELETE FROM editor_drafts WHERE id=:id")
    suspend fun delete(id: String)
}

@Dao
interface ReviewDao {
    @Query("SELECT * FROM review_sessions WHERE courseId=:courseId AND status='ONGOING' ORDER BY createdAt DESC LIMIT 1")
    suspend fun ongoingForCourse(courseId: String): ReviewSession?
    @Query("SELECT * FROM review_sessions WHERE id=:id")
    fun observeSession(id: String): Flow<ReviewSession?>
    @Query("SELECT * FROM review_sessions WHERE id=:id")
    suspend fun getSession(id: String): ReviewSession?
    @Query("SELECT * FROM review_sessions WHERE courseId=:courseId ORDER BY createdAt DESC")
    fun observeHistory(courseId: String): Flow<List<ReviewSession>>
    @Query("SELECT * FROM review_sessions ORDER BY createdAt ASC")
    suspend fun listAllSessions(): List<ReviewSession>
    @Query("SELECT * FROM review_items")
    suspend fun listAllItems(): List<ReviewItem>
    @Upsert
    suspend fun upsertSession(s: ReviewSession)
    @Query("SELECT * FROM review_items WHERE sessionId=:sessionId")
    fun observeItems(sessionId: String): Flow<List<ReviewItem>>
    @Query("SELECT * FROM review_items WHERE sessionId=:sessionId")
    suspend fun listItems(sessionId: String): List<ReviewItem>
    @Upsert
    suspend fun upsertItem(i: ReviewItem)
    @Upsert
    suspend fun upsertItems(items: List<ReviewItem>)
}
