package com.morchid.ecardledger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * 账单导入页。
 *
 * ## 为什么是「半自动」
 *
 * 微信/支付宝的账单**只能你自己导出**（没有对个人开放的接口），而且文件是发到**邮箱**的
 * 加密 zip —— 这两步跨了应用边界，App 做不到全自动。能自动化的部分我们做满：
 *
 *  1. **一键打开微信/支付宝**（跳转到 App 主页是允许的；再往里点具体页面就不行了 ——
 *     微信内部页面没有对外暴露，别的 App 起不来）
 *  2. **清单式引导**：照着一二三四点，不用记路径
 *  3. **从邮件里直接选本 App 打开**（已经在清单里注册了 .csv/.zip 的打开方式，
 *     邮件附件点「打开方式」就能选到本 App，不用先去文件管理器找）
 *  4. **加密 zip 直接在 App 里解密**，输入微信发给你的密码即可，不用先自己解压
 */
data class ImportActions(
    val onBack: () -> Unit = {},
    val onOpenWeChat: () -> Unit = {},
    val onOpenAlipay: () -> Unit = {},
    val onPickedFile: (android.net.Uri) -> Unit = {},
    val onSubmitPassword: (password: String) -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    state: UiState,
    actions: ImportActions = ImportActions(),
) {
    var password by remember { mutableStateOf("") }

    // 系统文件选择器：csv / zip / xlsx 都放行，实际能不能解析由解析器判断
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) actions.onPickedFile(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入官方账单") },
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
            Text(
                "通知记账是实时的，但红包、转账这类通知里没有金额。" +
                    "官方账单是唯一完整的来源，两者结合才不漏账、也不重复。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            // ------------------------------------------------ 步骤
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("怎么导出", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Step("1", "点下面的按钮打开微信（或支付宝）")
                    Step("2", "微信：我 → 服务 → 钱包 → 账单 → 右上角常见问题 → 下载账单")
                    Step("3", "选「用于个人对账」+ 时间范围，填你的邮箱")
                    Step("4", "微信会把**加密 zip** 发到你邮箱，解压密码由微信另外发给你")
                    Step("5", "在邮箱里点开附件 → 选「用我的账本打开」（或下载后回来点下面的「选择账单文件」）")
                    Step("6", "如果提示要密码，把微信给的密码填进来就行，不用自己先解压")
                }
            }

            Spacer(Modifier.height(12.dp))

            // ------------------------------------------------ 打开微信 / 支付宝
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.onOpenWeChat) { Text("打开微信") }
                OutlinedButton(onClick = actions.onOpenAlipay) { Text("打开支付宝") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "只能帮到这一步：微信内部的「钱包/账单」页面没有对外开放，别的 App 起不来它们，" +
                    "所以我们把最麻烦的「找路径」写清楚，而不是假装能替你点。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            // ------------------------------------------------ 选文件
            Button(
                onClick = {
                    picker.launch(
                        arrayOf(
                            "text/csv",
                            "text/comma-separated-values",
                            "text/plain",
                            "application/zip",
                            "application/x-zip-compressed",
                            "application/vnd.ms-excel",
                            "application/octet-stream",
                            "*/*",
                        ),
                    )
                },
                enabled = !state.importRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.importRunning) "正在读取…" else "选择账单文件（CSV 或 zip）")
            }

            if (state.importFileName.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "上次选的文件：" + state.importFileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 加密 zip：就地输密码
            if (state.importNeedPassword) {
                Spacer(Modifier.height(12.dp))
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("这个压缩包需要密码", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "微信导出账单时会把解压密码单独发给你（通常在「微信支付」的消息里）。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("解压密码") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Done,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { actions.onSubmitPassword(password) },
                            enabled = password.isNotBlank() && !state.importRunning,
                        ) { Text("用这个密码读取") }
                    }
                }
            }

            // ------------------------------------------------ 结果
            state.importMessage?.let { text ->
                Spacer(Modifier.height(12.dp))
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(text, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = actions.onDismissNotice) { Text("知道了") }
                    }
                }
            }

            state.importError?.let { text ->
                Spacer(Modifier.height(12.dp))
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(text, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = actions.onDismissNotice) { Text("知道了") }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Step(index: String, text: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            "$index.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(text.replace("**", ""), style = MaterialTheme.typography.bodyMedium)
    }
}
