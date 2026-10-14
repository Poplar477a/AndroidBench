package com.ufs.bench.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.ufs.bench.ui.theme.ContentBottomInset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ufs.bench.hardware.HardwareStorageInfo
import com.ufs.bench.ui.components.CpuProfileSelector
import com.ufs.bench.ui.components.SampleIntervalSelector
import com.ufs.bench.ui.components.profileLabel

/**
 * 设置页：放全局开关与应用级信息。
 * 专业模式是一个全局能力开关（决定能否读取存储设备与硬件详情），
 * 不属于单次测试的参数，因此从「自定义测试」移到这里。
 *
 * 颜色一律取 MaterialTheme.colorScheme 语义角色，硬编码 Color(0xFF…) 会让 Monet 壁纸取色失效。
 *
 * 说明：本页不展示 root 方案识别结果（KernelSU / Magisk 等），也不提供截图分享入口。
 * 结果分享由「测试中」页与「历史报告」页各自提供。
 */
@Composable
fun SettingsScreen(
    professionalMode: Boolean,
    cacheParticipates: Boolean,
    sampleIntervalMs: Int,
    cpuProfilePreference: String,
    cpuProfileActive: String,
    cpuProfileActiveMcpu: String,
    cpuProfileRequested: String,
    cpuProfileAbi: String,
    cpuProfileFallback: Boolean,
    compilerToolchain: String,
    cpuProfileNote: String,
    cpuProfilePendingRestart: Boolean,
    cpuProfileAvailable: List<String>,
    cpuProfileCatalog: List<com.ufs.bench.core.EngineProfileSpec>,
    cpuProfileCcOrigin: String,
    /** 档位状态机（需求 F1/A3）：保存值 / 待应用 / 上次已验证 / 切换阶段 */
    cpuProfileSaved: String = "",
    cpuProfilePending: String? = null,
    cpuProfileLastVerified: String? = null,
    cpuProfileSwitchStage: com.ufs.bench.ui.CpuProfileSwitchStage =
        com.ufs.bench.ui.CpuProfileSwitchStage.IDLE,
    onRollbackCpuProfile: () -> Unit = {},
    hasRoot: Boolean,
    hardwareInfo: HardwareStorageInfo,
    /**
     * Debug 构建：解锁全部选项（所有实验档位均可点选、不做本机可用性过滤）。
     * Release 保持正常门禁。默认 false，调用方传 BuildConfig.DEBUG。
     */
    debugUnlockAll: Boolean = false,
    onToggleProfessional: (Boolean) -> Unit,
    onToggleCache: (Boolean) -> Unit,
    onSampleIntervalChange: (Int) -> Unit,
    onSelectCpuProfile: (String) -> Unit,
) {
    val scroll = rememberScrollState()
    // 独立页面路由（与历史详情页同一套做法：本页内部状态切换 + 返回）
    var showCompileEnv by remember { mutableStateOf(false) }

    // root 相关失败一律**弹窗**强提示（ViewModel 通过 rootError 一次性送达）。
    // 只写状态栏文字是不够的 —— 长页面会把它滚出视野，实测被当成"没有任何校验"。
    if (showCompileEnv) {
        CompileEnvScreen(
            activeProfile = cpuProfileActive,
            requestedProfile = cpuProfileRequested,
            fallback = cpuProfileFallback,
            profileNote = cpuProfileNote,
            abi = cpuProfileAbi,
            compilerToolchain = compilerToolchain,
            availableProfiles = cpuProfileAvailable,
            catalog = cpuProfileCatalog,
            pendingRestart = cpuProfilePendingRestart,
            activeCcOrigin = cpuProfileCcOrigin,
            onBack = { showCompileEnv = false }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = ContentBottomInset),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // ---------- 专业模式开关 ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (professionalMode) Icons.Default.LockOpen else Icons.Default.Lock,
                                contentDescription = null,
                                tint = if (professionalMode && hasRoot)
                                    MaterialTheme.colorScheme.secondary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "专业模式",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (professionalMode)
                                "已开启：参考 AOSP system/vold/Benchmark.cpp 的 PerformanceBoost 做法" +
                                    "（提升进程优先级 + 实时 I/O 调度类），读取真实调度器与队列深度，" +
                                    "并输出全量 FIO 口径指标"
                            else
                                "已关闭：使用 O_DIRECT 直接 I/O，仅输出吞吐与延迟指标，" +
                                    "不读取存储设备与硬件详情",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 不再用缓存的 hasRoot 预判：那会让"首次授权"永远无法发生 ——
                    // 缓存值是启动时探测的 false，而用户根本没机会看到 su 授权弹窗。
                    // 现在一律把请求交给 ViewModel，由它做**一次真实的** su 调用，
                    // 成功即开启、失败则弹窗说明原因（rootError）。
                    Switch(
                        checked = professionalMode,
                        onCheckedChange = { wantEnable -> onToggleProfessional(wantEnable) }
                    )
                }

                if (!hasRoot) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🔒", fontSize = 13.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "未获取 Root 权限：开启专业模式需要 su 权限，未授权时无法开启。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (professionalMode && hasRoot) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "专业模式将额外输出：P50/P95/P99/P99.9 延迟、CPU 占用率、" +
                                "控制器超时计数、按所选采样档位记录的吞吐 / 延迟 / CPU 散点图，以及抖动与掉速记录。",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }

        // ---------- 缓存参与（全局） ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "缓存参与", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (cacheParticipates)
                            "已开启：缓冲 I/O，数值包含页缓存命中收益，反映「应用视角的实际体验」"
                        else
                            "已关闭：O_DIRECT 直连 + fadvise(DONTNEED)，尽量逼近闪存裸速",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = cacheParticipates, onCheckedChange = { onToggleCache(it) })
            }
        }

        // ---------- 采样档位（全局） ----------
        // 与专业模式、缓存参与同级：影响曲线的时间分辨率与开销，不随单次用例变化
        SampleIntervalSelector(
            selectedMs = sampleIntervalMs,
            onSelect = { onSampleIntervalChange(it) }
        )

        // 2026-10-13：专属调优区块与预编译环境表入口已整体移除（档位只剩 generic 基线，无需展示）。

        // ---------- 关于 ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "关于", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                if (hardwareInfo.sysfsReadable) {
                    // 专业模式下已用 root 实测取回真实硬件信息
                    // 直接呈现实测读到的原始值，不做归类
                    AboutRow("存储型号", hardwareInfo.storageType)
                    AboutRow("块设备", hardwareInfo.blockDevice)
                    AboutRow("I/O 调度器", hardwareInfo.ioScheduler)
                    AboutRow("硬件队列深度", hardwareInfo.queueDepthLimit.toString())
                } else {
                    AboutRow(
                        "存储设备详情",
                        "需专业模式（普通应用受 Android 13+ SELinux 限制，读不到 sysfs）"
                    )
                }
                // 应用版本：直接取 BuildConfig（源头是 build.gradle.kts 的 versionName），不硬编码
                AboutRow("应用版本", com.ufs.bench.BuildConfig.VERSION_NAME)
                AboutRow("机型", hardwareInfo.deviceName.ifEmpty { "—" })
                AboutRow("型号代码", hardwareInfo.deviceCode.ifEmpty { "—" })
                AboutRow("系统版本", hardwareInfo.androidRelease.ifEmpty { "—" })
                AboutRow("SoC", hardwareInfo.socModel)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = listOf(
                        "测量口径说明：",
                        "· 含缓存：缓冲 I/O，数值包含页缓存命中收益，反映应用视角的实际体验",
                        "· 无缓存参与：O_DIRECT + fadvise(DONTNEED)，尽量逼近闪存裸速",
                        "· 专业模式的执行方式参考 AOSP system/vold/Benchmark.cpp 的 PerformanceBoost 做法" +
                            "（提升进程优先级 + 实时 I/O 调度类）"
                    ).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                // 合规声明（2026-10 整改）：隐私、商标非从属、上游基准的非官方性质。
                // 上架审核与开源发布都会看这些项，随包放在「关于」页。
                Text(
                    text = listOf(
                        "数据与隐私：",
                        "· 本应用未申请网络权限（清单中没有 INTERNET），全部测量与历史记录只在本机完成",
                        "· 读取 CPU 拓扑与存储设备信息仅用于本机适配与结果标注，不采集、不上传任何数据",
                        "· 结果文件导出到公共 Documents 由你自行管理，可随时删除",
                        "",
                        "商标与非从属声明：",
                        "· UFS 是 JEDEC 的注册商标；CoreMark 是 EEMBC 的注册商标；SPEC / SPEC CPU 是 SPEC 的注册商标；",
                        "  Android 是 Google LLC 的商标；CrystalDiskMark 是 Noriyuki Miyazaki 的产品名",
                        "· 本工具为独立实现的测量工具，与上述任何组织均无隶属、赞助或背书关系",
                        "",
                        "结果口径：",
                        "· 内置基准为上游源码的裁剪移植（CoreMark 经移植包装、STREAM 为变体内核、tinymembench 裁剪移植）",
                        "· 结果一律表述为「相对本机自标定基线」的本地测量，不代表任何官方成绩，也未经官方认证",
                        "· 项目以 Apache-2.0 许可发布；第三方组件版权与许可见仓库 NOTICE 与 THIRD_PARTY_LICENSES.md"
                    ).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---------- 作者署名（页尾） ----------
        val ctx = LocalContext.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Poplar477a 使用 DeepSeek-V4.1Flash、Gemini3.8Flash 和 GPT-6Sol/Terra 开发",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "GitHub：https://github.com/Poplar477a",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.clickable {
                    // 点击打开系统浏览器；没有浏览器时静默失败，不影响应用
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Poplar477a"))
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}