package com.morchid.ecardledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morchid.ecardledger.data.LedgerEntry
import java.util.Calendar
import java.util.TimeZone

/**
 * 流水页：能看总流水，也能按日 / 月 / 年看。
 *
 * 分组是**在本地按时间聚合**的，不额外查库 —— 记录本来就在 `UiState.entries` 里
 * （上限 2000 条），分组只是换个摆放方式。
 *
 * 视觉上沿用方舟那一套：分组的表头是一条细线 + 中文 + 大写拉丁 + 右侧该组的收支合计。
 */
enum class LedgerPeriod(val label: String, val latin: String) {
    ALL("全部", "ALL"),
    DAY("日", "DAILY"),
    MONTH("月", "MONTHLY"),
    YEAR("年", "YEARLY"),
}

/** 列表里的一行：要么是分组表头，要么是一条流水 */
sealed interface LedgerRow {
    val key: String

    data class Header(
        override val key: String,
        val title: String,
        val latin: String,
        val incomeCents: Long,
        val expenseCents: Long,
    ) : LedgerRow

    data class Item(val entry: LedgerEntry) : LedgerRow {
        override val key: String get() = entry.orderId
    }
}

@Composable
fun LedgerScreen(
    state: UiState,
    actions: HomeActions = HomeActions(),
) {
    var period by remember { mutableStateOf(LedgerPeriod.ALL) }
    var editing by remember { mutableStateOf<LedgerEntry?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var bulkMode by remember { mutableStateOf(false) }
    val selected = remember { mutableListOf<String>() }
    var confirmDelete by remember { mutableStateOf(false) }
    /** 单条删除的二次确认对象 */
    var pendingSingleDelete by remember { mutableStateOf<LedgerEntry?>(null) }

    // 周期筛选：日/月/年各一个键，互斥，随时可清除
    var dateFilter by remember { mutableStateOf<String?>(null) }
    var monthFilter by remember { mutableStateOf<String?>(null) }
    var yearFilter by remember { mutableStateOf<String?>(null) }
    /** 日期条是否展开成网格 */
    var stripExpanded by remember { mutableStateOf(false) }

    val filtered = remember(state.visibleEntries, dateFilter, monthFilter, yearFilter) {
        when {
            dateFilter != null -> state.visibleEntries.filter { dayKeyOf(it.epochMillis) == dateFilter }
            monthFilter != null -> state.visibleEntries.filter { monthKeyOf(it.epochMillis) == monthFilter }
            yearFilter != null -> state.visibleEntries.filter { yearKeyOf(it.epochMillis) == yearFilter }
            else -> state.visibleEntries
        }
    }
    val rows = remember(filtered, period) {
        groupEntries(filtered, period)
    }
    val entryRows = rows.filterIsInstance<LedgerRow.Item>()

    // 日期条的可选项（今天前后各 15 天 / 本月前后各 6 个月 / 今年前后各 3 年）
    val stripItems = remember(period) { buildPeriodStrip(period, System.currentTimeMillis()) }
    val selectedKey = dateFilter ?: monthFilter ?: yearFilter

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        // 风景图是**列表第一项**：三个页面位置一致（都顶到屏幕最上沿），下滑时一起滚走
        item(key = "hero") {
            HeroBanner(refs = state.heroImages, height = 118.dp)
        }
        item(key = "controls") {
            Column {
        // ---------------------------------------------- 周期选择
        Row(
            Modifier
                .fillMaxWidth()
                .background(Ak.Scrim)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LedgerPeriod.entries.forEach { p ->
                AkPeriodChip(
                    label = p.label,
                    latin = p.latin,
                    selected = period == p,
                    onClick = {
                        period = p
                        // 点日/月/年 → **直接落在今日/今月/今年**，不弹对话框；
                        // 想换别的就在下面那条日期条上左右挑（真机反馈要的交互）
                        val now = System.currentTimeMillis()
                        stripExpanded = false
                        when (p) {
                            LedgerPeriod.DAY -> {
                                dateFilter = dayKeyOf(now)
                                monthFilter = null
                                yearFilter = null
                            }
                            LedgerPeriod.MONTH -> {
                                monthFilter = monthKeyOf(now)
                                dateFilter = null
                                yearFilter = null
                            }
                            LedgerPeriod.YEAR -> {
                                yearFilter = yearKeyOf(now)
                                dateFilter = null
                                monthFilter = null
                            }
                            else -> {
                                dateFilter = null
                                monthFilter = null
                                yearFilter = null
                            }
                        }
                    },
                )
            }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(Ak.Line),
            )
        }

        // 「只看 2026-10-01」那一行删掉了：日期条上已经高亮出选中项，
        // 再写一行是重复信息；要清掉筛选点「全部」即可。

        // ---------------------------------------------- 日期条
        // 点了日/月/年之后，当前周期**居中**，左右是更早/更晚的，可滑动、可展开成网格
        PeriodStrip(
            items = stripItems,
            selectedKey = selectedKey,
            expanded = stripExpanded,
            onSelect = { key ->
                when (period) {
                    LedgerPeriod.DAY -> {
                        dateFilter = key; monthFilter = null; yearFilter = null
                    }
                    LedgerPeriod.MONTH -> {
                        monthFilter = key; dateFilter = null; yearFilter = null
                    }
                    else -> {
                        yearFilter = key; dateFilter = null; monthFilter = null
                    }
                }
            },
            onToggle = { stripExpanded = !stripExpanded },
        )

        // ---------------------------------------------- 账户筛选
        if (state.accounts.isNotEmpty()) {
            AccountChips(
                state = state,
                onSelect = actions.onSelectAccount,
                onToggleReview = actions.onToggleReviewOnly,
            )
            Spacer(Modifier.height(6.dp))
        }

        // ---------------------------------------------- 工具条
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "共 " + entryRows.size + " 条 · " + period.latin,
                color = Ak.TextDim,
                fontSize = 11.sp,
                letterSpacing = 0.6.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (bulkMode) {
                    TextButton(onClick = {
                        if (selected.size == entryRows.size) {
                            selected.clear()
                        } else {
                            selected.clear()
                            selected.addAll(entryRows.map { it.entry.orderId })
                        }
                    }) {
                        Text(
                            if (selected.size == entryRows.size && entryRows.isNotEmpty()) {
                                "取消全选"
                            } else {
                                "全选"
                            },
                            color = Ak.accent,
                            fontSize = 12.sp,
                        )
                    }
                    TextButton(
                        onClick = { confirmDelete = true },
                        enabled = selected.isNotEmpty(),
                    ) {
                        Text("删除 " + selected.size + " 条", color = Ak.Expense, fontSize = 12.sp)
                    }
                    TextButton(onClick = { bulkMode = false; selected.clear() }) {
                        Text("完成", color = Ak.TextDim, fontSize = 12.sp)
                    }
                } else {
                    if (entryRows.isNotEmpty()) {
                        TextButton(onClick = { bulkMode = true }) {
                            Text("批量", color = Ak.TextDim, fontSize = 12.sp)
                        }
                    }
                    TextButton(
                        onClick = actions.onSync,
                        enabled = !state.loading && state.syncBlockedReason == null,
                    ) {
                        Text(
                            if (state.loading) "同步中…" else "同步",
                            color = if (state.syncBlockedReason == null) Ak.TextDim else Ak.TextFaint,
                            fontSize = 12.sp,
                        )
                    }
                    TextButton(onClick = { showAdd = true }) {
                        Text("+ 记一笔", color = Ak.accent, fontSize = 12.sp)
                    }
                }
            }
        }

        // ---------------------------------------------- 当前周期的合计
        // 看"某一天/某个月/某一年"时，最该先看到的就是这段时间一共收了多少、花了多少
        val periodIncome = filtered.filter { it.isIncome && !it.isTransfer }.sumOf { it.amountCents }
        val periodExpense = filtered.filter { !it.isIncome && !it.isTransfer }.sumOf { it.amountCents }
        val periodNet = periodIncome - periodExpense
        // 内部转账单独说一句：不然用户会觉得"我明明花了 X，怎么只统计了 Y"
        val periodTransfer = filtered.filter { it.isTransfer }.sumOf { it.amountCents }
        Row(
            Modifier
                .fillMaxWidth()
                .background(Ak.Scrim)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (period) {
                    LedgerPeriod.DAY -> "当日合计"
                    LedgerPeriod.MONTH -> "当月合计"
                    LedgerPeriod.YEAR -> "当年合计"
                    LedgerPeriod.ALL -> "全部合计"
                },
                color = Ak.TextDim,
                fontSize = 11.sp,
            )
            Spacer(Modifier.weight(1f))
            Text("+" + yuan(periodIncome), color = Ak.Income, fontSize = 12.sp)
            Spacer(Modifier.width(10.dp))
            Text("-" + yuan(periodExpense), color = Ak.Expense, fontSize = 12.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                "结余 " + (if (periodNet < 0) "-" else "+") + yuan(kotlin.math.abs(periodNet)),
                color = Ak.Text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        AkLine()
            }
        }

        // ---------------------------------------------- 列表
        if (rows.isEmpty()) {
                item(key = "empty") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AkEmpty(
                            if (state.loggedIn) {
                                "还没有记录。\n绑定校园卡可以自动同步；也可以在「我的 → 绑定中心」\n开启微信/支付宝的通知记账，或者用上面的「+ 记一笔」。"
                            } else {
                                "还没有记录。\n绑定校园卡可以自动同步；也可以先用「+ 记一笔」，\n或者在「我的 → 绑定中心」里开启微信/支付宝的通知记账。"
                            },
                        )
                    }
                }
            } else {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is LedgerRow.Header -> GroupHeader(row)
                        is LedgerRow.Item -> EntryRow(
                            entry = row.entry,
                            accountLabel = state.accounts
                                .firstOrNull { it.id == row.entry.accountId }?.name,
                            selectable = bulkMode,
                            checked = row.entry.orderId in selected,
                            onClick = {
                                if (bulkMode) {
                                    if (row.entry.orderId in selected) {
                                        selected.remove(row.entry.orderId)
                                    } else {
                                        selected.add(row.entry.orderId)
                                    }
                                } else {
                                    editing = row.entry
                                }
                            },
                        )
                    }
                }
            }
    }

    // 单条删除的二次确认（真机反馈：删记录不该一点就没）
    pendingSingleDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingSingleDelete = null },
            title = { Text("删除这条记录？") },
            text = {
                Text(
                    entry.merchant + " · " + yuan(entry.amountCents) + " 元\n\n" +
                        "删除后不会再被同步或重复通知插回来。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.onDelete(entry.orderId)
                    pendingSingleDelete = null
                }) { Text("删除", color = Ak.Expense) }
            },
            dismissButton = {
                TextButton(onClick = { pendingSingleDelete = null }) { Text("取消") }
            },
        )
    }

    // 二次确认：一次删几十条，误触代价太大
    if (confirmDelete) {
        val count = selected.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 $count 条记录？") },
            text = {
                Text(
                    "删除后不会再被同步或重复通知插回来。\n" +
                        "如果这些是误抓的待确认记录，删掉就行；" +
                        "如果里面有真实收支，建议先手动补记再删。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.onDeleteMany(selected.toList())
                    confirmDelete = false
                    bulkMode = false
                    selected.clear()
                }) { Text("删除", color = Ak.Expense) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }

    editing?.let { entry ->
        CategoryPickerDialog(
            entry = entry,
            onDismiss = { editing = null },
            onPick = { category ->
                actions.onSetCategory(entry.orderId, category)
                editing = null
            },
            onDelete = {
                // 不直接删：先关掉详情，弹出二次确认（删错了没法撤销）
                pendingSingleDelete = entry
                editing = null
            },
            onUnpair = if (entry.isTransfer && entry.transferGroupId != null) {
                {
                    actions.onUnpairTransfer(entry.transferGroupId!!)
                    editing = null
                }
            } else null,
            onConfirmAmount = { amountText, income, category ->
                actions.onConfirmEntry(entry.orderId, amountText, income, category)
                editing = null
            },
            onRename = { newName -> actions.onRename(entry.orderId, newName) },
        )
    }

    if (showAdd) {
        AddEntryDialog(
            accounts = state.accounts.filter { it.enabled },
            onDismiss = { showAdd = false },
            onConfirm = { amount, income, merchant, category, note, accountId ->
                actions.onAddManual(amount, income, merchant, category, note, accountId)
                showAdd = false
            },
        )
    }
}

/** 周期选择：方舟那种细边框小方块，选中时填黄 */
@Composable
private fun AkPeriodChip(
    label: String,
    latin: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 方舟的交互是"硬"的：没有涟漪
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .background(akChipBackground(selected))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = akChipText(selected),
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                latin,
                color = if (selected) AkSelectedInkDim else Ak.TextFaint,
                fontSize = 9.sp,
                letterSpacing = 1.2.sp,
            )
        }
    }
}

/** 选中态（黄底）上的深色字 */
private val AkSelectedInk: androidx.compose.ui.graphics.Color get() = Ak.onAccent
private val AkSelectedInkDim: androidx.compose.ui.graphics.Color
    get() = Ak.onAccent.copy(alpha = 0.6f)

/** 分组表头：日期 + 该组收支合计 */
@Composable
private fun GroupHeader(row: LedgerRow.Header) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Ak.PanelHigh)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(2.dp)
                    .height(12.dp)
                    .background(Ak.accent),
            )
            Spacer(Modifier.width(8.dp))
            Text(row.title, color = Ak.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(6.dp))
            Text(row.latin, color = Ak.TextFaint, fontSize = 9.sp, letterSpacing = 1.4.sp)
            Spacer(Modifier.weight(1f))
            if (row.incomeCents > 0) {
                Text("+" + yuan(row.incomeCents), color = Ak.Income, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
            }
            if (row.expenseCents > 0) {
                Text("-" + yuan(row.expenseCents), color = Ak.Expense, fontSize = 11.sp)
            }
        }
        AkLine()
    }
}

/**
 * 时间键：`2026-10-01` / `2026-10` / `2026`。
 *
 * 分组和「按日期筛选」共用同一套键 —— 否则会出现"选中的日期和分组表头对不上"这种鬼问题。
 * 时区默认学校时区（账单、校园卡流水都是这个口径）。
 */
internal val AkTimeZone: TimeZone get() = TimeZone.getTimeZone("Asia/Shanghai")

internal fun dayKeyOf(epochMillis: Long, zone: TimeZone = AkTimeZone): String {
    val cal = Calendar.getInstance(zone).apply { timeInMillis = epochMillis }
    return String.format(
        "%04d-%02d-%02d",
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH),
    )
}

internal fun monthKeyOf(epochMillis: Long, zone: TimeZone = AkTimeZone): String =
    dayKeyOf(epochMillis, zone).substring(0, 7)

internal fun yearKeyOf(epochMillis: Long, zone: TimeZone = AkTimeZone): String =
    dayKeyOf(epochMillis, zone).substring(0, 4)

/**
 * 把流水按周期分组。
 *
 * 时区用学校时区（账单、校园卡流水都是这个口径），
 * 否则「某一天的流水」跨时区会跑到两天里去。
 */
internal fun groupEntries(
    entries: List<LedgerEntry>,
    period: LedgerPeriod,
    timeZoneId: String = "Asia/Shanghai",
): List<LedgerRow> {
    if (period == LedgerPeriod.ALL) {
        return entries.map { LedgerRow.Item(it) }
    }
    val zone = TimeZone.getTimeZone(timeZoneId)

    // 已按时间倒序进来的，所以按顺序遇到新分组就插一个表头即可
    val rows = ArrayList<LedgerRow>()
    var currentKey: String? = null
    var income = 0L
    var expense = 0L

    entries.forEach { entry ->
        val key = when (period) {
            LedgerPeriod.DAY -> dayKeyOf(entry.epochMillis, zone)
            LedgerPeriod.MONTH -> monthKeyOf(entry.epochMillis, zone)
            else -> yearKeyOf(entry.epochMillis, zone)
        }

        if (key != currentKey) {
            // 收尾上一组：把它的合计回填到刚插进去的表头位置
            if (currentKey != null) {
                val idx = rows.indexOfLast { it is LedgerRow.Header }
                val old = rows[idx] as LedgerRow.Header
                rows[idx] = old.copy(incomeCents = income, expenseCents = expense)
            }
            income = 0L
            expense = 0L
            currentKey = key
            rows.add(
                LedgerRow.Header(
                    key = "header-$key",
                    title = key,
                    latin = period.latin,
                    incomeCents = 0L,
                    expenseCents = 0L,
                ),
            )
        }
        if (!entry.isTransfer) {
            if (entry.isIncome) income += entry.amountCents else expense += entry.amountCents
        }
        rows.add(LedgerRow.Item(entry))
    }
    // 最后一组
    if (currentKey != null) {
        val idx = rows.indexOfLast { it is LedgerRow.Header }
        val old = rows[idx] as LedgerRow.Header
        rows[idx] = old.copy(incomeCents = income, expenseCents = expense)
    }
    return rows
}

/**
 * 周期条的可选项。
 *
 * 日 → 今天前后各 15 天；月 → 本月前后各 6 个月；年 → 今年前后各 3 年。
 * 返回 `筛选键 to 显示文本`，键和分组表头用的是同一套（[dayKeyOf] 等），不会对不上。
 */
internal fun buildPeriodStrip(period: LedgerPeriod, nowMillis: Long): List<Pair<String, String>> {
    val cal = Calendar.getInstance(AkTimeZone)
    fun at(offset: Int, field: Int): Calendar {
        cal.timeInMillis = nowMillis
        cal.add(field, offset)
        return cal
    }
    return when (period) {
        LedgerPeriod.DAY -> (-15..15).map { offset ->
            val c = at(offset, Calendar.DAY_OF_MONTH)
            val key = dayKeyOf(c.timeInMillis)
            val label = if (offset == 0) {
                "今天"
            } else {
                "${c.get(Calendar.MONTH) + 1}.${c.get(Calendar.DAY_OF_MONTH)}"
            }
            key to label
        }
        LedgerPeriod.MONTH -> (-6..6).map { offset ->
            val c = at(offset, Calendar.MONTH)
            val key = monthKeyOf(c.timeInMillis)
            val label = if (offset == 0) {
                "本月"
            } else {
                "${c.get(Calendar.MONTH) + 1}月"
            }
            key to label
        }
        LedgerPeriod.YEAR -> (-3..3).map { offset ->
            val c = at(offset, Calendar.YEAR)
            val key = yearKeyOf(c.timeInMillis)
            val label = if (offset == 0) "今年" else "${c.get(Calendar.YEAR)}"
            key to label
        }
        LedgerPeriod.ALL -> emptyList()
    }
}

/**
 * 日期条：选中的那一项居中，左右是更早/更晚的，可滑动；「展开」变成网格一次看全。
 *
 * 为什么不做成弹窗日历：真机反馈「点日之后不要先弹选择日期，直接让我看当前日期」——
 * 大多数时候用户只是想看看今天，弹窗反而多一步。
 */
@Composable
private fun PeriodStrip(
    items: List<Pair<String, String>>,
    selectedKey: String?,
    expanded: Boolean,
    onSelect: (String) -> Unit,
    onToggle: () -> Unit,
) {
    if (items.isEmpty()) return
    val selectedIndex = items.indexOfFirst { it.first == selectedKey }.coerceAtLeast(0)

    Column(
        Modifier
            .fillMaxWidth()
            .background(Ak.Scrim),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (expanded) "全部 ${items.size} 项" else "左右滑动选日期",
                color = Ak.TextFaint,
                fontSize = 10.sp,
                letterSpacing = 0.5.sp,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onToggle) {
                Text(if (expanded) "收起" else "展开", color = Ak.accent, fontSize = 11.sp)
            }
        }
        if (expanded) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                items.map { it }.chunked(7).forEach { row ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        row.forEach { (key, label) ->
                            Box(Modifier.weight(1f)) {
                                StripChip(key, label, key == selectedKey, onSelect)
                            }
                        }
                        // 补齐空位，保持七列对齐
                        repeat(7 - row.size) { Box(Modifier.weight(1f)) {} }
                    }
                }
            }
        } else {
            // 让选中项落在第 4 个位置 → 视觉上居中
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = (selectedIndex - 3).coerceAtLeast(0),
            )
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items, key = { it.first }) { (key, label) ->
                    StripChip(key, label, key == selectedKey, onSelect)
                }
            }
            // 选中项变了（或刚切到这一周期）就滚过去，保证它居中
            LaunchedEffect(selectedKey, items) {
                listState.animateScrollToItem((selectedIndex - 3).coerceAtLeast(0))
            }
        }
    }
}

@Composable
private fun StripChip(
    key: String,
    label: String,
    selected: Boolean,
    onSelect: (String) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .background(akChipBackground(selected))
            .clickable(interactionSource = interaction, indication = null) { onSelect(key) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Ak.onAccent else Ak.Text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}