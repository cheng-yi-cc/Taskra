package com.taskra.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskra.TaskraApp
import com.taskra.data.Course
import com.taskra.data.Homework
import com.taskra.data.Semester
import com.taskra.data.SortMode
import com.taskra.data.Status
import com.taskra.data.ThemeMode
import com.taskra.data.UserPrefs
import com.taskra.util.HomeworkSort
import com.taskra.util.TimeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(private val app: TaskraApp) : ViewModel() {
    init {
        // 学期兜底：首装或数据为空时自动建立默认学期，保证编辑器/课程创建永远有学期可用。
        viewModelScope.launch {
            try {
                if (app.repo.listSemesters().isEmpty()) {
                    val id = java.util.UUID.randomUUID().toString()
                    app.repo.saveSemester(Semester(id, defaultSemesterName(), false, 0))
                    app.prefs.setCurrentSemester(id)
                }
            } catch (_: Exception) {
            }
        }
    }
    val prefs: StateFlow<UserPrefs> = app.prefs.prefs
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserPrefs())
    val semesters: StateFlow<List<Semester>> = app.repo.observeSemesters()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val coursesAll: StateFlow<List<Course>> = app.repo.observeCoursesAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val todoAll: StateFlow<List<Homework>> = app.repo.observeTodo()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 作业页“标记完成”后返回待办时要补播的动效：
     * 待办页切走会丢 remember 状态，所以卡片快照放在 ViewModel 里。
     */
    var pendingCheckId: String? = null
    var lastOpenedItem: TodoUiItem? = null
    var lastOpenedIndex: Int = 0

    // 待办筛选状态
    val query = MutableStateFlow("")
    val filterCourseId = MutableStateFlow<String?>(null) // null=全部，"UNCAT"=未分类
    val filterDue = MutableStateFlow("ALL") // ALL/OVERDUE/TODAY/WEEK/NONE
    val filterSemesterId = MutableStateFlow<String?>(null) // 按学期过滤（可选）

    fun saveOrder(key: String, ids: List<String>, onFailure: () -> Unit) = viewModelScope.launch {
        try { app.prefs.setListOrder(key, ids) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { com.taskra.util.postOnMain(onFailure) }
    }
    fun deleteDraft(id: String, onFailure: () -> Unit) = viewModelScope.launch {
        try { app.repo.deleteDraft(id) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { com.taskra.util.postOnMain(onFailure) }
    }
    fun setMottoVisible(v: Boolean) = viewModelScope.launch { app.prefs.setMottoVisible(v) }
    fun saveMotto(t: String) = viewModelScope.launch { app.prefs.setMotto(t) }
    fun setSort(m: String) = viewModelScope.launch { app.prefs.setSort(m) }
    fun setTheme(m: String) = viewModelScope.launch { app.prefs.setTheme(m) }
    fun setRemindersEnabled(enabled: Boolean) = viewModelScope.launch { app.prefs.setRemindersEnabled(enabled) }
    fun setReminderTime(minuteOfDay: Int) = viewModelScope.launch { app.prefs.setReminderTime(minuteOfDay) }
    fun setCurrentSemester(id: String?) = viewModelScope.launch { app.prefs.setCurrentSemester(id) }

    fun complete(id: String, onFailure: () -> Unit) = viewModelScope.launch {
        try { app.repo.markDone(id) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { com.taskra.util.postOnMain(onFailure) }
    }
    fun trashHomework(id: String, onFailure: () -> Unit) = viewModelScope.launch {
        try { app.repo.moveToTrash(id) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { com.taskra.util.postOnMain(onFailure) }
    }
    fun trashCourse(id: String, onFailure: () -> Unit) = viewModelScope.launch {
        try { app.repo.moveCourseToTrash(id) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { com.taskra.util.postOnMain(onFailure) }
    }
    fun undoComplete(id: String) = viewModelScope.launch { app.repo.markTodo(id) }

    @Suppress("unused")
    class Factory(private val app: TaskraApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(app) as T
    }
}

data class TodoUiItem(
    val h: Homework,
    val courseName: String?,
    val courseColor: Int,
    val overdue: Boolean,
    val dueText: String,
    val summary: String,
    val imageCount: Int,
)

/** 默认学期名：按当前月份推算春秋学期。 */
fun defaultSemesterName(): String {
    return try {
        val now = java.time.LocalDate.now()
        val term = if (now.monthValue in 2..7) "春季" else "秋季"
        "${now.year} $term"
    } catch (_: Exception) {
        "默认学期"
    }
}

/** 待办过滤+排序纯函数，便于单元测试。 */
fun filterTodo(
    all: List<Homework>,
    courses: Map<String, Course>,
    query: String,
    filterCourseId: String?,
    filterDue: String,
    filterSemesterId: String?,
    summaries: Map<String, Pair<String, Int>> = emptyMap(),
    searchTexts: Map<String, String> = emptyMap(),
    clock: com.taskra.util.AppClock = com.taskra.util.SystemClock,
): List<Homework> {
    var list = all.filter { it.status == Status.TODO && it.deletedAt == null }
    if (!filterSemesterId.isNullOrBlank()) list = list.filter { it.semesterId == filterSemesterId }
    if (filterCourseId == "UNCAT") list = list.filter { it.courseId == null }
    else if (!filterCourseId.isNullOrBlank()) list = list.filter { it.courseId == filterCourseId }
    when (filterDue) {
        "OVERDUE" -> list = list.filter {
            TimeUtils.isOverdue(it.deadlineType, it.deadlineDate, it.deadlineTime, it.deadlineZoneId, clock)
        }
        "TODAY" -> list = list.filter {
            TimeUtils.isDueToday(it.deadlineType, it.deadlineDate, it.deadlineZoneId, clock)
        }
        "WEEK" -> list = list.filter {
            TimeUtils.isInNext7Days(it.deadlineType, it.deadlineDate, it.deadlineTime, it.deadlineZoneId, clock)
        }
        "NONE" -> list = list.filter { it.deadlineType == com.taskra.data.DeadlineType.NONE }
    }
    val q = query.trim()
    if (q.isNotEmpty()) {
        list = list.filter { h ->
            h.title.contains(q, ignoreCase = true) ||
                (searchTexts[h.id]?.contains(q, ignoreCase = true) == true) ||
                (summaries[h.id]?.first?.contains(q, ignoreCase = true) == true) ||
                (h.courseId?.let { courses[it]?.name }?.contains(q, ignoreCase = true) == true)
        }
    }
    return list
}
