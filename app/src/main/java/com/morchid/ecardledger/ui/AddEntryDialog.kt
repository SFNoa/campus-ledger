package com.morchid.ecardledger.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.morchid.ecardledger.data.Account
import com.morchid.ecardledger.data.AutoCategory

/**
 * 手动补记一笔（现金/其他消费也能记进来）。
 *
 * 这里用 ui.window.Dialog + 自绘 Surface，而不是 Material3 的 AlertDialog。
 * 原因**不是** AlertDialog 有 bug —— 两者在真机上都是常规写法 —— 而是：
 * 「内容里含输入框的对话框」在 Robolectric 下无法断言，Compose 永远达不到 idle
 * （实测 20 万次帧尝试仍不收敛，报 AppNotIdleException）；
 * 而同一个 OutlinedTextField 放在对话框外面就完全正常。
 * 这是 Robolectric + Compose 的已知问题（robolectric#7055、SO 79608556），
 * 属于测试环境的限制，不是本 App 的缺陷。
 *
 * 所以这个对话框只能靠真机手工验证，README 的验证状态里已如实注明。
 * 顺带用 verticalScroll 包了一层，避免小屏上内容超高被裁掉。
 */
@Composable
fun AddEntryDialog(
    /** 可以记到哪些账户（校园卡 / 微信 / 支付宝…）。空列表时退回默认账户。 */
    accounts: List<Account> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (
        amount: String,
        isIncome: Boolean,
        merchant: String,
        category: String,
        note: String,
        accountId: String?,
    ) -> Unit,
) {
    var amount by remember { mutableStateOf("") }
    var isIncome by remember { mutableStateOf(false) }
    var merchant by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(AutoCategory.DEFAULT_CATEGORIES.first()) }
    var note by remember { mutableStateOf("") }
    // 默认选第一个账户；有了这个选择器，红包/转账这类抓不到的场景
    // 才能手动补记到微信（以前手动记账只能记到校园卡）
    var accountId by remember { mutableStateOf(accounts.firstOrNull()?.id) }

    Dialog(
        onDismissRequest = onDismiss,
        // decorFitsSystemWindows = false 让对话框自己处理键盘遮挡，
        // 否则键盘弹起时会盖住底部固定的「保存」按钮
        properties = DialogProperties(decorFitsSystemWindows = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                // 必须限制高度，否则内容会超出屏幕、保存按钮被挤出可视区
                // （小屏模拟器上实测过，verticalScroll 也救不了没有约束的高度）。
                // 用比例而不是固定 dp，这样在各种屏幕上都自适应。
                .fillMaxHeight(0.92f)
                // 键盘弹起时整体上移，保证底部按钮始终可点
                .imePadding()
                .padding(vertical = 12.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                // 上半部分可滚动；下半部分的按钮**固定**在底部。
                // 之前所有内容都在一个滚动容器里，屏幕一矮（320×640）「保存」就被挤出可视区，
                // 而滚动又常被输入框吃掉手势 —— 用户根本存不下去。固定底部才是根治。
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                ) {
                    Spacer(Modifier.height(20.dp))
                    Text("手动记一笔", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(14.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !isIncome,
                            onClick = { isIncome = false },
                            label = { Text("支出") },
                        )
                        FilterChip(
                            selected = isIncome,
                            onClick = { isIncome = true },
                            label = { Text("收入") },
                        )
                    }
                    Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("金额（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("商户 / 说明") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))

                // 账户选择器：一笔钱到底记到哪个账户
                if (accounts.isNotEmpty()) {
                    Text("记到哪个账户", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            // 横向滚动：账户多了也不会把对话框撑高
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        accounts.forEach { account ->
                            FilterChip(
                                selected = account.id == accountId,
                                onClick = { accountId = account.id },
                                label = { Text(account.name) },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                Text("分类", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                // 单行横向滚动，而不是三行换行：
                // 小屏（320×640）上三行会把内容顶出可视区，导致「保存」够不到
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

                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("备注（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // 固定在底部的操作栏 —— 无论上面内容多高都在
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        onConfirm(amount, isIncome, merchant, category, note, accountId)
                    }) {
                        Text("保存")
                    }
                }
            }
        }
    }
}
