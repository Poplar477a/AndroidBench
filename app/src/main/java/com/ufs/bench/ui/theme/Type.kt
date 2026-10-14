package com.ufs.bench.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 排版令牌：沿用 M3 默认字体（系统无衬线，保证中文可读性），
 * 只收紧标题字重与次要正文字距，让信息层级更接近 MD3 参考设计。
 */
private val Default: Typography = Typography()

val UfsBenchTypography: Typography = Default.copy(
    headlineSmall = Default.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Default.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Default.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    bodyMedium = Default.bodyMedium.copy(lineHeight = 22.sp),
    bodySmall = Default.bodySmall.copy(lineHeight = 18.sp)
)
