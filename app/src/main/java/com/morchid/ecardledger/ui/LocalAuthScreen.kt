package com.morchid.ecardledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.morchid.ecardledger.data.LocalAuth
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

/**
 * App 自己的账号：注册 / 登录。
 *
 * 这是 App 的**入口**，取代了「一进来就是校园卡登录」的老流程。
 *
 * 要说清的三件事（都写在界面上，因为这直接关系到用户信任）：
 *  1. 这个账号**只存在这台手机上**，不联网、不上传、不需要服务器
 *  2. 它**不是学校的账号**（校园卡是后面单独「绑定」的事）
 *  3. 因为纯本地，所以**没有找回密码** —— 忘了只能清数据重来
 */
@Composable
fun LocalAuthScreen(
    state: UiState,
    onRegister: (String, String, String) -> Unit,
    onLogin: (String, String) -> Unit,
    onDismissNotice: () -> Unit,
    /** 注册时选头像（可跳过）。真机反馈：注册这一步就要能传头像 */
    onPickAvatar: (android.net.Uri) -> Unit = {},
) {
    val registering = !state.hasLocalAccount
    var username by remember { mutableStateOf(if (registering) "" else state.localUsername) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) onPickAvatar(uri) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        Text("我的账本", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            if (registering) "先创建一个本机账号" else "输入密码解锁",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 头像：只在注册时出现，可以不传
        if (registering) {
            Spacer(Modifier.height(18.dp))
            AvatarView(ref = state.avatarRef, size = 76.dp)
            TextButton(onClick = { avatarPicker.launch(arrayOf("image/*")) }) {
                Text(
                    if (state.avatarRef.isBlank()) "上传头像（可选）" else "换一张",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("用户名") },
            singleLine = true,
            enabled = !state.loading,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            enabled = !state.loading,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = if (registering) ImeAction.Next else ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        if (registering) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it },
                label = { Text("确认密码") },
                singleLine = true,
                enabled = !state.loading,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "用户名至少 ${LocalAuth.MIN_USERNAME_LENGTH} 个字符，密码至少 ${LocalAuth.MIN_PASSWORD_LENGTH} 位",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        state.error?.let { text ->
            Spacer(Modifier.height(12.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(text, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onDismissNotice) { Text("知道了") }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                if (registering) onRegister(username, password, confirm) else onLogin(username, password)
            },
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    state.loading -> "处理中…"
                    registering -> "创建账号"
                    else -> "解锁"
                },
            )
        }

        Spacer(Modifier.height(24.dp))

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("关于这个账号", style = MaterialTheme.typography.labelLarge)
                Bullet("它**只存在这台手机上**：不联网、不上传、我们看不到你的密码。")
                Bullet("它**不是学校账号**。校园卡、微信、支付宝是在登录之后按需「绑定」的。")
                Bullet("密码用加盐哈希保存，明文不落盘。")
                Bullet("因为纯本地，**没有找回密码** —— 忘了只能清数据重来。这是为了不给锁留后门。")
                if (!registering) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "忘了密码？没有后门可走，只能在系统设置里清除本 App 数据后重新开始。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Bullet(text: String) {
    Text(
        "· " + text.replace("**", ""),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Normal,
    )
}
