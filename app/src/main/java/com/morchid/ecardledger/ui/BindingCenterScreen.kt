package com.morchid.ecardledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.morchid.ecardledger.data.AccountIds
import com.morchid.ecardledger.data.AccountType
import java.util.Locale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import com.morchid.ecardledger.data.school.LoginKind
import com.morchid.ecardledger.data.school.SchoolRegistry

/**
 * 绑定中心的动作集合（同 [HomeActions] 的理由：收成对象，便于扩展）。
 */
data class BindingActions(
    val onBack: () -> Unit = {},
    val onBindCampus: () -> Unit = {},
    /** 选定院校后去登录（参数是 SchoolProfile.id） */
    val onAddSchool: (String) -> Unit = {},
    val onUnbindCampus: () -> Unit = {},
    val onToggleChannel: (AccountType, Boolean) -> Unit = { _, _ -> },
    val onSetBalance: (accountId: String, yuanText: String) -> Unit = { _, _ -> },
    val onOpenNotificationSetup: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

/**
 * 绑定中心：校园卡 / 微信 / 支付宝都在这里绑定与解绑。
 *
 * 设计立场：**没有一个是必须的**。不绑校园卡可以用手动记账，
 * 不绑微信/支付宝也不影响校园卡同步。页面上要明说这一点，
 * 否则用户会以为「不绑定就没法用」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BindingCenterScreen(
    state: UiState,
    actions: BindingActions = BindingActions(),
) {
    var confirmUnbind by remember { mutableStateOf(false) }
    val balanceInputs = remember { mutableStateMapOf<String, String>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("绑定中心") },
                navigationIcon = { TextButton(onClick = actions.onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            (state.error ?: state.message)?.let { text ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (state.error != null) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(text, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = actions.onDismissNotice) { Text("知道了") }
                    }
                }
            }

            Text(
                "下面每一项都可以不绑定 —— 不绑校园卡也能手动记账，微信/支付宝是可选的自动记账渠道。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            // ------------------------------------------------ 校园卡
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "校园卡" + if (state.schoolName.isNotEmpty()) " · ${state.schoolName}" else "",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))

                    if (state.loggedIn) {
                        Text("✅ 已绑定：" + state.username, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "同步时会直接向学校服务器发请求，并受频率限制保护（详见 README）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))

                        if (!confirmUnbind) {
                            OutlinedButton(onClick = { confirmUnbind = true }) { Text("解除绑定") }
                        } else {
                            Text(
                                "解除后不再自动同步校园卡；已经同步进来的记录会保留，不会删除。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    actions.onUnbindCampus()
                                    confirmUnbind = false
                                }) { Text("确认解除") }
                                TextButton(onClick = { confirmUnbind = false }) { Text("取消") }
                            }
                        }
                    } else {
                        Text(
                            "未绑定 —— 绑定后可以自动同步校园卡流水，余额与消费记录都会自己进来。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        val showSchoolPicker = remember { mutableStateOf(false) }
                Button(onClick = { showSchoolPicker.value = true }) { Text("添加高校") }
                if (showSchoolPicker.value) {
                    SchoolPickerDialog(
                        currentId = state.schoolId,
                        onDismiss = { showSchoolPicker.value = false },
                        onPick = { id ->
                            showSchoolPicker.value = false
                            actions.onAddSchool(id)
                        },
                    )
                }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ------------------------------------------------ 微信 / 支付宝
            ChannelCard(
                title = "微信",
                accountId = AccountIds.WECHAT,
                type = AccountType.WECHAT,
                state = state,
                actions = actions,
                balanceInputs = balanceInputs,
            )
            Spacer(Modifier.height(12.dp))
            ChannelCard(
                title = "支付宝",
                accountId = AccountIds.ALIPAY,
                type = AccountType.ALIPAY,
                state = state,
                actions = actions,
                balanceInputs = balanceInputs,
            )

            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick = actions.onOpenNotificationSetup,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("通知权限、教程与自检")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ChannelCard(
    title: String,
    accountId: String,
    type: AccountType,
    state: UiState,
    actions: BindingActions,
    balanceInputs: MutableMap<String, String>,
) {
    val account = state.accountOf(accountId)
    val on = account?.enabled == true
    val dismissKeyboard = rememberDismissKeyboard()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Switch(checked = on, onCheckedChange = { actions.onToggleChannel(type, it) })
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (on) {
                    "✅ 已开启：监听「$title」的支付通知，自动记账"
                } else {
                    "未开启。开启并授予通知使用权后，「$title」的收支会自动记进来。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (on) {
                Spacer(Modifier.height(12.dp))

                // 当前余额 = 快照 + 快照之后的流水。
                // 老实现只存了快照，所以流水进进出出、余额纹丝不动（真机反馈过）。
                val snapshot = account?.balanceCents ?: 0L
                val current = state.accountBalances[accountId] ?: snapshot
                Text(
                    "当前余额 ¥" + yuanText(current),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (current != snapshot) {
                    Text(
                        "由你填的快照 ¥" + yuanText(snapshot) + " 加上之后的流水自动算出",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = balanceInputs[accountId] ?: yuanText(current),
                        onValueChange = { balanceInputs[accountId] = it },
                        label = { Text("$title 余额（元）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = {
                        actions.onSetBalance(
                            accountId,
                            balanceInputs[accountId] ?: yuanText(current),
                        )
                        balanceInputs.remove(accountId)
                        // 提交后收键盘（输入框不会自己失焦）
                        dismissKeyboard()
                    }) { Text("保存") }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "填你现在在「$title」里看到的余额。保存后这个账户的每笔收支都会自动加减，" +
                        "所以余额会跟着流水变，不用反复回来改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun yuanText(cents: Long): String = String.format(Locale.CHINA, "%.2f", cents / 100.0)

/**
 * 院校选择：先选学校，再去登录。
 *
 * 内置列表来自 [SchoolRegistry]（一所学校 = 一个 `SchoolProfile` 配置对象）。
 * 「自定义院校」（自己填流水网址）是下一步，这里先留出入口位置说明。
 */
@Composable
private fun SchoolPickerDialog(
    currentId: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择院校") },
        text = {
            Column {
                Text(
                    "选你要绑定的学校，然后再输校园卡账号密码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                SchoolRegistry.all.forEach { school ->
                    val selected = school.id == currentId
                    // 只有账号密码登录的学校才能代填；微信授权那类点了也白点
                    val usable = school.loginKind == LoginKind.CAS_FORM
                    val interaction = remember { MutableInteractionSource() }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                enabled = usable,
                                interactionSource = interaction,
                                indication = null,
                            ) { onPick(school.id) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                school.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (usable) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            if (!usable) {
                                Text(
                                    "暂不支持：该校只有微信登录，App 无法代填。\n可用「账单导入」或手动记账。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (selected) {
                            Text(
                                "当前",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    HorizontalDivider()
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "你的学校不在列表里？下一步会开放「自定义院校」：\n" +
                        "填入校园卡流水的网址和账号密码即可（需要学校的系统是 CAS 那类登录）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}