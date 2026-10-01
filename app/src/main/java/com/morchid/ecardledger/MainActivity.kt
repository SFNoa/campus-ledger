package com.morchid.ecardledger

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morchid.ecardledger.ui.Ak
import com.morchid.ecardledger.ui.AkAccent
import com.morchid.ecardledger.ui.AkBackground
import com.morchid.ecardledger.ui.AkBase
import com.morchid.ecardledger.ui.AssetActions
import com.morchid.ecardledger.ui.AssetScreen
import com.morchid.ecardledger.ui.BindingActions
import com.morchid.ecardledger.ui.BindingCenterScreen
import com.morchid.ecardledger.ui.EcardLedgerTheme
import com.morchid.ecardledger.ui.HomeActions
import com.morchid.ecardledger.ui.ImportActions
import com.morchid.ecardledger.ui.ImportScreen
import com.morchid.ecardledger.ui.LedgerScreen
import com.morchid.ecardledger.ui.LedgerViewModel
import com.morchid.ecardledger.ui.LocalAuthScreen
import com.morchid.ecardledger.ui.LoginScreen
import com.morchid.ecardledger.ui.NotificationSetupScreen
import com.morchid.ecardledger.ui.PermissionActions
import com.morchid.ecardledger.ui.PermissionsScreen
import com.morchid.ecardledger.ui.ProfileActions
import com.morchid.ecardledger.ui.ProfileScreen
import com.morchid.ecardledger.ui.UiState
import kotlinx.coroutines.launch
import com.morchid.ecardledger.ui.ProfileScreen

/**
 * 界面路由。
 *
 * 加新页面只需要：在这里加一项 + 在下面的 `when` 里加一个分支。
 * 刻意不用导航库 —— 页面就这几个，一个 list 当栈足够，而且没有额外依赖。
 */
private sealed interface Screen {
    /** 主界面：底部三标签（流水 / 资产 / 我的） */
    data object Tabs : Screen

    /** 绑定中心：校园卡 / 微信 / 支付宝 */
    data object Bindings : Screen

    /** 通知子系统的设置页：权限、教程、自检、配对 */
    data object NotificationSetup : Screen

    /** 账单导入（半自动：一键打开微信 + 从邮件里直接选本 App + 就地解密） */
    data object Import : Screen

    /** 绑定校园卡（从绑定中心进来时保留返回） */
    data object CampusBind : Screen
}

/**
 * 界面流程：
 *
 * ```
 * 本机账号（注册 / 解锁）      ← 入口，只存在这台手机上
 *   ↓
 * 绑定校园卡（可跳过，会记住）  ← 想自动同步校园卡流水才需要
 *   ↓
 * 主页 ──→ 我的 ──→ 绑定中心 ──→ 通知权限与自检
 *                 └─→ 绑定校园卡
 * ```
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全面屏：内容画到屏幕边缘，insets 由各页自己留（顶部风景图因此能顶到状态栏下面）
        enableEdgeToEdge()
        // 从邮件附件 / 文件管理器点「用我的账本打开」进来的账单
        val incomingUri = incomingFileUri(intent)

        setContent {
            // ViewModel 必须**在主题之前**取：主题色存在状态里，主题得按它来渲染
            val viewModel: LedgerViewModel = viewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()

            EcardLedgerTheme(
                base = AkBase.byName(state.themeBase),
                accent = AkAccent.byName(state.themeAccent),
            ) {
                Surface(
                    // 键盘弹起时**整屏往上让**。
                    // 全面屏（edge-to-edge）下 adjustResize 不再自动生效，必须自己吃 ime inset ——
                    // 放在根容器上一次搞定，三个标签页和所有子页面（绑定中心/导入/登录）全覆盖。
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding(),
                    color = MaterialTheme.colorScheme.background,
                ) {

                    // 极简返回栈：push / pop 而已
                    val stack = remember { mutableStateListOf<Screen>(Screen.Tabs) }
                    val current = stack.last()
                    fun push(screen: Screen) = stack.add(screen)
                    fun pop() {
                        if (stack.size > 1) stack.removeAt(stack.lastIndex)
                    }
                    // 加密 zip 输密码后要接着读同一个 uri
                    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }

                    /**
                     * 系统返回键（手势返回同理）：**先按层级退回上一级**，而不是直接退出。
                     *
                     * 真机反馈过「习惯性回退一下，App 就没了」—— 因为之前根本没接管返回键，
                     * 系统在子页面上也照退不误。现在：
                     *  - 有子页面（绑定中心/通知设置/账单导入/绑定校园卡）→ 弹掉子页面
                     *  - 已经在主界面 → 交给 MainTabs 去处理「不在资产页就先回资产页」
                     *  - 已经在资产页 → 不拦截，这时才真的退出（符合系统惯例）
                     */
                    BackHandler(enabled = stack.size > 1) { pop() }

                    // 从外部点进来的账单：直接进导入页并开始读
                    LaunchedEffect(incomingUri) {
                        if (incomingUri != null) {
                            pendingImportUri = incomingUri
                            push(Screen.Import)
                            viewModel.importBill(incomingUri)
                        }
                    }

                    // 校园卡绑定成功后自动退回上一页（这里不做导航跳转判断，
                    // 只负责「绑定页已经完成使命」这一件事）
                    LaunchedEffect(state.loggedIn) {
                        if (state.loggedIn && stack.last() == Screen.CampusBind) pop()
                    }

                    // ① 还没解锁：App 自己的账号，不是学校账号
                    if (!state.unlocked) {
                        LocalAuthScreen(
                            state = state,
                            onRegister = viewModel::registerLocal,
                            onLogin = viewModel::loginLocal,
                            onDismissNotice = viewModel::consumeMessage,
                            // 注册页就能传头像
                            onPickAvatar = viewModel::setAvatar,
                        )
                        return@Surface
                    }

                    // ② 首次运行：权限引导（一次性，可跳过）
                    //    放在绑定之前 —— 用户一开始就知道「要用自动记账得先给权限」
                    if (!state.permissionsOnboarded) {
                        PermissionsScreen(
                            state = state,
                            actions = PermissionActions(
                                onOpenNotificationAccess = {
                                    openSafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                },
                                onOpenBatterySettings = {
                                    openSafely(
                                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                                    )
                                },
                                onRefresh = viewModel::refresh,
                                // 权限教学之后直接进绑定中心（真机反馈要的顺序：
                                // 注册 → 权限 → 绑定中心）
                                onDone = {
                                    viewModel.completePermissionsOnboarding()
                                    push(Screen.Bindings)
                                },
                            ),
                        )
                        return@Surface
                    }

                    when (current) {
                        // ------------------------------------------ 主界面：三个标签
                        // 真机反馈：**首次进入不要再弹校园卡绑定**。
                        // 现在的路径是「注册 → 权限教学 →（onDone 推入）绑定中心」，
                        // 校园卡登录页只在用户主动去绑定时才出现（见 Screen.CampusBind）。
                        Screen.Tabs -> {
                            MainTabs(
                                state = state,
                                viewModel = viewModel,
                                onPush = ::push,
                            )
                        }

                        Screen.Bindings -> BindingCenterScreen(
                            state = state,
                            actions = BindingActions(
                                onBack = ::pop,
                                onBindCampus = { push(Screen.CampusBind) },
                            // 选了学校再进登录页：schoolId 一换，后面的同步和账户 id 全跟着换
                            onAddSchool = { id ->
                                viewModel.selectSchool(id)
                                push(Screen.CampusBind)
                            },
                                onUnbindCampus = viewModel::unbindCampus,
                                onToggleChannel = viewModel::setChannelEnabled,
                                onSetBalance = viewModel::setAccountBalance,
                                onOpenNotificationSetup = { push(Screen.NotificationSetup) },
                                onDismissNotice = viewModel::consumeMessage,
                            ),
                        )

                        Screen.CampusBind -> LoginScreen(
                            state = state,
                            onLogin = { username, password, remember ->
                                viewModel.login(username, password, remember)
                            },
                            onBack = ::pop,
                        )

                        Screen.NotificationSetup -> NotificationSetupScreen(
                            state = state,
                            onBack = ::pop,
                            onRefresh = viewModel::refresh,
                            onOpenSystemSettings = {
                                // 通知使用权是敏感权限，App 不能自己申请，
                                // 只能把用户送到系统设置页自己去勾
                                openSafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            },
                            onSelfCheck = viewModel::simulateNotification,
                            onRebuildPairs = viewModel::pairTransfersNow,
                            onToggleDiagnostics = viewModel::setNotificationDiagnostics,
                            onAddSender = viewModel::addSender,
                            onRemoveSender = viewModel::removeSender,
                            onAddChannel = viewModel::addChannel,
                            onRemoveChannel = viewModel::removeChannel,
                            onClearLog = viewModel::clearNotificationLog,
                            onDiagnosticsCopied = viewModel::notifyDiagnosticsCopied,
                            onDismissNotice = viewModel::consumeMessage,
                        )

                        Screen.Import -> ImportScreen(
                            state = state,
                            actions = ImportActions(
                                onBack = ::pop,
                                // 只能帮到「打开 App 主页」这一步：
                                // 微信内部的「钱包/账单」页面没有对外暴露，别的 App 起不来
                                onOpenWeChat = { openAppSafely("com.tencent.mm") },
                                onOpenAlipay = { openAppSafely("com.eg.android.AlipayGphone") },
                                onPickedFile = { uri ->
                                    pendingImportUri = uri
                                    viewModel.importBill(uri)
                                },
                                onSubmitPassword = { pwd ->
                                    pendingImportUri?.let { viewModel.importBill(it, pwd) }
                                },
                                onDismissNotice = viewModel::dismissImportNotice,
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * 跳系统设置页。
     * 某些 ROM 会缺这些 Activity，所以失败就静默忽略 —— 不能让一次跳转把 App 弄崩。
     */
    private fun openSafely(intent: Intent) {
        runCatching { startActivity(intent) }
    }

    /** 打开另一个 App 的主页（微信/支付宝）。拿不到入口就说明没装。 */
    private fun openAppSafely(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) openSafely(intent)
    }


    /**
     * 从「打开方式 / 分享」进来的账单文件 uri。
     * 支持 ACTION_VIEW 的 intent.data，以及 ACTION_SEND 的 EXTRA_STREAM。
     */
    private fun incomingFileUri(intent: Intent?): android.net.Uri? {
        val i = intent ?: return null
        return when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION")
            (i.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri)
            else -> null
        }
    }
}

/** 资产页在 pager 里的下标 —— 返回键要退回这里 */
private const val TAB_ASSET = 1

/**
 * 主界面：底部三标签 + 左右滑动切换。
 *
 * 打开 App **默认落在资产页**（中间那个）：第一眼看到的是总资产和今日流水。
 * 三个页面放在 HorizontalPager 里，所以左右滑动和点底部按钮是同一套状态，
 * 不会出现「滑过去之后底部高亮没跟上」这种不一致。
 */
@Composable
private fun MainTabs(
    state: UiState,
    viewModel: LedgerViewModel,
    onPush: (Screen) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = TAB_ASSET, pageCount = { 3 })
    val scope = rememberCoroutineScope()

    // 返回键：不在资产页 → 先回资产页；已经在资产页 → 不拦截（这时才真的退出）
    BackHandler(enabled = pagerState.currentPage != TAB_ASSET) {
        scope.launch { pagerState.animateScrollToPage(TAB_ASSET) }
    }

    Scaffold(
        containerColor = Ak.Bg,
        // insets 由各页自己处理：顶部风景图要顶到状态栏下面，底栏自己留导航栏高度
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AkBottomBar(
                current = pagerState.currentPage,
                onSelect = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
            )
        },
    ) { padding ->
        AkBackground(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // 相邻页预组合：滑动时不会先白一下再出内容
                beyondViewportPageCount = 1,
            ) { page ->
                when (page) {
                    0 -> LedgerScreen(
                        state = state,
                        actions = HomeActions(
                            onSync = { viewModel.syncNow() },
                            onOpenProfile = {},
                            onSetCategory = viewModel::setCategory,
                            onAddManual = { amount, income, merchant, category, note, accountId ->
                                viewModel.addManualEntry(
                                    amount, income, merchant, category, note, accountId,
                                )
                            },
                            onDelete = viewModel::deleteEntry,
                        onMarkTransfer = viewModel::setManualTransfer,
                            onDismissNotice = viewModel::consumeMessage,
                            onSelectAccount = viewModel::selectAccount,
                            onUnpairTransfer = viewModel::unpairTransfer,
                            onBindCampus = viewModel::openCampusBinding,
                            onToggleReviewOnly = viewModel::toggleReviewOnly,
                            onDeleteMany = viewModel::deleteEntries,
                            onConfirmEntry = viewModel::confirmEntry,
                            onRename = viewModel::renameEntry,
                        ),
                    )
                    TAB_ASSET -> AssetScreen(
                        state = state,
                        actions = AssetActions(
                            onAddHeroImage = viewModel::addHeroImage,
                            onRemoveHeroImage = viewModel::removeHeroImage,
                            onSetBudget = viewModel::setDailyBudget,
                            onOpenBindings = { onPush(Screen.Bindings) },
                        ),
                    )
                    else -> ProfileScreen(
                        state = state,
                        actions = ProfileActions(
                            onOpenBindings = { onPush(Screen.Bindings) },
                            onOpenNotificationSetup = { onPush(Screen.NotificationSetup) },
                            onOpenImport = { onPush(Screen.Import) },
                            onPickAvatar = viewModel::setAvatar,
                            onSetThemeAccent = viewModel::setThemeAccent,
                            onSetThemeBase = viewModel::setThemeBase,
                            // 顶部轮换图的设置在「我的 → 外观」里
                            onAddHeroImage = viewModel::addHeroImage,
                            onRemoveHeroImage = viewModel::removeHeroImage,
                            onResetHeroImages = viewModel::resetHeroImages,
                            onSetBudget = viewModel::setDailyBudget,
                            onSetBudgetMode = viewModel::setBudgetMode,
                            onChangePassword = viewModel::changeLocalPassword,
                            onLock = viewModel::lockLocal,
                            onDismissNotice = viewModel::consumeMessage,
                        ),
                        // 作为主标签时没有"上一级"
                        showBack = false,
                    )
                }
            }
        }
    }
}

/**
 * 底部导航：方舟那套——深色条 + 一条顶线，选中项是黄字 + 顶上的黄短条。
 * 没有涟漪：方舟的交互反馈是"硬"的。
 */
@Composable
private fun AkBottomBar(current: Int, onSelect: (Int) -> Unit) {
    val items = listOf(
        Triple("流水", "LEDGER", 0),
        Triple("资产", "ASSETS", TAB_ASSET),
        Triple("我的", "PROFILE", 2),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .background(Ak.Panel)
            // 全面屏手势条：底栏要自己留出导航栏高度，否则按钮会被盖住
            .navigationBarsPadding(),
    ) {
        // 顶线：选中项那一段是黄的
        Row(Modifier.fillMaxWidth().height(2.dp)) {
            items.forEach { (_, _, index) ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(if (index == current) Ak.accent else Ak.Line),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            items.forEach { (cn, latin, index) ->
                val selected = index == current
                val interaction = remember { MutableInteractionSource() }
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) { onSelect(index) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val labelColor by animateColorAsState(
                        targetValue = if (selected) Ak.accent else Ak.TextDim,
                        animationSpec = tween(durationMillis = 220),
                        label = "tab-color",
                    )
                    Text(
                        cn,
                        color = labelColor,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        latin,
                        color = if (selected) Ak.accent.copy(alpha = 0.7f) else Ak.TextFaint,
                        fontSize = 8.sp,
                        letterSpacing = 1.6.sp,
                    )
                }
            }
        }
    }
}
