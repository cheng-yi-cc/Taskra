package com.taskra.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskra.TaskraApp
import com.taskra.sync.DEFAULT_SYNC_ENDPOINT
import com.taskra.ui.motion.LocalReduceMotion
import com.taskra.ui.motion.TaskraMotion
import com.taskra.ui.motion.appleContentSize
import com.taskra.ui.motion.errorShake
import kotlinx.coroutines.launch

@Composable
fun SyncSettings(app: TaskraApp, onNotify: (String) -> Unit = {}) {
    val reduced = LocalReduceMotion.current
    val state by app.sync.state.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var showConnect by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(DEFAULT_SYNC_ENDPOINT) }
    var inputCode by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorShakeTrigger by remember { mutableIntStateOf(0) }

    Column(Modifier.appleContentSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnimatedVisibility(
                visible = state.busy || working,
                enter = TaskraMotion.popEnter(reduced),
                exit = TaskraMotion.popExit(reduced),
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            AnimatedContent(
                targetState = state.message,
                transitionSpec = { TaskraMotion.crossfadeSpec(reduced) },
                label = "syncStatusMsg",
            ) { msg ->
                Text(msg)
            }
        }
        AnimatedContent(
            targetState = state.paired,
            transitionSpec = { TaskraMotion.crossfadeSpec(reduced) },
            label = "syncActions",
        ) { paired ->
            Row {
                if (!paired) {
                    TextButton(enabled = !working && !state.busy, onClick = { error = null; showConnect = true }) { Text("连接设备") }
                } else {
                    TextButton(enabled = !working && !state.busy, onClick = { app.sync.requestSync() }) { Text("立即同步") }
                    TextButton(enabled = !working && !state.busy, onClick = {
                        scope.launch {
                            working = true
                            try { pairCode = app.sync.createPairCode().code }
                            catch (_: Exception) { error = "暂时无法生成配对码，请先完成同步"; errorShakeTrigger++ }
                            finally { working = false }
                        }
                    }) { Text("生成配对码") }
                }
            }
        }
        AnimatedVisibility(
            visible = error != null && !showConnect,
            enter = TaskraMotion.expandEnter(reduced),
            exit = TaskraMotion.collapseExit(reduced),
        ) {
            Text(
                error.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showConnect) {
        val codeFocus = remember { FocusRequester() }
        var focusRequested by remember { mutableStateOf(false) }
        fun submitConnect() {
            if (working || address.isBlank() || inputCode.isBlank()) return
            scope.launch {
                working = true
                error = null
                try {
                    app.sync.connect(address, inputCode)
                    inputCode = ""
                    showConnect = false
                    onNotify("已连接同步")
                } catch (_: Exception) {
                    error = "连接失败，请检查地址、配对码和网络"
                    errorShakeTrigger++
                } finally { working = false }
            }
        }
        AlertDialog(
            onDismissRequest = { if (!working) showConnect = false },
            title = { Text("连接同步") },
            text = {
                Column(Modifier.appleContentSize()) {
                    OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("服务器地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = inputCode,
                        onValueChange = { inputCode = it; error = null },
                        label = { Text("配对码") },
                        singleLine = true,
                        isError = error != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitConnect() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(codeFocus)
                            .onGloballyPositioned {
                                if (!focusRequested) {
                                    focusRequested = true
                                    codeFocus.requestFocus()
                                }
                            }
                            .errorShake(errorShakeTrigger),
                    )
                    AnimatedVisibility(
                        visible = error != null,
                        enter = TaskraMotion.expandEnter(reduced),
                        exit = TaskraMotion.collapseExit(reduced),
                    ) {
                        Text(
                            error.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !working && address.isNotBlank() && inputCode.isNotBlank(), onClick = { submitConnect() }) {
                    Text(if (working) "连接中…" else "连接")
                }
            },
            dismissButton = { TextButton(enabled = !working, onClick = { showConnect = false }) { Text("取消") } },
        )
    }
    pairCode?.let { code ->
        AlertDialog(
            onDismissRequest = { pairCode = null },
            title = { Text("设备配对码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("配对码 5 分钟内有效，使用一次后失效。", style = MaterialTheme.typography.bodyMedium)
                    SelectionContainer {
                        Text(
                            code,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontFeatureSettings = "tnum",
                                letterSpacing = 3.sp,
                            ),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(code))
                    pairCode = null
                    onNotify("已复制配对码")
                }) { Text("复制") }
            },
            dismissButton = { TextButton(onClick = { pairCode = null }) { Text("关闭") } },
        )
    }
}
