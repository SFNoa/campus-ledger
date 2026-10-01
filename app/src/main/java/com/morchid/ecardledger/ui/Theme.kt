package com.morchid.ecardledger.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color

/**
 * 可选主题色。
 *
 * 默认是**水色**（青蓝那一挂），其余几档也都在水色/柔和的范围内 ——
 * 界面是长时间盯着的，跳脱的高饱和色只在需要被看见的地方用。
 */
enum class AkAccent(
    val label: String,
    val latin: String,
    val color: Color,
    /** 铺在主题色**实底**上的文字色（保证对比度） */
    val onAccent: Color,
) {
    AQUA("水色", "AQUA", Color(0xFF3FD4DE), Color(0xFF00252A)),
    TEAL("青碧", "TEAL", Color(0xFF2FC0A8), Color(0xFF00201B)),
    AZURE("天青", "AZURE", Color(0xFF4FA8FF), Color(0xFF001A33)),
    VIOLET("藕荷", "VIOLET", Color(0xFF9C8CFF), Color(0xFF150E33)),
    SAKURA("桃粉", "SAKURA", Color(0xFFFF8FB1), Color(0xFF3A0E1C)),
    AMBER("琥珀", "AMBER", Color(0xFFFFC24B), Color(0xFF2A1B00)),
    ;

    companion object {
        fun byName(name: String?): AkAccent =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: AQUA
    }

    /**
     * 浅色底上，主题色实底要配**深色字**；深色底才用原本的 onAccent。
     * 否则「琥珀」这种亮黄配浅字会糊成一片。
     */
    fun onOnAccentSafe(): Color = onAccent
}

/**
 * 可选**底色**。
 *
 * 真机反馈纠正过一次：「基础底色不该是黑色…最好是白色或者半透明」。
 * 所以默认是**半透明**（毛玻璃感），另外给纯白/雾灰，也保留深青给喜欢暗色的人。
 */
enum class AkBase(val label: String, val latin: String) {
    /** 半透明：近白底 + 半透明白面板，压住风景图时是毛玻璃 */
    FROST("半透明", "FROST"),

    /** 纯白 */
    WHITE("纯白", "WHITE"),

    /** 雾灰：比纯白柔和一点 */
    MIST("雾灰", "MIST"),

    /** 深青：暗色底 */
    DARK("深青", "DARK"),
    ;

    /** 浅色底要配浅色 Material 配色，否则对话框/输入框还是黑底 */
    val isLight: Boolean get() = this != DARK

    companion object {
        fun byName(name: String?): AkBase =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: FROST
    }
}

/**
 * 应用主题。
 *
 * ⚠️ 这里**不是** Material 的常规写法：配色不是通过 `MaterialTheme.colorScheme` 传下去的，
 * 而是直接写进 [Ak] 这个单例 —— 因为界面上大量绘制（`drawBehind`、自绘切角、Canvas）
 * 拿不到 `@Composable` 上下文，读不到 colorScheme。用 [key] 把子树按主题重建，
 * 保证换色时一定生效（换主题是低频操作，重建一次完全不心疼）。
 */
@Composable
fun EcardLedgerTheme(
    base: AkBase = AkBase.FROST,
    accent: AkAccent = AkAccent.AQUA,
    content: @Composable () -> Unit,
) {
    Ak.apply(base, accent)
    key(base, accent) {
        MaterialTheme(
            colorScheme = if (base.isLight) {
                lightColorScheme(
                    primary = accent.color,
                    onPrimary = accent.onOnAccentSafe(),
                    primaryContainer = Ak.accent.copy(alpha = 0.18f),
                    onPrimaryContainer = Ak.Text,
                    // 容器色要显式给：不给的话 Material 会用它默认的紫色系，
                    // 芯片/卡片就成了淡紫（真机截图里看到的）
                    secondary = Ak.Income,
                    onSecondary = Color.White,
                    secondaryContainer = Ak.accent.copy(alpha = 0.16f),
                    onSecondaryContainer = Ak.Text,
                    tertiary = Ak.Expense,
                    tertiaryContainer = Ak.Expense.copy(alpha = 0.14f),
                    onTertiaryContainer = Ak.Text,
                    background = Ak.Bg,
                    onBackground = Ak.Text,
                    surface = Ak.PanelSolid,
                    onSurface = Ak.Text,
                    surfaceVariant = Ak.PanelHigh,
                    onSurfaceVariant = Ak.TextDim,
                    outline = Ak.LineStrong,
                    error = Ak.Expense,
                    onError = Color.White,
                )
            } else {
                darkColorScheme(
                    primary = accent.color,
                    onPrimary = accent.onAccent,
                    primaryContainer = Ak.accent.copy(alpha = 0.22f),
                    onPrimaryContainer = Ak.Text,
                    secondary = Ak.Income,
                    onSecondary = Color(0xFF00210F),
                    secondaryContainer = Ak.accent.copy(alpha = 0.20f),
                    onSecondaryContainer = Ak.Text,
                    tertiary = Ak.Expense,
                    tertiaryContainer = Ak.Expense.copy(alpha = 0.18f),
                    onTertiaryContainer = Ak.Text,
                    background = Ak.Bg,
                    onBackground = Ak.Text,
                    surface = Ak.PanelSolid,
                    onSurface = Ak.Text,
                    surfaceVariant = Ak.PanelHigh,
                    onSurfaceVariant = Ak.TextDim,
                    outline = Ak.LineStrong,
                    error = Ak.Expense,
                    onError = Color(0xFF2A0000),
                )
            },
            content = content,
        )
    }
}
