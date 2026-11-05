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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
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
                Box(Modifier.fillMaxSize()) {
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        if (showBottom) {
                            val navEdge = MaterialTheme.colorScheme.outlineVariant
                            NavigationBar(
                                modifier = Modifier.drawBehind {
                                    drawLine(navEdge, Offset.Zero, Offset(size.width, 0f), 0.8.dp.toPx())
                                },
                                containerColor = MaterialTheme.colorScheme.background,
                                tonalElevation = 0.dp,
                            ) {
                                NavigationBarItem(
                                    alwaysShowLabel = false,
                                    selected = route == "todo",
                                    onClick = {
                                        nav.navigate("todo") {
                                            popUpTo("todo") { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    },
                                    modifier = Modifier.testTag("nav_todo"),
                                    icon = { Icon(Icons.Outlined.CheckCircle, contentDescription = "待办") },
                                    label = { Text("待办") },
                                )
                                NavigationBarItem(
                                    alwaysShowLabel = false,
                                    selected = route == "courses",
                                    onClick = {
                                        nav.navigate("courses") {
                                            popUpTo("todo") { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    },
                                    modifier = Modifier.testTag("nav_courses"),
                                    icon = { Icon(Icons.Outlined.Book, contentDescription = "课程") },
                                    label = { Text("课程") },
                                )
                                NavigationBarItem(
                                    alwaysShowLabel = false,
                                    selected = route == "settings",
                                    onClick = {
                                        nav.navigate("settings") {
                                            popUpTo("todo") { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    },
                                    modifier = Modifier.testTag("nav_settings"),
                                    icon = { Icon(Icons.Outlined.Settings, contentDescription = "设置") },
                                    label = { Text("设置") },
                                )
                            }
                        }
                    },
                ) { pad ->
                    Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)) {
                        // 平板宽屏：限制正文最大宽度并居中；手机单列不受影响
                        Box(
                            Modifier
                                .widthIn(max = 720.dp)
                                .fillMaxSize()
                                .align(Alignment.Center)
                        ) {
                        NavHost(navController = nav, startDestination = "todo") {
                            composable("todo") {
                                TodoScreen(
                                    app = app, vm = vm,
                                    onOpenHomework = { nav.navigate("homework/$it") },
                                    onNewHomework = { nav.navigate("editor/new") },
                                    onOpenDrafts = { nav.navigate("drafts") },
                                )
                            }
                            composable("courses") {
                                CoursesScreen(
                                    app = app, vm = vm,
                                    onOpenCourse = { nav.navigate("course/$it") },
                                    onOpenHomework = { nav.navigate("homework/$it") },
                                    onOpenUncat = { nav.navigate("uncat/$it") },
                                )
                            }
                            composable("settings") {
                                SettingsScreen(
                                    app = app, vm = vm,
                                    onOpenTrash = { nav.navigate("trash") },
                                )
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
                    }
                }
                if (showOpening) OpeningAnimation(onFinished = { showOpening = false })
                }
            }
        }
    }
}
