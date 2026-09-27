package com.taskra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import com.taskra.ui.screens.CourseDetailScreen
import com.taskra.ui.screens.CoursesScreen
import com.taskra.ui.screens.DraftsScreen
import com.taskra.ui.screens.EditorScreen
import com.taskra.ui.screens.HomeworkDetailScreen
import com.taskra.ui.screens.ImageViewerScreen
import com.taskra.ui.screens.ReviewScreen
import com.taskra.ui.screens.SettingsScreen
import com.taskra.ui.screens.TodoScreen
import com.taskra.ui.screens.TrashScreen
import com.taskra.ui.theme.TaskraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TaskraApp
        setContent {
            val vm: AppViewModel = viewModel(factory = AppViewModel.Factory(app))
            val prefs by vm.prefs.collectAsState()
            val dark = when (prefs.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                else -> isSystemInDarkTheme()
            }
            TaskraTheme(darkTheme = dark) {
                val nav = rememberNavController()
                val backStack by nav.currentBackStackEntryAsState()
                val route = backStack?.destination?.route ?: "todo"
                val showBottom = route in listOf("todo", "courses", "settings")
                Scaffold(
                    bottomBar = {
                        if (showBottom) {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = route == "todo",
                                    onClick = {
                                        nav.navigate("todo") {
                                            popUpTo("todo") { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    },
                                    modifier = Modifier.testTag("nav_todo"),
                                    icon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null) },
                                    label = { Text("待办") },
                                )
                                NavigationBarItem(
                                    selected = route == "courses",
                                    onClick = {
                                        nav.navigate("courses") {
                                            popUpTo("todo") { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    },
                                    modifier = Modifier.testTag("nav_courses"),
                                    icon = { Icon(Icons.Outlined.Book, contentDescription = null) },
                                    label = { Text("课程") },
                                )
                                NavigationBarItem(
                                    selected = route == "settings",
                                    onClick = {
                                        nav.navigate("settings") {
                                            popUpTo("todo") { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    },
                                    modifier = Modifier.testTag("nav_settings"),
                                    icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                                    label = { Text("设置") },
                                )
                            }
                        }
                    },
                ) { pad ->
                    Box(Modifier.fillMaxSize().padding(pad)) {
                        // 平板宽屏：限制正文最大宽度并居中；手机单列不受影响
                        Box(
                            Modifier
                                .fillMaxSize()
                                .widthIn(max = 720.dp)
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
                                    onOpenDrafts = { nav.navigate("drafts") },
                                )
                            }
                            composable(
                                "course/{courseId}",
                                arguments = listOf(navArgument("courseId") { type = NavType.StringType }),
                            ) { e ->
                                val id = e.arguments?.getString("courseId") ?: return@composable
                                CourseDetailScreen(
                                    app = app, vm = vm, courseId = id, semesterId = null,
                                    onBack = { nav.popBackStack() },
                                    onOpenHomework = { nav.navigate("homework/$it") },
                                    onNewHomework = { semId, cId ->
                                        nav.navigate("editor/new?semesterId=$semId&courseId=${cId ?: ""}")
                                    },
                                    onOpenReview = { nav.navigate("review/$it") },
                                )
                            }
                            composable(
                                "homework/{id}",
                                arguments = listOf(navArgument("id") { type = NavType.StringType }),
                            ) { e ->
                                val id = e.arguments?.getString("id") ?: return@composable
                                HomeworkDetailScreen(
                                    app = app, vm = vm, homeworkId = id,
                                    onBack = { nav.popBackStack() },
                                    onEdit = { nav.navigate("editor/edit/$it") },
                                    onViewImage = { hid, idx -> nav.navigate("viewer/$hid/$idx") },
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
                                    onDone = { nav.popBackStack() },
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
                                    onDone = { nav.popBackStack() },
                                    onCancel = { nav.popBackStack() },
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
                                "viewer/{id}/{index}",
                                arguments = listOf(
                                    navArgument("id") { type = NavType.StringType },
                                    navArgument("index") { type = NavType.IntType },
                                ),
                            ) { e ->
                                val id = e.arguments?.getString("id") ?: return@composable
                                val idx = e.arguments?.getInt("index") ?: 0
                                ImageViewerScreen(app = app, homeworkId = id, startIndex = idx, onBack = { nav.popBackStack() })
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
                                    onContinueNew = { nav.navigate("editor/new") },
                                    onContinueEdit = { nav.navigate("editor/edit/$it") },
                                )
                            }
                            composable(
                                "uncat/{semesterId}",
                                arguments = listOf(navArgument("semesterId") { type = NavType.StringType }),
                            ) { e ->
                                val semId = e.arguments?.getString("semesterId") ?: return@composable
                                CourseDetailScreen(
                                    app = app, vm = vm, courseId = null, semesterId = semId,
                                    onBack = { nav.popBackStack() },
                                    onOpenHomework = { nav.navigate("homework/$it") },
                                    onNewHomework = { sId, _ ->
                                        nav.navigate("editor/new?semesterId=$sId&courseId=")
                                    },
                                    onOpenReview = { nav.navigate("review/$it") },
                                )
                            }
                        }
                        }
                    }
                }
            }
        }
    }
}
