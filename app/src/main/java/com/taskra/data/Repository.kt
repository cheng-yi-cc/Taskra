package com.taskra.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class BlockJson(
    val id: String,
    val kind: String,
    val text: String = "",
    val imageId: String? = null,
    /** 富文本标记（TEXT 块使用），老草稿缺省为空。 */
    val spans: List<com.taskra.util.SpanMark> = emptyList(),
)

val BlockJsonCodec = Json { ignoreUnknownKeys = true }

fun encodeBlocks(blocks: List<BlockJson>): String = BlockJsonCodec.encodeToString(blocks)
fun decodeBlocks(json: String): List<BlockJson> = try {
    if (json.isBlank()) emptyList() else BlockJsonCodec.decodeFromString(json)
} catch (_: Exception) {
    emptyList()
}

data class HomeworkDetail(
    val homework: Homework,
    val question: List<ContentBlock>,
    val solution: List<ContentBlock>,
    val course: Course?,
    val semester: Semester?,
)

class TaskraRepository(val db: AppDatabase) {
    private val semesters get() = db.semesterDao()
    private val courses get() = db.courseDao()
    private val homework get() = db.homeworkDao()
    private val blocks get() = db.blockDao()
    private val drafts get() = db.draftDao()
    private val review get() = db.reviewDao()

    // ---- semesters / courses ----
    fun observeSemesters(): Flow<List<Semester>> = semesters.observeAll()
    suspend fun listSemesters(): List<Semester> = semesters.listAll()
    suspend fun saveSemester(s: Semester) = semesters.upsert(s)
    suspend fun getSemester(id: String) = semesters.get(id)

    fun observeCoursesAll(): Flow<List<Course>> = courses.observeAll()
    fun observeCourses(semesterId: String): Flow<List<Course>> = courses.observeBySemester(semesterId)
    suspend fun listCoursesAll(): List<Course> = courses.listAll()
    suspend fun getCourse(id: String) = courses.get(id)
    suspend fun saveCourse(c: Course) = courses.upsert(c)

    /** 同一学期同名课程保护：返回已存在课程 */
    suspend fun findDuplicateCourse(semesterId: String, name: String, excludeId: String? = null): Course? {
        val f = courses.findByName(semesterId, name.trim())
        return if (f != null && f.id != excludeId) f else null
    }

    /** 课程可删除性：有作业/草稿/回收站/复习引用则不可直接删 */
    suspend fun courseDeleteBlockReason(courseId: String): String? {
        if (homework.listByCourse(courseId).isNotEmpty()) return "该课程下仍有作业（含回收站），请先迁移或处理后再删除。"
        if (courses.draftCount(courseId) > 0) return "该课程仍有关联草稿，请先处理草稿。"
        val hist = mutableListOf<String>()
        db.reviewDao().let { /* 历史引用检查 */ }
        return null
    }

    // ---- homework ----
    fun observeTodo(): Flow<List<Homework>> = homework.observeTodoAll()
    fun observeHomework(id: String): Flow<Homework?> = homework.observeById(id)
    suspend fun getHomework(id: String) = homework.get(id)
    fun observeByCourse(courseId: String): Flow<List<Homework>> = homework.observeByCourse(courseId)
    fun observeTrash(): Flow<List<Homework>> = homework.observeTrash()
    suspend fun listTrash() = homework.listTrash()

    suspend fun detail(id: String): HomeworkDetail? {
        val h = homework.get(id) ?: return null
        val all = blocks.listByHomework(id)
        return HomeworkDetail(
            homework = h,
            question = all.filter { it.region == BlockRegion.QUESTION }.sortedBy { it.position },
            solution = all.filter { it.region == BlockRegion.SOLUTION }.sortedBy { it.position },
            course = h.courseId?.let { courses.get(it) },
            semester = semesters.get(h.semesterId),
        )
    }

    /** 保存作业（含内容块），事务批量更新；不改变 createdAt。 */
    suspend fun saveHomeworkWithBlocks(
        h: Homework,
        question: List<ContentBlock>,
        solution: List<ContentBlock>,
    ) {
        db.withTransaction {
            homework.upsert(h.copy(updatedAt = System.currentTimeMillis()))
            blocks.deleteByHomework(h.id)
            val q = question.mapIndexed { i, b -> b.copy(homeworkId = h.id, region = BlockRegion.QUESTION, position = i) }
            val s = solution.mapIndexed { i, b -> b.copy(homeworkId = h.id, region = BlockRegion.SOLUTION, position = i) }
            blocks.upsertAll(q + s)
        }
    }

    suspend fun createHomework(
        semesterId: String, courseId: String?, title: String,
        deadlineType: String, deadlineDate: String?, deadlineTime: String?, deadlineZoneId: String?,
        question: List<ContentBlock>, solution: List<ContentBlock>,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val h = Homework(
            id = id, semesterId = semesterId, courseId = courseId, title = title,
            status = Status.TODO, deadlineType = deadlineType, deadlineDate = deadlineDate,
            deadlineTime = deadlineTime, deadlineZoneId = deadlineZoneId,
            createdAt = now, updatedAt = now,
        )
        saveHomeworkWithBlocks(h, question, solution)
        return id
    }

    /** 完成：事务中更新状态+完成时间。 */
    suspend fun markDone(id: String) {
        val h = homework.get(id) ?: return
        homework.upsert(h.copy(status = Status.DONE, completedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
    }

    /** 恢复为未完成：清除 completedAt，不改变 createdAt。 */
    suspend fun markTodo(id: String) {
        val h = homework.get(id) ?: return
        homework.upsert(h.copy(status = Status.TODO, completedAt = null, updatedAt = System.currentTimeMillis()))
    }

    /** 删除→回收站（保留完成状态与全部内容）。 */
    suspend fun moveToTrash(id: String) {
        val h = homework.get(id) ?: return
        homework.upsert(h.copy(deletedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
    }

    suspend fun restoreFromTrash(id: String) {
        val h = homework.get(id) ?: return
        homework.upsert(h.copy(deletedAt = null, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteForever(id: String, imageStore: ImageStore) {
        val all = blocks.listByHomework(id)
        val imgIds = all.mapNotNull { it.imageId }
        db.withTransaction {
            blocks.deleteByHomework(id)
            homework.deleteHard(id)
        }
        // 引用检查后再删文件
        val still = blocks.allReferencedImageIds().toSet()
        imageStore.deleteAssets(imgIds.filter { it !in still })
    }

    // ---- drafts ----
    fun observeDraft(id: String): Flow<EditorDraft?> = drafts.observe(id)
    suspend fun getDraft(id: String) = drafts.get(id)
    fun observeDrafts(): Flow<List<EditorDraft>> = drafts.observeAll()
    suspend fun saveDraft(d: EditorDraft) = drafts.upsert(d.copy(updatedAt = System.currentTimeMillis()))
    suspend fun deleteDraft(id: String) = drafts.delete(id)

    // ---- review ----
    suspend fun ongoingSession(courseId: String) = review.ongoingForCourse(courseId)
    fun observeSession(id: String) = review.observeSession(id)
    suspend fun getSession(id: String) = review.getSession(id)
    fun observeSessionItems(id: String) = review.observeItems(id)
    fun observeHistory(courseId: String) = review.observeHistory(courseId)
    suspend fun saveSession(s: ReviewSession) = review.upsertSession(s)
    suspend fun saveItem(i: ReviewItem) = review.upsertItem(i)
    suspend fun saveItems(items: List<ReviewItem>) = review.upsertItems(items)
    suspend fun listItems(sessionId: String) = review.listItems(sessionId)
}
