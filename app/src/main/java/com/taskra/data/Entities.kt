package com.taskra.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "semesters")
@Serializable
data class Semester(
    @PrimaryKey val id: String,
    val name: String,
    val isArchived: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "courses",
    foreignKeys = [ForeignKey(
        entity = Semester::class,
        parentColumns = ["id"],
        childColumns = ["semesterId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index("semesterId"), Index(value = ["semesterId", "name"], unique = true)],
)
@Serializable
data class Course(
    @PrimaryKey val id: String,
    val semesterId: String,
    val name: String,
    val colorIndex: Int = 0,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "homework",
    foreignKeys = [
        ForeignKey(entity = Semester::class, parentColumns = ["id"], childColumns = ["semesterId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = Course::class, parentColumns = ["id"], childColumns = ["courseId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index("semesterId"), Index("courseId"), Index("status"),
        Index("deletedAt"), Index("createdAt"), Index("completedAt"),
    ],
)
@Serializable
data class Homework(
    @PrimaryKey val id: String,
    val semesterId: String,
    val courseId: String?,
    val title: String,
    val status: String = Status.TODO, // TODO / DONE
    val deadlineType: String = DeadlineType.NONE,
    val deadlineDate: String? = null, // yyyy-MM-dd
    val deadlineTime: String? = null, // HH:mm
    val deadlineZoneId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val deletedAt: Long? = null, // 删除=进回收站
)

@Entity(
    tableName = "content_blocks",
    foreignKeys = [ForeignKey(
        entity = Homework::class, parentColumns = ["id"], childColumns = ["homeworkId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("homeworkId"), Index(value = ["homeworkId", "region", "position"])],
)
@Serializable
data class ContentBlock(
    @PrimaryKey val id: String,
    val homeworkId: String,
    val region: String = BlockRegion.QUESTION, // QUESTION / SOLUTION（SOLUTION 为历史数据，新版不再创建与展示）
    val kind: String = BlockKind.TEXT, // TEXT / IMAGE
    val position: Int = 0,
    val text: String? = null,
    val imageId: String? = null,
    /** 富文本标记 JSON（List<SpanMark>）：加粗/高亮/标题级别；纯文本为空数组。 */
    val spanJson: String = "[]",
)

@Entity(tableName = "image_assets")
@Serializable
data class ImageAsset(
    @PrimaryKey val id: String,
    val relativePath: String, // 相对 filesDir，如 images/xxx.jpg，不存绝对路径
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "editor_drafts")
@Serializable
data class EditorDraft(
    @PrimaryKey val id: String, // "new" 单例 或 "edit:<homeworkId>"
    val homeworkId: String? = null,
    val title: String = "",
    val semesterId: String? = null,
    val courseId: String? = null,
    val deadlineType: String = DeadlineType.NONE,
    val deadlineDate: String? = null,
    val deadlineTime: String? = null,
    val deadlineZoneId: String? = null,
    val blocksJson: String = "[]",
    val solutionJson: String = "[]",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "review_sessions",
    foreignKeys = [ForeignKey(
        entity = Course::class, parentColumns = ["id"], childColumns = ["courseId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index("courseId")],
)
@Serializable
data class ReviewSession(
    @PrimaryKey val id: String,
    val courseId: String,
    val status: String = SessionStatus.ONGOING,
    val homeworkIdsJson: String = "[]", // 开始时固定的作业ID快照
    val currentIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
)

@Entity(
    tableName = "review_items",
    primaryKeys = ["sessionId", "homeworkId"],
    foreignKeys = [ForeignKey(
        entity = ReviewSession::class, parentColumns = ["id"], childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
@Serializable
data class ReviewItem(
    val sessionId: String,
    val homeworkId: String,
    val result: String = ReviewResult.NONE,
    val practicedAt: Long? = null,
)
