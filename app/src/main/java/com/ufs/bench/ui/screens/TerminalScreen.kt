package com.ufs.bench.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ufs.bench.spec.ByobTerminal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * BYOB 终端界面（终端样式：纯文本、等宽、原样直出）
 *  · 平时就滚动输出本应用日志（可用 log off 关闭）
 *  · 顶部「导入组件包」走系统文件选择器（SAF）导入 .zip，校验后落入私有目录
 */
@Composable
fun TerminalScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lines by ByobTerminal.lines.collectAsState()
    var input by remember { mutableStateOf("") }
    var logOn by remember { mutableStateOf(ByobTerminal.isLogEnabled()) }
    val listState = rememberLazyListState()

    // SAF 选择 .zip 组件包 → 导入（SHA-256 / ELF / 路径穿越三重校验）
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch { ByobTerminal.importFromUri(ctx, uri) }
        else ByobTerminal.append("[import] 已取消")
    }

    // 新输出自动滚到底部（终端行为）
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }
    // 日志流：默认开启，每 2 秒拉一次本进程日志
    LaunchedEffect(Unit) {
        while (true) {
            ByobTerminal.pollLogs()
            delay(2000)
        }
    }

    fun submit() {
        val cmd = input.trim()
        input = ""
        when {
            cmd == "import" -> picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            cmd.isNotEmpty() -> scope.launch { ByobTerminal.exec(ctx, cmd) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0B))
    ) {
        // 顶部工具条：导入组件包 / 日志开关
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF151515))
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = {
                picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            }) {
                Text("导入组件包(.zip)", color = Color(0xFF7CFC9A), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            TextButton(onClick = {
                logOn = !logOn
                ByobTerminal.setLog(logOn)
            }) {
                Text(if (logOn) "日志:开" else "日志:关", color = Color(0xFF8AB4F8), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            items(lines) { line ->
                Text(
                    text = if (line.isEmpty()) " " else line,
                    color = when {
                        line.startsWith(">") -> Color(0xFF7CFC9A)
                        line.startsWith("[log]") -> Color(0xFF9AA0A6)
                        else -> Color(0xFFD0D0D0)
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF141414))
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .height(56.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "$",
                color = Color(0xFF7CFC9A),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 16.dp)
            )
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text("输入命令（help / list / run <id> / import）",
                        color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                },
                textStyle = TextStyle(color = Color(0xFFE0E0E0), fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                )
            )
        }
    }
}
