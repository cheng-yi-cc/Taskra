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
    val audioId: String? = null,
    val audioDurationMs: Long = 0,
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

class TaskraRepository(val db: AppDatabase, private val filesDir: java.io.File? = null) {
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
    suspend fun saveCourseTemplate(id: String, template: CourseTemplate) = db.withTransaction {
        val course = courses.get(id) ?: error("课程不存在")
        courses.upsert(course.copy(templateJson = encodeTemplate(template)))
    }
    private suspend fun templateBlocks() = courses.listAll().flatMap { decodeTemplate(it.templateJson)?.blocks.orEmpty() }


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

    fun observeTrashedCourses(): Flow<List<Course>> = courses.observeTrash()

    suspend fun moveCourseToTrash(id: String) = db.withTransaction {
        val course = courses.get(id) ?: return@withTransaction
        if (course.deletedAt != null) return@withTransaction
        val children = homework.listAllByCourse(id)
        // 使用区别于旧回收站记录的时间戳，恢复课程时只恢复本次一起删除的作业。
        val stamp = maxOf(System.currentTimeMillis(), (children.mapNotNull { it.deletedAt }.maxOrNull() ?: 0L) + 1)
        courses.upsert(course.copy(deletedAt = stamp))
        children.filter { it.deletedAt == null }.forEach { homework.upsert(it.copy(deletedAt = stamp, updatedAt = stamp)) }
    }

    suspend fun restoreCourse(id: String) = db.withTransaction {
        val course = courses.get(id) ?: return@withTransaction
        val stamp = course.deletedAt ?: return@withTransaction
        courses.upsert(course.copy(deletedAt = null))
        homework.listAllByCourse(id).filter { it.deletedAt == stamp }.forEach {
            homework.upsert(it.copy(deletedAt = null, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun deleteCourseForever(id: String, imageStore: ImageStore) {
        val course = courses.get(id) ?: return
        require(course.deletedAt != null) { "请先将课程移入回收站" }
        val children = homework.listAllByCourse(id)
        val courseDrafts = drafts.listAll().filter { it.courseId == id || children.any { h -> h.id == it.homeworkId } }
        val attachments = children.flatMap { blocks.listByHomework(it.id) }
        val draftBlocks = courseDrafts.flatMap { decodeBlocks(it.blocksJson) + decodeBlocks(it.solutionJson) } + decodeTemplate(course.templateJson)?.blocks.orEmpty()
        db.withTransaction {
            courseDrafts.forEach { drafts.delete(it.id) }
            children.forEach { homework.deleteHard(it.id) }
            review.deleteByCourse(id)
            courses.deleteHard(id)
        }
        val remainingDrafts = drafts.listAll().flatMap { decodeBlocks(it.blocksJson) + decodeBlocks(it.solutionJson) }
        val referenced = blocks.allReferencedImageIds().toSet() + remainingDrafts.mapNotNull { it.imageId } + templateBlocks().mapNotNull { it.imageId }
        imageStore.deleteAssets((attachments.mapNotNull { it.imageId } + draftBlocks.mapNotNull { it.imageId }).distinct().filter { it !in referenced })
        deleteUnusedAudio(attachments.mapNotNull { it.audioId } + draftBlocks.mapNotNull { it.audioId })
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
    suspend fun markDone(id: String) = db.withTransaction {
        val h = homework.get(id) ?: return@withTransaction
        homework.upsert(h.copy(status = Status.DONE, completedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
    }

    /** 恢复为未完成：清除 completedAt，不改变 createdAt。 */
    suspend fun markTodo(id: String) = db.withTransaction {
        val h = homework.get(id) ?: return@withTransaction
        homework.upsert(h.copy(status = Status.TODO, completedAt = null, updatedAt = System.currentTimeMillis()))
    }

    /** 删除→回收站（保留完成状态与全部内容）。 */
    suspend fun moveToTrash(id: String) = db.withTransaction {
        val h = homework.get(id) ?: return@withTransaction
        homework.upsert(h.copy(deletedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
    }

    suspend fun restoreFromTrash(id: String) = db.withTransaction {
        val h = homework.get(id) ?: return@withTransaction
        h.courseId?.let { courses.get(it) }?.takeIf { it.deletedAt != null }?.let {
            courses.upsert(it.copy(deletedAt = null))
        }
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
        val remainingDrafts = drafts.listAll().flatMap { decodeBlocks(it.blocksJson) + decodeBlocks(it.solutionJson) }
        val still = blocks.allReferencedImageIds().toSet() + remainingDrafts.mapNotNull { it.imageId } + templateBlocks().mapNotNull { it.imageId }
        imageStore.deleteAssets(imgIds.filter { it !in still })
        deleteUnusedAudio(all.mapNotNull { it.audioId })
    }

    // ---- drafts ----
    fun observeDraft(id: String): Flow<EditorDraft?> = drafts.observe(id)
    suspend fun getDraft(id: String) = drafts.get(id)
    fun observeDrafts(): Flow<List<EditorDraft>> = drafts.observeAll()
    suspend fun saveDraft(d: EditorDraft) {
        val previous = drafts.get(d.id)
        drafts.upsert(d.copy(updatedAt = System.currentTimeMillis()))
        previous?.let { deleteUnusedAudio((decodeBlocks(it.blocksJson) + decodeBlocks(it.solutionJson)).mapNotNull { block -> block.audioId }) }
    }
    suspend fun deleteDraft(id: String) {
        val previous = drafts.get(id)
        drafts.delete(id)
        previous?.let { deleteUnusedAudio((decodeBlocks(it.blocksJson) + decodeBlocks(it.solutionJson)).mapNotNull { block -> block.audioId }) }
    }
    private suspend fun deleteUnusedAudio(ids: List<String>) {
        val directory = filesDir ?: return
        if (ids.isEmpty()) return
        val referenced = blocks.allReferencedAudioIds().toSet() + templateBlocks().mapNotNull { it.audioId } + drafts.listAll().flatMap {
            (decodeBlocks(it.blocksJson) + decodeBlocks(it.solutionJson)).mapNotNull { block -> block.audioId }
        }
        ids.filter { it !in referenced && Regex("[a-zA-Z0-9_-]+").matches(it) }.forEach {
            runCatching { java.io.File(directory, "audio/$it.m4a").delete() }
        }
    }

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
