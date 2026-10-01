package com.morchid.ecardledger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morchid.ecardledger.data.AccountIds
import com.morchid.ecardledger.data.AccountType
import com.morchid.ecardledger.data.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「我的」页的动作集合（同 [HomeActions] 的理由：收成对象，便于扩展）。
 */
data class ProfileActions(
    val onBack: () -> Unit = {},
    val onOpenBindings: () -> Unit = {},
    val onOpenNotificationSetup: () -> Unit = {},
    val onOpenImport: () -> Unit = {},
    val onPickAvatar: (android.net.Uri) -> Unit = {},
    /** 换主题色（传 [AkAccent] 的枚举名） */
    val onSetThemeAccent: (String) -> Unit = {},
    /** 换底色（传 [AkBase] 的枚举名） */
    val onSetThemeBase: (String) -> Unit = {},
    val onAddHeroImage: (android.net.Uri) -> Unit = {},
    val onRemoveHeroImage: (String) -> Unit = {},
    val onResetHeroImages: () -> Unit = {},
    /** 省钱计划的额度（设过之后在「我的」改） */
    val onSetBudget: (String) -> Unit = {},
    /** 切换花费方式：省钱计划 / 自由支出（额度保留） */
    val onSetBudgetMode: (Boolean) -> Unit = {},
    val onChangePassword: (oldPassword: String, newPassword: String, confirm: String) -> Unit =
        { _, _, _ -> },
    val onLock: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

/**
 * 「我的」页：账号、绑定、密码、锁定。
 *
 * 改密码做成**页内展开**而不是弹窗 —— 一来设置页弹模态框体验差，
 * 二来「带输入框的对话框」在 Robolectric 下渲染测试会卡死（本项目踩过），
 * 页内表单可以直接被渲染测试覆盖。
 */
@OptIn(ExperimentalMaterial3Api::class)
/**
 * 头像：方舟那种**方形切角**头像框，而不是圆的。
 * 没有设置过就显示一个斜线占位块。
 */
@Composable
fun AvatarView(ref: String, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        ref,
    ) {
        value = if (ref.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                ImageStore.load(context, ref, 320)?.asImageBitmap()
            }
        }
    }
    val image = bitmap
    Box(
        Modifier
            .size(size)
            .clip(AkCutCorner(cut = 10.dp, topStart = false, topEnd = true, bottomStart = true))
            .background(Ak.PanelHigh)
            .border(
                1.dp,
                Ak.LineStrong,
                AkCutCorner(cut = 10.dp, topStart = false, topEnd = true, bottomStart = true),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AkSlashes(count = 4, color = Ak.LineStrong)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
/**
 * 外观设置里的轮换图缩略图。
 * 直接从同一个 [ImageStore] 取，改动会立刻反映到三个页面的顶部图。
 */
@Composable
private fun HeroThumb(ref: String, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, ref) {
        value = withContext(Dispatchers.IO) {
            ImageStore.load(context, ref, 200)?.asImageBitmap()
        }
    }
    val image = bitmap
    Box(
        Modifier
            .size(size)
            .background(Ak.PanelHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AkSlashes(count = 3, color = Ak.LineStrong)
        }
    }
}

@Composable
fun ProfileScreen(
    state: UiState,
    actions: ProfileActions = ProfileActions(),
    /** 作为主标签页时没有"上一级"，就不显示返回 */
    showBack: Boolean = true,
) {
    var changingPassword by remember { mutableStateOf(false) }
    /** 正在查看的协议；null = 没打开 */
    var legalDoc by remember { mutableStateOf<LegalDoc?>(null) }
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    // 头像：从相册选一张，复制进 App 私有目录后展示
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) actions.onPickAvatar(uri) }

    // 顶部轮换图（外观设置里）
    val heroPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) actions.onAddHeroImage(uri) }

    // 提交后收键盘
    val dismissKeyboard = rememberDismissKeyboard()

    val wechatOn = state.accountOf(AccountIds.WECHAT)?.enabled == true
    val alipayOn = state.accountOf(AccountIds.ALIPAY)?.enabled == true

    // 刻意**不套 Scaffold**：它默认注入系统栏 inset，会把风景图从屏幕最上沿顶下去。
    // 真机反馈「我的页全面屏没修好、别的页正常」就是这个原因（另外两页没套 Scaffold）。
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
            // 风景图作为**内容的第一项** —— 下滑时跟着滚上去，和资产页/流水页一致
            HeroBanner(refs = state.heroImages, height = 130.dp)

            Column(Modifier.padding(16.dp)) {
            // ---------------------------------------------- 头像 + 账号
            AkPanel(tag = "PROFILE", accent = Ak.accent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarView(ref = state.avatarRef, size = 64.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.localUsername.ifBlank { "本机账号" },
                            color = Ak.Text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "账号只存在这台手机上 · LOCAL ONLY",
                            color = Ak.TextFaint,
                            fontSize = 10.sp,
                            letterSpacing = 0.8.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { avatarPicker.launch(arrayOf("image/*")) },
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(
                                if (state.avatarRef.isBlank()) "+ 上传头像" else "更换头像",
                                color = Ak.accent,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            // ---------------------------------------------- 底色 + 主题色
            AkPanel(tag = "THEME", accent = Ak.accent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(width = 3.dp, height = 14.dp)
                            .background(Ak.accent),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("外观", color = Ak.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text("APPEARANCE", color = Ak.TextFaint, fontSize = 10.sp, letterSpacing = 2.sp)
                }
                Spacer(Modifier.height(8.dp))

                // 底色
                Text("底色", color = Ak.Text, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val currentBase = state.themeBase.ifBlank { AkBase.FROST.name }
                    AkBase.entries.forEach { option ->
                        val selected = option.name == currentBase
                        val interaction = remember { MutableInteractionSource() }
                        Box(
                            Modifier
                                .background(akChipBackground(selected))
                                .clickable(
                                    interactionSource = interaction,
                                    indication = null,
                                ) { actions.onSetThemeBase(option.name) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                option.label,
                                color = akChipText(selected),
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // 顶部轮换图：从资产页挪过来的 —— 那儿是"看"的地方，这儿才是"设置"的地方
                Text("顶部图片", color = Ak.Text, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.heroImages.forEach { ref ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            HeroThumb(ref = ref, size = 54.dp)
                            TextButton(onClick = { actions.onRemoveHeroImage(ref) }) {
                                Text("移除", color = Ak.TextDim, fontSize = 10.sp)
                            }
                        }
                    }
                    val addInteraction = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .size(54.dp)
                            .background(Ak.PanelHigh)
                            .clickable(
                                interactionSource = addInteraction,
                                indication = null,
                            ) { heroPicker.launch(arrayOf("image/*")) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("+", color = Ak.accent, fontSize = 22.sp)
                    }
                }
                Row {
                    TextButton(onClick = actions.onResetHeroImages) {
                        Text("恢复默认两张", color = Ak.TextDim, fontSize = 11.sp)
                    }
                }

                // 主题色（属于「外观」，必须紧跟底色和顶部图片，别被别的面板插进来）
                Spacer(Modifier.height(12.dp))
                Text("主题色", color = Ak.Text, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val current = state.themeAccent.ifBlank { AkAccent.AQUA.name }
                    AkAccent.entries.forEach { option ->
                        val selected = option.name == current
                        val interaction = remember { MutableInteractionSource() }
                        Column(
                            Modifier.clickable(
                                interactionSource = interaction,
                                indication = null,
                            ) { actions.onSetThemeAccent(option.name) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .background(option.color)
                                    .then(if (selected) Modifier.border(2.dp, Ak.Text) else Modifier),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                option.label,
                                color = if (selected) Ak.Text else Ak.TextDim,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            // ---------------------------------------------- 省钱计划
            // 额度设过之后，改额度的地方在这儿（资产页只显示状态 + 会变短的绿条）。
            // ⚠️ 它必须**独立成一个面板**，不能塞进上面的「外观」里 ——
            // 之前就是塞进去了，结果主题色被挤到省钱计划下面（真机反馈抓到的）。
            BudgetEditor(
                state = state,
                onSetBudget = actions.onSetBudget,
                onSetBudgetMode = actions.onSetBudgetMode,
                hint = "选「省钱计划」并填一个额度，资产页才会显示进度。",
            )
            Spacer(Modifier.height(14.dp))

            // ---------------------------------------------- 安全提示（如果有）
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

            // ------------------------------------------------ 账号安全
            // 用户名和「只在本机」在上面的头像区已经显示过一次了，这里不再重复，
            // 只留真正的安全操作（改密码）
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "账号安全",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))

                    if (!changingPassword) {
                        OutlinedButton(onClick = { changingPassword = true }) { Text("修改密码") }
                    } else {
                        Text("修改密码", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = oldPassword,
                            onValueChange = { oldPassword = it },
                            label = { Text("当前密码") },
                            singleLine = true,
                            enabled = !state.loading,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Next,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newPassword,
                            onValueChange = { newPassword = it },
                            label = { Text("新密码") },
                            singleLine = true,
                            enabled = !state.loading,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Next,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it },
                            label = { Text("确认新密码") },
                            singleLine = true,
                            enabled = !state.loading,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        Row2(
                            left = {
                                Button(
                                    onClick = {
                                        actions.onChangePassword(oldPassword, newPassword, confirmPassword)
                                        oldPassword = ""; newPassword = ""; confirmPassword = ""
                                        changingPassword = false
                                        dismissKeyboard()
                                    },
                                    enabled = !state.loading,
                                ) { Text("保存") }
                            },
                            right = {
                                TextButton(
                                    onClick = {
                                        changingPassword = false
                                        oldPassword = ""; newPassword = ""; confirmPassword = ""
                                    },
                                ) { Text("取消") }
                            },
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "改密码需要输入当前密码；忘了密码没有找回途径，只能清除 App 数据重来。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ------------------------------------------------ 绑定状态
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("绑定", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))

                    BindingLine(
                        name = "校园卡",
                        status = if (state.loggedIn) "已绑定 " + state.username else "未绑定",
                        on = state.loggedIn,
                    )
                    BindingLine(
                        name = "微信",
                        status = if (wechatOn) "已开启通知记账" else "未开启",
                        on = wechatOn,
                    )
                    BindingLine(
                        name = "支付宝",
                        status = if (alipayOn) "已开启通知记账" else "未开启",
                        on = alipayOn,
                    )

                    Spacer(Modifier.height(12.dp))
                    Button(onClick = actions.onOpenBindings, modifier = Modifier.fillMaxWidth()) {
                        Text("打开绑定中心")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = actions.onOpenNotificationSetup,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("通知自动记账设置")
                    }
                    Spacer(Modifier.height(8.dp))
                    // 账单导入：通知抓不到红包/转账，官方账单才是完整来源
                    OutlinedButton(
                        onClick = actions.onOpenImport,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("导入官方账单（补全红包/转账）")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ------------------------------------------------ 锁定
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("锁定", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "锁定后下次打开要重新输密码。绑定关系不会丢。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = actions.onLock) { Text("立即锁定") }
                }
            }

            // ---------------------------------------------- 协议与政策（离线可读）
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { legalDoc = LegalDoc.TERMS }) {
                    Text("用户协议", fontSize = 12.sp, color = Ak.TextDim)
                }
                TextButton(onClick = { legalDoc = LegalDoc.PRIVACY }) {
                    Text("隐私政策", fontSize = 12.sp, color = Ak.TextDim)
                }
            }
            legalDoc?.let { doc ->
                LegalDialog(doc = doc, onDismiss = { legalDoc = null })
            }

            Spacer(Modifier.height(24.dp))
            }
    }
}

@Composable
private fun BindingLine(name: String, status: String, on: Boolean) {
    Row2(
        left = {
            Text(name, style = MaterialTheme.typography.bodyLarge)
        },
        right = {
            Text(
                if (on) "✅ $status" else status,
                style = MaterialTheme.typography.bodySmall,
                color = if (on) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
    )
}

/** 一行两栏：左标题、右状态。抽出来避免各处重复写 Row + weight */
@Composable
private fun Row2(
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        left()
        right()
    }
}

/** 给外部判断「这个渠道是否开着」用（保持和仓库层一致的语义） */
internal fun UiState.isChannelOn(type: AccountType): Boolean {
    val id = when (type) {
        AccountType.WECHAT -> AccountIds.WECHAT
        AccountType.ALIPAY -> AccountIds.ALIPAY
        else -> return false
    }
    return accountOf(id)?.enabled == true
}
