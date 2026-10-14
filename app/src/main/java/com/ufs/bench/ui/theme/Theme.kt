package com.ufs.bench.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * 应用统一主题入口。
 *
 * 取色优先级：
 * 1. 动态取色（Monet / Material You）：Android 12（API 31）及以上从用户壁纸派生
 *    配色，用 [dynamicLightColorScheme] / [dynamicDarkColorScheme]。
 * 2. 固定回退：API 31 以下（或 [dynamicColor] = false）使用 [LightColorScheme] /
 *    [DarkColorScheme] 两套独立方案，而不是只给一套深色配色。
 *
 * 本函数同时把系统状态栏/导航栏配色与图标明暗对齐到当前 ColorScheme。
 * XML 窗口主题（values/themes.xml + values-night/themes.xml）只负责冷启动首帧，
 * 取色生效后以本函数为准。
 */
@Composable
fun UfsBenchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    SystemBarsEffect(darkTheme = darkTheme, statusBarColor = colorScheme.surface, navigationBarColor = colorScheme.surfaceContainer)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = UfsBenchTypography,
        shapes = UfsBenchShapes,
        content = content
    )
}

/** 把窗口状态栏/导航栏配色与图标明暗对齐到主题，避免浅色模式下白图标压白底。 */
@Composable
private fun SystemBarsEffect(darkTheme: Boolean, statusBarColor: Color, navigationBarColor: Color) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        @Suppress("DEPRECATION")
        window.statusBarColor = statusBarColor.toArgb()
        @Suppress("DEPRECATION")
        window.navigationBarColor = navigationBarColor.toArgb()
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

/** 从 Compose 的 Context 链里取出宿主 Activity（主题/窗口相关 API 需要）。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 预览：确认固定配色在浅/深两套下都成立（动态取色无法在预览中取到壁纸）。 */
@Preview(name = "主题色板（浅色回退）", showBackground = true)
@Composable
private fun UfsBenchThemeLightPreview() {
    UfsBenchTheme(darkTheme = false, dynamicColor = false) { PaletteShowcase() }
}

@Preview(name = "主题色板（深色回退）", showBackground = true)
@Composable
private fun UfsBenchThemeDarkPreview() {
    UfsBenchTheme(darkTheme = true, dynamicColor = false) { PaletteShowcase() }
}

@Composable
private fun PaletteShowcase() {
    val roles = listOf(
        "primary" to MaterialTheme.colorScheme.primary,
        "secondary" to MaterialTheme.colorScheme.secondary,
        "tertiary" to MaterialTheme.colorScheme.tertiary,
        "error" to MaterialTheme.colorScheme.error,
        "surfaceVariant" to MaterialTheme.colorScheme.surfaceVariant,
        "surfaceContainerHighest" to MaterialTheme.colorScheme.surfaceContainerHighest
    )
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.padding(UfsBenchSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(UfsBenchSpacing.md)
        ) {
            Text("UFS Benchmark", style = MaterialTheme.typography.titleLarge)
            Text("次要正文 onSurfaceVariant", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(modifier = Modifier.fillMaxWidth()) {
                Text("大圆角卡片（shapes.medium）", modifier = Modifier.padding(UfsBenchSpacing.lg))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(UfsBenchSpacing.sm)
            ) {
                roles.forEach { (name, color) ->
                    Column {
                        Surface(color = color, modifier = Modifier.height(48.dp).fillMaxWidth()) {}
                        Text(name, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
