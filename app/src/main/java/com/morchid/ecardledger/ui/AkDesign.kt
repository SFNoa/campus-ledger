package com.morchid.ecardledger.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 配色与界面零件。
 *
 * ## 视觉方向（修正过两次）
 *
 * 《明日方舟》在这里是**动画与排布**的参考 —— 切角面板、细线分割、大写拉丁副标题、
 * 干脆的短动画、硬反馈（无涟漪）。**配色不照搬它的黑黄**。
 *
 * 底色和主题色是**两件事**，各自可换：
 *  - 底色：默认**半透明**（近白底 + 半透明白面板，压住风景图是毛玻璃），另有纯白/雾灰/深青
 *  - 主题色：默认**水色**，另有青碧/天青/藕荷/桃粉/琥珀
 *
 * ## 透明度
 *
 * 浅色底下"半透明"才有意义：面板、置顶栏、底栏都用半透明白，
 * 顶部风景图能透出来，也让"内容从下面滑过去"在视觉上成立。
 */
object Ak {
    /**
     * 当前主题色。由 [EcardLedgerTheme] 写入（普通 var + `key(...)` 重建子树，
     * 不用快照状态 —— 自绘代码读它更省心）。
     */
    var accent: Color = AkAccent.AQUA.color
        internal set
    var onAccent: Color = AkAccent.AQUA.onAccent
        internal set

    /** 当前底色 */
    var base: AkBase = AkBase.FROST
        internal set

    // ---------------------------------------------------------------- 底色相关（随底色切换）
    var Bg: Color = Color(0xFFF5F9FA)
        internal set
    var BgDeep: Color = Color(0xFFEAF1F3)
        internal set

    /** 面板：浅色底下是「半透明白」，压住风景图就是毛玻璃 */
    var Panel: Color = Color(0xB3FFFFFF)
        internal set
    var PanelHigh: Color = Color(0xCCFFFFFF)
        internal set

    /** 需要不透明时用（对话框、输入框底） */
    var PanelSolid: Color = Color(0xFFFFFFFF)
        internal set

    /** 置顶栏 / 底栏的半透明底 */
    var Scrim: Color = Color(0xD9FFFFFF)
        internal set
    var ScrimLight: Color = Color(0x99FFFFFF)
        internal set

    /** 细线 */
    var Line: Color = Color(0x1F0B1F26)
        internal set
    var LineStrong: Color = Color(0x330B1F26)
        internal set

    var Text: Color = Color(0xFF0E1C22)
        internal set
    var TextDim: Color = Color(0xFF5A6C74)
        internal set
    var TextFaint: Color = Color(0x8C5A6C74)
        internal set

    /** 语义色：浅色底上要压深一档才看得清 */
    var Income: Color = Color(0xFF12A66A)
        internal set
    var Expense: Color = Color(0xFFE0484D)
        internal set
    var Warning: Color = Color(0xFFD9822B)
        internal set

    /** 背景网格线 */
    var gridLine: Color = Color(0x0A0B1F26)
        internal set

    internal fun apply(b: AkBase, a: AkAccent) {
        base = b
        accent = a.color
        onAccent = a.onAccent
        when (b) {
            AkBase.FROST -> {
                Bg = Color(0xFFF5F9FA); BgDeep = Color(0xFFEAF1F3)
                Panel = Color(0xB3FFFFFF); PanelHigh = Color(0xCCFFFFFF)
                PanelSolid = Color(0xFFFFFFFF)
                Scrim = Color(0xD9FFFFFF); ScrimLight = Color(0x99FFFFFF)
                Line = Color(0x1F0B1F26); LineStrong = Color(0x330B1F26)
                Text = Color(0xFF0E1C22); TextDim = Color(0xFF5A6C74); TextFaint = Color(0x8C5A6C74)
                Income = Color(0xFF12A66A); Expense = Color(0xFFE0484D); Warning = Color(0xFFD9822B)
                gridLine = Color(0x0A0B1F26)
            }
            AkBase.WHITE -> {
                Bg = Color(0xFFFFFFFF); BgDeep = Color(0xFFF5F7F8)
                Panel = Color(0xFFF7FAFB); PanelHigh = Color(0xFFEDF2F4)
                PanelSolid = Color(0xFFFFFFFF)
                Scrim = Color(0xF2FFFFFF); ScrimLight = Color(0xCCFFFFFF)
                Line = Color(0x1A0B1F26); LineStrong = Color(0x330B1F26)
                Text = Color(0xFF0E1C22); TextDim = Color(0xFF5A6C74); TextFaint = Color(0x8C5A6C74)
                Income = Color(0xFF12A66A); Expense = Color(0xFFE0484D); Warning = Color(0xFFD9822B)
                gridLine = Color(0x080B1F26)
            }
            AkBase.MIST -> {
                Bg = Color(0xFFEDF1F3); BgDeep = Color(0xFFE2E8EB)
                Panel = Color(0xE6FFFFFF); PanelHigh = Color(0xF2FFFFFF)
                PanelSolid = Color(0xFFFFFFFF)
                Scrim = Color(0xE6FFFFFF); ScrimLight = Color(0xB3FFFFFF)
                Line = Color(0x240B1F26); LineStrong = Color(0x380B1F26)
                Text = Color(0xFF101E24); TextDim = Color(0xFF55666E); TextFaint = Color(0x8C55666E)
                Income = Color(0xFF0F9A62); Expense = Color(0xFFD64247); Warning = Color(0xFFC97A26)
                gridLine = Color(0x0D0B1F26)
            }
            AkBase.DARK -> {
                Bg = Color(0xFF061317); BgDeep = Color(0xFF030C0F)
                Panel = Color(0x14FFFFFF); PanelHigh = Color(0x24FFFFFF)
                PanelSolid = Color(0xFF0C1F26)
                Scrim = Color(0xE6061317); ScrimLight = Color(0x99061317)
                Line = Color(0x1FFFFFFF); LineStrong = Color(0x42FFFFFF)
                Text = Color(0xFFE9F6F8); TextDim = Color(0xFF93AEB4); TextFaint = Color(0x70FFFFFF)
                Income = Color(0xFF45E0A0); Expense = Color(0xFFFF6B7A); Warning = Color(0xFFFFA24B)
                gridLine = Color(0x0AFFFFFF)
            }
        }
    }

    /** 主题色的淡化版：给描边、装饰线用 */
    val accentSoft: Color get() = accent.copy(alpha = 0.55f)
    val accentGlow: Color get() = accent.copy(alpha = 0.20f)
    val accentDeep: Color get() = accent.copy(alpha = 0.75f)
}

/** 切角形状：默认切右上角 */
class AkCutCorner(
    private val cut: Dp = 10.dp,
    private val topStart: Boolean = false,
    private val topEnd: Boolean = true,
    private val bottomEnd: Boolean = false,
    private val bottomStart: Boolean = false,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val c = with(density) { cut.toPx() }.coerceAtMost(minOf(size.width, size.height) / 2f)
        val path = Path().apply {
            moveTo(if (topStart) c else 0f, 0f)
            if (topEnd) {
                lineTo(size.width - c, 0f)
                lineTo(size.width, c)
            } else {
                lineTo(size.width, 0f)
            }
            if (bottomEnd) {
                lineTo(size.width, size.height - c)
                lineTo(size.width - c, size.height)
            } else {
                lineTo(size.width, size.height)
            }
            if (bottomStart) {
                lineTo(c, size.height)
                lineTo(0f, size.height - c)
            } else {
                lineTo(0f, size.height)
            }
            if (topStart) lineTo(0f, c)
            close()
        }
        return Outline.Generic(path)
    }
}

/**
 * 面板：深色底 + 细边框 + 切角。
 *
 * @param accent 左上角的小色块（方舟面板常见的身份标识）；null 不画
 * @param tag 右上角的短标签（如 "TODAY"）
 */
@Composable
fun AkPanel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    tag: String? = null,
    contentPadding: Dp = 14.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(AkCutCorner())
            .background(Ak.Panel)
            .border(1.dp, Ak.Line, AkCutCorner()),
    ) {
        if (accent != null) {
            Box(
                Modifier
                    .padding(start = 1.dp, top = 1.dp)
                    .size(width = 3.dp, height = 22.dp)
                    .background(accent),
            )
        }
        Column(Modifier.padding(contentPadding)) {
            if (tag != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        tag,
                        color = Ak.TextFaint,
                        fontSize = 10.sp,
                        letterSpacing = 1.6.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.height(2.dp))
            }
            content()
        }
    }
}

/** 区块标题：黄条 + 中文 + 大写拉丁 + 一条延伸的细线 */
@Composable
fun AkSectionTitle(
    cn: String,
    latin: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 16.dp)
                .background(Ak.accent),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            cn,
            color = Ak.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            latin.uppercase(),
            color = Ak.TextFaint,
            fontSize = 10.sp,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(Ak.Line),
        )
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** 细分割线；[strong] 时更亮一档 */
@Composable
fun AkLine(modifier: Modifier = Modifier, strong: Boolean = false) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(if (strong) Ak.LineStrong else Ak.Line),
    )
}

/** 斜线装饰（三个斜杠） */
@Composable
fun AkSlashes(
    count: Int = 3,
    color: Color = Ak.accent,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(width = (count * 5 + 2).dp, height = 12.dp)
            .drawBehind {
                val w = 2.dp.toPx()
                val gap = 5.dp.toPx()
                repeat(count) { i ->
                    val x = i * gap
                    drawLine(
                        color = color,
                        start = Offset(x, size.height),
                        end = Offset(x + w + 2.dp.toPx(), 0f),
                        strokeWidth = w,
                    )
                }
            },
    )
}

/**
 * 金额数字：大字号 + 小一号的货币符号。
 * 方舟的数字排版很有辨识度 —— 金额本身是主角，符号和单位都退到后面。
 */
@Composable
fun AkAmount(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Ak.Text,
    fontSize: Int = 34,
    prefix: String = "¥",
    suffix: String? = null,
) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            prefix,
            color = color.copy(alpha = 0.7f),
            fontSize = (fontSize * 0.45f).sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = (fontSize * 0.12f).dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text,
            color = color,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold,
        )
        if (suffix != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                suffix,
                color = Ak.TextFaint,
                fontSize = (fontSize * 0.35f).sp,
                modifier = Modifier.padding(bottom = (fontSize * 0.14f).dp),
            )
        }
    }
}

/** 状态小标签：细边框 + 彩色文字 */
@Composable
fun AkTag(
    text: String,
    color: Color = Ak.accent,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(1.dp))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(1.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            color = color,
            fontSize = 10.sp,
            letterSpacing = 0.5.sp,
        )
    }
}

/**
 * 背景装饰：极暗的网格 + 一条斜向色块。
 * 用 drawBehind 画，不给布局增加任何节点。
 */
@Composable
fun AkBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .background(Ak.Bg)
            .drawBehind {
                val step = 44.dp.toPx()
                val lineColor = Ak.gridLine
                var x = 0f
                while (x <= size.width) {
                    drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), 1f)
                    x += step
                }
                var y = 0f
                while (y <= size.height) {
                    drawLine(lineColor, Offset(0f, y), Offset(size.width, y), 1f)
                    y += step
                }
                // 右上角一道警戒色斜带
                val band = Path().apply {
                    moveTo(size.width * 0.72f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width, size.height * 0.16f)
                    close()
                }
                drawPath(band, Ak.accentGlow)
            },
    ) {
        content()
    }
}

/** 空状态：斜线 + 一句人话 */
@Composable
fun AkEmpty(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AkSlashes(count = 4, color = Ak.LineStrong)
        Spacer(Modifier.height(10.dp))
        Text(
            text,
            color = Ak.TextDim,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
    }
}

/** 只有描边的进度条（方舟里常见的那种细长条） */
@Composable
fun AkProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = Ak.accent,
    trackColor: Color = Ak.Line,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(trackColor),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .background(color),
        )
    }
}

/**
 * 提交后收键盘 + 清焦点。
 *
 * 真机反馈：「调整每日额度点了保存之后，光标和输入法还赖在界面上」——
 * 输入框不会自己失焦，输入法自然也不走。凡是有"提交"动作的输入框都该调这个。
 */
@Composable
fun rememberDismissKeyboard(): () -> Unit {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return {
        focus.clearFocus()
        keyboard?.hide()
    }
}

/**
 * 选中态配色（**带动画**）。
 *
 * 自己搭的芯片（周期、日期条、花费方式、底部标签）原来是一下子换色，
 * 而 Material 的 FilterChip 是渐变过去的 —— 观感差在这。这两个函数把节奏对齐。
 */
@Composable
fun akChipBackground(selected: Boolean): Color = animateColorAsState(
    targetValue = if (selected) Ak.accent else Ak.PanelHigh,
    animationSpec = tween(durationMillis = 220),
    label = "chip-bg",
).value

@Composable
fun akChipText(selected: Boolean): Color = animateColorAsState(
    targetValue = if (selected) Ak.onAccent else Ak.Text,
    animationSpec = tween(durationMillis = 220),
    label = "chip-text",
).value