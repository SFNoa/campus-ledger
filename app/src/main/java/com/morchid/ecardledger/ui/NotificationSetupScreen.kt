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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.morchid.ecardledger.data.AccountIds
import com.morchid.ecardledger.data.AccountType
import java.util.Locale

/**
 * 「通知自动记账」设置页。
 *
 * 这一页承担三件事：
 *  1. **告诉用户要开哪些权限、在哪儿开**（通知使用权是敏感权限，App 无法自行申请）
 *  2. 自检：真机上不好随便造一条微信通知，用它验证「通知 → 解析 → 记账」链路是否通
 *  3. 内部转账的手动重扫
 *
 * 注意：**渠道开关和余额不在这里**，它们在「绑定中心」——
 * 这一页只管通知子系统自己的事（权限、教程、自检、配对），避免两处重复。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSetupScreen(
    state: UiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onSelfCheck: (AccountType, String, String) -> Unit,
    onRebuildPairs: () -> Unit,
    onToggleDiagnostics: (Boolean) -> Unit = {},
    onAddSender: (String) -> Unit = {},
    onRemoveSender: (String) -> Unit = {},
    onAddChannel: (String) -> Unit = {},
    onRemoveChannel: (String) -> Unit = {},
    onClearLog: () -> Unit = {},
    onDiagnosticsCopied: () -> Unit = {},
    onDismissNotice: () -> Unit,
) {
    // 用户去系统设置里勾完权限回来时，状态得刷新一下
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onRefresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showSelfCheck by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通知自动记账") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
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
            // 提示用页内内联展示，不弹模态框：设置页打断用户没意义，
            // 而且自检结果本来就该在这一页看得见
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
                        TextButton(onClick = onDismissNotice) { Text("知道了") }
                    }
                }
            }

            // ------------------------------------------------ 状态
            // 自检：权限在、但系统没把服务绑上。小米/红米上最常见，
            // 尤其是 App 更新之后（MIUI 的 AutoStartManagerService 会直接拒绝绑定）。
            if (state.listenerNeedsAttention) {
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "⚠️ 通知使用权已授权，但系统没有把我们拉起来",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "小米/红米上很常见，App 更新之后尤其容易出现。三步解决：\n" +
                                "1. 设置 → 应用设置 → 应用管理 → 我的账本 → **自启动 = 允许**\n" +
                                "2. 同一页 → **省电策略 = 无限制**\n" +
                                "3. 回本页把「通知使用权」**关掉再打开**，或重启手机\n\n" +
                                "上次连上：" + state.listenerLastConnectedText,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            val granted = state.notificationAccessGranted
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (granted) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (granted) "✅ 通知使用权已开启" else "⚠️ 还没开启通知使用权",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "这是系统的敏感权限，App 不能自己申请，必须你手动去设置里勾选。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!granted) {
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onOpenSystemSettings) { Text("去开启通知使用权") }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ------------------------------------------------ 教程
            SectionTitle("开启步骤")
            Step("1", "点上面的「去开启通知使用权」，系统会打开「通知使用权」列表")
            Step("2", "在列表里找到「我的账本」，把开关打开（系统可能弹确认框）")
            Step("3", "打开微信：我 → 设置 → 新消息通知（关着的话收不到通知，也就记不了账）")
            Step("4", "打开支付宝：我的 → 设置 → 通用 → 新消息通知")
            Step("5", "建议把本 App 加入电池优化白名单，否则后台被杀会漏记")

            Spacer(Modifier.height(12.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("它到底读了什么", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "只读取微信/支付宝的支付通知内容（金额、商户）用来自动记账；" +
                            "不读其他应用的通知，不联网上传，不需要你的微信/支付宝账号密码，" +
                            "也不逆向它们的接口 —— 所以不存在账号被风控的风险。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // 渠道开关和余额都挪到「绑定中心」了 —— 那里是绑定的统一入口，
            // 这一页只负责「权限 / 教程 / 自检 / 内部转账配对」这些通知子系统自己的事。
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "要开启微信/支付宝的自动记账、或填写它们的余额，请到「绑定中心」。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ------------------------------------------------ 自检
            SectionTitle("自检")
            Text(
                "真机上不好随便造一条微信通知，这里点一下会走一遍「通知 → 解析 → 记账」的完整链路，",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    showSelfCheck = true
                    onSelfCheck(
                        AccountType.WECHAT,
                        "微信支付",
                        "微信支付凭证：已支付¥3.50 向「自检商户」付款",
                    )
                }) { Text("模拟一条微信支出") }
                OutlinedButton(onClick = {
                    showSelfCheck = true
                    onSelfCheck(
                        AccountType.ALIPAY,
                        "支付宝",
                        "支付宝：收款到账 ¥20.00",
                    )
                }) { Text("模拟一条支付宝收入") }
            }
            if (showSelfCheck) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "自检结果会显示在这一页顶部；也可以回流水页看这条记录是否出现。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "下面两个是噪音样本：它们都应该被忽略或记成待确认，绝不该变成正常账目。" +
                    "以前聊天消息里提到金额就会被记成支出，现在不会了。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    showSelfCheck = true
                    onSelfCheck(AccountType.WECHAT, "张三", "我转你 50 元，明天还你")
                }) { Text("模拟一条聊天消息") }
                OutlinedButton(onClick = {
                    showSelfCheck = true
                    onSelfCheck(AccountType.WECHAT, "微信", "[微信红包]恭喜发财，大吉大利")
                }) { Text("模拟一条红包通知") }
            }

            Spacer(Modifier.height(20.dp))

            // ------------------------------------------------ 发送方白名单
            SectionTitle("支付通知的来源白名单")
            Text(
                "先按发送方筛一道：聊天消息的标题是联系人昵称或群名，支付通知的标题是「微信支付」" +
                    "这类固定字样。只有标题命中白名单（或正文出现「已支付」这类平台措辞）的通知才会被解析，" +
                    "其余一律忽略 —— 所以聊天消息不会再被记成账。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            state.notifSenders.forEach { sender ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(sender, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onRemoveSender(sender) }) { Text("移除") }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "如果真实的支付通知没被识别，打开下面的诊断看到它的标题后，一键加进来即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            // ------------------------------------------------ 渠道白名单（比标题硬）
            SectionTitle("通知渠道白名单")
            Text(
                "渠道比标题更可靠：微信支付的通知走它自己的渠道，和聊天消息根本不是一个渠道。" +
                    "标题会被版本改得面目全非，渠道 id 稳定得多。渠道 id 可以从下面的诊断里看到。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (state.notifChannels.isEmpty()) {
                Text(
                    "（还没有添加渠道 —— 从诊断里挑一条支付通知，点它下面的「这个渠道也当成支付通知」）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.notifChannels.forEach { id ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(id, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { onRemoveChannel(id) }) { Text("移除") }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ------------------------------------------------ 通知诊断
            SectionTitle("通知诊断")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("记录收到的通知与判定结果", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "默认关闭。打开后会把微信/支付宝通知的标题与正文（截断）记在本机，" +
                            "用来排查「为什么这条没记上」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.notifDiagnostics, onCheckedChange = onToggleDiagnostics)
            }

            if (state.notifLog.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                state.notifLog.take(10).forEach { entry ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (entry.verdict == "已忽略") {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                        ),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                entry.appName + " · " + entry.verdict,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                "标题：" + entry.title.ifBlank { "（无）" },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "正文：" + entry.text.take(50),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                entry.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // 渠道 id：比标题更硬的结构性信号（微信支付有独立渠道），
                            // 显示出来一是排查方便，二是将来做渠道白名单要靠它
                            if (entry.channelId.isNotBlank()) {
                                Text(
                                    "渠道：" + entry.channelId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (entry.channelId !in state.notifChannels) {
                                    TextButton(onClick = { onAddChannel(entry.channelId) }) {
                                        Text("这个渠道也当成支付通知")
                                    }
                                }
                            }
                            if (entry.verdict == "已忽略" && entry.title.isNotBlank() &&
                                entry.title !in state.notifSenders
                            ) {
                                TextButton(onClick = { onAddSender(entry.title) }) {
                                    Text("把「${entry.title.take(12)}」加入白名单")
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 复制出来贴给我（或自己对照着加白名单）——
                    // 我没法凭空知道每个微信版本的通知长什么样，只能靠真实样本补规则
                    val clipboard = LocalClipboardManager.current
                    TextButton(onClick = {
                        val dump = state.notifLog.joinToString("\n") { e ->
                            "[${e.appName}] 标题=${e.title} | 正文=${e.text} | ${e.verdict} | ${e.reason}"
                        }
                        clipboard.setText(AnnotatedString(dump))
                        onDiagnosticsCopied()
                    }) { Text("复制诊断记录") }
                    TextButton(onClick = onClearLog) { Text("清空诊断记录") }
                }
            } else if (state.notifDiagnostics) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "还没有记录。去微信付一笔（或让朋友发条消息），再回到这里看。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionTitle("内部转账配对")
            Text(
                "同步来的校园卡充值，和微信/支付宝那边的支出如果是同一笔钱，" +
                    "会被自动识别成内部转账，并从收支统计里剔除，避免同一笔钱算两次。" +
                    "刚开启通知、或以后导入账单之后，点下面可以把之前没配上的补一遍。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRebuildPairs) { Text("重新扫描内部转账") }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun Step(index: String, text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            index + ".",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
