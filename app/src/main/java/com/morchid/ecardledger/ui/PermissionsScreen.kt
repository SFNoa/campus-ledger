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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 首次运行的权限引导（一次性，可跳过）。
 *
 * 设计立场：
 *  1. **每一项都说清「为什么」**。通知使用权能读到通知内容，用户有理由问为什么要给。
 *  2. **可跳过**。手动记账不需要任何权限，硬拦着不给进是不合理的。
 *  3. **不为不存在的功能要权限**。这个 App 自己不发通知，
 *     所以**不申请** POST_NOTIFICATIONS —— 要一个用不上的权限只会损耗信任。
 */
data class PermissionActions(
    val onOpenNotificationAccess: () -> Unit = {},
    val onOpenBatterySettings: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onDone: () -> Unit = {},
)

@Composable
fun PermissionsScreen(
    state: UiState,
    actions: PermissionActions = PermissionActions(),
) {
    // 用户去系统设置里勾完回来时，状态要刷新（否则卡片还显示「未开启」）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) actions.onRefresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text("开启必要的权限", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "不想用自动记账就直接跳过 —— 手动记一笔不需要任何权限。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        PermissionItem(
            title = "通知使用权",
            required = true,
            granted = state.notificationAccessGranted,
            why = "在系统设置里，把「我的账本」右边的开关打开。",
            onOpen = actions.onOpenNotificationAccess,
        )

        Spacer(Modifier.height(12.dp))

        PermissionItem(
            title = "忽略电池优化",
            required = false,
            granted = state.batteryExempt,
            why = "选「允许」，否则后台可能被回收，那段时间的收支会漏记。",
            onOpen = actions.onOpenBatterySettings,
        )

        Spacer(Modifier.height(24.dp))

        Button(onClick = actions.onDone, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.notificationAccessGranted) "完成，开始记账" else "先跳过")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "随时能在「我的 → 绑定中心」里改。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PermissionItem(
    title: String,
    required: Boolean,
    granted: Boolean,
    why: String,
    onOpen: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        granted -> "✅ 已开启"
                        required -> "需要开启"
                        else -> "建议开启"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                why,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!granted) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = onOpen) { Text("去开启") }
            } else {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onOpen) { Text("查看 / 修改") }
            }
        }
    }
}