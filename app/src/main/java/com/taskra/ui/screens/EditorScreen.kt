package com.taskra.ui.screens

import com.taskra.ui.components.notebookPaper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.room.withTransaction
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.withFrameNanos
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.core.content.ContextCompat
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.taskra.data.AudioRecorder
import com.taskra.data.audioTime
import com.taskra.ui.components.AudioAttachment
import com.taskra.ui.components.DeadlineDialog
import com.taskra.ui.components.DraftFeedback
import com.taskra.ui.components.EditorToolbar
import com.taskra.ui.components.ImagePasteHost
import com.taskra.ui.components.rememberImagePaste
import com.taskra.ui.components.imagePasteKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.taskra.ui.components.PaperTopBar as TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.taskra.TaskraApp
import com.taskra.data.BlockJson
import com.taskra.data.BlockKind
import com.taskra.data.BlockRegion
import com.taskra.data.ContentBlock
import com.taskra.data.CourseTemplate
import com.taskra.data.decodeTemplate
import com.taskra.ui.components.WeeklyDeadlineDialog
import com.taskra.data.Course
import com.taskra.data.DeadlineType
import com.taskra.data.EditorDraft
import com.taskra.data.Semester
import com.taskra.data.decodeBlocks
import com.taskra.data.encodeBlocks
import com.taskra.ui.AppViewModel
import com.taskra.ui.defaultSemesterName
import com.taskra.util.editableDocument
import com.taskra.util.removeEditorAttachment
import androidx.compose.foundation.clickable
import com.taskra.util.SpanType
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import com.taskra.sync.SyncGates
import java.io.File
import java.time.ZoneId
import java.util.UUID

/** 单文档编辑器：自动保留草稿，确认后发布，图文与录音按光标位置排列。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(
    app: TaskraApp,
    vm: AppViewModel,
    homeworkId: String?, // null=新建
    presetSemesterId: String?,
    presetCourseId: String?,
    onDone: (hasDrafts: Boolean) -> Unit,
    onCancel: () -> Unit,
    templateCourseId: String? = null,
    draftIdOverride: String? = null,
) {
    var editorLockHeld by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        SyncGates.editor.withLock {
            editorLockHeld = true
            try { awaitCancellation() } finally { editorLockHeld = false }
        }
    }
    if (!editorLockHeld) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("正在同步…") }
        return
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val semesters by vm.semesters.collectAsState()
    val coursesAll by vm.coursesAll.collectAsState()
    val prefs by vm.prefs.collectAsState()

    val draftId = if (templateCourseId != null) "template:$templateCourseId" else if (homeworkId == null) draftIdOverride ?: "new" else "edit:$homeworkId"
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
    var showDeadline by remember { mutableStateOf(false) }
    var saveRevision by remember { mutableStateOf(0) }
    var publishing by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var savedSnapshot by remember { mutableStateOf<EditorDraft?>(null) }
    val saveMutex = remember { Mutex() }
    val recorder = remember { AudioRecorder(app) }
    var recording by remember { mutableStateOf(false) }
    var recordingTime by remember { mutableStateOf(0L) }
    var recordingTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }
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
    val documentState = rememberLazyListState()

    LaunchedEffect(pendingFocusId) {
        pendingFocusId?.let { id ->
            try {
                val index = question.indexOfFirst { it.id == id }
                if (index >= 0) documentState.scrollToItem(index)
                withFrameNanos { }
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
    fun insertAttachmentAt(targetId: String, offset: Int, attachment: BlockJson) {
        val idx = question.indexOfFirst { it.id == targetId && it.kind == BlockKind.TEXT }
        if (idx < 0) {
            val img = attachment
            question = editableDocument(question + img)
            activeId = question.lastOrNull { it.kind == BlockKind.TEXT }?.id
            pendingFocusId = activeId
            return
        }
        val b = question[idx]
        val o = offset.coerceIn(0, b.text.length)
        val before = b.copy(id = UUID.randomUUID().toString(), text = b.text.substring(0, o), spans = sliceSpans(b.spans, 0, o))
        val after = BlockJson(UUID.randomUUID().toString(), BlockKind.TEXT, b.text.substring(o), spans = sliceSpans(b.spans, o, b.text.length))
        val img = attachment
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

    val imagePaste = rememberImagePaste { uris, release ->
        if (!loaded || publishing || leaving || submitted || importing > 0) false
        else {
            val target = insertTarget()
            importing++
            scope.launch {
                try {
                    var currentTarget = target
                    for (uri in uris.take(10)) {
                        val image = app.images.importFromUri(uri)
                        insertAttachmentAt(currentTarget.first, currentTarget.second, BlockJson(UUID.randomUUID().toString(), BlockKind.IMAGE, imageId = image.assetId))
                        currentTarget = insertTarget()
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { snackbar.showSnackbar("无法读取剪贴板图片，请重新复制后粘贴") }
                finally { importing--; release() }
            }
            true
        }
    }

    fun applyCourseTemplate(course: Course) {
        courseId = course.id
        semesterId = course.semesterId
        if (homeworkId != null || templateCourseId != null) return
        val template = decodeTemplate(course.templateJson) ?: return
        if (question.all { it.kind == BlockKind.TEXT && it.text.isBlank() }) {
            question = editableDocument(template.blocks.map { it.copy(id = UUID.randomUUID().toString()) })
            fieldValues.clear()
            activeId = null
        }
        deadlineDate = template.nextDate()
        deadlineTime = template.time
        deadlineZone = TimeZoneId()
        deadlineType = if (deadlineDate == null) DeadlineType.NONE else if (deadlineTime == null) DeadlineType.DATE else DeadlineType.DATETIME
    }

    fun draftSnapshot() = EditorDraft(
        id = draftId, homeworkId = homeworkId, title = title,
        semesterId = semesterId, courseId = courseId,
        deadlineType = deadlineType, deadlineDate = deadlineDate,
        deadlineTime = deadlineTime, deadlineZoneId = deadlineZone,
        blocksJson = encodeBlocks(question), solutionJson = "[]", updatedAt = 0,
    )

    // 载入：编辑已有作业 → 正式内容（仅题目区）；否则恢复草稿
    LaunchedEffect(draftId) {
        fieldValues.clear()
        if (templateCourseId != null) {
            val template = decodeTemplate(app.repo.getCourse(templateCourseId)?.templateJson) ?: CourseTemplate()
            val draft = app.repo.getDraft(draftId)
            question = draft?.let { decodeBlocks(it.blocksJson) } ?: template.blocks
            deadlineDate = if (draft != null) draft.deadlineDate else template.weekday?.toString()
            deadlineTime = if (draft != null) draft.deadlineTime else template.time
            deadlineType = if (deadlineDate == null) DeadlineType.NONE else if (deadlineTime == null) DeadlineType.DATE else DeadlineType.DATETIME
        } else if (homeworkId != null) {
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
                    .filter { it.kind in listOf(BlockKind.TEXT, BlockKind.IMAGE, BlockKind.AUDIO) }
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
                    BlockJson(it.id, it.kind, it.text ?: "", it.imageId, decodeSpans(it.spanJson), it.audioId, it.audioDurationMs)
                }.ifEmpty { listOf(BlockJson(UUID.randomUUID().toString(), BlockKind.TEXT, "")) }
            }
        } else {
            val draft = app.repo.getDraft(draftId)
            if (draft != null) {
                title = draft.title
                semesterId = draft.semesterId ?: presetSemesterId
                courseId = draft.courseId ?: presetCourseId
                deadlineType = draft.deadlineType
                deadlineDate = draft.deadlineDate
                deadlineTime = draft.deadlineTime
                deadlineZone = draft.deadlineZoneId ?: TimeZoneId()
                question = decodeBlocks(draft.blocksJson)
                    .filter { it.kind in listOf(BlockKind.TEXT, BlockKind.IMAGE, BlockKind.AUDIO) }
                    .ifEmpty { question }
            } else {
                presetCourseId?.let { app.repo.getCourse(it) }?.let { applyCourseTemplate(it) }
            }
        }
        question = editableDocument(question)
        savedSnapshot = if (templateCourseId == null && homeworkId == null && app.repo.getDraft(draftId) == null && question.any { it.kind != BlockKind.TEXT || it.text.isNotBlank() }) null else draftSnapshot()
        loaded = true
    }

    // 防抖与手动离开共用一把锁；发布先取消并等待旧保存，防止旧草稿复活。
    var saveJob by remember { mutableStateOf<Job?>(null) }
    suspend fun persistDraft(): Boolean = saveMutex.withLock {
        if (submitted) return@withLock true
        val snapshot = draftSnapshot()
        if (snapshot == savedSnapshot) return@withLock true
        saveState = "保存中…"
        try {
            app.repo.saveDraft(snapshot)
            savedSnapshot = snapshot
            saveState = "草稿已保存"
            saveRevision++
            true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { saveState = "保存失败"; false }
    }
    LaunchedEffect(loaded, semesterId, courseId, deadlineType, deadlineDate, deadlineTime, question) {
        if (!loaded || publishing || submitted || leaving || draftSnapshot() == savedSnapshot) return@LaunchedEffect
        saveState = "保存中…"
        saveJob?.cancelAndJoin()
        saveJob = scope.launch { delay(500); persistDraft() }
    }
    suspend fun flushDraftNow(): Boolean {
        saveJob?.cancelAndJoin()
        return persistDraft()
    }
    fun leaveEditor() {
        if (leaving || publishing || submitted || !loaded || importing > 0) return
        leaving = true
        scope.launch {
            if (flushDraftNow()) postOnMain { onCancel() }
            else { leaving = false; snackbar.showSnackbar("草稿保存失败，请重试") }
        }
    }
    BackHandler(enabled = loaded && !imeShown && !recording) { leaveEditor() }

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
                        insertAttachmentAt(target.first, target.second, BlockJson(UUID.randomUUID().toString(), BlockKind.IMAGE, imageId = img.assetId))
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
                    insertAttachmentAt(p.third.first, p.third.second, BlockJson(UUID.randomUUID().toString(), BlockKind.IMAGE, imageId = img.assetId))
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
            if (!flushDraftNow()) {
                snackbar.showSnackbar("草稿保存失败，请重试")
                return@launch
            }
            val (assetId, file) = app.images.cameraTargetFile()
            val uri: Uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            pendingCamera = Triple(assetId, file, bid to off)
            try { takePicture.launch(uri) }
            catch (_: Exception) { pendingCamera = null; snackbar.showSnackbar("无法打开相机") }
        }
    }

    fun launchAlbum() {
        val (bid, off) = insertTarget()
        pendingPhotoTarget = bid to off
        scope.launch {
            if (flushDraftNow()) photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            else snackbar.showSnackbar("草稿保存失败，请重试")
        }
    }

    fun finishRecording() {
        if (!recording) return
        recording = false
        try {
            val audio = recorder.finish()
            val target = recordingTarget ?: insertTarget()
            insertAttachmentAt(target.first, target.second, BlockJson(
                UUID.randomUUID().toString(), BlockKind.AUDIO,
                audioId = audio.id, audioDurationMs = audio.durationMs,
            ))
            scope.launch { flushDraftNow() }
        } catch (_: Exception) {
            scope.launch { snackbar.showSnackbar("录音未保存，请重试") }
        }
        recordingTarget = null
    }
    fun startRecording() {
        try {
            recordingTarget = insertTarget()
            recorder.start()
            recordingTime = 0
            recording = true
        } catch (_: Exception) { scope.launch { snackbar.showSnackbar("无法开始录音，请检查麦克风权限") } }
    }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording()
        else scope.launch { snackbar.showSnackbar("需要麦克风权限才能录音") }
    }
    fun requestRecording() {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
        else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(recording) {
        while (recording) { recordingTime = recorder.elapsedMs; delay(200) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val finishOnStop by rememberUpdatedState { finishRecording() }
    val flushOnStop by rememberUpdatedState {
        if (loaded && !publishing && !submitted && !leaving) scope.launch { flushDraftNow() }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { finishOnStop(); flushOnStop() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); recorder.cancel() }
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
            val line = if (sel.collapsed) lineRangeOf(b.text, sel.start) else sel.min until sel.max
            if (line.isEmpty()) return "这一行没有文字"
            val toggled = if (level == 0) com.taskra.util.clearMark(b.spans, SpanType.HEADING, line)
                else com.taskra.util.setMark(b.spans, SpanType.HEADING, level, line)
            val ns = normalizeSpans(toggled, b.text.length)
            question = question.toMutableList().also { it[idx] = b.copy(spans = ns) }
            refreshField(id, b.text, ns)
            return null
        } else {
            if (sel.collapsed) return "先选中要设置的文字"
            val range = sel.min until sel.max
            val toggled = toggleMark(b.spans, type, 0, range)
            val ns = normalizeSpans(toggled, b.text.length)
            question = question.toMutableList().also { it[idx] = b.copy(spans = ns) }
            refreshField(id, b.text, ns)
            return null
        }
    }

    fun deleteAttachment(blockId: String) {
        val index = question.indexOfFirst { it.id == blockId }
        val before = question.getOrNull(index - 1)
        question = removeEditorAttachment(question, blockId)
        fieldValues.keys.toList().filter { key -> question.none { it.id == key } }.forEach { fieldValues.remove(it) }
        question.filter { it.kind == BlockKind.TEXT }.forEach { block ->
            val previous = fieldValues[block.id]
            val cursor = if (block.id == before?.id) before.text.length else previous?.selection?.start ?: block.text.length
            fieldValues[block.id] = TextFieldValue(
                annotatedForEditing(block.text, block.spans, highlight, baseSize),
                TextRange(cursor.coerceIn(0, block.text.length)),
            )
        }
        activeId = before?.id?.takeIf { id -> question.any { it.id == id } }
            ?: question.firstOrNull { it.kind == BlockKind.TEXT }?.id
        if (imeShown) pendingFocusId = activeId
    }

    suspend fun doSave() {
        if (templateCourseId != null) {
            val template = CourseTemplate(question.filter { it.kind != BlockKind.TEXT || it.text.isNotBlank() }, deadlineDate?.toIntOrNull(), deadlineTime)
            app.repo.db.withTransaction {
                app.repo.saveCourseTemplate(templateCourseId, template)
                app.repo.deleteDraft(draftId)
            }
            submitted = true
            savedSnapshot = draftSnapshot()
            postOnMain { onDone(false) }
            return
        }
        // 去掉空白文字块后校验：至少一段非空文字或一张图片，否则保留为草稿
        val cleaned = question.filter { it.kind != BlockKind.TEXT || it.text.isNotBlank() }
        val hasText = cleaned.any { it.kind == BlockKind.TEXT && it.text.isNotBlank() }
        val hasImg = cleaned.any { it.kind == BlockKind.IMAGE && it.imageId != null }
        if (!hasText && !hasImg && cleaned.none { it.kind == BlockKind.AUDIO && it.audioId != null }) {
            flushDraftNow()
            snackbar.showSnackbar("请添加作业内容")
            return
        }
        val semId = courseId?.let { app.repo.getCourse(it)?.semesterId } ?: ensureSemesterId()
        if (semId == null) {
            snackbar.showSnackbar("课程准备失败，请重试")
            return
        }
        var cId = courseId
        if (cId != null) {
            val c = app.repo.getCourse(cId)
            if (c == null || c.deletedAt != null || c.semesterId != semId) cId = null
        }
        val firstText = cleaned.firstOrNull { it.kind == BlockKind.TEXT && it.text.isNotBlank() }?.text
        val finalTitle = title.trim().ifBlank {
            TitleGenerator.generate(firstText, coursesAll.find { it.id == cId }?.name, deadlineDate).let {
                if (it == "未命名作业") { if (hasImg) "图片作业" else "录音作业" } else it
            }
        }
        app.repo.db.withTransaction {
            if (homeworkId == null) {
                val q = cleaned.mapIndexed { i, b ->
                    ContentBlock(
                        UUID.randomUUID().toString(), "", BlockRegion.QUESTION, b.kind, i,
                        b.text.ifBlank { null }, b.imageId, encodeSpans(b.spans), b.audioId, b.audioDurationMs,
                    )
                }
                app.repo.createHomework(semId, cId, finalTitle, deadlineType, deadlineDate, deadlineTime, deadlineZone, q, emptyList())
                app.repo.deleteDraft(draftId)
            } else {
                val old = app.repo.getHomework(homeworkId) ?: error("作业不存在")
                val q = cleaned.mapIndexed { i, b ->
                    ContentBlock(
                        b.id.ifBlank { UUID.randomUUID().toString() }, homeworkId, BlockRegion.QUESTION, b.kind, i,
                        b.text.ifBlank { null }, b.imageId, encodeSpans(b.spans), b.audioId, b.audioDurationMs,
                    )
                }
                app.repo.saveHomeworkWithBlocks(
                    old.copy(
                        semesterId = semId, courseId = cId, title = finalTitle,
                        deadlineType = deadlineType, deadlineDate = deadlineDate,
                        deadlineTime = deadlineTime, deadlineZoneId = deadlineZone,
                    ),
                    q, app.repo.detail(homeworkId)?.solution.orEmpty(),
                )
                app.repo.deleteDraft(draftId)
            }
        }
        submitted = true
        savedSnapshot = draftSnapshot()
        val hasDrafts = app.repo.db.draftDao().listAll().any { !it.id.startsWith("template:") }
        postOnMain { onDone(hasDrafts) }
    }

    val semCourses = coursesAll
    val courseName = remember(courseId, coursesAll) {
        coursesAll.find { it.id == courseId }?.name ?: "未分类"
    }
    // 当前聚焦块的格式状态（工具条高亮显示）
    val activeBlock = remember(question, activeId) {
        question.firstOrNull { it.id == activeId && it.kind == BlockKind.TEXT }
    }
    val activeSel = fieldValues[activeBlock?.id]?.selection

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(if (templateCourseId != null) "课程模板" else if (homeworkId == null) "新建作业" else "编辑作业", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = { leaveEditor() }, enabled = loaded && !publishing && !leaving && importing == 0) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    DraftFeedback(saveState, saveRevision)
                    FilledIconButton(
                        onClick = {
                            if (publishing || submitted) return@FilledIconButton
                            publishing = true
                            scope.launch {
                                try {
                                    saveJob?.cancelAndJoin()
                                    doSave()
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) { snackbar.showSnackbar("作业保存失败，请重试") }
                                finally { publishing = false }
                            }
                        },
                        enabled = loaded && !publishing && !leaving && importing == 0,
                        modifier = Modifier.padding(end = 12.dp).testTag("publish_homework"),
                    ) {
                        if (publishing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.Check, contentDescription = "确定")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            EditorToolbar(
                visible = imeShown && loaded && activeId != null && !showDeadline && !showNewCourse && !publishing && !leaving,
                onPasteImage = if (imagePaste.available) ({ imagePaste.paste() }) else null,
                formatMode = formatMode, onFormatMode = { formatMode = it },
                onRecord = { requestRecording() }, onAlbum = { launchAlbum() }, onCamera = { launchCamera() },
                bold = activeBlock != null && activeSel != null && !activeSel.collapsed && hasMarkAt(activeBlock.spans, SpanType.BOLD, activeSel.min, activeSel.max),
                highlight = activeBlock != null && activeSel != null && !activeSel.collapsed && hasMarkAt(activeBlock.spans, SpanType.HIGHLIGHT, activeSel.min, activeSel.max),
                sizeLevel = activeBlock?.let { headingLevelAt(it.spans, activeSel?.start ?: 0) } ?: 0,
                onBold = { scope.launch { applyFormat(SpanType.BOLD)?.let { snackbar.showSnackbar(it) } } },
                onHighlight = { scope.launch { applyFormat(SpanType.HIGHLIGHT)?.let { snackbar.showSnackbar(it) } } },
                onSize = { level -> scope.launch { applyFormat(SpanType.HEADING, level)?.let { snackbar.showSnackbar(it) } } },
            )
        },
    ) { pad ->
        if (!loaded) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("加载中…") }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().notebookPaper().padding(pad)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).testTag("editor_metadata")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        androidx.compose.material3.TextButton(onClick = { showCourseMenu = true }, enabled = templateCourseId == null, contentPadding = PaddingValues(0.dp)) {
                            Icon(Icons.Outlined.Book, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(courseName, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = showCourseMenu, onDismissRequest = { showCourseMenu = false }) {
                            DropdownMenuItem(text = { Text("未分类") }, onClick = { courseId = null; showCourseMenu = false })
                            semCourses.forEach { c ->
                                DropdownMenuItem(text = { Text(c.name) }, onClick = { applyCourseTemplate(c); showCourseMenu = false })
                            }
                            DropdownMenuItem(text = { Text("新建课程") }, leadingIcon = { Icon(Icons.Outlined.Add, null) }, onClick = { showCourseMenu = false; showNewCourse = true })
                        }
                    }
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { showDeadline = true }, modifier = Modifier.testTag("deadline_button"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (templateCourseId != null) {
                            if (deadlineDate == null) "每周截止" else "周" + listOf("一", "二", "三", "四", "五", "六", "日")[(deadlineDate!!.toInt() - 1).coerceIn(0, 6)] + (deadlineTime?.let { " $it" } ?: "")
                        } else if (deadlineType == DeadlineType.NONE) "截止时间" else
                            (deadlineDate?.substringAfter("-")?.replace("-", "/") ?: "") +
                                if (deadlineType == DeadlineType.DATETIME) " ${deadlineTime.orEmpty()}" else "", maxLines = 1)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("题目与要求", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("editor_section").clickable { pendingFocusId = question.firstOrNull { it.kind == BlockKind.TEXT }?.id })
            }
            LazyColumn(
                state = documentState,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("editor_document"),
                contentPadding = PaddingValues(24.dp, 8.dp, 24.dp, 80.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
            // 单文档：文字块为无边框输入区，图片块行内展示 + 角标删除
            items(question.size, key = { i -> "q-${question[i].id}" }) { i ->
                val block = question[i]
                if (block.kind == BlockKind.TEXT) {
                    val tfv = fieldOf(block)
                    val fr = remember(block.id) { FocusRequester() }
                    val bivr = remember(block.id) { BringIntoViewRequester() }
                    focusMap[block.id] = fr
                    ImagePasteHost(imagePaste) { BasicTextField(
                        value = tfv,
                        readOnly = publishing || leaving,
                        onValueChange = { updateBlockText(block.id, it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("question_text")
                            .imagePasteKeys(imagePaste)
                            .focusRequester(fr)
                            .bringIntoViewRequester(bivr)
                            .onFocusChanged {
                                if (it.isFocused) {
                                    activeId = block.id
                                    imagePaste.refresh()
                                    scope.launch {
                                        try {
                                            bivr.bringIntoView()
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                            }
                            .heightIn(min = 48.dp)
                            .padding(vertical = 6.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onBackground
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            if (tfv.text.isEmpty() && question.all { it.kind == BlockKind.TEXT && it.text.isBlank() } && block.id == question.first().id) {
                                Text(
                                    "写下题目…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                            inner()
                        },
                    ) }
                } else if (block.kind == BlockKind.AUDIO && block.audioId != null) {
                    AudioAttachment(block.audioId, block.audioDurationMs, onRemove = { deleteAttachment(block.id) })
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
                                onClick = { deleteAttachment(block.id) },
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
            if (importing > 0) item { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
            }
        }
    }
    if (showDeadline && templateCourseId != null) {
        WeeklyDeadlineDialog(deadlineDate?.toIntOrNull(), deadlineTime, onDismiss = { showDeadline = false }) { day, time ->
            deadlineDate = day?.toString(); deadlineTime = time
            deadlineType = if (day == null) DeadlineType.NONE else if (time == null) DeadlineType.DATE else DeadlineType.DATETIME
            showDeadline = false
        }
    }
    if (showDeadline && templateCourseId == null) {
        DeadlineDialog(deadlineDate, deadlineTime, deadlineType, onDismiss = { showDeadline = false }) { type, date, time ->
            deadlineType = type
            deadlineDate = date
            deadlineTime = time
            showDeadline = false
        }
    }
    if (recording) {
        AlertDialog(
            onDismissRequest = { },
            icon = { Icon(Icons.Outlined.GraphicEq, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(audioTime(recordingTime)) },
            confirmButton = { IconButton(onClick = { finishRecording() }) { Icon(Icons.Outlined.Stop, "结束并插入录音") } },
            dismissButton = { IconButton(onClick = { recorder.cancel(); recording = false; recordingTarget = null }) { Icon(Icons.Outlined.Close, "取消录音") } },
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
                            snackbar.showSnackbar("课程准备失败，请重试")
                            return@launch
                        }
                        val dup = app.repo.db.courseDao().listAll().firstOrNull { it.name == name }
                        if (dup?.deletedAt != null) {
                            snackbar.showSnackbar("回收站中有同名课程，请先恢复或彻底删除")
                            return@launch
                        }
                        if (dup != null) {
                            semesterId = dup.semesterId
                            applyCourseTemplate(dup)
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
