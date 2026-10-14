package com.ufs.bench.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 是否走了 root（专业手段）。
 *
 * 判定以**原生回填的 privilegeMethod 为准**：root 运行是「专业手段（root）…」，
 * 非 root 是「常规手段（无 root）…」—— 两者都含 "root" 字样，因此不能用 contains("root")，
 * 必须认「专业手段」这个词。测试尚未产出该方法串时（例如刚开始跑），回退看用例的权限档。
 */
fun isRootRun(professional: Boolean, privilegeMethod: String = ""): Boolean =
    if (privilegeMethod.isNotEmpty()) privilegeMethod.contains("专业手段")
    else professional

/**
 * root 模式标记。
 *
 * 需求方口径：手段披露要留痕，但不要整段文字 —— 这里只给一个极短徽标。
 * 只在 root（专业手段）运行时出现；常规模式不显示任何徽标，避免噪声。
 */
@Composable
fun RootModeBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = "ROOT 模式",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}

