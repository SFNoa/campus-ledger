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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.morchid.ecardledger.data.Account
import com.morchid.ecardledger.data.AutoCategory
import com.morchid.ecardledger.data.EntrySource
import com.morchid.ecardledger.data.LedgerEntry
import com.morchid.ecardledger.data.LedgerRepository
import java.util.Locale

/**
 * 主页的动作集合。
 *
 * 收成一个对象而不是十几个参数，理由有两个：
 *  - 新增动作不用改函数签名，调用方也不用跟着改（GUI 可扩展性）
 *  - 调用方必须写命名参数，不会再出现「回调传到错误的参数位置」
 *    —— 主页参数一度到 11 个，位置传参的坑已经踩过两次
 *
 * 全部带默认空实现：测试里只传关心的那几个就行。
 */
data class HomeActions(
    val onSync: () -> Unit = {},
    val onOpenProfile: () -> Unit = {},
    val onSetCategory: (orderId: String, category: String) -> Unit = { _, _ -> },
    val onAddManual:
        (amount: String, isIncome: Boolean, merchant: String, category: String, note: String, accountId: String?) -> Unit =
        { _, _, _, _, _, _ -> },
    val onDelete: (orderId: String) -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    val onSelectAccount: (accountId: String?) -> Unit = {},
    val onUnpairTransfer: (groupId: String) -> Unit = {},
    /** 手动标成内部转账（orderId, 是否标记） */
    val onMarkTransfer: (orderId: String, on: Boolean) -> Unit = { _, _ -> },
    val onBindCampus: () -> Unit = {},
    val onToggleReviewOnly: () -> Unit = {},
    val onDeleteMany: (orderIds: List<String>) -> Unit = {},
    val onConfirmEntry: (orderId: String, amountText: String, isIncome: Boolean, category: String) -> Unit =
        { _, _, _, _ -> },
    val onRename: (orderId: String, newName: String) -> Unit = { _, _ -> },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState,
    actions: HomeActions = HomeActions(),
) {
    var tab by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<LedgerEntry?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的账本") },
                actions = {
                    TextButton(
                        onClick = actions.onSync,
                        // 被频率限制时直接禁用：点了也只会被拦住，不如把原因写在余额卡里
                        enabled = !state.loading && state.syncBlockedReason == null,
                    ) {
                        Text(if (state.loading) "同步中…" else "同步")
                    }
                    // 「自动记账」「锁定」都收进了「我的」页，顶栏只留最常用的两个，
                    // 顺便解决窄屏上标题被挤成两行的问题
                    TextButton(onClick = actions.onOpenProfile) { Text("我的") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AssetsCard(state = state, onBindCampus = actions.onBindCampus)
            if (state.accounts.isNotEmpty()) {
                AccountChips(
                    state = state,
                    onSelect = actions.onSelectAccount,
                    onToggleReview = actions.onToggleReviewOnly,
                )
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("流水") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("统计") })
            }
            when (tab) {
                0 -> TransactionList(
                    entries = state.visibleEntries,
                    accounts = state.accounts,
                    // 没绑校园卡时别叫用户去点「同步」—— 那时同步没东西可同步
                    emptyHint = if (state.loggedIn) {
                        "还没有记录，点右上角「同步」抓取校园卡流水"
                    } else {
                        "还没有记录。\n绑定校园卡可以自动同步；也可以先用「手动记一笔」，\n" +
                            "或者在「我的 → 绑定中心」里开启微信/支付宝的通知记账。"
                    },
                    onDeleteMany = actions.onDeleteMany,
                    onEdit = { editing = it },
                    onAdd = { showAdd = true },
                )
                else -> StatsScreen(state.visibleEntries, Modifier.fillMaxSize())
            }
        }
    }

    // ⚠️ 这两个对话框必须真的能关掉。
    // 曾经把 onDismissRequest 和按钮回调都写成空 lambda，结果弹过一次提示后就再也关不掉、
    // 整个界面被永久挡住（单元测试只断言了「弹出来」，所以没发现；真机验证时才发现）。
    state.message?.let { text ->
        AlertDialog(
            onDismissRequest = actions.onDismissNotice,
            confirmButton = { TextButton(onClick = actions.onDismissNotice) { Text("好") } },
            title = { Text("提示") },
            text = { Text(text) },
        )
    }

    state.error?.let { text ->
        AlertDialog(
            onDismissRequest = actions.onDismissNotice,
            confirmButton = { TextButton(onClick = actions.onDismissNotice) { Text("知道了") } },
            title = { Text("出错了") },
            text = { Text(text) },
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
            // 任何记录都能删（以前只有手动记的能删，结果通知误抓的记录删不掉）
            onDelete = {
                actions.onDelete(entry.orderId)
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

/**
 * 资产总览。
 *
 * 这个 App 是**账本**，不是校园卡查询工具 —— 所以首页给的是所有账户的合计与明细，
 * 而不是只有校园卡余额。数据来源是 `accounts` 表（校园卡由同步写入，微信/支付宝手动填）。
 */
@Composable
internal fun AssetsCard(
    state: UiState,
    onBindCampus: () -> Unit,
    /** 资产页把总资产放在顶部动画区，这里就不要再重复一遍 */
    showTotal: Boolean = true,
) {
    val visibleAccounts = state.accounts.filter { it.enabled }
    // 用「当前余额」而不是账户表里的快照值：
    // 微信/支付宝的余额 = 快照 + 快照之后的流水，所以通知抓的收支会让余额动起来
    fun balanceOf(account: Account): Long = state.accountBalances[account.id] ?: account.balanceCents
    val totalCents = visibleAccounts.sumOf { balanceOf(it) }
    // 有账户但余额是 0 且需要手动维护 —— 提示去哪儿填
    val hasUnfilledManual = visibleAccounts.any { it.balanceManual && balanceOf(it) == 0L }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (showTotal) {
                Text("总资产", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "¥ " + yuan(totalCents),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                Text("账户", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${visibleAccounts.size} 个账户 · 上次同步 " + (state.lastSync ?: "从未"),
                style = MaterialTheme.typography.bodySmall,
            )
            state.card?.let { card ->
                Text(
                    card.cardName + " · " + card.ownerName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (visibleAccounts.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "还没有账户。绑定校园卡、或在「我的 → 绑定中心」开启微信/支付宝。",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Spacer(Modifier.height(10.dp))
                visibleAccounts.forEach { account ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(account.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "¥ " + yuan(balanceOf(account)) +
                                if (account.balanceManual) " · 手动" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // 没绑校园卡时给引导，而不是摆一个空余额
            if (!state.loggedIn) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "校园卡还没绑定。绑定后余额和流水会自动同步进来；" +
                        "不绑也能用手动记账、以及微信/支付宝的通知记账。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onBindCampus) { Text("去绑定校园卡") }
            }

            if (hasUnfilledManual) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "微信/支付宝的余额要手动填：在「我的 → 绑定中心」里改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 自动同步失败时的内联提示（不弹模态框，用户可点右上角「同步」重试）
            state.syncError?.let { text ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "自动同步失败：$text",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // 频率限制 / 风控冻结期间：说明「为什么现在不能同步、还要等多久」。
            // 这跟「失败」是两件事 —— 这里根本没发出请求，正是为了避免触发学校风控。
            state.syncBlockedReason?.let { text ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "⏳ $text",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun TransactionList(
    entries: List<LedgerEntry>,
    accounts: List<Account>,
    emptyHint: String,
    onDeleteMany: (List<String>) -> Unit,
    onEdit: (LedgerEntry) -> Unit,
    onAdd: () -> Unit,
) {
    val labelOf = remember(accounts) { accounts.associate { it.id to it.name } }

    // 批量管理：待确认的旧消息往往一次要删一堆，一条条点太折磨
    var bulkMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("共 ${entries.size} 条", style = MaterialTheme.typography.bodySmall)
            if (bulkMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        if (selected.size == entries.size) {
                            selected.clear()
                        } else {
                            selected.clear()
                            selected.addAll(entries.map { it.orderId })
                        }
                    }) {
                        Text(if (selected.size == entries.size && entries.isNotEmpty()) "取消全选" else "全选")
                    }
                    TextButton(
                        onClick = { confirmDelete = true },
                        enabled = selected.isNotEmpty(),
                    ) {
                        Text("删除 ${selected.size} 条")
                    }
                    TextButton(onClick = {
                        bulkMode = false
                        selected.clear()
                    }) { Text("完成") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (entries.isNotEmpty()) {
                        TextButton(onClick = { bulkMode = true }) { Text("批量") }
                    }
                    OutlinedButton(onClick = onAdd) { Text("手动记一笔") }
                }
            }
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(emptyHint, modifier = Modifier.padding(24.dp))
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(entries, key = { it.orderId }) { entry ->
                    EntryRow(
                        entry = entry,
                        accountLabel = labelOf[entry.accountId],
                        selectable = bulkMode,
                        checked = entry.orderId in selected,
                        onClick = {
                            if (bulkMode) {
                                if (entry.orderId in selected) {
                                    selected.remove(entry.orderId)
                                } else {
                                    selected.add(entry.orderId)
                                }
                            } else {
                                onEdit(entry)
                            }
                        },
                    )
                }
            }
        }
    }

    // 批量删除要二次确认 —— 一次删几十条，误触代价太大
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
                    onDeleteMany(selected.toList())
                    confirmDelete = false
                    bulkMode = false
                    selected.clear()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

/** 账户筛选：内部转账让「每个账户各看各的」变得有必要 */
@Composable
internal fun AccountChips(
    state: UiState,
    onSelect: (String?) -> Unit,
    onToggleReview: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = state.selectedAccountId == null && !state.reviewOnly,
            onClick = { onSelect(null) },
            label = { Text("全部") },
        )
        // 待确认的记录单独一个入口 —— 通知抓来但没认全的需要集中处理/清理
        if (state.needsReviewCount > 0) {
            FilterChip(
                selected = state.reviewOnly,
                onClick = onToggleReview,
                label = { Text("待确认 ${state.needsReviewCount}") },
            )
        }
        // 只列启用的账户：没启用的渠道（比如关掉的支付宝）不会有任何记录，
        // 列出来只是干扰；而且这样和上面的「资产总览」口径一致
        state.accounts.filter { it.enabled }.forEach { account ->
            FilterChip(
                selected = state.selectedAccountId == account.id,
                onClick = { onSelect(account.id) },
                label = { Text(account.name) },
            )
        }
    }
}

@Composable
internal fun EntryRow(
    entry: LedgerEntry,
    accountLabel: String?,
    selectable: Boolean = false,
    checked: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 批量模式下每行前面加勾选框
        if (selectable) {
            Checkbox(checked = checked, onCheckedChange = { onClick() })
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.merchant, style = MaterialTheme.typography.bodyLarge)
                if (entry.isTransfer) {
                    Spacer(Modifier.width(6.dp))
                    MiniTag("转账", MaterialTheme.colorScheme.tertiaryContainer)
                }
                if (entry.kind == LedgerRepository.KIND_NEEDS_REVIEW) {
                    Spacer(Modifier.width(6.dp))
                    MiniTag("待确认", MaterialTheme.colorScheme.errorContainer)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(entry.timeText)
                    accountLabel?.let { append(" · ").append(it) }
                    if (entry.manual) append(" · 手动")
                    if (entry.source == EntrySource.NOTIFICATION) append(" · 通知")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                (if (entry.isIncome) "+" else "-") + yuan(entry.amountCents),
                style = MaterialTheme.typography.titleMedium,
                color = if (entry.isIncome) Color(0xFF2E7D32) else Color(0xFFC62828),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                entry.category.ifEmpty { "未分类" },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer,
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun MiniTag(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
internal fun CategoryPickerDialog(
    entry: LedgerEntry,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onDelete: (() -> Unit)?,
    onUnpair: (() -> Unit)?,
    /** 非 null 时显示"标记为内部转账"（已经有了对手的就不用显示） */
    onMarkTransfer: (() -> Unit)? = null,
    /** 「待确认」的记录：填金额 + 选方向，补完就是一条正常账目 */
    onConfirmAmount: ((amountText: String, isIncome: Boolean, category: String) -> Unit)? = null,
    /** 改名（交易对方/商户）；null 表示这个入口不提供改名 */
    onRename: ((newName: String) -> Unit)? = null,
) {
    // 待确认记录需要用户自己填金额和方向，所以这里要有局部状态
    val needsReview = entry.kind == LedgerRepository.KIND_NEEDS_REVIEW
    var amountText by remember { mutableStateOf("") }
    var income by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf(entry.category.ifBlank { "其他" }) }
    var name by remember { mutableStateOf(entry.merchant) }
    var renaming by remember { mutableStateOf(false) }
    /** 分类的二级选择：非 null 表示正在看这个一级分类的二级选项 */
    var pendingTop by remember { mutableStateOf<String?>(null) }
    // 提交后收键盘
    val dismissKeyboard = rememberDismissKeyboard()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.merchant) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    entry.timeText + " · " + yuan(entry.amountCents) + " 元 · " + entry.kind,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.isTransfer) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "这是一笔内部转账，已计入账户余额但不计入收支统计。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (entry.rawText.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "原始内容：" + entry.rawText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 改名：账单/通知里的名字经常没法看（「商户消费」「POS消费」），
                // 改成自己认得的说法（「三食堂」「充饭卡」）是刚需。
                // **触发按钮在对话框底部**（和删除/取消并列）；点了才展开这个输入框 ——
                // 一是对话框干净，二是 Robolectric 渲染带输入框的对话框会卡死（踩过一次）。
                if (onRename != null && renaming) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("名称") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                onRename(name)
                                dismissKeyboard()
                            },
                            enabled = name.isNotBlank() && name != entry.merchant,
                        ) { Text("保存") }
                    }
                }

                if (needsReview && onConfirmAmount != null) {
                    // 「待确认」的正确出路就是补全，而不是只能删掉
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "这笔通知没识别出金额。填上金额再选收/支，它就正常入账，" +
                            "账户余额也会跟着更新。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !income,
                            onClick = { income = false },
                            label = { Text("支出") },
                        )
                        FilterChip(
                            selected = income,
                            onClick = { income = true },
                            label = { Text("收入") },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("金额（元）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("分类", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AutoCategory.DEFAULT_CATEGORIES.forEach { item ->
                            FilterChip(
                                selected = item == category,
                                onClick = { category = item },
                                label = { Text(item) },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            onConfirmAmount(amountText, income, category)
                            dismissKeyboard()
                        },
                        enabled = amountText.isNotBlank(),
                    ) { Text("保存金额并入账") }
                } else {
                    Spacer(Modifier.height(12.dp))
                    val top = pendingTop
                    if (top == null) {
                        Text("选择分类", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(8.dp))
                        AutoCategory.DEFAULT_CATEGORIES.chunked(3).forEach { row ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                row.forEach { item ->
                                    AssistChip(
                                        // 有二级分类的先展开，没有的直接落库
                                        onClick = {
                                            if (AutoCategory.subsOf(item).isEmpty()) {
                                                onPick(item)
                                            } else {
                                                pendingTop = item
                                            }
                                        },
                                        label = { Text(item) },
                                    )
                                }
                            }
                        }
                    } else {
                        // 二级分类：购物 → 衣物 / 日用品 …；也可以只记到一级
                        Text("$top · 再选一层（可不选）", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(8.dp))
                        AutoCategory.subsOf(top).chunked(3).forEach { row ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                row.forEach { sub ->
                                    AssistChip(
                                        onClick = { onPick("$top/$sub") },
                                        label = { Text(sub) },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { onPick(top) }) { Text("只记到「$top」") }
                            TextButton(onClick = { pendingTop = null }) { Text("返回") }
                        }
                    }
                }

                if (onMarkTransfer != null) {
                    Spacer(Modifier.height(10.dp))
                    val markInteraction = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .background(Ak.PanelHigh)
                            .clickable(
                                interactionSource = markInteraction,
                                indication = null,
                                onClick = onMarkTransfer,
                            )
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        Text(
                            "标记为内部转账 · 不计入收支",
                            color = Ak.accent,
                            fontSize = 12.sp,
                        )
                    }
                }

                if (onUnpair != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onUnpair) { Text("解除转账配对") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        // 「改名称」和「删除」并列放在下面 —— 真机反馈要求的（原来它孤零零待在正文里）
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("删除", color = Ak.Expense) }
                }
                if (onRename != null && !renaming) {
                    TextButton(onClick = { renaming = true }) { Text("改名称") }
                }
            }
        },
    )
}

/**
 * 分 → 元的显示文本。
 *
 * 故意是 internal 而不是 private：资产页、流水页、我的页都要用它，
 * 各处自己写一遍格式化迟早会出现「有的地方两位小数、有的地方一位」。
 */
internal fun yuan(cents: Long): String = String.format(Locale.CHINA, "%.2f", cents / 100.0)
