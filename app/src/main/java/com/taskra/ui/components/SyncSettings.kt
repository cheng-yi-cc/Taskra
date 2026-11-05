package com.taskra.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.taskra.TaskraApp
import com.taskra.sync.DEFAULT_SYNC_ENDPOINT
import kotlinx.coroutines.launch

@Composable
fun SyncSettings(app: TaskraApp) {
    val state by app.sync.state.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var showConnect by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(DEFAULT_SYNC_ENDPOINT) }
    var inputCode by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Text(state.message)
    Row {
        if (!state.paired) {
            TextButton(enabled = !working && !state.busy, onClick = { error = null; showConnect = true }) { Text("连接设备") }
        } else {
            TextButton(enabled = !working && !state.busy, onClick = { app.sync.requestSync() }) { Text("立即同步") }
            TextButton(enabled = !working && !state.busy, onClick = {
                scope.launch {
                    working = true
                    try { pairCode = app.sync.createPairCode().code }
                    catch (_: Exception) { error = "暂时无法生成配对码，请先完成同步" }
                    finally { working = false }
                }
            }) { Text("生成配对码") }
        }
    }
    error?.let { Text(it) }

    if (showConnect) {
        AlertDialog(
            onDismissRequest = { if (!working) showConnect = false },
            title = { Text("连接同步") },
            text = {
                Column {
                    OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("服务器地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = inputCode, onValueChange = { inputCode = it }, label = { Text("配对码") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    error?.let { Text(it) }
                }
            },
            confirmButton = {
                TextButton(enabled = !working && address.isNotBlank() && inputCode.isNotBlank(), onClick = {
                    scope.launch {
                        working = true
                        error = null
                        try {
                            app.sync.connect(address, inputCode)
                            inputCode = ""
                            showConnect = false
                        } catch (_: Exception) {
                            error = "连接失败，请检查地址、配对码和网络"
                        } finally { working = false }
                    }
                }) { Text(if (working) "连接中…" else "连接") }
            },
            dismissButton = { TextButton(enabled = !working, onClick = { showConnect = false }) { Text("取消") } },
        )
    }
    pairCode?.let { code ->
        AlertDialog(
            onDismissRequest = { pairCode = null },
            title = { Text("设备配对码") },
            text = { Column { Text("配对码 5 分钟内有效，使用一次后失效。") ; SelectionContainer { Text(code) } } },
            confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(code)); pairCode = null }) { Text("复制") } },
            dismissButton = { TextButton(onClick = { pairCode = null }) { Text("关闭") } },
        )
    }
}
