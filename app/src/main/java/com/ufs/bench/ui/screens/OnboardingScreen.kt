package com.ufs.bench.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 首次启动的新手引导。
 *
 * 设计取舍：
 *  · **一定要有「跳过」** —— 老用户重装/清数据后不该被迫再读一遍；
 *  · 内容按「你要做什么 → 数据怎么看 → 有哪些坑」组织，不堆功能清单；
 *  · 页脚明确写出成绩的适用边界（温度 / 后台负载 / 剩余空间），
 *    避免第一次拿到数字的人把它当成器件规格。
 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    onSkip: () -> Unit
) {
    var page by remember { mutableIntStateOf(0) }
    val pages = listOf(
        OnboardPage(
            title = "UFS Benchmark 是什么",
            body = "一个纯本地运行的 Android 存储性能测试工具，" +
                "移植了 FIO 与 Iometer 的测试口径，并内置三项上游官方基准（CoreMark / STREAM / tinymembench）的裁剪移植版。\n\n" +
                "全部测量在本机完成，不联网、不上传任何数据。"
        ),
        OnboardPage(
            title = "第一步：选一个用例",
            body = "「首页」内置了 CDM 风格的四档预设（顺序读写 / 深队列顺序 / 高并发随机 / 单队列随机），" +
                "点一下即选中，再按下方「开始基准测试」运行。\n\n" +
                "需要自定义块大小、队列深度、读写比例或终止条件时，去「自定义测试」页配置，" +
                "可只保存为当前用例，也可以直接开跑。"
        ),
        OnboardPage(
            title = "第二步：看懂结果",
            body = "结果页会给出吞吐（MB/s）、IOPS、以及延迟分位（P50 / P95 / P99 / P99.9 / MAX）。\n\n" +
                "读写在图表与延迟表里**一律分开呈现**：写路径要等 FTL 回收与落盘确认，" +
                "长尾通常比读高一个量级，合并成一个数字会掩盖问题。"
        ),
        OnboardPage(
            title = "进阶：专业模式与调优档位",
            body = "「设置」里可以开启专业模式（需要 Root）：参考 AOSP PerformanceBoost 提升进程优先级、" +
                "设置实时 I/O 调度类，并输出全量 FIO 口径指标。\n\n" +
                "专属调优已取消：统一使用 generic 基线（本项目实测最优，无需选择档位）。" +
                "不同档位由不同编译目标产出，**不是同一口径**，横向比较时请固定档位。"
        ),
        OnboardPage(
            title = "开始之前请注意",
            body = "存储成绩受温度、后台负载与剩余空间影响很大：\n\n" +
                "· 测试前尽量清空后台并保持电量充足；\n" +
                "· 剩余空间过少会显著拉低写入成绩；\n" +
                "· 单次结果只代表这一次测量，不要当作器件规格。\n\n" +
                "模拟器上的数值不具备与真机可比性。"
        )
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "第 ${page + 1} / ${pages.size} 步",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onSkip) {
                    Text("跳过")
                }
            }

            Spacer(Modifier.height(28.dp))

            Text(
                text = pages[page].title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = pages[page].body,
                    modifier = Modifier.padding(18.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(28.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (page > 0) {
                    OutlinedButton(onClick = { page-- }) { Text("上一步") }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                Button(
                    onClick = { if (page == pages.lastIndex) onFinish() else page++ }
                ) {
                    Text(if (page == pages.lastIndex) "开始使用" else "下一步")
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = "引导只在首次启动出现；跳过或读完后都不会再次自动弹出。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private data class OnboardPage(val title: String, val body: String)
