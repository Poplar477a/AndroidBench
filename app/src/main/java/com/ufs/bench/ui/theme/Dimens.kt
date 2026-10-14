package com.ufs.bench.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 尺寸令牌：统一 4dp 网格，避免各页面各写一套魔法数字。
 * 只提供语义化间距，不绑定任何具体页面。
 */
object UfsBenchSpacing {
    val none = 0.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/**
 * 可滚动页面**内容底部**的额外留白。
 *
 * 背景：以卡片结尾的可滚动页（测试中 / 历史详情 / 首页 / 自定义测试 / 历史报告 / 设置），
 * 最后一张卡在滚到底时仍会紧贴视口下沿，被底部导航栏视觉压掉一截——
 * 收起的「编译工具链」卡就是这样，用户可能点不到「展开」。
 *
 * 取值依据：M3 NavigationBar 高 80dp，这里给 104dp（80 + 24 呼吸），确保最后一张卡
 * 能完整滚到导航栏之上、不贴边。
 */
val ContentBottomInset = 104.dp

/**
 * 圆角令牌：参考 Legado with MD3 的"大圆角表面"观感。
 * 卡片/分组容器用 lg，弹窗与底部抽屉用 xl。
 */
object UfsBenchRadius {
    val sm = 8.dp
    val md = 12.dp
    val lg = 20.dp
    val xl = 28.dp
}

/**
 * MD3 形状系统：整体比 M3 默认值更圆一些，突出大圆角表面。
 * 组件通过 MaterialTheme.shapes.* 取值（Card 默认用 medium）。
 */
val UfsBenchShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(UfsBenchRadius.sm),
    small = RoundedCornerShape(UfsBenchRadius.md),
    medium = RoundedCornerShape(UfsBenchRadius.lg),
    large = RoundedCornerShape(UfsBenchRadius.lg),
    extraLarge = RoundedCornerShape(UfsBenchRadius.xl)
)
