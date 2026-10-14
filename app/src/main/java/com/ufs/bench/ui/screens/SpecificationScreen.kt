package com.ufs.bench.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import com.ufs.bench.ui.theme.ContentBottomInset
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ufs.bench.model.BenchmarkMode
import com.ufs.bench.model.CacheMode
import com.ufs.bench.model.IometerAccessSpec
import com.ufs.bench.model.StopCriterion

/**
 * 自定义测试 —— 自定义存储测试配置台。
 * 只负责存储 I/O 测试的参数定制；内置微基准工作负载在首页专区独立选择。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SpecificationScreen(
    currentSpec: IometerAccessSpec,
    onApplyAndRun: (IometerAccessSpec) -> Unit,
    onApplyOnly: ((IometerAccessSpec) -> Unit)? = null
) {
    var mode by remember {
        mutableStateOf(
            if (currentSpec.mode == BenchmarkMode.BUILTIN_KERNEL) BenchmarkMode.STANDARD_READ_WRITE
            else currentSpec.mode
        )
    }
    var reqSize by remember { mutableIntStateOf(currentSpec.requestSizeBytes) }
    var pctRead by remember { mutableFloatStateOf(currentSpec.percentRead.toFloat()) }
    var isRandom by remember { mutableStateOf(currentSpec.percentRandom >= 50) }
    var queueDepth by remember { mutableIntStateOf(currentSpec.queueDepth) }
    var threads by remember { mutableIntStateOf(currentSpec.workerThreads) }
    var fileSizeMb by remember { mutableIntStateOf(currentSpec.fileSizeMb.coerceAtLeast(64)) }
    var durationSec by remember { mutableIntStateOf(currentSpec.durationSec.coerceAtLeast(1)) }
    var criterion by remember { mutableStateOf(currentSpec.stopCriterion) }
    var targetCapacityMb by remember { mutableIntStateOf(currentSpec.targetCapacityMb.coerceAtLeast(256)) }
    var capacityText by remember { mutableStateOf(targetCapacityMb.toString()) }

    var durationText by remember { mutableStateOf(durationSec.toString()) }

    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = ContentBottomInset),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "自定义存储测试（Custom Storage Test）",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "此处仅配置存储读写参数；SPEC CPU 移植版负载请在「首页」的 SPEC 专区选择",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val chipLeadingCheck: @Composable (Boolean) -> (@Composable () -> Unit)? = { isSel ->
            if (isSel) {
                {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                    )
                }
            } else null
        }

        // ---------- 1. 读写模式 ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "读写模式（I/O Pattern）", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == BenchmarkMode.STANDARD_READ_WRITE,
                        onClick = { mode = BenchmarkMode.STANDARD_READ_WRITE },
                        label = { Text("标准读写（先读后写）") },
                        leadingIcon = chipLeadingCheck(mode == BenchmarkMode.STANDARD_READ_WRITE)
                    )
                    FilterChip(
                        selected = mode == BenchmarkMode.MIXED_SIMULTANEOUS,
                        onClick = { mode = BenchmarkMode.MIXED_SIMULTANEOUS },
                        label = { Text("独立混合（同时并发）") },
                        leadingIcon = chipLeadingCheck(mode == BenchmarkMode.MIXED_SIMULTANEOUS)
                    )
                    FilterChip(
                        selected = mode == BenchmarkMode.READ_ONLY,
                        onClick = { mode = BenchmarkMode.READ_ONLY },
                        label = { Text("单项纯读") },
                        leadingIcon = chipLeadingCheck(mode == BenchmarkMode.READ_ONLY)
                    )
                    FilterChip(
                        selected = mode == BenchmarkMode.WRITE_ONLY,
                        onClick = { mode = BenchmarkMode.WRITE_ONLY },
                        label = { Text("单项纯写") },
                        leadingIcon = chipLeadingCheck(mode == BenchmarkMode.WRITE_ONLY)
                    )
                }
            }
        }

        // ---------- 2. 块大小 ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "块大小（Transfer Request Size）", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                val blocks = listOf(
                    4 * 1024 to "4 KB", 8 * 1024 to "8 KB", 16 * 1024 to "16 KB",
                    64 * 1024 to "64 KB", 128 * 1024 to "128 KB",
                    1024 * 1024 to "1 MB", 2 * 1024 * 1024 to "2 MB"
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    blocks.forEach { (b, l) ->
                        FilterChip(
                            selected = reqSize == b,
                            onClick = { reqSize = b },
                            label = { Text(l) },
                            leadingIcon = chipLeadingCheck(reqSize == b)
                        )
                    }
                }
            }
        }

        // ---------- 3. 终止准则（按时间 / 按容量，二选一） ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "测试终止准则（二选一）", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StopCriterion.entries.forEach { c ->
                        FilterChip(
                            selected = criterion == c,
                            onClick = { criterion = c },
                            label = { Text(c.label) },
                            leadingIcon = chipLeadingCheck(criterion == c)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = criterion.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                if (criterion == StopCriterion.TIME) {
                    // 只显示时长
                    Text(text = "单阶段时长（秒）", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(3, 5, 10, 30, 60, 120).forEach { s2 ->
                            FilterChip(
                                selected = durationSec == s2,
                                onClick = { durationSec = s2; durationText = s2.toString() },
                                label = { Text("${s2}s") },
                                leadingIcon = chipLeadingCheck(durationSec == s2)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { v ->
                            val digits = v.filter { it.isDigit() }
                            val num = digits.toIntOrNull()
                            if (digits.isEmpty()) {
                                durationText = ""
                            } else if (num != null) {
                                if (num <= 3600) {
                                    durationText = digits
                                    if (num >= 1) durationSec = num
                                } else {
                                    durationText = "3600"
                                    durationSec = 3600
                                }
                            }
                        },
                        label = { Text("自定义时长（秒，1 ~ 3600）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (mode == BenchmarkMode.STANDARD_READ_WRITE)
                            "标准模式会依次跑读、写两个阶段，总耗时约为时长的 2 倍"
                        else "总耗时约为 ${durationSec} 秒",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    // 只显示容量
                    Text(text = "目标累计容量（MB）", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(512, 1024, 2048, 4096, 8192, 16384).forEach { mb ->
                            FilterChip(
                                selected = targetCapacityMb == mb,
                                onClick = { targetCapacityMb = mb; capacityText = mb.toString() },
                                label = { Text(if (mb >= 1024) "${mb / 1024} GB" else "$mb MB") },
                                leadingIcon = chipLeadingCheck(targetCapacityMb == mb)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = capacityText,
                        onValueChange = { v ->
                            val digits = v.filter { it.isDigit() }
                            val num = digits.toLongOrNull()
                            if (digits.isEmpty()) {
                                capacityText = ""
                            } else if (num != null) {
                                if (num <= 1048576L) {
                                    capacityText = digits
                                    if (num >= 64L) targetCapacityMb = num.toInt()
                                } else {
                                    capacityText = "1048576"
                                    targetCapacityMb = 1048576
                                }
                            }
                        },
                        label = { Text("自定义容量（MB，64 ~ 1048576）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (mode == BenchmarkMode.STANDARD_READ_WRITE)
                            "读写两阶段各自压到 ${targetCapacityMb} MB 为止"
                        else "压测至累计 ${targetCapacityMb} MB 为止",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // ---------- 4. 缓存参与模式已迁移至「设置」页的全局开关 ----------

        // ---------- 5. 测试手段已迁移至「设置」页的全局开关 ----------

        // ---------- 6. 混合比例（仅独立混合模式） ----------
        if (mode == BenchmarkMode.MIXED_SIMULTANEOUS) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = "读写混合比例", fontWeight = FontWeight.Bold)
                        Text(
                            text = "${pctRead.toInt()}% 读 / ${100 - pctRead.toInt()}% 写",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(value = pctRead, onValueChange = { pctRead = it }, valueRange = 0f..100f, steps = 19)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pctRead = 90f }, modifier = Modifier.weight(1f)) { Text("90/10") }
                        Button(onClick = { pctRead = 70f }, modifier = Modifier.weight(1f)) { Text("70/30") }
                        Button(onClick = { pctRead = 50f }, modifier = Modifier.weight(1f)) { Text("50/50") }
                    }
                }
            }
        }

        // ---------- 7. 访问模式（随机 / 顺序） ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "访问模式（随机 / 顺序）", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isRandom) "随机访问：测小块 IOPS 与延迟" else "顺序访问：测连续吞吐",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Switch(checked = isRandom, onCheckedChange = { isRandom = it })
            }
        }

        // ---------- 8. 队列与并发 ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "队列深度与并发线程", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("队列深度：$queueDepth", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 4, 8, 16, 32, 64).forEach { q ->
                        FilterChip(
                            selected = queueDepth == q,
                            onClick = { queueDepth = q },
                            label = { Text("Q$q") },
                            leadingIcon = chipLeadingCheck(queueDepth == q)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("并发线程：$threads", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 4, 8, 16, 32).forEach { t ->
                        FilterChip(
                            selected = threads == t,
                            onClick = { threads = t },
                            label = { Text("T$t") },
                            leadingIcon = chipLeadingCheck(threads == t)
                        )
                    }
                }
            }
        }

        // ---------- 提交 ----------
        val assembleSpec = {
            val modeTitle = when (mode) {
                BenchmarkMode.STANDARD_READ_WRITE -> "标准读写"
                BenchmarkMode.MIXED_SIMULTANEOUS -> "独立混合 ${pctRead.toInt()}/${100 - pctRead.toInt()}"
                BenchmarkMode.READ_ONLY -> "纯读"
                else -> "纯写"
            }
            IometerAccessSpec(
                name = "自定义存储测试 - $modeTitle",
                description = "${reqSize / 1024}K " + (if (isRandom) "随机" else "顺序") +
                    " Q${queueDepth}T${threads} · " +
                    (if (criterion == StopCriterion.TIME) "${durationSec}s"
                     else "累计 ${targetCapacityMb}MB"),
                mode = mode,
                requestSizeBytes = reqSize,
                percentRead = pctRead.toInt(),
                percentRandom = if (isRandom) 100 else 0,
                queueDepth = queueDepth,
                workerThreads = threads,
                fileSizeMb = fileSizeMb,
                durationSec = durationSec,
                stopCriterion = criterion,
                targetCapacityMb = targetCapacityMb
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (onApplyOnly != null) {
                OutlinedButton(
                    onClick = { onApplyOnly(assembleSpec()) },
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("保存为当前用例", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Button(
                onClick = { onApplyAndRun(assembleSpec()) },
                modifier = Modifier.weight(if (onApplyOnly != null) 1f else 2f).height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    if (onApplyOnly != null) "立即开始测试" else "应用配置并立即开始测试",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
