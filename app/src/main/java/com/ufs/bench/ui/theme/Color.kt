package com.ufs.bench.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 固定回退配色（API < 31，或调用方显式关闭动态取色时使用）。
 *
 * 色相沿用原应用的品牌基调：青（cyan，H≈195）作主色、紫（violet，H≈265）作第三色，
 * 各个角色值按 Material 3 色调板（tone 10/30/40/80/90/95）手工对齐，
 * 浅色与深色两套都独立成套，并保证正文/背景对比度满足 MD3 要求。
 *
 * ⚠️ 界面代码不得直接引用本文件里的颜色常量，必须通过 MaterialTheme.colorScheme.*
 * 语义角色取值：Android 12+ 的壁纸动态取色（Monet）会整体替换 ColorScheme，
 * 任何硬编码 Color(0xFF...) 都不会跟着壁纸变化。
 */

// ---------- 浅色（Light） ----------
private val LightPrimary = Color(0xFF006780)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFBCE9FF)
private val LightOnPrimaryContainer = Color(0xFF001E28)
private val LightInversePrimary = Color(0xFF5CD5F2)

private val LightSecondary = Color(0xFF4B6070)
private val LightOnSecondary = Color(0xFFFFFFFF)
private val LightSecondaryContainer = Color(0xFFCEE5F7)
private val LightOnSecondaryContainer = Color(0xFF071D2B)

private val LightTertiary = Color(0xFF5B58CB)
private val LightOnTertiary = Color(0xFFFFFFFF)
private val LightTertiaryContainer = Color(0xFFE1DFFF)
private val LightOnTertiaryContainer = Color(0xFF12056B)

private val LightError = Color(0xFFBA1A1A)
private val LightOnError = Color(0xFFFFFFFF)
private val LightErrorContainer = Color(0xFFFFDAD6)
private val LightOnErrorContainer = Color(0xFF410002)

private val LightBackground = Color(0xFFF6FAFC)
private val LightOnBackground = Color(0xFF191C1E)
private val LightSurface = Color(0xFFF6FAFC)
private val LightOnSurface = Color(0xFF191C1E)
private val LightSurfaceVariant = Color(0xFFDCE3E9)
private val LightOnSurfaceVariant = Color(0xFF40484C)
private val LightOutline = Color(0xFF70787C)
private val LightOutlineVariant = Color(0xFFC0C8CC)
private val LightInverseSurface = Color(0xFF2E3133)
private val LightInverseOnSurface = Color(0xFFEFF1F3)
private val LightScrim = Color(0xFF000000)

private val LightSurfaceDim = Color(0xFFD6DBDD)
private val LightSurfaceBright = Color(0xFFF6FAFC)
private val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
private val LightSurfaceContainerLow = Color(0xFFF0F4F6)
private val LightSurfaceContainer = Color(0xFFEAEFF1)
private val LightSurfaceContainerHigh = Color(0xFFE4E9EB)
private val LightSurfaceContainerHighest = Color(0xFFDFE3E5)

// ---------- 深色（Dark） ----------
private val DarkPrimary = Color(0xFF5CD5F2)
private val DarkOnPrimary = Color(0xFF003545)
private val DarkPrimaryContainer = Color(0xFF004D61)
private val DarkOnPrimaryContainer = Color(0xFFB6EBFF)
private val DarkInversePrimary = Color(0xFF006780)

private val DarkSecondary = Color(0xFFB2C9DC)
private val DarkOnSecondary = Color(0xFF1D3241)
private val DarkSecondaryContainer = Color(0xFF34495A)
private val DarkOnSecondaryContainer = Color(0xFFCEE5F7)

private val DarkTertiary = Color(0xFFC5C2FF)
private val DarkOnTertiary = Color(0xFF2C2A86)
private val DarkTertiaryContainer = Color(0xFF4340A6)
private val DarkOnTertiaryContainer = Color(0xFFE1DFFF)

private val DarkError = Color(0xFFFFB4AB)
private val DarkOnError = Color(0xFF690005)
private val DarkErrorContainer = Color(0xFF93000A)
private val DarkOnErrorContainer = Color(0xFFFFDAD6)

private val DarkBackground = Color(0xFF0E1416)
private val DarkOnBackground = Color(0xFFDEE3E6)
private val DarkSurface = Color(0xFF0E1416)
private val DarkOnSurface = Color(0xFFDEE3E6)
private val DarkSurfaceVariant = Color(0xFF40484C)
private val DarkOnSurfaceVariant = Color(0xFFC0C8CC)
private val DarkOutline = Color(0xFF8A9297)
private val DarkOutlineVariant = Color(0xFF40484C)
private val DarkInverseSurface = Color(0xFFDEE3E6)
private val DarkInverseOnSurface = Color(0xFF2B3134)
private val DarkScrim = Color(0xFF000000)

private val DarkSurfaceDim = Color(0xFF0E1416)
private val DarkSurfaceBright = Color(0xFF343A3D)
private val DarkSurfaceContainerLowest = Color(0xFF090F11)
private val DarkSurfaceContainerLow = Color(0xFF171D1F)
private val DarkSurfaceContainer = Color(0xFF1B2124)
private val DarkSurfaceContainerHigh = Color(0xFF252B2E)
private val DarkSurfaceContainerHighest = Color(0xFF303639)

/** 浅色固定方案：API < 31 时取代动态取色。 */
internal val LightColorScheme: ColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    inversePrimary = LightInversePrimary,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = LightPrimary,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    scrim = LightScrim,
    surfaceBright = LightSurfaceBright,
    surfaceDim = LightSurfaceDim,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainerLowest = LightSurfaceContainerLowest
)

/** 深色固定方案：API < 31 时取代动态取色。 */
internal val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    inversePrimary = DarkInversePrimary,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = DarkPrimary,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = DarkScrim,
    surfaceBright = DarkSurfaceBright,
    surfaceDim = DarkSurfaceDim,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainerLowest = DarkSurfaceContainerLowest
)
