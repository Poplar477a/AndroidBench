package com.ufs.bench.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ufs.bench.core.EngineProfileSpec
import com.ufs.bench.ui.components.CPU_PROFILE_OPTIONS
import com.ufs.bench.ui.components.C1_CORE_NO_TIER_NOTE
import com.ufs.bench.ui.components.C1_CORE_TABLE_HINT
import com.ufs.bench.ui.components.COMPILER_AUTHORITY_DISCLAIMER
import com.ufs.bench.ui.components.profileLabel
import com.ufs.bench.ui.theme.ContentBottomInset
import java.util.Locale
import java.util.zip.ZipFile

/**
 * 预编译包编译环境表（独立页面，设置页入口进入）。
 *
 * 数据来源约定（**不许 UI 硬编码编译器/优化等级**）：
 *  · 编译器 / 优化等级 / ABI：取自原生编译期生成的真实串（compilerToolchain）。
 *    ⚠️ 原生目前只暴露**当前生效档**的编译串，其余档位没有该值——这里如实写「—（未加载，原生未暴露）」
 *       而不是编造；若后续原生提供逐档元数据，本页直接接入即可。
 *  · 体积：**运行时读本 APK 内 lib/<abi>/ 下 .so 的真实字节数**（比写死更可信）。
 *  · 档位 / -mtune / 覆盖机型 / 备注：契约表里的静态说明文案。
 */
@Composable
fun CompileEnvScreen(
    activeProfile: String,
    requestedProfile: String,
    fallback: Boolean,
    profileNote: String,
    abi: String,
    compilerToolchain: String,
    /** 生效变体的工具链来源（原生 ccOrigin）；空串=原生未提供 */
    activeCcOrigin: String,
    availableProfiles: List<String>,
    catalog: List<EngineProfileSpec>,
    pendingRestart: Boolean,
    onBack: () -> Unit
) {
    val scroll = rememberScrollState()
    val libSizes = rememberApkLibSizes()
    val activeCompiler = rememberedClang(compilerToolchain)
    val activeOptLevel = rememberedOptLevel(compilerToolchain)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = ContentBottomInset),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------- 标题栏（带返回） ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "预编译包编译环境表",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "本项目预编译的全部原生库档位与它们的真实编译环境",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                text = "本机：生效 " + (profileLabel(activeProfile) ?: activeProfile.ifEmpty { "—" }) +
                    (if (abi.isNotEmpty()) " · ABI $abi" else "") +
                    (if (pendingRestart) " · 已请求 " +
                        (profileLabel(requestedProfile) ?: requestedProfile) + "，下次启动生效" else ""),
                modifier = Modifier.padding(12.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        if (fallback && profileNote.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = "已降级：$profileNote",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // ---------- 公共编译环境（原生按构造对所有档相同：同一次 CMake 配置，只有 -mtune 不同） ----------
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "当前生效档的原生编译串（其余档见下表逐档列出）",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "编译器：" + (activeCompiler ?: "—（原生未提供）") +
                        "\n优化等级：" + (activeOptLevel ?: "—（原生未提供）") +
                        "\n目标 ABI：" + abi.ifEmpty { "—" },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "取自原生编译期生成的真实串（非 UI 硬编码）。六档 NDK 档位由同一次 CMake 配置、" +
                        "同一组编译参数编出，只有 -mtune 目标不同；**实验档不共享该配置**（独立工具链构建），故其编译器与参数逐档另列。",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---------- 档位表 ----------
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "编译器 / 优化等级 / 编译参数 / 体积 / 备注",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = C1_CORE_TABLE_HINT,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 逐档列出**本 ABI 实际随包**的全部档位：元数据以原生 catalog 为准（含实验档），
                // 这样表名说「全部」时与产物一致；auto 是偏好不是包，不列。
                val rows = buildTierRows(catalog, libSizes, abi)
                rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    val isActive = row.profile == activeProfile
                    val supported = availableProfiles.isEmpty() || row.profile in availableProfiles
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = row.label + "  ·  -mtune=" + row.mcpu,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (supported || row.experimental)
                                    MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            when {
                                isActive -> Text(
                                    text = "本机生效",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                row.experimental -> Text(
                                    // 实验档：不进选择器、自动检测也不会选中它，必须显著标注，
                                    // 避免用户以为可以正常选用
                                    text = "实验性 · 仅供联调",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                                !supported -> Text(
                                    text = "本机不可用",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Text(
                            text = "适用：" + row.devices,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "编译器：" + toolchainOrigin(row, isActive, activeCcOrigin),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "优化等级：" + (row.optLevel ?: "—（原生未提供，待补）") +
                                "　编译参数：" + (row.compileFlags ?: "—（原生未提供，待补）"),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "本 ABI（" + abi.ifEmpty { "unknown" } + "）：" +
                                if (row.packaged) "随包提供" else "不随包提供（仅 arm64-v8a 出该档）" +
                                    "　体积：" + (row.sizeText ?: "—（本 APK 内无该档 .so）")
                            ,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (row.profile == "x925") {
                            // 新架构核心（C1/C2 系列）在本工具链下没有精确档位，只能近似
                            Text(
                                text = "备注：" + C1_CORE_TABLE_HINT,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = "说明：编译器 / 优化等级 / 目标 ABI 取自原生编译期生成的真实串；-mtune 与「是否随包」" +
                "取自原生逐档元数据（同样由编译期宏生成）；体积为运行期读取本 APK 内 .so 的实际字节数。",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // 专属调优取消：C1/C2 等新架构档位说明不再展示（档位表只剩 generic 基线）。
        }

        // ---------- 底部权威性声明 ----------
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                text = COMPILER_AUTHORITY_DISCLAIMER,
                modifier = Modifier.padding(12.dp),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

/** 表格一行的完整数据（全部字段来自原生元数据或 APK 实测，UI 不臆造） */
private data class TierRow(
    val profile: String,
    val label: String,
    val mcpu: String,
    val devices: String,
    val packaged: Boolean,
    val experimental: Boolean,
    val sizeText: String?,
    /** 优化等级 / 编译参数：原生逐档字段就绪前为 null（显示「—（原生未提供，待补）」，不填默认值） */
    val optLevel: String? = null,
    val compileFlags: String? = null
)

/** 实验档的展示名与说明（表格用；这些档位**不在**设置页选择器里对外提供） */
private val EXPERIMENTAL_TIER_INFO: Map<String, Pair<String, String>> = mapOf(
    "c1ultra" to ("C1-Ultra（实验）" to "ARM C1 系列；本工具链无精确调度模型，见下方说明"),
    "oryon1" to ("Oryon-1（实验）" to "高通 Oryon 家族；实验档位，不对用户提供"),
    "oryon2" to ("Oryon-2（实验）" to "高通 Oryon 家族；实验档位，不对用户提供")
)

/**
 * 组装表格行：以**原生 catalog**（本 ABI 实际随包的全部档位，含实验档）为准；
 * catalog 为空时才退回契约六档，避免完全空白。
 */
private fun buildTierRows(
    catalog: List<com.ufs.bench.core.EngineProfileSpec>,
    libSizes: Map<String, Long>,
    abi: String
): List<TierRow> {
    // 专属调优取消后，档位表只剩 generic 基线一行；不再展示已下架的 SoC 专属档。
    val fromCatalog = catalog.filter { it.profile == "baseline" }.map {
        TierRow(
            profile = it.profile,
            label = "generic 基线",
            mcpu = it.mcpu,
            devices = "全部设备",
            packaged = it.packagedForThisAbi,
            experimental = false,
            sizeText = libSizes[it.profile]?.let { b -> formatBytes(b) }
        )
    }
    if (fromCatalog.isNotEmpty()) return fromCatalog
    return listOf(TierRow("baseline", "generic 基线", "generic", "全部设备", abi == "arm64-v8a", false,
        libSizes["baseline"]?.let { b -> formatBytes(b) }))
}

/**
 * 编译该档的工具链来源。
 *
 * 优先级：**生效档**直接用原生 `ccOrigin`（权威）；未生效档按来源分组：
 *   · NDK 六档 —— 同一次 CMake 配置，工具链为 NDK 内置 Clang（版本见上方公共行）；
 *   · c1ultra —— 独立 LLVM 23.1.3；
 *   · oryon* —— Qualcomm Snapdragon LLVM ARM Compiler 19.0.0。
 * 后两条是 lead 已核实的构建事实；待原生把逐档工具链（EngineProfileSpec.toolchain）落地后，
 * 这里改为直接取原生值，UI 不再持有任何工具链字面量。
 */
private fun toolchainOrigin(row: TierRow, isActive: Boolean, activeCcOrigin: String): String {
    if (isActive && activeCcOrigin.isNotEmpty()) return activeCcOrigin
    if (!row.experimental) return "NDK 内置 Clang（版本见上方公共行）"
    return when {
        row.profile == "c1ultra" -> "独立 LLVM 23.1.3（非 NDK 内置）"
        row.profile.startsWith("oryon") -> "Qualcomm Snapdragon LLVM ARM Compiler 19.0.0（非 NDK 内置）"
        else -> "—（原生未提供）"
    }
}

/** 读取本 APK 内 lib/<abi>/ 下 .so 的真实字节数，键为档位 id（baseline/x1/…） */
@Composable
private fun rememberApkLibSizes(): Map<String, Long> {
    val ctx = LocalContext.current
    return remember(ctx) {
        runCatching {
            ZipFile(ctx.applicationInfo.sourceDir).use { zip ->
                val names = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("lib/") && it.name.endsWith(".so") }
                    .associate { it.name.substringAfterLast('/') to it.size }
                // 全量扫包：不写死档位名，实验档（c1ultra / oryon1 / oryon2 等）自动纳入，
                // 避免「产物里有 9 档、表里只认 6 档」这种对不上产物的情况。
                val out = HashMap<String, Long>()
                names.forEach { (file, size) ->
                    val tier = when {
                        file == "libdynamo_engine.so" -> "baseline"
                        file.startsWith("libdynamo_engine_") && file.endsWith(".so") ->
                            file.removePrefix("libdynamo_engine_").removeSuffix(".so")
                        else -> null
                    }
                    if (tier != null) out[tier] = size
                }
                out
            }
        }.getOrDefault(emptyMap())
    }
}

private fun formatBytes(bytes: Long): String =
    String.format(Locale.US, "%.2f MB", bytes / 1048576.0)

/** 从原生编译串里取编译器名（例如 `Clang 14.0.7`）；取不到返回 null，不编造 */
private fun rememberedClang(toolchain: String): String? =
    Regex("Clang ([0-9]+\\.[0-9]+\\.[0-9]+)").find(toolchain)?.value

/** 从原生编译串里取优化等级（例如 `-O3`）；取不到返回 null，不编造 */
private fun rememberedOptLevel(toolchain: String): String? =
    Regex("优化等级 (-O[0-9a-zA-Z]+)").find(toolchain)?.groupValues?.get(1)
