package com.taskra.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WeeklyDeadlineDialog(day: Int?, time: String?, onDismiss: () -> Unit, onApply: (Int?, String?) -> Unit) {
    var selected by remember { mutableStateOf(day) }
    var timed by remember { mutableStateOf(time != null) }
    val clock = rememberTimePickerState(time?.substringBefore(":")?.toIntOrNull() ?: 18, time?.substringAfter(":")?.toIntOrNull() ?: 0, true)
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("每周截止时间") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected == null, { selected = null }, label = { Text("不设置") })
                listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, label ->
                    FilterChip(selected == index + 1, { selected = index + 1 }, label = { Text("周$label") }, modifier = Modifier.testTag("weekday:${index + 1}"))
                }
            }
            if (selected != null) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("具体时间", Modifier.weight(1f))
                    Switch(timed, { timed = it })
                }
                if (timed) TimeInput(clock)
            }
        } },
        confirmButton = { TextButton(modifier = Modifier.testTag("weekly_confirm"), onClick = {
            onApply(selected, if (selected != null && timed) "%02d:%02d".format(clock.hour, clock.minute) else null)
        }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
