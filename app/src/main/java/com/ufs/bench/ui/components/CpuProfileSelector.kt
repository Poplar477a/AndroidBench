package com.ufs.bench.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ufs.bench.ui.CpuProfileSwitchStage

/**
 * 一个 CPU 定向调优档位。
 *
 * [id] 是传给原生 `configure(profilePreference)` 的取值（契约固定，UI 不得自造）。
 * 分档与覆盖面来自 task-16 的冻结表，纯展示文案。
 */
data class CpuProfileOption(
    val id: String,
    val label: String,
    val mcpu: String,
    val devices: String
)

/**
 * 档位目录（顺序即展示顺序：基线 → 由旧到新）。
 *
 * **已移除「自动」选项** —— 需求方裁定取消自动识别，改为纯手动选择：
 * 自动识别会让同一台设备上的实际档位难以预期（实测 8E5 上选任意 Oryon 档都报回退），
 * 用户既看不懂也无法控制。现在每一个档位都必须由用户显式点选，
 * 「当前生效」显示的就是用户选的那一档（若被安全红线裁剪会明确播报）。
 */
// ── 参数人话翻译（给非专业用户）──────────────────────────────────
//  -mtune=xxx：只让编译器按该核心的流水线节奏排指令，不改指令集；现代大核乱序执行会自行重排，
//            本机实测定向反而变慢（x925 -9%、oryon-1 -10%）。
//  -mcpu=xxx ：允许使用该核心的扩展指令（如 SVE2）；实测同代条件下仍慢约 9%，且算错风险更高。
//  LTO       ：跨文件内联，通常几 % 收益；本项目在访存受限用例上与基线持平。
//  PGO       ：按真实运行的采样重排冷热代码；训练集必须与目标用例一致，否则无收益。
// ───────────────────────────────────────────────────────────
val CPU_PROFILE_OPTIONS: List<CpuProfileOption> = listOf(
    // 默认项放第一：最保守、对所有设备安全。
    // 实测结论（一加 15 / SM8850，锁频 2.112 GHz，SPEC 766）：baseline 全场最优；
    // 所有 -mtune 定向档与 SDLLVM 档均未跑赢它（见 docs/verified-facts.md 第 9/10 轮）。
    CpuProfileOption("baseline", "公平基线（默认·实测最优）", "不指定 -mtune", "所有设备（含模拟器），用于对照"),
    CpuProfileOption("x1", "X1", "cortex-x1", "骁龙 888"),
    CpuProfileOption("x2", "X2", "cortex-x2", "骁龙 8 Gen 1、天玑 9000"),
    CpuProfileOption("x3", "X3", "cortex-x3", "骁龙 8 Gen 2、天玑 9200"),
    CpuProfileOption("x4", "X4", "cortex-x4", "骁龙 8 Gen 3、天玑 9300"),
    CpuProfileOption("x925", "X925", "cortex-x925", "天玑 9400")
)

/**
 * **实验档**的展示定义。
 *
 * 它们**只在「本机确实可用」时**才出现在选择器里（见 [buildProfileOptions]）：
 * 例如高通 Oryon 设备上会出现 Oryon 档，其它设备上根本不显示。
 * 这是刻意的——实验档**永不自动选中**，但设备匹配时应允许用户显式选用，
 * 否则用户在自己机器上选不到专为该机器编的档位。
 */
// 诚实标注（需求方裁定 + 实测支撑）：这些档是**实验性参数探索**，不得宣传为"更快"。
// 实测：-mtune 定向在 Oryon 上为负收益（x925 −9%、oryon-1 −10%），SDLLVM 档 −39%，ISA 档 −58%；
// 唯一实测正收益的优化是"大核亲和性绑定"，它已进入**标准基线路径**（两种模式都生效）。
private val EXPERIMENTAL_PROFILE_OPTIONS: List<CpuProfileOption> = listOf(
    CpuProfileOption("oryon2", "Oryon-2（实验）", "oryon-2-v1", "骁龙 8 Elite Gen 5（SM8850 / Kaanapali）"),
    CpuProfileOption("oryon1", "Oryon-1（实验）", "oryon-1", "骁龙 8 Elite（SM8750）"),
    // 全大核 ISA 专用档：用 -mcpu 启用完整 ISA（实测含 1526 条 SVE 指令），
    // 只允许在同构全大核设备上加载；非全大核设备由运行期门禁拒绝（降级不静默）。
    CpuProfileOption(
        "oryon2isa", "Oryon-2 ISA（全大核专用）", "-mcpu=oryon-2-v1",
        "仅同构全大核设备（如 SM8850 的 8×0x002）；含小核/中核的设备会被门禁拒绝加载"
    ),
    CpuProfileOption("c1ultra", "C1-Ultra（实验）", "c1-ultra", "ARM C1 系列（Lumex）")
)

/**
 * 组装本机应展示的档位列表：
 * 标准档**总是展示**（不可用的置灰），
 * 实验档**仅在原生判定本机可用时**追加，避免在无关设备上造成困惑。
 * 已取消「自动」：列表中不再有任何"由系统替用户挑档"的选项。
 */
fun buildProfileOptions(): List<CpuProfileOption> {
    // 需求「解除检测限制」：实验档恒列出且可选。
    // 之前它们只在 unlockAll（Debug）或原生判定本机可用时才出现 —— 那正是
    // 「8E5 上选了 Oryon 却报回退 / 别的机器上根本选不到」这类问题的来源。
    // 现在 availableProfiles 只用于「本机未验证」标注，不再决定是否列出。
    return CPU_PROFILE_OPTIONS + EXPERIMENTAL_PROFILE_OPTIONS
}

/**
 * CPU 定向调优选择器（全局设置，与专业模式/缓存参与/采样档位同级）。
 *
 * 检测结果的地位（需求「解除检测限制」后）：**仅用于标注，不再用于禁用**。
 * [unavailableProfiles] 由调用方传入「原生未确认本机可用」的档位集合，UI 把它显示成
 * 「本机未验证」，但**一律允许手动点选** —— 是否加载由用户显式选择决定，
 * 风险（旧核执行到新指令 = SIGILL）在界面上明说，由用户知情承担。
 * UI 仍然**不在 Kotlin 侧推断 CPU 核心**：能不能跑只由原生在加载时判定并如实播报。
 *
 * 生效语义：原生库一旦加载，同 ABI 的其它变体无法可靠替换，因此「已请求」与「当前生效」
 * 可能不同——UI 必须同时显示两者，绝不静默。
 */
@Composable
fun CpuProfileSelector(
    selected: String,
    activeProfile: String,
    activeMcpu: String,
    abi: String,
    fallback: Boolean,
    note: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 仅用于标注「未随包提供」的档位集合（判据 = 原生 packagedForThisAbi），
     * **不再具有禁用/裁剪的权力**：需求「解除检测限制」后所有档位一律可手动点选。
     */
    unavailableProfiles: Set<String> = emptySet(),
    /** 原生逐档元数据（-mtune 目标）；缺省时退回契约表文案 */
    catalog: List<com.ufs.bench.core.EngineProfileSpec> = emptyList(),
    /** 已落盘的档位（用户意图） */
    cpuProfileSaved: String = "",
    /** 已保存但本进程未生效的档位 */
    cpuProfilePending: String? = null,
    /** 上一次已验证生效的档位（回退目标） */
    cpuProfileLastVerified: String? = null,
    /** 切换阶段：保存 / 待应用 / 重启中 / 已验证 / 降级 分开呈现（需求 F1/A3） */
    switchStage: CpuProfileSwitchStage = CpuProfileSwitchStage.IDLE,
    /** 一键回退到上一已验证档位 */
    onRollback: () -> Unit = {}
) {
    // 档位列表是**恒定全集**（与检测无关），故无 key 可记 —— 只算一次。
    val options = remember { buildProfileOptions() }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "CPU 档位",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "专属调优已取消：统一使用 generic 基线（本项目实测最优）。" +
                    "不同档位由不同 -mtune 编出，不是同一口径。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            // ---------- 当前生效（原生权威值）+ 降级提示 ----------
            val activeLabel = profileLabel(activeProfile)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (fallback) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "当前生效：" + (activeLabel ?: "未知") +
                            (if (activeMcpu.isNotEmpty()) "（$activeMcpu）" else ""),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (fallback) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    if (fallback && note.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "已降级：$note",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    } else if (note.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = note,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "ABI：" + abi.ifEmpty { "—" },
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // ---- 切换阶段（需求 F1/A3）：保存成功不等于已生效，必须分开说清 ----
                    val stageLine = when (switchStage) {
                        CpuProfileSwitchStage.VERIFIED ->
                            "已生效：本进程实际运行的就是落盘档位（" +
                                (profileLabel(cpuProfileSaved) ?: cpuProfileSaved) + "）"
                        CpuProfileSwitchStage.SAVED_PENDING_RESTART ->
                            "已保存、等待重启生效：" + (profileLabel(cpuProfilePending ?: "") ?: (cpuProfilePending ?: ""))
                        CpuProfileSwitchStage.RESTART_REQUESTED ->
                            "已保存，正在重启应用以生效：" + (profileLabel(cpuProfilePending ?: "") ?: (cpuProfilePending ?: ""))
                        CpuProfileSwitchStage.SAVED_NEXT_LOAD ->
                            "已保存：引擎尚未加载，下次加载即生效（无需重启）"
                        CpuProfileSwitchStage.FALLBACK ->
                            "已保存但本机此进程无法提供该档（ABI 切片/翻译层不支持），重启也不会变"
                        CpuProfileSwitchStage.SAVE_FAILED ->
                            "保存失败：档位未改变，仍是当前生效档"
                        CpuProfileSwitchStage.IDLE -> ""
                    }
                    if (stageLine.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stageLine,
                            fontSize = 10.sp,
                            color = if (fallback) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    if (switchStage == CpuProfileSwitchStage.FALLBACK && !cpuProfileLastVerified.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        androidx.compose.material3.TextButton(onClick = onRollback) {
                            Text("回退到上一次已验证档位（" + cpuProfileLastVerified + "）", fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---------- 档位列表 ----------
            options.forEach { option ->
                // 「本机未验证」只是标注；需求「解除检测限制」后任何档位都可手动点选。
                val unverified = option.id in unavailableProfiles
                val isSelected = option.id == selected
                val isActive = option.id == activeProfile
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(option.id) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onSelect(option.id) }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            // 与结果页/分享图统一口径：定向档显示 `-mtune=<cpu>`；
                            // 值优先取原生逐档元数据（权威），缺失时退回契约表；
                            // auto 是策略描述（不是具体 -mtune 目标），原样显示，避免 "-mtune=-mtune="。
                            text = option.label + "  ·  " + mcpuLabel(option, catalog),
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (unverified) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = option.devices,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    when {
                        unverified -> Badge("未随包提供", MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.onSurfaceVariant)
                        isActive -> Badge("生效中", MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.onSecondaryContainer)
                        isSelected -> Badge("已请求", MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "选择档位后会自动保存并自动重启应用以生效（原生库已加载时无法原地换档）；" +
                    "若未能自动重启，请手动重启一次应用。「未随包提供」只表示本 ABI 的 APK 里没有这个 .so，" +
                    "选中后会如实降级为可用档，不会假装生效。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            // 权威性声明：必须明确否定权威性，不能用「仅供参考」含糊带过
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = COMPILER_AUTHORITY_DISCLAIMER,
                    modifier = Modifier.padding(10.dp),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Surface(color = container, shape = RoundedCornerShape(6.dp)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = content
        )
    }
}

/**
 * 档位行的第二段文案。
 *
 * 六档里的定向档统一显示 `-mtune=<cpu>`（与结果页、分享图同口径）；
 * `auto` 显示策略描述；原生逐档元数据缺失时退回契约表文案 —— 但静态文案本身已含 `-mtune`
 * 字样（如「不指定 -mtune」）时不再加前缀，避免出现 `-mtune=-mtune=`。
 */
private fun mcpuLabel(
    option: CpuProfileOption,
    catalog: List<com.ufs.bench.core.EngineProfileSpec>
): String {
    // 已取消自动档，不存在策略型档位；此处保留直通以防未来新增非 -mtune 档位
    val native = catalog.firstOrNull { it.profile == option.id }?.mcpu
    // 披露不得失真：全大核 ISA 档实际用的是 -mcpu（启用完整 ISA），其余档才是 -mtune。
    if (native != null) {
        return if (option.id in com.ufs.bench.hardware.AllBigCoreOnlyProfiles.IDS) "-mcpu=" + native
        else "-mtune=" + native
    }
    return option.mcpu
}

/** 档位 id → 展示名；未知 id 返回 null（不猜、不显示假名） */
fun profileLabel(id: String): String? =
    (CPU_PROFILE_OPTIONS + EXPERIMENTAL_PROFILE_OPTIONS).firstOrNull { it.id == id }?.label

/**
 * 「本结果由哪个档位执行」一行（结果页与历史详情页共用）。
 *
 * 数据全部来自 [com.ufs.bench.model.BenchmarkSummary] 的原生字段：
 * engineProfile / engineMcpu / engineProfileRequested / engineProfileFallback / engineProfileNote。
 * 老记录（新字段加入前）为空串 → 直接不渲染，不显示假信息。
 */
@Composable
fun EngineProfileLine(
    engineProfile: String,
    engineMcpu: String,
    requestedProfile: String,
    fallback: Boolean,
    note: String,
    modifier: Modifier = Modifier
) {
    if (engineProfile.isEmpty()) return
    val label = profileLabel(engineProfile) ?: engineProfile
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (fallback) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // fallback 时把「已降级」提到**第一行**并加警告图标、加粗加大：
            // 需求方明确要求「降级必须直接在结果里提醒」，不能被当成普通信息忽略。
            val requestedLabel = profileLabel(requestedProfile) ?: requestedProfile
            if (fallback) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "已降级",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "已降级：请求 " + requestedLabel + "，实际 " + label,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                if (note.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = note,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Text(
                text = "本结果由 " + label +
                    (if (engineMcpu.isNotEmpty()) "（-mtune=$engineMcpu）" else "") + " 档执行",
                fontSize = if (fallback) 13.sp else 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (fallback) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!fallback && note.isNotEmpty()) {
                Text(
                    text = note,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "档位仅为调度调优（-mtune）：不启用该核心独有的 ISA 扩展，可安全运行于同 SoC 的全部核心（含小核）。跨档位数值不可直接比较。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
