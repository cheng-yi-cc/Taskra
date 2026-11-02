package com.taskra

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskra.ui.components.*
import com.taskra.ui.theme.TaskraTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** 逐帧检查真实绘制坐标；不读写手机作业数据。 */
@RunWith(AndroidJUnit4::class)
class DragAnimationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun followsFingerAndLandsWithoutReplayingSwapWhenSaveReturns() {
        val coordinates = mutableMapOf<String, LayoutCoordinates>()
        var state: DragOrderState? = null
        rule.runOnUiThread { rule.activity.setContent { TaskraTheme {
            var source by remember { mutableStateOf(listOf("a", "b", "c")) }
            val scope = rememberCoroutineScope()
            val list = rememberLazyListState()
            val drag = rememberDragOrder(list, source) { order, _ ->
                scope.launch { delay(400); source = order }
            }
            SideEffect { state = drag }
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(top = 100.dp),
                contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(drag.ids, key = { it }) { id ->
                    Box(reorderItemModifier(drag, id).onGloballyPositioned { coordinates[id] = it }
                        .testTag(id).fillMaxWidth().height(112.dp).background(
                            when (id) { "a" -> Color(0xFFB94335); "b" -> Color(0xFFE8D6BD); else -> Color(0xFFD3DDD0) }
                        ).padding(20.dp)) { Text("卡片 $id") }
                }
            }
        } } }
        rule.waitUntil(8000) { rule.onAllNodesWithTag("b").fetchSemanticsNodes().isNotEmpty() }
        fun center(id: String): Float {
            var y = 0f
            rule.runOnUiThread { val c = coordinates.getValue(id); y = c.localToRoot(Offset(c.size.width / 2f, c.size.height / 2f)).y }
            return y
        }
        fun frame() { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
        val initialA = center("a"); val initialB = center("b")
        val distance = initialB - initialA
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithTag("a").performTouchInput { down(center) }
            rule.mainClock.advanceTimeBy(700)
            rule.onNodeWithTag("a").performTouchInput { moveBy(Offset(0f, distance - 24f)) }
            val held = mutableListOf<Float>()
            repeat(12) {
                frame()
                held += center("a")
            }
            rule.runOnUiThread { assertEquals(listOf("b", "a", "c"), state!!.ids) }
            assertTrue("跟手坐标 $held，目标 ${initialB - 24f}", held.all { abs(it - (initialB - 24f)) < 3f })
            val beforeRelease = center("a")
            rule.onNodeWithTag("a").performTouchInput { up() }
            var previousA = beforeRelease
            var previousB = center("b")
            repeat(45) {
                frame()
                val a = center("a"); val b = center("b")
                assertTrue("松手不能瞬移或回旧位置：$previousA -> $a", abs(a - previousA) < 12f)
                assertTrue("邻项不能回跳：$previousB -> $b", b <= previousB + 3f)
                previousA = a; previousB = b
            }
            assertEquals(initialB, center("a"), 2f)
            assertEquals(initialA, center("b"), 2f)
        } finally { rule.mainClock.autoAdvance = true }
    }
}
