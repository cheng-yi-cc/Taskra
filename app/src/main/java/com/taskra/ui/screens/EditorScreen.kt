package com.taskra.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.taskra.TaskraApp
import com.taskra.data.BlockJson
import com.taskra.data.BlockKind
import com.taskra.data.BlockRegion
import com.taskra.data.ContentBlock
import com.taskra.data.Course
import com.taskra.data.DeadlineType
import com.taskra.data.EditorDraft
import com.taskra.data.Semester
import com.taskra.data.decodeBlocks
import com.taskra.data.encodeBlocks
import com.taskra.ui.AppViewModel
import com.taskra.ui.defaultSemesterName
import com.taskra.util.SpanType
import com.taskra.util.TimeUtils
import com.taskra.util.TitleGenerator
import com.taskra.util.adjustSpans
import com.taskra.util.annotatedForEditing
import com.taskra.util.decodeSpans
import com.taskra.util.encodeSpans
import com.taskra.util.hasMarkAt
import com.taskra.util.headingLevelAt
import com.taskra.util.lineRangeOf
import com.taskra.util.normalizeSpans
import com.taskra.util.postOnMain
import com.taskra.util.rememberHighlightColor
import com.taskra.util.sliceSpans
import com.taskra.util.toggleMark
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * 作业编辑器（单文档模式）：
 * - 初始一个输入框；打字即插入文字。
 * - 输入法弹出时，编辑器底部出现一栏工具条：相机 / 相册 / T。
 * - T 切换为文字格式栏（高亮/加粗/变大 + 右侧叉返回），带过渡动画。
 * - 收起输入法时工具条随之消失。
 * - 图片在光标处插入（拆分文字块），点角标叉删除。
 * - 只记录“题目与要求”，无解答区；无学期选择（内部自动归属）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(
    app: TaskraApp,
    vm: AppViewModel,
    homeworkId: String?, // null=新建
    presetSemesterId: String?,
    presetCourseId: String?,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val semesters by vm.semesters.collectAsState()
    val coursesAll by vm.coursesAll.collectAsState()
    val prefs by vm.prefs.collectAsState()

    val draftId = if (homeworkId == null) "new" else "edit:$homeworkId"
    var loaded by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var semesterId by remember { mutableStateOf(presetSemesterId) }
    var courseId by remember { mutableStateOf<String?>(presetCourseId) }
    var deadlineType by remember { mutableStateOf(DeadlineType.NONE) }
    var deadlineDate by remember { mutableStateOf<String?>(null) }
    var deadlineTime by remember { mutableStateOf<String?>(null) }
    var deadlineZone by remember { mutableStateOf(TimeZoneId()) }
    var question by remember { mutableStateOf(listOf(BlockJson(UUID.randomUUID().toString(), BlockKind.TEXT, ""))) }
    var saveState by remember { mutableStateOf("草稿已保存") }
    var importing by remember { mutableStateOf(0) }
    var showCourseMenu by remember { mutableStateOf(false) }
    var showNewCourse by remember { mutableStateOf(false) }
    var newCourseName by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var formatMode by remember { mutableStateOf(false) }
    var activeId by remember { mutableStateOf<String?>(null) }
    var pendingFocusId by remember { mutableStateOf<String?>(null) }
    // 待插入图片的目标（拍照/相册返回后使用）：目标文字块 + 偏移
    var pendingPhotoTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var pendingCamera by remember { mutableStateOf<Triple<String, File, Pair<String, Int>>?>(null) }

    val highlight = rememberHighlightColor()
    val baseSize = MaterialTheme.typography.bodyLarge.fontSize
    // 每个文字块的输入态（含光标/选区），按块 ID 记忆，保证重组不丢光标、不打断输入法组词。
    val fieldValues = remember { mutableStateMapOf<String, TextFieldValue>() }
    // 焦点与滚动定位（普通 Map 即可，不触发重组）
    val focusMap = remember { mutableMapOf<String, FocusRequester>() }
    val bringMap = remember { mutableMapOf<String, BringIntoViewRequester>() }

    LaunchedEffect(pendingFocusId) {
        pendingFocusId?.let { id ->
            try {
                focusMap[id]?.requestFocus()
            } catch (_: Exception) {
            }
            pendingFocusId = null
        }
    }

    // 输入法是否可见：控制底部工具条显隐（ime 访问器是组合函数，先在组合上下文读取）
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    var imeShown by remember { mutableStateOf(false) }
    LaunchedEffect(imeInsets, density) {
        snapshotFlow { imeInsets.getBottom(density) > 0 }.collect { imeShown = it }
    }
    LaunchedEffect(imeShown) {
        if (!imeShown) formatMode = false
    }

    /** 学期兜底：预设 → 当前偏好 → 未归档 → 任意 → 自动新建。创建课程无响应的根因就是这里曾返回空。 */
    suspend fun ensureSemesterId(): String? {
        semesterId?.let { return it }
        prefs.currentSemesterId?.let { semesterId = it; return it }
        val first = semesters.firstOrNull { !it.isArchived } ?: semesters.firstOrNull()
        if (first != null) {
            semesterId = first.id
            return first.id
        }
        return try {
            val id = UUID.randomUUID().toString()
            app.repo.saveSemester(Semester(id, defaultSemesterName(), false, 0))
            app.prefs.setCurrentSemester(id)
            semesterId = id
            id
        } catch (_: Exception) {
            null
        }
    }

    fun fieldOf(block: BlockJson): TextFieldValue =
        fieldValues.getOrPut(block.id) {
            TextFieldValue(annotatedForEditing(block.text, block.spans, highlight, baseSize))
        }

    fun refreshField(id: String, text: String, spans: List<com.taskra.util.SpanMark>) {
        val cur = fieldValues[id]
        fieldValues[id] = (cur ?: TextFieldValue()).copy(
            annotatedString = annotatedForEditing(text, spans, highlight, baseSize)
        )
    }

    fun updateBlockText(id: String, v: TextFieldValue) {
        val idx = question.indexOfFirst { it.id == id }
        if (idx < 0) return
        val old = question[idx]
        val newText = v.annotatedString.text
        if (newText == old.text) {
            fieldValues[id] = v
            return
        }
        val newSpans = normalizeSpans(adjustSpans(old.spans, old.text, newText), newText.length)
        question = question.toMutableList().also { it[idx] = old.copy(text = newText, spans = newSpans) }
        fieldValues[id] = v.copy(annotatedString = annotatedForEditing(newText, newSpans, highlight, baseSize))
    }

    /** 在目标文字块光标处插入图片（拆分文字，前后保留）。 */
    fun insertImageAt(targetId: String, offset: Int, imageId: String) {
        val idx = question.indexOfFirst { it.id == targetId && it.kind == BlockKind.TEXT }
        if (idx < 0) {
            val img = BlockJson(UUID.randomUUID().toString(), BlockKind.IMAGE, "", imageId)
            question = question + img
            return
        }
        val b = question[idx]
        val o = offset.coerceIn(0, b.text.length)
        val before = b.copy(id = UUID.randomUUID().toString(), text = b.text.substring(0, o), spans = sliceSpans(b.spans, 0, o))
        val after = BlockJson(UUID.randomUUID().toString(), BlockKind.TEXT, b.text.substring(o), spans = sliceSpans(b.spans, o, b.text.length))
        val img = BlockJson(UUID.randomUUID().toString(), BlockKind.IMAGE, "", imageId)
        fieldValues.remove(b.id)
        fieldValues[before.id] = TextFieldValue(annotatedForEditing(before.text, before.spans, highlight, baseSize))
        fieldValues[after.id] = TextFieldValue(annotatedForEditing(after.text, after.spans, highlight, baseSize))
        activeId = after.id
        pendingFocusId = after.id // 拆分后焦点跟到后一段，键盘不收起
        question = question.toMutableList().also {
            it[idx] = before
            it.add(idx + 1, img)
            it.add(idx + 2, after)
        }
    }

    /** 工具条插入目标：当前聚焦的文字块 + 光标；无文字块时末尾追加一个。 */
    fun insertTarget(): Pair<String, Int> {
        val id = activeId?.takeIf { aid -> question.any { it.id == aid && it.kind == BlockKind.TEXT } }
            ?: question.lastOrNull { it.kind == BlockKind.TEXT }?.id
        if (id == null) {
            val nid = UUID.randomUUID().toString()
            question = question + BlockJson(nid, BlockKind.TEXT, "")
            fieldValues[nid] = TextFieldValue(AnnotatedString(""))
            activeId = nid
            return nid to 0
        }
        val block = question.first { it.id == id }
        val off = fieldValues[id]?.selection?.start ?: block.text.length
        return id to off.coerceIn(0, block.text.length)
    }

    // 载入：编辑已有作业 → 正式内容（仅题目区）；否则恢复草稿
    LaunchedEffect(draftId) {
        fieldValues.clear()
        if (homeworkId != null) {
            val d = app.repo.detail(homeworkId)
            val draft = app.repo.getDraft(draftId)
            if (draft != null) {
                title = draft.title
                semesterId = draft.semesterId
                courseId = draft.courseId
                deadlineType = draft.deadlineType
                deadlineDate = draft.deadlineDate
                deadlineTime = draft.deadlineTime
                deadlineZone = draft.deadlineZoneId ?: TimeZoneId()
                question = decodeBlocks(draft.blocksJson)
                    .filter { it.kind == BlockKind.TEXT || it.kind == BlockKind.IMAGE }
                    .ifEmpty { listOf(BlockJson(UUID.randomUUID().toString(), BlockKind.TEXT, "")) }
            } else if (d != null) {
                title = d.homework.title
                semesterId = d.homework.semesterId
                courseId = d.homework.courseId
                deadlineType = d.homework.deadlineType
                deadlineDate = d.homework.deadlineDate
                deadlineTime = d.homework.deadlineTime
                deadlineZone = d.homework.deadlineZoneId ?: TimeZoneId()
                question = d.question.map {
                    BlockJson(it.id, it.kind, it.text ?: "", it.imageId, decodeSpans(it.spanJson))
                }.ifEmpty { listOf(BlockJson(UUID.randomUUID().toString(), BlockKind.TEXT, "")) }
            }
        } else {
            val draft = app.repo.getDraft("new")
            if (draft != null) {
                title = draft.title
                semesterId = draft.semesterId ?: presetSemesterId
                courseId = draft.courseId ?: presetCourseId
                deadlineType = draft.deadlineType
                deadlineDate = draft.deadlineDate
                deadlineTime = draft.deadlineTime
                deadlineZone = draft.deadlineZoneId ?: TimeZoneId()
                question = decodeBlocks(draft.blocksJson)
                    .filter { it.kind == BlockKind.TEXT || it.kind == BlockKind.IMAGE }
                    .ifEmpty { question }
            }
        }
        loaded = true
    }

    // 自动保存：输入暂停约500ms后串行写入；只在成功后显示已保存
    var saveJob by remember { mutableStateOf<Job?>(null) }
    fun scheduleAutosave() {
        if (!loaded) return
        saveState = "保存中…"
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            try {
                app.repo.saveDraft(
                    EditorDraft(
                        id = draftId,
                        homeworkId = homeworkId,
                        title = title,
                        semesterId = semesterId,
                        courseId = courseId,
                        deadlineType = deadlineType,
                        deadlineDate = deadlineDate,
                        deadlineTime = deadlineTime,
                        deadlineZoneId = deadlineZone,
                        blocksJson = encodeBlocks(question),
                        solutionJson = "[]",
                    )
                )
                saveState = "草稿已保存"
            } catch (_: Exception) {
                saveState = "保存失败"
            }
        }
    }
    LaunchedEffect(title, semesterId, courseId, deadlineType, deadlineDate, deadlineTime, question) {
        scheduleAutosave()
    }

    suspend fun flushDraftNow() {
        saveJob?.cancel()
        try {
            app.repo.saveDraft(
                EditorDraft(
                    id = draftId, homeworkId = homeworkId, title = title,
                    semesterId = semesterId, courseId = courseId,
                    deadlineType = deadlineType, deadlineDate = deadlineDate,
                    deadlineTime = deadlineTime, deadlineZoneId = deadlineZone,
                    blocksJson = encodeBlocks(question), solutionJson = "[]",
                )
            )
            saveState = "草稿已保存"
        } catch (_: Exception) {
            saveState = "保存失败"
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris ->
        if (uris.isEmpty()) {
            pendingPhotoTarget = null
            return@rememberLauncherForActivityResult
        }
        val start = pendingPhotoTarget
        scope.launch {
            importing = uris.size
            try {
                var insertIdx = question.size
                var target = start
                uris.forEach { uri ->
                    val img = app.images.importFromUri(uri)
                    if (target != null) {
                        insertImageAt(target.first, target.second, img.assetId)
                        // 后续图片顺延到刚插入图片之后
                        val newIdx = question.indexOfFirst { it.kind == BlockKind.IMAGE && it.imageId == img.assetId }
                        insertIdx = if (newIdx >= 0) newIdx + 1 else question.size
                        target = null
                    } else {
                        val imgBlock = BlockJson(UUID.randomUUID().toString(), BlockKind.IMAGE, "", img.assetId)
                        question = question.toMutableList().also {
                            it.add(insertIdx.coerceIn(0, it.size), imgBlock)
                            insertIdx++
                        }
                    }
                }
            } catch (e: Exception) {
                snackbar.showSnackbar("导入失败：${e.message}")
            } finally {
                importing = 0
                pendingPhotoTarget = null
            }
        }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val p = pendingCamera
        scope.launch {
            if (ok && p != null) {
                try {
                    val img = app.images.commitCameraFile(p.first, p.second)
                    insertImageAt(p.third.first, p.third.second, img.assetId)
                } catch (e: Exception) {
                    snackbar.showSnackbar("拍照保存失败：${e.message}")
                }
            }
            pendingCamera = null
        }
    }

    fun launchCamera() {
        val (bid, off) = insertTarget()
        scope.launch {
            flushDraftNow() // 进入相机前提交当前编辑状态
            val (assetId, file) = app.images.cameraTargetFile()
            val uri: Uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            pendingCamera = Triple(assetId, file, bid to off)
            takePicture.launch(uri)
        }
    }

    fun launchAlbum() {
        val (bid, off) = insertTarget()
        pendingPhotoTarget = bid to off
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    /** 格式应用：返回提示文案（空表示成功）。 */
    fun applyFormat(type: String, level: Int = 0): String? {
        val id = activeId?.takeIf { aid -> question.any { it.id == aid && it.kind == BlockKind.TEXT } }
            ?: question.lastOrNull { it.kind == BlockKind.TEXT }?.id
            ?: return "先点一下要编辑的文字"
        val idx = question.indexOfFirst { it.id == id }
        val b = question[idx]
        val tfv = fieldValues[id]
        val sel = tfv?.selection ?: TextRange(b.text.length)
        if (type == SpanType.HEADING) {
            val line = lineRangeOf(b.text, sel.start)
            if (line.isEmpty()) return "这一行没有文字"
            val toggled = toggleMark(b.spans, SpanType.HEADING, level, line)
            val ns = normalizeSpans(toggled, b.text.length)
            question = question.toMutableList().also { it[idx] = b.copy(spans = ns) }
            refreshField(id, b.text, ns)
            return null
        } else {
            if (sel.collapsed) return "先选中要设置的文字"
            val range = sel.min..sel.max
            val toggled = toggleMark(b.spans, type, 0, range)
            val ns = normalizeSpans(toggled, b.text.length)
            question = question.toMutableList().also { it[idx] = b.copy(spans = ns) }
            refreshField(id, b.text, ns)
            return null
        }
    }

    fun deleteImage(blockId: String) {
        question = question.filterNot { it.id == blockId }.ifEmpty {
            listOf(BlockJson(UUID.randomUUID().toString(), BlockKind.TEXT, ""))
        }
        fieldValues.remove(blockId)
    }

    suspend fun doSave() {
        // 去掉空白文字块后校验：至少一段非空文字或一张图片，否则保留为草稿
        val cleaned = question.filter { it.kind == BlockKind.IMAGE || it.text.isNotBlank() }
        val hasText = cleaned.any { it.kind == BlockKind.TEXT && it.text.isNotBlank() }
        val hasImg = cleaned.any { it.kind == BlockKind.IMAGE && it.imageId != null }
        if (!hasText && !hasImg) {
            flushDraftNow()
            snackbar.showSnackbar("题目至少需要一段文字或一张图片，已保留为草稿")
            return
        }
        val semId = ensureSemesterId()
        if (semId == null) {
            snackbar.showSnackbar("学期准备失败，请重试")
            return
        }
        var cId = courseId
        if (cId != null) {
            val c = app.repo.getCourse(cId)
            if (c == null || c.semesterId != semId) cId = null
        }
        val firstText = cleaned.firstOrNull { it.kind == BlockKind.TEXT && it.text.isNotBlank() }?.text
        val finalTitle = title.trim().ifBlank {
            TitleGenerator.generate(firstText, coursesAll.find { it.id == cId }?.name, deadlineDate)
        }
        if (homeworkId == null) {
            val q = cleaned.mapIndexed { i, b ->
                ContentBlock(
                    UUID.randomUUID().toString(), "", BlockRegion.QUESTION, b.kind, i,
                    b.text.ifBlank { null }, b.imageId, encodeSpans(b.spans),
                )
            }
            app.repo.createHomework(semId, cId, finalTitle, deadlineType, deadlineDate, deadlineTime, deadlineZone, q, emptyList())
            app.repo.deleteDraft("new")
            postOnMain { onDone() }
        } else {
            val old = app.repo.getHomework(homeworkId) ?: return
            val q = cleaned.mapIndexed { i, b ->
                ContentBlock(
                    b.id.ifBlank { UUID.randomUUID().toString() }, homeworkId, BlockRegion.QUESTION, b.kind, i,
                    b.text.ifBlank { null }, b.imageId, encodeSpans(b.spans),
                )
            }
            app.repo.saveHomeworkWithBlocks(
                old.copy(
                    semesterId = semId, courseId = cId, title = finalTitle,
                    deadlineType = deadlineType, deadlineDate = deadlineDate,
                    deadlineTime = deadlineTime, deadlineZoneId = deadlineZone,
                ),
                q, emptyList(),
            )
            app.repo.deleteDraft(draftId)
            postOnMain { onDone() }
        }
    }

    val semCourses = remember(coursesAll, semesterId) {
        coursesAll.filter { it.semesterId == semesterId }
    }
    val courseName = remember(courseId, coursesAll) {
        coursesAll.find { it.id == courseId }?.name ?: "未分类"
    }
    // 当前聚焦块的格式状态（工具条高亮显示）
    val activeBlock = remember(question, activeId) {
        question.firstOrNull { it.id == activeId && it.kind == BlockKind.TEXT }
    }
    val activeSel = fieldValues[activeBlock?.id]?.selection

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (homeworkId == null) "新建作业" else "编辑作业") },
                navigationIcon = {
                    IconButton(onClick = {
                        scope.launch { flushDraftNow() }
                        onCancel() // 退出保留草稿
                    }) { Icon(Icons.Outlined.ArrowBack, contentDescription = "返回（保留草稿）") }
                },
                actions = {
                    Text(
                        saveState,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    IconButton(onClick = {
                        saveJob?.cancel() // 先取消待触发的自动保存，避免提交后旧内容复活
                        scope.launch { doSave() }
                    }) { Icon(Icons.Outlined.Check, contentDescription = "保存") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // 输入法上方工具条：输入法收起时随之消失
            AnimatedVisibility(
                visible = imeShown && loaded,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                Surface(
                    tonalElevation = 2.dp,
                    shadowElevation = 1.dp,
                ) {
                    AnimatedContent(targetState = formatMode, label = "toolbar") { fmt ->
                        if (!fmt) {
                            // 主工具条：相机 / 相册 / T
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = { launchCamera() }) {
                                    Icon(Icons.Outlined.PhotoCamera, contentDescription = "相机拍照插入")
                                }
                                IconButton(onClick = { launchAlbum() }) {
                                    Icon(Icons.Outlined.Image, contentDescription = "相册选择插入")
                                }
                                TextButton(onClick = { formatMode = true }) {
                                    Text("T", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                                }
                            }
                        } else {
                            // 文字格式栏：高亮 / H1 H2 H3 / B / 叉（返回）
                            val boldOn = activeBlock != null && activeSel != null && !activeSel.collapsed &&
                                hasMarkAt(activeBlock.spans, SpanType.BOLD, activeSel.min, activeSel.max)
                            val hlOn = activeBlock != null && activeSel != null && !activeSel.collapsed &&
                                hasMarkAt(activeBlock.spans, SpanType.HIGHLIGHT, activeSel.min, activeSel.max)
                            val hLevel = activeBlock?.let { headingLevelAt(it.spans, activeSel?.start ?: 0) } ?: 0
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FormatChip("高亮", boldOn = hlOn, onClick = {
                                    scope.launch {
                                        applyFormat(SpanType.HIGHLIGHT)?.let { snackbar.showSnackbar(it) }
                                    }
                                })
                                FormatChip("H1", boldOn = hLevel == 1, onClick = {
                                    scope.launch { applyFormat(SpanType.HEADING, 1) }
                                })
                                FormatChip("H2", boldOn = hLevel == 2, onClick = {
                                    scope.launch { applyFormat(SpanType.HEADING, 2) }
                                })
                                FormatChip("H3", boldOn = hLevel == 3, onClick = {
                                    scope.launch { applyFormat(SpanType.HEADING, 3) }
                                })
                                FormatChip("B", boldOn = boldOn, isBold = true, onClick = {
                                    scope.launch {
                                        applyFormat(SpanType.BOLD)?.let { snackbar.showSnackbar(it) }
                                    }
                                })
                                IconButton(onClick = { formatMode = false }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "返回工具栏")
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { pad ->
        if (!loaded) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("加载中…") }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题（可留空自动生成）") },
                    modifier = Modifier.fillMaxWidth().testTag("editor_title"),
                    singleLine = true,
                )
            }
            item {
                // 课程选择（含新建课程）；学期由内部自动归属，不再手动选择
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("课程：$courseName", modifier = Modifier.weight(1f))
                    Box {
                        OutlinedButton(onClick = { showCourseMenu = true }) { Text("选择") }
                        DropdownMenu(expanded = showCourseMenu, onDismissRequest = { showCourseMenu = false }) {
                            DropdownMenuItem(text = { Text("未分类") }, onClick = {
                                courseId = null; showCourseMenu = false
                            })
                            semCourses.forEach { c ->
                                DropdownMenuItem(text = { Text(c.name) }, onClick = {
                                    courseId = c.id; showCourseMenu = false
                                })
                            }
                            DropdownMenuItem(text = { Text("＋ 新建课程") }, onClick = {
                                showCourseMenu = false; showNewCourse = true
                            })
                        }
                    }
                }
            }
            item {
                Text("截止时间", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = deadlineType == DeadlineType.NONE, onClick = {
                        deadlineType = DeadlineType.NONE
                    }, label = { Text("不设置") })
                    FilterChip(selected = deadlineType == DeadlineType.DATE, onClick = {
                        deadlineType = DeadlineType.DATE
                        if (deadlineDate == null) deadlineDate = TimeUtils.todayString()
                    }, label = { Text("仅日期") })
                    FilterChip(selected = deadlineType == DeadlineType.DATETIME, onClick = {
                        deadlineType = DeadlineType.DATETIME
                        if (deadlineDate == null) {
                            val (d, t) = TimeUtils.nowDateTimeStrings()
                            deadlineDate = d; deadlineTime = t
                        } else if (deadlineTime == null) {
                            deadlineTime = TimeUtils.nowDateTimeStrings().second
                        }
                    }, label = { Text("日期+时间") })
                }
                if (deadlineType != DeadlineType.NONE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { showDatePicker = true }) {
                            Text(deadlineDate ?: "选择日期")
                        }
                        Spacer(Modifier.width(8.dp))
                        if (deadlineType == DeadlineType.DATETIME) {
                            OutlinedButton(onClick = { showTimePicker = true }) {
                                Text(deadlineTime ?: "选择时间")
                            }
                        }
                    }
                    deadlineDate?.let { d ->
                        val past = try {
                            java.time.LocalDate.parse(d).isBefore(java.time.LocalDate.now())
                        } catch (_: Exception) { false }
                        if (past) Text(
                            "补录过去的截止时间，仅提示，不禁止保存",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Text("题目与要求", style = MaterialTheme.typography.titleMedium)
            }
            // 单文档：文字块为无边框输入区，图片块行内展示 + 角标删除
            items(question.size, key = { i -> "q-${question[i].id}" }) { i ->
                val block = question[i]
                if (block.kind == BlockKind.TEXT) {
                    val tfv = fieldOf(block)
                    val fr = remember(block.id) { FocusRequester() }
                    val bivr = remember(block.id) { BringIntoViewRequester() }
                    focusMap[block.id] = fr
                    bringMap[block.id] = bivr
                    BasicTextField(
                        value = tfv,
                        onValueChange = { updateBlockText(block.id, it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("question_text")
                            .focusRequester(fr)
                            .bringIntoViewRequester(bivr)
                            .onFocusChanged {
                                if (it.isFocused) {
                                    activeId = block.id
                                    scope.launch {
                                        try {
                                            bivr.bringIntoView()
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                            }
                            .padding(vertical = 6.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onBackground
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            if (tfv.text.isEmpty()) {
                                Text(
                                    "在这里记录作业内容…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                            inner()
                        },
                    )
                } else {
                    val imgId = block.imageId
                    if (imgId != null) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            AsyncImage(
                                model = File(app.filesDir, "images/$imgId.jpg"),
                                contentDescription = "作业图片",
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.FillWidth,
                            )
                            IconButton(
                                onClick = { deleteImage(block.id) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                            ) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "删除这张图片",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
            item {
                if (importing > 0) Text("正在导入 $importing 张图片…")
                Text(
                    "点下方文字可继续输入；拍照或选图会插到光标处",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text(
                    "右上角 ✓ 保存（草稿自动保留）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
                        deadlineDate = d.toString()
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) { DatePicker(state) }
    }
    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("时间（24小时制 HH:mm）") },
            text = {
                var t by remember { mutableStateOf(deadlineTime ?: "18:00") }
                Column {
                    OutlinedTextField(value = t, onValueChange = { t = it }, singleLine = true, placeholder = { Text("如 18:30") })
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        if (Regex("""\d{1,2}:\d{2}""").matches(t.trim())) {
                            deadlineTime = t.trim()
                            showTimePicker = false
                        }
                    }) { Text("确定") }
                }
            },
            confirmButton = { },
        )
    }
    if (showNewCourse) {
        AlertDialog(
            onDismissRequest = { showNewCourse = false },
            title = { Text("新建课程") },
            text = {
                OutlinedTextField(value = newCourseName, onValueChange = { newCourseName = it }, label = { Text("课程名") }, singleLine = true, modifier = Modifier.testTag("course_name_field"))
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val name = newCourseName.trim()
                        if (name.isEmpty()) return@launch
                        val semId = ensureSemesterId()
                        if (semId == null) {
                            snackbar.showSnackbar("学期准备失败，请重试")
                            return@launch
                        }
                        val dup = app.repo.findDuplicateCourse(semId, name)
                        if (dup != null) {
                            courseId = dup.id
                            snackbar.showSnackbar("已存在同名课程，已直接选用")
                        } else {
                            val id = UUID.randomUUID().toString()
                            app.repo.saveCourse(Course(id, semId, name, (0..7).random()))
                            courseId = id
                        }
                        newCourseName = ""
                        showNewCourse = false
                    }
                }) { Text("创建并选用") }
            },
            dismissButton = { TextButton(onClick = { showNewCourse = false }) { Text("取消") } },
        )
    }
}

private fun TimeZoneId(): String = ZoneId.systemDefault().id

@Composable
private fun FormatChip(label: String, boldOn: Boolean, isBold: Boolean = false, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (boldOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        ),
    ) {
        Text(
            label,
            style = if (isBold || boldOn) {
                MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            } else {
                MaterialTheme.typography.titleMedium
            },
        )
    }
}
