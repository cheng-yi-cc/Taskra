package com.taskra

import android.app.UiModeManager
import android.content.ComponentCallbacks
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.taskra.data.ThemeMode
import com.taskra.ui.AppViewModel
import com.taskra.ui.components.OpeningAnimation
import com.taskra.ui.motion.LocalReduceMotion
import com.taskra.ui.motion.TaskraMotion
import com.taskra.ui.motion.applePressScale
import com.taskra.ui.screens.CourseDetailScreen
import com.taskra.ui.screens.CoursesScreen
import com.taskra.ui.screens.DraftsScreen
import com.taskra.ui.screens.EditorScreen
import com.taskra.ui.screens.ImageViewerScreen
import com.taskra.ui.screens.ReviewScreen
import com.taskra.ui.screens.SettingsScreen
import com.taskra.ui.screens.TodoScreen
import com.taskra.ui.screens.TrashScreen
import com.taskra.ui.theme.TaskraTheme

class MainActivity : ComponentActivity() {
    private var reminderHomeworkId by mutableStateOf<String?>(null)

    /**
     * 系统是否处于深色模式。
     * 在红米/小米澎湃 OS (HyperOS / MIUI) 等定制 ROM 上，非白名单应用的 Activity
     * Configuration.uiMode 可能被系统“单应用深色策略”拦截，且下拉控制中心切换深浅色时
     * Activity 未必重建或触发 onResume。因此通过：
     * 1) Settings.Secure("ui_night_mode") + UiModeManager.nightMode + Configuration.uiMode 多源读取；
     * 2) ContentObserver 实时监听 ui_night_mode 变化 + Application ComponentCallbacks +
     *    Activity onConfigurationChanged / onStart / onResume / onWindowFocusChanged 全链路刷新，
     * 确保前台下拉控制中心秒切、分屏/小窗实时切换、切后台返回均立即生效。
     */
    private var systemDark by mutableStateOf(false)

    private fun nightOf(config: Configuration): Boolean =
        (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun resolveSystemDark(configHint: Configuration? = null): Boolean {
        val secureMode = runCatching {
            Settings.Secure.getInt(contentResolver, "ui_night_mode", -1)
        }.getOrDefault(-1)
        if (secureMode == UiModeManager.MODE_NIGHT_YES) return true
        if (secureMode == UiModeManager.MODE_NIGHT_NO) return false

        val uiModeManager = getSystemService(UiModeManager::class.java)
        when (uiModeManager?.nightMode) {
            UiModeManager.MODE_NIGHT_YES -> return true
            UiModeManager.MODE_NIGHT_NO -> return false
        }

        if (configHint != null) return nightOf(configHint)
        return nightOf(Resources.getSystem().configuration) ||
            nightOf(applicationContext.resources.configuration) ||
            nightOf(resources.configuration)
    }

    private fun refreshSystemDark(configHint: Configuration? = null) {
        systemDark = resolveSystemDark(configHint)
    }

    private val nightModeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshSystemDark()
        }
    }

    private val appConfigCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            refreshSystemDark(newConfig)
        }

        override fun onLowMemory() = Unit
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshSystemDark(newConfig)
    }

    override fun onStart() {
        super.onStart()
        refreshSystemDark()
    }

    override fun onResume() {
        super.onResume()
        refreshSystemDark()
        (application as TaskraApp).reminders.refresh()
        (application as TaskraApp).updater.onAppResumed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshSystemDark()
    }

    override fun onDestroy() {
        runCatching { contentResolver.unregisterContentObserver(nightModeObserver) }
        runCatching { applicationContext.unregisterComponentCallbacks(appConfigCallbacks) }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        reminderHomeworkId = intent.getStringExtra(com.taskra.reminders.HomeworkReminders.EXTRA_HOMEWORK_ID)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) reminderHomeworkId = intent.getStringExtra(com.taskra.reminders.HomeworkReminders.EXTRA_HOMEWORK_ID)
        refreshSystemDark()
        runCatching {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor("ui_night_mode"),
                false,
                nightModeObserver,
            )
        }
        runCatching { applicationContext.registerComponentCallbacks(appConfigCallbacks) }
        enableEdgeToEdge()
        val app = application as TaskraApp
        setContent {
            val vm: AppViewModel = viewModel(factory = AppViewModel.Factory(app))
            val prefs by vm.prefs.collectAsState()
            val todo by vm.todoAll.collectAsState()
            val dark = when (prefs.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                else -> systemDark
            }
            TaskraTheme(darkTheme = dark) {
                val nav = rememberNavController()
                val backStack by nav.currentBackStackEntryAsState()
                val route = backStack?.destination?.route ?: "todo"
                val showBottom = route in listOf("todo", "courses", "settings")
                var showOpening by rememberSaveable { mutableStateOf(!app.openingShown && savedInstanceState == null) }
                LaunchedEffect(Unit) { app.openingShown = true }
                LaunchedEffect(showOpening) {
                    if (!showOpening) app.updater.checkOnStartupIfNeeded()
                }
                com.taskra.update.UpdateDialogHost(app.updater)
                com.taskra.ui.components.ReminderPermissionPrompt(app, prefs.remindersEnabled && !showOpening,
                    todo.any { it.deadlineType != com.taskra.data.DeadlineType.NONE })
                LaunchedEffect(reminderHomeworkId) {
                    val id = reminderHomeworkId ?: return@LaunchedEffect
                    val homework = app.repo.getHomework(id)
                    if (homework != null && homework.deletedAt == null) {
                        nav.navigate("homework/${Uri.encode(id)}") { launchSingleTop = true }
                    }
                    reminderHomeworkId = null
                }
                val reduced = LocalReduceMotion.current
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    // 平板宽屏：限制正文最大宽度并居中，同时裁剪页面滑动转场不溢出两侧留白。
                    // 底部导航独立悬浮于底层外侧，仅做 graphicsLayer 纵向滑入滑出，彻底避免转场期间逐帧触发 Scaffold 重排。
                    Box(
                        Modifier
                            .widthIn(max = 720.dp)
                            .fillMaxSize()
                            .align(Alignment.Center)
                            .clipToBounds()
                    ) {
                        val rootContentModifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                            .consumeWindowInsets(WindowInsets.navigationBars)
                            .padding(bottom = 64.dp)
                        NavHost(
                            navController = nav,
                            startDestination = "todo",
                            enterTransition = {
                                val from = initialState.destination.route
                                val to = targetState.destination.route
                                when {
                                    reduced -> fadeIn(tween(TaskraMotion.ReducedFadeMs, easing = TaskraMotion.Decelerate))
                                    isRootRoute(from) && isRootRoute(to) -> {
                                        val dir = if (rootTabIndex(to) >= rootTabIndex(from)) 1 else -1
                                        fadeIn(tween(170, easing = TaskraMotion.Decelerate)) +
                                            slideInHorizontally(tween(TaskraMotion.StandardMs, easing = TaskraMotion.FluidOut)) {
                                                (it * 0.04f * dir).toInt()
                                            }
                                    }
                                    isModalRoute(to) -> {
                                        slideInVertically(tween(TaskraMotion.ModalMs, easing = TaskraMotion.FluidOut)) {
                                            (it * 0.07f).toInt()
                                        } + fadeIn(tween(180, easing = TaskraMotion.Decelerate))
                                    }
                                    isViewerRoute(to) -> {
                                        scaleIn(tween(240, easing = TaskraMotion.FluidOut), initialScale = 0.93f) +
                                            fadeIn(tween(180, easing = TaskraMotion.Decelerate))
                                    }
                                    else -> {
                                        // 不透明纯位移推入（对齐 iOS UINavigationController）：避免全屏 alpha saveLayer FBO 开销
                                        slideInHorizontally(tween(TaskraMotion.PageMs, easing = TaskraMotion.FluidOut)) { it }
                                    }
                                }
                            },
                            exitTransition = {
                                val from = initialState.destination.route
                                val to = targetState.destination.route
                                when {
                                    reduced -> fadeOut(tween(TaskraMotion.ReducedFadeMs, easing = TaskraMotion.Standard))
                                    isRootRoute(from) && isRootRoute(to) -> {
                                        val dir = if (rootTabIndex(to) >= rootTabIndex(from)) 1 else -1
                                        fadeOut(tween(130, easing = TaskraMotion.Standard)) +
                                            slideOutHorizontally(tween(190, easing = TaskraMotion.FluidOut)) {
                                                (-it * 0.04f * dir).toInt()
                                            }
                                    }
                                    isModalRoute(to) -> {
                                        scaleOut(tween(250, easing = TaskraMotion.FluidOut), targetScale = 0.978f)
                                    }
                                    isViewerRoute(to) -> {
                                        fadeOut(tween(150, easing = TaskraMotion.Standard))
                                    }
                                    else -> {
                                        slideOutHorizontally(tween(TaskraMotion.PageMs, easing = TaskraMotion.FluidOut)) {
                                            -(it * 0.22f).toInt()
                                        }
                                    }
                                }
                            },
                            popEnterTransition = {
                                val from = initialState.destination.route
                                val to = targetState.destination.route
                                when {
                                    reduced -> fadeIn(tween(TaskraMotion.ReducedFadeMs, easing = TaskraMotion.Decelerate))
                                    isRootRoute(from) && isRootRoute(to) -> {
                                        fadeIn(tween(170, easing = TaskraMotion.Decelerate))
                                    }
                                    isModalRoute(from) -> {
                                        scaleIn(tween(240, easing = TaskraMotion.FluidOut), initialScale = 0.978f)
                                    }
                                    isViewerRoute(from) -> {
                                        fadeIn(tween(170, easing = TaskraMotion.Decelerate))
                                    }
                                    else -> {
                                        slideInHorizontally(tween(290, easing = TaskraMotion.FluidOut)) {
                                            -(it * 0.22f).toInt()
                                        }
                                    }
                                }
                            },
                            popExitTransition = {
                                val from = initialState.destination.route
                                val to = targetState.destination.route
                                when {
                                    reduced -> fadeOut(tween(TaskraMotion.ReducedFadeMs, easing = TaskraMotion.Standard))
                                    isRootRoute(from) && isRootRoute(to) -> {
                                        fadeOut(tween(130, easing = TaskraMotion.Standard))
                                    }
                                    isModalRoute(from) -> {
                                        slideOutVertically(tween(220, easing = TaskraMotion.FluidOut)) {
                                            (it * 0.08f).toInt()
                                        } + fadeOut(tween(160, easing = TaskraMotion.Standard))
                                    }
                                    isViewerRoute(from) -> {
                                        scaleOut(tween(200, easing = TaskraMotion.FluidOut), targetScale = 0.93f) +
                                            fadeOut(tween(150, easing = TaskraMotion.Standard))
                                    }
                                    else -> {
                                        slideOutHorizontally(tween(290, easing = TaskraMotion.FluidOut)) { it }
                                    }
                                }
                            },
                        ) {
                            composable("todo") {
                                Box(rootContentModifier) {
                                    TodoScreen(
                                        app = app, vm = vm,
                                        onOpenHomework = { nav.navigate("homework/$it") },
                                        onNewHomework = { courseId ->
                                            if (courseId.isNullOrBlank()) nav.navigate("editor/new")
                                            else nav.navigate("editor/new?semesterId=&courseId=${Uri.encode(courseId)}")
                                        },
                                        onOpenDrafts = { nav.navigate("drafts") },
                                    )
                                }
                            }
                            composable("courses") {
                                Box(rootContentModifier) {
                                    CoursesScreen(
                                        app = app, vm = vm,
                                        onOpenCourse = { nav.navigate("course/$it") },
                                        onOpenHomework = { nav.navigate("homework/$it") },
                                        onOpenUncat = { nav.navigate("uncat/$it") },
                                    )
                                }
                            }
                            composable("settings") {
                                Box(rootContentModifier) {
                                    SettingsScreen(
                                        app = app, vm = vm,
                                        onOpenTrash = { nav.navigate("trash") },
                                    )
                                }
                            }
                            composable(
                                "course/{courseId}",
                                arguments = listOf(navArgument("courseId") { type = NavType.StringType }),
                            ) { e ->
                                val id = e.arguments?.getString("courseId") ?: return@composable
                                CourseDetailScreen(
                                    onEditTemplate = { nav.navigate("editor/template/$it") },                                    app = app, vm = vm, courseId = id, semesterId = null,
                                    onBack = { nav.popBackStack() },
                                    onOpenHomework = { nav.navigate("homework/$it") },
                                    onNewHomework = { semId, cId ->
                                        nav.navigate("editor/new?semesterId=$semId&courseId=${cId ?: ""}")
                                    },
                                )
                            }
                            composable(
                                "homework/{id}",
                                arguments = listOf(navArgument("id") { type = NavType.StringType }),
                            ) { e ->
                                val id = e.arguments?.getString("id") ?: return@composable
                                // 点开待办直接就是可编辑的作业页：右上角保存 / 标记完成。
                                EditorScreen(
                                    app = app, vm = vm, homeworkId = id,
                                    presetSemesterId = null, presetCourseId = null,
                                    onDone = { nav.popBackStack() },
                                    onCancel = { nav.popBackStack() },
                                    onViewImage = { imageId -> nav.navigate("viewer/$id/${Uri.encode(imageId)}") },
                                )
                            }
                            composable(
                                "editor/new?semesterId={semesterId}&courseId={courseId}",
                                arguments = listOf(
                                    navArgument("semesterId") { defaultValue = ""; type = NavType.StringType },
                                    navArgument("courseId") { defaultValue = ""; type = NavType.StringType },
                                ),
                            ) { e ->
                                val sem = e.arguments?.getString("semesterId")?.ifBlank { null }
                                val c = e.arguments?.getString("courseId")?.ifBlank { null }
                                EditorScreen(
                                    app = app, vm = vm, homeworkId = null,
                                    presetSemesterId = sem, presetCourseId = c,
                                    onDone = { hasDrafts ->
                                        if (nav.previousBackStackEntry?.destination?.route == "drafts" && !hasDrafts) {
                                            nav.popBackStack("todo", false)
                                        } else nav.popBackStack()
                                    },
                                    onCancel = { nav.popBackStack() },
                                )
                            }
                            composable("editor/template/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                                EditorScreen(app, vm, homeworkId = null, presetSemesterId = null,
                                    presetCourseId = entry.arguments?.getString("id"),
                                    templateCourseId = entry.arguments?.getString("id"),
                                    onDone = { nav.popBackStack() }, onCancel = { nav.popBackStack() })
                            }
                            composable("editor/draft/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                                val draftId = entry.arguments?.getString("id") ?: return@composable
                                EditorScreen(
                                    app = app, vm = vm, homeworkId = null, presetSemesterId = null,
                                    presetCourseId = null, draftIdOverride = draftId,
                                    onDone = { hasDrafts ->
                                        if (nav.previousBackStackEntry?.destination?.route == "drafts" && !hasDrafts) {
                                            nav.popBackStack("todo", false)
                                        } else nav.popBackStack()
                                    },
                                    onCancel = { nav.popBackStack() },
                                )
                            }
                            composable(
                                "editor/edit/{id}",
                                arguments = listOf(navArgument("id") { type = NavType.StringType }),
                            ) { e ->
                                val id = e.arguments?.getString("id") ?: return@composable
                                EditorScreen(
                                    app = app, vm = vm, homeworkId = id,
                                    presetSemesterId = null, presetCourseId = null,
                                    onDone = { hasDrafts ->
                                        if (nav.previousBackStackEntry?.destination?.route == "drafts" && !hasDrafts) {
                                            nav.popBackStack("todo", false)
                                        } else nav.popBackStack()
                                    },
                                    onCancel = { nav.popBackStack() },
                                    onViewImage = { imageId -> nav.navigate("viewer/$id/${Uri.encode(imageId)}") },
                                )
                            }
                            composable(
                                "review/{id}",
                                arguments = listOf(navArgument("id") { type = NavType.StringType }),
                            ) { e ->
                                val id = e.arguments?.getString("id") ?: return@composable
                                ReviewScreen(app = app, vm = vm, sessionId = id, onBack = { nav.popBackStack() })
                            }
                            composable(
                                "viewer/{id}/{imageId}",
                                arguments = listOf(
                                    navArgument("id") { type = NavType.StringType },
                                    navArgument("imageId") { type = NavType.StringType },
                                ),
                            ) { e ->
                                val id = e.arguments?.getString("id") ?: return@composable
                                val imageId = e.arguments?.getString("imageId") ?: return@composable
                                ImageViewerScreen(app = app, homeworkId = id, startImageId = imageId, onBack = { nav.popBackStack() })
                            }
                            composable("trash") {
                                TrashScreen(
                                    app = app, vm = vm,
                                    onBack = { nav.popBackStack() },
                                    onOpenHomework = { nav.navigate("homework/$it") },
                                )
                            }
                            composable("drafts") {
                                DraftsScreen(
                                    app = app,
                                    onBack = { nav.popBackStack() },
                                    onContinueNew = { nav.navigate("editor/draft/${Uri.encode(it)}") },
                                    onContinueEdit = { nav.navigate("editor/edit/$it") },
                                )
                            }
                            composable(
                                "uncat/{semesterId}",
                                arguments = listOf(navArgument("semesterId") { type = NavType.StringType }),
                            ) { e ->
                                val semId = e.arguments?.getString("semesterId") ?: return@composable
                                CourseDetailScreen(
                                    onEditTemplate = { nav.navigate("editor/template/$it") },                                    app = app, vm = vm, courseId = null, semesterId = semId,
                                    onBack = { nav.popBackStack() },
                                    onOpenHomework = { nav.navigate("homework/$it") },
                                    onNewHomework = { sId, _ ->
                                        nav.navigate("editor/new?semesterId=$sId&courseId=")
                                    },
                                )
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = showBottom,
                        enter = if (reduced) fadeIn(tween(TaskraMotion.ReducedFadeMs))
                        else slideInVertically(tween(TaskraMotion.StandardMs, easing = TaskraMotion.FluidOut)) { it },
                        exit = if (reduced) fadeOut(tween(TaskraMotion.ReducedFadeMs))
                        else slideOutVertically(tween(190, easing = TaskraMotion.Standard)) { it },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    ) {
                        TaskraBottomBar(
                            currentRoute = route,
                            onSelect = { target ->
                                nav.navigate(target) {
                                    popUpTo("todo") { inclusive = false }
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                    if (showOpening) OpeningAnimation(onFinished = { showOpening = false })
                }
            }
        }
    }
}

private val RootTabOrder = mapOf("todo" to 0, "courses" to 1, "settings" to 2)
private fun isRootRoute(route: String?): Boolean = route in RootTabOrder
private fun rootTabIndex(route: String?): Int = RootTabOrder[route] ?: 0
private fun isModalRoute(route: String?): Boolean =
    route != null && (route.startsWith("editor/new") || route.startsWith("editor/draft"))
private fun isViewerRoute(route: String?): Boolean =
    route != null && route.startsWith("viewer/")

private data class BottomTabSpec(
    val route: String,
    val label: String,
    val tag: String,
    val outlined: ImageVector,
    val filled: ImageVector,
)

private val BottomTabs = listOf(
    BottomTabSpec("todo", "待办", "nav_todo", Icons.Outlined.CheckCircle, Icons.Filled.CheckCircle),
    BottomTabSpec("courses", "课程", "nav_courses", Icons.Outlined.Book, Icons.Filled.Book),
    BottomTabSpec("settings", "设置", "nav_settings", Icons.Outlined.Settings, Icons.Filled.Settings),
)

/**
 * 轻量流体底部导航：选中背景胶囊沿水平弹簧平滑滑行，图标随选中态在空心/实心间轻弹切换。
 * 平板宽屏居中对齐 720dp 内容栏，窄屏等分三槽不裁切。
 */
@Composable
private fun TaskraBottomBar(
    currentRoute: String,
    onSelect: (String) -> Unit,
) {
    val reduced = LocalReduceMotion.current
    val haptic = LocalHapticFeedback.current
    val navEdge = MaterialTheme.colorScheme.outlineVariant
    val activeIdx = (BottomTabs.indexOfFirst { it.route == currentRoute }).coerceAtLeast(0)
    val indicatorIndexState = animateFloatAsState(
        targetValue = activeIdx.toFloat(),
        animationSpec = if (reduced) tween(0) else TaskraMotion.spatialSpring(),
        label = "bottomBarPill",
    )

    Surface(
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(navEdge, Offset.Zero, Offset(size.width, 0f), 0.8.dp.toPx())
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(),
            ) {
                val tabWidth = maxWidth / BottomTabs.size
                val pillWidth = (tabWidth * 0.66f).coerceIn(64.dp, 108.dp)
                val pillInset = (tabWidth - pillWidth) / 2f
                // 滑动选中胶囊：纯 graphicsLayer 水平平移，零重组，随时可打断换向。
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .graphicsLayer {
                            translationX = (indicatorIndexState.value * tabWidth.toPx()) + pillInset.toPx()
                        }
                        .width(pillWidth)
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)),
                )
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BottomTabs.forEach { tab ->
                        val isSelected = tab.route == currentRoute
                        val interaction = remember { MutableInteractionSource() }
                        val tint by animateColorAsState(
                            targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            animationSpec = TaskraMotion.colorSpec(reduced, TaskraMotion.QuickMs),
                            label = "navTint_${tab.route}",
                        )
                        val popScale = remember { Animatable(1f) }
                        LaunchedEffect(isSelected) {
                            if (isSelected && !reduced) {
                                popScale.snapTo(0.88f)
                                popScale.animateTo(1f, TaskraMotion.tactileSpring())
                            } else {
                                popScale.snapTo(1f)
                            }
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag(tab.tag)
                                .semantics { selected = isSelected }
                                .applePressScale(interaction, pressedScale = 0.93f)
                                .clickable(
                                    interactionSource = interaction,
                                    indication = null,
                                    role = Role.Tab,
                                    onClick = {
                                        if (!isSelected) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onSelect(tab.route)
                                        }
                                    },
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .height(32.dp)
                                    .graphicsLayer {
                                        val s = popScale.value
                                        scaleX = s
                                        scaleY = s
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                AnimatedContent(
                                    targetState = isSelected,
                                    transitionSpec = { TaskraMotion.iconSwapSpec(reduced) },
                                    label = "navIcon_${tab.route}",
                                ) { active ->
                                    Icon(
                                        imageVector = if (active) tab.filled else tab.outlined,
                                        contentDescription = tab.label,
                                        tint = tint,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                            AnimatedVisibility(
                                visible = isSelected,
                                enter = if (reduced) fadeIn(tween(TaskraMotion.ReducedFadeMs))
                                else fadeIn(tween(140, easing = TaskraMotion.Decelerate)) +
                                    expandVertically(TaskraMotion.spatialSpring(), expandFrom = Alignment.Top),
                                exit = if (reduced) fadeOut(tween(TaskraMotion.ReducedFadeMs))
                                else fadeOut(tween(100, easing = TaskraMotion.Standard)) +
                                    shrinkVertically(TaskraMotion.spatialSpring(), shrinkTowards = Alignment.Top),
                            ) {
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tint,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
