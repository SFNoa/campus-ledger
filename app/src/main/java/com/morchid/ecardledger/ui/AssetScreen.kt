package com.morchid.ecardledger.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morchid.ecardledger.data.ImageStore
import com.morchid.ecardledger.data.LedgerEntry
import com.morchid.ecardledger.data.LedgerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 资产页（进入 App 的默认页）。
 *
 * ## 入场动画的分镜
 *
 * 参照《明日方舟》的界面动效：**快、干脆、有"载入"感**，不拖泥带水。分三段：
 *  1. `0 → 260ms`：顶部风景图淡入，屏幕正中出现总资产
 *  2. `260 → 1160ms`：总资产**从屏幕正中滑到顶部**并略微缩小，底下一道警戒黄细线拉满
 *  3. `1160 → 1700ms`：今日流水用**擦除**（从左到右 clip）带出来，各行依次亮起
 *
 * 总资产是**悬浮层**（不是列表里的一项）：只有这样才能真的从"屏幕中央"出发；
 * 列表里给它预留了同样高度的空位，动画结束后它正好停在预留位上。
 */
data class AssetActions(
    val onOpenProfile: () -> Unit = {},
    val onAddHeroImage: (android.net.Uri) -> Unit = {},
    val onRemoveHeroImage: (String) -> Unit = {},
    val onSetBudget: (String) -> Unit = {},
    val onOpenBindings: () -> Unit = {},
)

private val HERO_HEIGHT = 208.dp
private val TOTAL_BLOCK_HEIGHT = 104.dp
private val AK_EASE = CubicBezierEasing(0.16f, 0.9f, 0.14f, 1f)

@Composable
fun AssetScreen(
    state: UiState,
    actions: AssetActions = AssetActions(),
) {
    // 分镜（真机反馈后重排过两次）：
    //   0~8%    完全空白（什么都还没有）
    //   8~30%   顶部风景图**淡入**
    //   20~40%  总资产在屏幕正中，**从中心向两边展开**
    //   40~72%  总资产往上走到顶部
    //   68~100% 今日流水**从中心向两边展开**带出来，后面的区块依次跟上
    // 时长从 1.7s → 2.6s → 3.4s：要让人看清，不是一闪而过
    val intro = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        intro.animateTo(1f, tween(durationMillis = 3400, easing = AK_EASE))
    }
    val p = intro.value
    // 真机反馈：总资产那段（展开 + 上移）要慢一点看清，其余的快一点跟上
    val heroAlpha = ((p - 0.04f) / 0.14f).coerceIn(0f, 1f)
    val totalReveal = ((p - 0.16f) / 0.32f).coerceIn(0f, 1f)
    val moveProgress = ((p - 0.44f) / 0.40f).coerceIn(0f, 1f)
    val todayProgress = ((p - 0.82f) / 0.10f).coerceIn(0f, 1f)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenHeightPx = with(density) { maxHeight.toPx() }
        // 全面屏：内容画到屏幕边缘，所以置顶栏要停在状态栏**下面**
        val statusBarDp = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
        val startY = screenHeightPx / 2f - with(density) { TOTAL_BLOCK_HEIGHT.toPx() / 2f }
        val endY = with(density) { (statusBarDp + HERO_HEIGHT + 6.dp).toPx() }
        // 列表的滚动位置：总资产要跟着它"折叠"到顶
        val scrollState = rememberScrollState()

        // 入场动画结束后，总资产随滚动上移，**到顶就钉住不再动** ——
        // 真机反馈「下滑后总资产跟着一起下滑」：之前它固定在风景图下方那个高度，
        // 图滚走之后它就悬在屏幕中间，内容从下面过去，看着就像它在动。
        val dockedY = (endY - scrollState.value).coerceAtLeast(0f)
        val overlayY = if (moveProgress < 1f) {
            startY + (endY - startY) * moveProgress
        } else {
            dockedY
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            // ---------------------------------------------- 顶部风景图
            // 换图/移除的入口在「我的 → 外观」里（那儿才是设置的地方），这里只负责好看
            Box(Modifier.alpha(heroAlpha)) {
                HeroBanner(refs = state.heroImages)
            }

            // 给置顶的总资产预留位置（动画结束后它停在这里，正好在风景图下面）
            Spacer(Modifier.height(statusBarDp + TOTAL_BLOCK_HEIGHT + 12.dp))

            Column(Modifier.padding(horizontal = 12.dp)) {
                // ------------------------------------------ 今日收支（擦除入场）
                WipeReveal(progress = todayProgress) {
                    TodayBlock(state)
                }

                Spacer(Modifier.height(14.dp))

                // ------------------------------------------ 省钱计划（放在分类占比前面）
                WipeReveal(progress = ((p - 0.78f) / 0.22f).coerceIn(0f, 1f)) {
                    BudgetBlock(state)
                }

                Spacer(Modifier.height(14.dp))

                // ------------------------------------------ 分类占比
                WipeReveal(progress = ((p - 0.85f) / 0.15f).coerceIn(0f, 1f)) {
                    CategoryShareBlock(state.todayEntries)
                }

                Spacer(Modifier.height(14.dp))

                // ------------------------------------------ 账户明细（复用老组件，去掉重复的总资产）
                WipeReveal(progress = ((p - 0.90f) / 0.10f).coerceIn(0f, 1f)) {
                    AssetsCard(
                        state = state,
                        onBindCampus = actions.onOpenBindings,
                        showTotal = false,
                    )
                }

                Spacer(Modifier.height(28.dp))
            }
        }

        // ---------------------------------------------- 总资产：**固定不动的置顶栏**
        //
        // 真机反馈过「下滑后总资产跟着一起下滑」。它在实现上本来就是悬浮层（不随列表滚动），
        // 但没有底色 —— 内容从它下面滑过时糊在一起，看着就像它在动。
        // 所以动画到位后给它铺一层半透明底 + 一条细分隔线，明确变成一根置顶栏。
        TotalOverlay(
            state = state,
            offsetY = overlayY,
            lineProgress = moveProgress,
            revealProgress = totalReveal,
            barAlpha = ((moveProgress - 0.45f) / 0.55f).coerceIn(0f, 1f),
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/** 从中心向两边展开（真机反馈要的入场方式，而不是从左到右擦除） */
@Composable
private fun WipeReveal(progress: Float, content: @Composable () -> Unit) {
    val t = progress.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .alpha(if (t <= 0f) 0f else 1f)
            .drawWithContent {
                val half = size.width / 2f * t
                clipRect(left = size.width / 2f - half, right = size.width / 2f + half) {
                    this@drawWithContent.drawContent()
                }
            },
    ) {
        content()
    }
}

/** 总资产置顶栏：大号数字 + 一道拉满的主题色细线 + 半透明底 */
@Composable
private fun TotalOverlay(
    state: UiState,
    offsetY: Float,
    lineProgress: Float,
    /** 从中心向两边展开的进度 */
    revealProgress: Float,
    /** 半透明底的浓度：动画到位后才是 1，避免一开始在画面正中就糊一块底 */
    barAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val total = state.accounts.filter { it.enabled }
        .sumOf { state.accountBalances[it.id] ?: it.balanceCents }
    val reveal = revealProgress.coerceIn(0f, 1f)

    Box(
        modifier
            .offsetPx(offsetY)
            .fillMaxWidth()
            .height(TOTAL_BLOCK_HEIGHT)
            .alpha(if (reveal <= 0f) 0f else 1f)
            .drawWithContent {
                val half = size.width / 2f * reveal
                clipRect(left = size.width / 2f - half, right = size.width / 2f + half) {
                    this@drawWithContent.drawContent()
                }
            }
            .background(Ak.Scrim.copy(alpha = Ak.Scrim.alpha * barAlpha)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AkSlashes(count = 3)
            Spacer(Modifier.width(8.dp))
            Text(
                "总资产",
                color = Ak.TextDim,
                fontSize = 12.sp,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "TOTAL ASSETS",
                color = Ak.TextFaint,
                fontSize = 9.sp,
                letterSpacing = 1.6.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        AkAmount(
            text = yuan(total),
            fontSize = 40,
            color = Ak.Text,
        )
        Spacer(Modifier.height(10.dp))
        // 拉满的黄线：给"载入完成"一个明确的收尾
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Ak.Line),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(lineProgress)
                    .height(2.dp)
                    .background(Ak.accent),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (state.accounts.none { it.enabled }) {
                "还没有账户 · ACCOUNT REQUIRED"
            } else {
                "${state.accounts.count { it.enabled }} 个账户 · " +
                    "上次同步 " + (state.lastSync ?: "从未")
            },
            color = Ak.TextFaint,
            fontSize = 10.sp,
            letterSpacing = 0.6.sp,
        )
        }
        // 置顶栏底部的分隔线：让"内容从这里滑过去"这件事看得出来
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(Ak.Line.copy(alpha = Ak.Line.alpha * barAlpha)),
        )
    }
}

/** 今日收支：收入在左（绿）、支出在右（红） */
@Composable
private fun TodayBlock(state: UiState) {
    val today = state.today
    AkPanel(tag = "TODAY") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 14.dp)
                    .background(Ak.accent),
            )
            Spacer(Modifier.width(8.dp))
            Text("今日流水", color = Ak.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("DAILY LOG", color = Ak.TextFaint, fontSize = 10.sp, letterSpacing = 2.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            // 收入 —— 左，绿
            Column(Modifier.weight(1f)) {
                Text("收入 INCOME", color = Ak.Income, fontSize = 10.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(2.dp))
                AkAmount(
                    text = yuan(today.incomeCents),
                    color = Ak.Income,
                    fontSize = 24,
                    prefix = "+",
                )
            }
            // 中间一条竖线分割
            Box(
                Modifier
                    .width(1.dp)
                    .height(46.dp)
                    .background(Ak.Line),
            )
            // 支出 —— 右，红
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
            ) {
                Text("支出 EXPENSE", color = Ak.Expense, fontSize = 10.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(2.dp))
                AkAmount(
                    text = yuan(today.spentCents),
                    color = Ak.Expense,
                    fontSize = 24,
                    prefix = "-",
                )
            }
        }

        // 结余 = 今日收入 − 今日支出（真机反馈：只有收支两个数，少了"这一进一出净落下多少"）
        val todayNet = today.incomeCents - today.spentCents
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("结余 BALANCE", color = Ak.TextDim, fontSize = 10.sp, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text(
                (if (todayNet < 0) "-" else "+") + yuan(kotlin.math.abs(todayNet)),
                color = if (todayNet >= 0) Ak.Income else Ak.Expense,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        // 今日流水**明细**：上面是合计，下面列出今天每一笔（真机反馈要的）
        Spacer(Modifier.height(10.dp))
        AkLine()
        if (state.todayEntries.isEmpty()) {
            AkEmpty("今天还没有记录")
        } else {
            state.todayEntries.take(6).forEach { e ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            e.merchant,
                            color = Ak.Text,
                            fontSize = 13.sp,
                            maxLines = 1,
                        )
                        Text(
                            e.category.ifBlank { "其他" },
                            color = Ak.TextFaint,
                            fontSize = 10.sp,
                        )
                    }
                    Text(
                        (if (e.isIncome) "+" else "-") + yuan(e.amountCents),
                        color = if (e.isIncome) Ak.Income else Ak.Expense,
                        fontSize = 13.sp,
                    )
                }
            }
            if (state.todayEntries.size > 6) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "还有 " + (state.todayEntries.size - 6) + " 笔 · 到「流水」看全部",
                    color = Ak.TextFaint,
                    fontSize = 10.sp,
                )
            }
        }

        if (today.isOverBudget) {
            Spacer(Modifier.height(10.dp))
            AkLine()
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AkTag("超出额度", color = Ak.Expense)
                Spacer(Modifier.width(8.dp))
                Text(
                    "今天已超出 ¥" + yuan(today.overBudgetCents) + "，明天省一点",
                    color = Ak.Expense,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** 今日分类占比：细条形，方舟那种"数据条" */
@Composable
private fun CategoryShareBlock(entries: List<LedgerEntry>) {
    val expenses = entries.filter { !it.isIncome && !it.isTransfer && it.amountCents > 0 }
    val byCategory = expenses
        .groupBy { it.category.ifBlank { "其他" } }
        .mapValues { (_, list) -> list.sumOf { it.amountCents } }
        .toList()
        .sortedByDescending { it.second }
    val total = byCategory.sumOf { it.second }

    AkPanel(tag = "SHARE") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 14.dp)
                    .background(Ak.accent),
            )
            Spacer(Modifier.width(8.dp))
            Text("今日分类占比", color = Ak.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("CATEGORY", color = Ak.TextFaint, fontSize = 10.sp, letterSpacing = 2.sp)
        }
        Spacer(Modifier.height(10.dp))
        if (byCategory.isEmpty()) {
            AkEmpty("今天还没有支出记录")
        } else {
            byCategory.take(6).forEach { (category, cents) ->
                val fraction = if (total > 0) cents.toFloat() / total else 0f
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(category, color = Ak.Text, fontSize = 13.sp)
                        Text(
                            yuan(cents) + " · " + (fraction * 100).toInt() + "%",
                            color = Ak.TextDim,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    AkProgress(fraction = fraction, color = Ak.accent)
                }
            }
        }
    }
}

/** 省钱计划：每日平均花费上限 */
/**
 * 省钱计划（资产页只负责"看"）。
 *
 * 没设过 → 在这一页设一次；**设过之后编辑入口搬到「我的」**（真机反馈要求的），
 * 这里只留状态：今日已花 / 还剩，以及一条**会随花费变短**的绿条。
 */
@Composable
private fun BudgetBlock(state: UiState) {
    val today = state.today
    // 没设额度（在「我的」里选了「自由支出」）→ 资产页**不显示**省钱计划。
    // 设置入口现在只在「我的」里（真机反馈要求）。
    if (today.hasBudget) BudgetStatusCard(today)
}

/** 已设额度时的状态卡：绿条代表**剩余**，花掉多少就短多少（带动画） */
@Composable
private fun BudgetStatusCard(today: LedgerRepository.TodayTotals) {
    val remaining = (today.budgetCents - today.spentCents).coerceAtLeast(0L)
    val target = if (today.budgetCents > 0) {
        remaining.toFloat() / today.budgetCents
    } else {
        0f
    }
    // 花一笔就短一截，用动画滑过去（真机反馈要的"减少绿条"）
    val fraction by animateFloatAsState(
        targetValue = target.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 900, easing = AK_EASE),
        label = "budget-remaining",
    )

    AkPanel(tag = "PLAN", accent = if (today.isOverBudget) Ak.Expense else Ak.Income) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 14.dp)
                    .background(Ak.accent),
            )
            Spacer(Modifier.width(8.dp))
            Text("省钱计划", color = Ak.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("SAVING PLAN", color = Ak.TextFaint, fontSize = 10.sp, letterSpacing = 2.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("今日已花 ¥" + yuan(today.spentCents), color = Ak.Text, fontSize = 13.sp)
            Text(
                if (today.isOverBudget) {
                    "超支 ¥" + yuan(today.overBudgetCents)
                } else {
                    "还剩 ¥" + yuan(remaining)
                },
                color = if (today.isOverBudget) Ak.Expense else Ak.Income,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(6.dp))
        // 绿条 = 剩余额度：满格是没花，花完就空
        AkProgress(fraction = fraction, color = Ak.Income)
        Spacer(Modifier.height(6.dp))
        Text(
            "额度 ¥" + yuan(today.budgetCents) + " · 要改额度到「我的 → 省钱计划」",
            color = Ak.TextFaint,
            fontSize = 10.sp,
        )
    }
}

/**
 * 花费方式：**自由支出** 或 **省钱计划**（含额度编辑）。
 *
 * - 自由支出：不设上限，资产页**不显示**省钱计划
 * - 省钱计划：填一个每日额度并保存后，资产页才出现那张状态卡（真机反馈要求）
 */
@Composable
internal fun BudgetEditor(
    state: UiState,
    onSetBudget: (String) -> Unit,
    onSetBudgetMode: (Boolean) -> Unit,
    hint: String = "选「省钱计划」并填一个每日额度，资产页才会显示进度。",
) {
    val today = state.today
    val dismissKeyboard = rememberDismissKeyboard()
    var input by remember(today.budgetCents) {
        mutableStateOf(if (state.budgetSavedCents > 0L) yuan(state.budgetSavedCents) else "")
    }
    // 选中"省钱计划"这一档（还没填额度时也要露出输入框）
    var planSelected by remember(today.hasBudget) { mutableStateOf(today.hasBudget) }

    AkPanel(tag = "PLAN", accent = if (today.isOverBudget) Ak.Expense else Ak.accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 14.dp)
                    .background(Ak.accent),
            )
            Spacer(Modifier.width(8.dp))
            Text("花费方式", color = Ak.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("SPENDING MODE", color = Ak.TextFaint, fontSize = 10.sp, letterSpacing = 2.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip(label = "自由支出", selected = !planSelected) {
                // 只关掉省钱计划，**不清空额度**（切回来还在）
                planSelected = false
                onSetBudgetMode(false)
                dismissKeyboard()
            }
            ModeChip(label = "省钱计划", selected = planSelected) {
                planSelected = true
                // 之前存过额度就立刻启用，资产页马上显示；没存过就等用户填完保存
                if (state.budgetSavedCents > 0L) onSetBudgetMode(true)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(hint, color = Ak.TextDim, fontSize = 12.sp, lineHeight = 18.sp)

        if (planSelected) {
            Spacer(Modifier.height(10.dp))
            if (today.hasBudget) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("今日已花 ¥" + yuan(today.spentCents), color = Ak.Text, fontSize = 13.sp)
                    Text("当前额度 ¥" + yuan(state.budgetSavedCents), color = Ak.TextDim, fontSize = 13.sp)
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("每日额度（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        onSetBudget(input)
                        dismissKeyboard()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Ak.accent,
                        contentColor = Color(0xFF1A1500),
                    ),
                ) { Text("保存") }
            }
            if (!today.hasBudget) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "填一个额度并保存，资产页才会显示省钱计划。",
                    color = Ak.TextFaint,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

/** 二选一的小方块（自由支出 / 省钱计划） */
@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .background(akChipBackground(selected))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = akChipText(selected),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/**
 * 顶部风景图轮换（三个页面共用）。
 *
 * 每 6 秒换一张；传了回调才显示"换图/移除"按钮（流水页和我的页只当装饰用）。
 */
@Composable
internal fun HeroBanner(
    refs: List<String>,
    height: Dp = HERO_HEIGHT,
    onPickImage: (() -> Unit)? = null,
    onRemoveImage: ((String) -> Unit)? = null,
) {
    val pages = refs.ifEmpty { listOf("") }
    val pagerState = rememberPagerState(pageCount = { pages.size })

    // 自动轮换：只有一张时不转会（也就不会自己滚回第一张）
    LaunchedEffect(pages.size) {
        if (pages.size <= 1) return@LaunchedEffect
        while (true) {
            delay(6000)
            val next = (pagerState.currentPage + 1) % pages.size
            runCatching { pagerState.animateScrollToPage(next) }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(HERO_HEIGHT),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = pages.size > 1,
        ) { page ->
            HeroImage(ref = pages[page])
        }
        // 底部压暗，保证上面的文字能看清
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to Ak.Bg,
                    ),
                ),
        )

        // 页码指示：短横线
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            pages.indices.forEach { i ->
                Box(
                    Modifier
                        .size(width = if (i == pagerState.currentPage) 18.dp else 8.dp, height = 2.dp)
                        .background(
                            if (i == pagerState.currentPage) Ak.accent else Ak.LineStrong,
                        ),
                )
            }
        }

        // 加图 / 删图：只有资产页传了回调，另外两个页面只当装饰
        if (onPickImage != null || onRemoveImage != null) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onRemoveImage != null && pages.size > 1) {
                    TextButton(onClick = { onRemoveImage.invoke(pages[pagerState.currentPage]) }) {
                        Text("移除", color = Ak.TextDim, fontSize = 11.sp)
                    }
                }
                if (onPickImage != null) {
                    TextButton(onClick = { onPickImage.invoke() }) {
                        Text("+ 换图", color = Ak.accent, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** 单张风景图；引用可能是预置 asset，也可能是用户传进来的文件 */
@Composable
private fun HeroImage(ref: String) {
    val context = LocalContext.current
    // 只按屏幕实际宽度解码：原来固定 900px，等于白解一倍像素、白占一倍显存（滑动掉帧的元凶之一）
    val targetPx = heroTargetWidthPx()
    val bitmap by produceState<ImageBitmap?>(initialValue = null, ref) {
        value = if (ref.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                ImageStore.load(context, ref, targetPx)?.asImageBitmap()
            }
        }
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        // 图还没解码好 / 图没了：给一块占位底色，不留白。
        // 顺便把引用名写出来 —— 排查「图怎么没出来」时一眼就知道是哪一张的问题
        Box(
            Modifier
                .fillMaxSize()
                .background(Ak.PanelHigh),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AkSlashes(count = 5, color = Ak.LineStrong)
                if (ref.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        ref.removePrefix("asset:").removePrefix("file:").takeLast(28),
                        color = Ak.TextFaint,
                        fontSize = 9.sp,
                    )
                }
            }
        }
    }
}

/** Compose 没有直接的 px offset modifier，这里包一层 */
private fun Modifier.offsetPx(y: Float): Modifier =
    this.offset { IntOffset(0, y.toInt()) }

/**
 * 顶部图按**屏幕宽度**解码就够。
 *
 * 之前固定 900px：在 320dp 的屏上等于白解一倍像素、白占一倍显存 —— 滑动掉帧的元凶之一。
 */
@Composable
private fun heroTargetWidthPx(): Int = with(LocalDensity.current) {
    LocalConfiguration.current.screenWidthDp.dp.toPx().toInt().coerceAtLeast(320)
}