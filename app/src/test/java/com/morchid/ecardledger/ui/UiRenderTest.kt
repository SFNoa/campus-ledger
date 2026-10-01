package com.morchid.ecardledger.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.morchid.ecardledger.data.Account
import com.morchid.ecardledger.data.AccountIds
import com.morchid.ecardledger.data.AccountType
import com.morchid.ecardledger.data.CardInfo
import com.morchid.ecardledger.data.LedgerEntry
import com.morchid.ecardledger.data.LedgerRepository
import com.morchid.ecardledger.data.NotificationLogEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose 界面的无头渲染测试（Robolectric 在 JVM 上跑真实 Compose）。
 *
 * 目的不是「像素级正确」，而是把最致命的一类问题挡住：
 * 界面在真实运行时直接抛异常（闪退）。这类问题编译器发现不了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class UiRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val card = CardInfo(
        cardName = "本科生卡",
        balanceCents = 13704,
        sno = "8********7",
        ownerName = "测试同学",
        frozen = false,
        lost = false,
    )

    private fun entries(): List<LedgerEntry> = listOf(
        LedgerEntry(
            orderId = "O1", timeText = "2026-09-28 12:02:13", epochMillis = 1_790_000_000_000,
            amountCents = 400, isIncome = false, kind = "消费",
            merchant = "南校区2食堂2楼白案组", payName = "持卡人消费",
            balanceCents = 13830, toAccount = "3200010", category = "餐饮", note = "", manual = false,
        ),
        LedgerEntry(
            orderId = "O2", timeText = "2026-09-27 19:52:28", epochMillis = 1_789_900_000_000,
            amountCents = 1350, isIncome = false, kind = "消费",
            merchant = "南校区学生宿舍内xzx洗浴", payName = "持卡人消费",
            balanceCents = 14230, toAccount = "1000085", category = "洗浴", note = "", manual = false,
        ),
        LedgerEntry(
            orderId = "O3", timeText = "2026-09-22 11:42:09", epochMillis = 1_789_500_000_000,
            amountCents = 20000, isIncome = true, kind = "充值",
            merchant = "微信支付转账", payName = "微信支付转账",
            balanceCents = 21148, toAccount = "0", category = "充值", note = "", manual = true,
        ),
    )

    private fun homeState(entries: List<LedgerEntry>) = UiState(
        loggedIn = true,
        username = "8207260917",
        card = card,
        entries = entries,
        lastSync = "2026-09-28 13:00",
        // 首页现在是「资产总览」，余额来自 accounts 表（而不是内存里的 card）
        accounts = listOf(
            Account("campus:csu", "校园卡", AccountType.CAMPUS_CARD, balanceCents = 13_704, balanceManual = false),
        ),
    )

    // ------------------------------------------------------------ 登录页

    @Test
    fun `登录页能渲染出标题与按钮`() {
        compose.setContent {
            EcardLedgerTheme { LoginScreen(state = UiState(), onLogin = { _, _, _ -> }) }
        }
        compose.onNodeWithText("绑定校园卡").assertIsDisplayed()
        compose.onNodeWithText("登录并同步").assertIsDisplayed()
        compose.onNodeWithText("学号").assertIsDisplayed()
    }

    @Test
    fun `登录页能显示错误文案`() {
        compose.setContent {
            EcardLedgerTheme {
                LoginScreen(
                    state = UiState(error = "登录失败：账号或密码不正确"),
                    onLogin = { _, _, _ -> },
                )
            }
        }
        compose.onNodeWithText("登录失败：账号或密码不正确").assertIsDisplayed()
    }

    @Test
    fun `登录页在加载中不崩`() {
        compose.setContent {
            EcardLedgerTheme { LoginScreen(state = UiState(loading = true), onLogin = { _, _, _ -> }) }
        }
        // 加载时按钮变成进度指示器，按钮文字仍在（Compose 里分支渲染）
        compose.onNodeWithText("绑定校园卡").assertIsDisplayed()
    }

    // ------------------------------------------------------------ 主页

    @Test
    fun `主页空数据时不崩并给出引导文案`() {
        compose.setContent {
            EcardLedgerTheme {
                TestHome(homeState(emptyList()))
            }
        }
        compose.onNodeWithText("还没有记录，点右上角「同步」抓取校园卡流水").assertIsDisplayed()
        compose.onNodeWithText("总资产").assertIsDisplayed()
        compose.onAllNodesWithText("¥ 137.04").assertCountEquals(2) // 总资产 + 校园卡明细
    }

    @Test
    fun `主页能渲染余额、流水条目与正负号`() {
        compose.setContent {
            EcardLedgerTheme {
                TestHome(homeState(entries()))
            }
        }
        compose.onNodeWithText("总资产").assertIsDisplayed()
        compose.onAllNodesWithText("¥ 137.04").assertCountEquals(2) // 总资产 + 校园卡明细
        compose.onNodeWithText("南校区2食堂2楼白案组").assertIsDisplayed()
        compose.onNodeWithText("-4.00").assertIsDisplayed()
        compose.onNodeWithText("+200.00").assertIsDisplayed()
        compose.onNodeWithText("共 3 条").assertIsDisplayed()
    }

    @Test
    fun `点条目能弹出分类选择框`() {
        var picked: Pair<String, String>? = null
        compose.setContent {
            EcardLedgerTheme {
                HomeScreen(
                    state = homeState(entries()),
                    actions = HomeActions(
                        onSetCategory = { orderId, category -> picked = orderId to category },
                    ),
                )
            }
        }
        compose.onNodeWithText("南校区2食堂2楼白案组").performClick()
        compose.onNodeWithText("选择分类").assertIsDisplayed()
        // 「购物」有二级分类，点它先展开二级（超市现在归在购物/日用品下）
        compose.onNodeWithText("购物").performClick()
        compose.onNodeWithText("日用品").performClick()
        // 回调应当带着正确的 orderId 与所选的分类（二级用 "一级/二级" 表示）
        assert(picked == ("O1" to "购物/日用品")) { "实际回调: $picked" }
    }

    // 注意：这里**故意没有**「点手动记一笔弹出对话框」的用例。
    // 含输入框的对话框在 Robolectric 下永远达不到 idle（robolectric#7055），
    // 该对话框只能真机手工验证，详见 AddEntryDialog.kt 的注释。

    // ------------------------------------------------------------ 统计页

    @Test
    fun `切到统计页能渲染汇总与三张图而不崩`() {
        compose.setContent {
            EcardLedgerTheme {
                TestHome(homeState(entries()))
            }
        }
        compose.onNodeWithText("统计").performClick()

        compose.onNodeWithText("支出").assertIsDisplayed()
        compose.onNodeWithText("收入").assertIsDisplayed()
        compose.onNodeWithText("结余").assertIsDisplayed()
        // 支出 = 4.00 + 13.50 = 17.50；收入 = 200.00；结余 = 182.50
        compose.onNodeWithText("¥ 17.50").assertIsDisplayed()
        compose.onNodeWithText("¥ 200.00").assertIsDisplayed()
        compose.onNodeWithText("¥ 182.50").assertIsDisplayed()
        compose.onNodeWithText("每日支出").assertIsDisplayed()
        compose.onNodeWithText("分类占比（仅支出）").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("余额趋势").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `统计页没有数据时给出提示`() {
        compose.setContent {
            EcardLedgerTheme { StatsScreen(emptyList()) }
        }
        compose.onNodeWithText("还没有数据，先同步一次").assertIsDisplayed()
    }

    @Test
    fun `主页面上的提示与错误会弹对话框`() {
        compose.setContent {
            EcardLedgerTheme {
                TestHome(homeState(entries()).copy(message = "抓取成功，新增 3 条"))
            }
        }
        compose.onNodeWithText("提示").assertIsDisplayed()
        compose.onNodeWithText("抓取成功，新增 3 条").assertIsDisplayed()
    }

    @Test
    fun `重启后卡信息不在内存里时，余额仍然来自账户表`() {
        compose.setContent {
            EcardLedgerTheme {
                // card = null 模拟「App 重启后卡信息丢了」——
                // 老实现会显示「上次同步的余额」这种兜底文案，现在直接读账户表
                TestHome(homeState(entries()).copy(card = null))
            }
        }
        compose.onNodeWithText("总资产").assertIsDisplayed()
        compose.onNodeWithText("总资产").assertIsDisplayed()
        compose.onAllNodesWithText("¥ 137.04").assertCountEquals(2) // 总资产 + 校园卡明细
    }

    @Test
    fun `没有任何账户时给出绑定引导而不是空数字`() {
        compose.setContent {
            EcardLedgerTheme {
                TestHome(UiState(loggedIn = false, accounts = emptyList(), entries = emptyList()))
            }
        }
        compose.onNodeWithText("还没有账户", substring = true).assertExists()
        compose.onNodeWithText("去绑定校园卡").assertIsDisplayed()
    }

    @Test
    fun `自动同步失败只显示内联提示，不弹对话框`() {
        compose.setContent {
            EcardLedgerTheme {
                TestHome(homeState(entries()).copy(syncError = "登录态已失效，请重新登录"))
            }
        }
        compose.onNodeWithText("自动同步失败：登录态已失效，请重新登录").assertExists()
        // 自动动作不该用模态框打断用户
        compose.onNodeWithText("出错了").assertDoesNotExist()
        compose.onNodeWithText("提示").assertDoesNotExist()
    }

    // 下面两条是回归测试：曾经把 onDismissRequest 和按钮回调都写成空 lambda，
    // 结果弹一次提示就再也关不掉、整个界面被永久挡住。
    // 真机上点「好」没反应才发现的，所以这里必须断言「能关掉」，而不只是「弹出来」。

    @Test
    fun `提示对话框能被关掉`() {
        var dismissed = 0
        compose.setContent {
            EcardLedgerTheme {
                TestHome(
                    homeState(entries()).copy(message = "抓取成功，新增 3 条"),
                    onDismiss = { dismissed++ },
                )
            }
        }
        compose.onNodeWithText("好").performClick()
        assertEquals("点「好」必须回调 dismiss，否则界面会被永久挡住", 1, dismissed)
    }

    @Test
    fun `错误对话框能被关掉`() {
        var dismissed = 0
        compose.setContent {
            EcardLedgerTheme {
                TestHome(
                    homeState(entries()).copy(error = "登录态已失效，请重新登录"),
                    onDismiss = { dismissed++ },
                )
            }
        }
        compose.onNodeWithText("知道了").performClick()
        assertEquals("点「知道了」必须回调 dismiss", 1, dismissed)
    }

    // ------------------------------------------------ 多账户 / 转账 / 通知设置

    /**
     * HomeScreen 的参数已经很多，统一用命名参数包一层，
     * 避免位置传错（前一轮就因为位置参数把回调传错了）。
     */
    @Composable
    private fun TestHome(
        state: UiState,
        onUnpair: (String) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        HomeScreen(
            state = state,
            actions = HomeActions(
                onUnpairTransfer = onUnpair,
                onDismissNotice = onDismiss,
            ),
        )
    }

    private fun accountState(entries: List<LedgerEntry>, selected: String? = null) = homeState(entries).copy(
        accounts = listOf(
            Account(
                "campus:csu", "校园卡", AccountType.CAMPUS_CARD,
                balanceCents = 13_704, balanceManual = false,
            ),
            Account(AccountIds.WECHAT, "微信", AccountType.WECHAT, balanceCents = 12_345),
            Account(AccountIds.ALIPAY, "支付宝", AccountType.ALIPAY, enabled = false),
        ),
        selectedAccountId = selected,
    )

    @Test
    fun `内部转账记录会带转账标记`() {
        val transfer = entries()[0].copy(isTransfer = true, transferGroupId = "g1")
        compose.setContent { EcardLedgerTheme { TestHome(accountState(listOf(transfer))) } }
        compose.onNodeWithText("转账").assertExists()
    }

    @Test
    fun `待确认记录会带待确认标记`() {
        val review = entries()[0].copy(
            kind = LedgerRepository.KIND_NEEDS_REVIEW,
            amountCents = 0,
            rawText = "你有一笔交易",
        )
        compose.setContent { EcardLedgerTheme { TestHome(accountState(listOf(review))) } }
        compose.onNodeWithText("待确认").assertExists()
    }

    @Test
    fun `账户筛选芯片会显示各账户并能按账户过滤`() {
        val campusEntry = entries()[0]
        val wechatEntry = entries()[2].copy(accountId = AccountIds.WECHAT)

        // 全部：两条都在
        compose.setContent {
            EcardLedgerTheme { TestHome(accountState(listOf(campusEntry, wechatEntry))) }
        }
        compose.onNodeWithText("全部").assertExists()
        // 账户名现在同时出现在「筛选芯片」和「资产明细」里，所以各 2 处
        compose.onAllNodesWithText("微信").assertCountEquals(2)
        compose.onAllNodesWithText("校园卡").assertCountEquals(2)
        compose.onNodeWithText("共 2 条").assertExists()
    }

    @Test
    fun `选中某个账户后只显示该账户的流水`() {
        val campusEntry = entries()[0]
        val wechatEntry = entries()[2].copy(accountId = AccountIds.WECHAT)
        compose.setContent {
            EcardLedgerTheme {
                TestHome(accountState(listOf(campusEntry, wechatEntry), selected = AccountIds.WECHAT))
            }
        }
        compose.onNodeWithText("共 1 条").assertExists()
        compose.onNodeWithText("微信支付转账").assertExists()
    }

    @Test
    fun `统计页排除了内部转账并说明原因`() {
        val list = entries() + entries()[0].copy(
            orderId = "T1",
            isTransfer = true,
            transferGroupId = "g1",
        )
        compose.setContent { EcardLedgerTheme { TestHome(accountState(list)) } }
        compose.onNodeWithText("统计").performClick()
        compose.onNodeWithText("已排除 1 笔内部转账，避免同一笔钱被算两次").assertExists()
    }

    @Test
    fun `通知设置页未授权时给出开启引导与步骤`() {
        compose.setContent {
            EcardLedgerTheme {
                NotificationSetupScreen(
                    state = accountState(entries()).copy(notificationAccessGranted = false),
                    onBack = {},
                    onRefresh = {},
                    onOpenSystemSettings = {},
                    onSelfCheck = { _, _, _ -> },
                    onRebuildPairs = {},
                    onDismissNotice = {},
                )
            }
        }
        compose.onNodeWithText("还没开启通知使用权", substring = true).assertExists()
        compose.onNodeWithText("去开启通知使用权").assertExists()
        compose.onNodeWithText("开启步骤").assertExists()
        // 渠道开关和余额已经挪到绑定中心，这一页只留权限/教程/自检/配对
        compose.onNodeWithText("请到「绑定中心」", substring = true).assertExists()
    }

    @Test
    fun `通知设置页已授权时显示已开启`() {
        compose.setContent {
            EcardLedgerTheme {
                NotificationSetupScreen(
                    state = accountState(entries()).copy(notificationAccessGranted = true),
                    onBack = {},
                    onRefresh = {},
                    onOpenSystemSettings = {},
                    onSelfCheck = { _, _, _ -> },
                    onRebuildPairs = {},
                    onDismissNotice = {},
                )
            }
        }
        compose.onNodeWithText("通知使用权已开启", substring = true).assertExists()
        compose.onNodeWithText("重新扫描内部转账").assertExists()
    }

    @Test
    fun `通知设置页的提示是页内内联而不是弹窗`() {
        compose.setContent {
            EcardLedgerTheme {
                NotificationSetupScreen(
                    state = accountState(entries()).copy(message = "自检成功：南校区2食堂 3.5 元，已记入微信"),
                    onBack = {},
                    onRefresh = {},
                    onOpenSystemSettings = {},
                    onSelfCheck = { _, _, _ -> },
                    onRebuildPairs = {},
                    onDismissNotice = {},
                )
            }
        }
        compose.onNodeWithText("自检成功", substring = true).assertExists()
        // 不该用模态框打断（设置页弹模态框没意义）
        compose.onNodeWithText("提示").assertDoesNotExist()
    }

    // ------------------------------------------------ 本机账号（App 的入口）

    @Test
    fun `没有本机账号时入口是注册模式`() {
        compose.setContent {
            EcardLedgerTheme {
                LocalAuthScreen(
                    state = UiState(hasLocalAccount = false),
                    onRegister = { _, _, _ -> },
                    onLogin = { _, _ -> },
                    onDismissNotice = {},
                )
            }
        }
        compose.onNodeWithText("先创建一个本机账号").assertIsDisplayed()
        compose.onNodeWithText("创建账号").assertIsDisplayed()
        compose.onNodeWithText("确认密码").assertIsDisplayed()
    }

    @Test
    fun `已有账号时入口是解锁模式`() {
        compose.setContent {
            EcardLedgerTheme {
                LocalAuthScreen(
                    state = UiState(hasLocalAccount = true, localUsername = "testuser"),
                    onRegister = { _, _, _ -> },
                    onLogin = { _, _ -> },
                    onDismissNotice = {},
                )
            }
        }
        compose.onNodeWithText("输入密码解锁").assertIsDisplayed()
        compose.onNodeWithText("解锁").assertIsDisplayed()
        compose.onNodeWithText("确认密码").assertDoesNotExist()
        compose.onNodeWithText("testuser").assertIsDisplayed()
    }

    @Test
    fun `入口页必须说清这是本机账号、不是学校账号、且没有找回密码`() {
        compose.setContent {
            EcardLedgerTheme {
                LocalAuthScreen(
                    state = UiState(hasLocalAccount = true),
                    onRegister = { _, _, _ -> },
                    onLogin = { _, _ -> },
                    onDismissNotice = {},
                )
            }
        }
        compose.onNodeWithText("只存在这台手机上", substring = true).assertExists()
        compose.onNodeWithText("不是学校账号", substring = true).assertExists()
        compose.onNodeWithText("没有找回密码", substring = true).assertExists()
    }

    @Test
    fun `入口页的错误是内联显示而不是弹窗`() {
        compose.setContent {
            EcardLedgerTheme {
                LocalAuthScreen(
                    state = UiState(hasLocalAccount = true, error = "用户名或密码不正确"),
                    onRegister = { _, _, _ -> },
                    onLogin = { _, _ -> },
                    onDismissNotice = {},
                )
            }
        }
        compose.onNodeWithText("用户名或密码不正确").assertExists()
        compose.onNodeWithText("提示").assertDoesNotExist()
    }

    @Test
    fun `未绑定校园卡时主页给引导而不是空余额`() {
        compose.setContent {
            EcardLedgerTheme {
                TestHome(homeState(emptyList()).copy(loggedIn = false))
            }
        }
        compose.onNodeWithText("校园卡还没绑定", substring = true).assertExists()
        compose.onNodeWithText("去绑定校园卡").assertIsDisplayed()
        // 即使没绑校园卡，总览也仍然在（它是账本的首页，不是校园卡页）
        compose.onNodeWithText("总资产").assertIsDisplayed()
    }

    @Test
    fun `主页总资产是各账户余额之和`() {
        compose.setContent {
            EcardLedgerTheme { TestHome(accountState(emptyList())) }
        }
        // accountState：校园卡 137.04 + 微信 123.45（支付宝未启用，不计入）
        compose.onNodeWithText("¥ 260.49").assertIsDisplayed()
        // 账户名同时出现在「筛选芯片」和「资产明细」里，各 2 处
        compose.onAllNodesWithText("校园卡").assertCountEquals(2)
        compose.onAllNodesWithText("微信").assertCountEquals(2)
        // 未启用的账户两边都不该出现
        compose.onNodeWithText("支付宝").assertDoesNotExist()
        compose.onNodeWithText("¥ 123.45 · 手动", substring = true).assertExists()
    }

    @Test
    fun `手动维护余额且为 0 时提示去哪儿填`() {
        compose.setContent {
            EcardLedgerTheme {
                TestHome(
                    accountState(emptyList()).copy(
                        accounts = listOf(
                            Account("campus:csu", "校园卡", AccountType.CAMPUS_CARD, balanceCents = 100, balanceManual = false),
                            Account(AccountIds.WECHAT, "微信", AccountType.WECHAT, balanceCents = 0),
                        ),
                    ),
                )
            }
        }
        compose.onNodeWithText("余额要手动填", substring = true).assertExists()
    }

    // ------------------------------------------------ 「我的」页

    private fun profileState() = accountState(entries()).copy(
        localUsername = "testuser",
        notificationAccessGranted = true,
        loggedIn = true,
        username = "8207260917",
    )

    @Test
    fun `我的页显示账号、绑定状态与锁定入口`() {
        compose.setContent {
            EcardLedgerTheme { ProfileScreen(state = profileState()) }
        }
        compose.onNodeWithText("testuser").assertIsDisplayed()
        compose.onNodeWithText("修改密码").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("校园卡").assertExists()
        compose.onNodeWithText("微信").assertExists()
        compose.onNodeWithText("支付宝").assertExists()
        // 我的页现在顶部有风景图、下面还有主题色面板，"立即锁定"在更下面了
        compose.onNodeWithText("立即锁定").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `我的页点修改密码会展开三段式表单`() {
        compose.setContent {
            EcardLedgerTheme { ProfileScreen(state = profileState()) }
        }
        compose.onNodeWithText("当前密码").assertDoesNotExist()
        // 页面变长了（顶图、外观、省钱计划都在上面），先滚到按钮再点
        compose.onNodeWithText("修改密码").performScrollTo().performClick()
        compose.onNodeWithText("当前密码").assertExists()
        compose.onNodeWithText("新密码").assertExists()
        compose.onNodeWithText("确认新密码").assertExists()
        // 「保存」现在不止一个（省钱计划里也有），所以数一下有几个
        org.junit.Assert.assertTrue(
            compose.onAllNodesWithText("保存").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `我的页的提示是页内内联而不是弹窗`() {
        compose.setContent {
            EcardLedgerTheme {
                ProfileScreen(state = profileState().copy(error = "当前密码不正确"))
            }
        }
        compose.onNodeWithText("当前密码不正确").assertExists()
        compose.onNodeWithText("提示").assertDoesNotExist()
    }

    // ------------------------------------------------ 绑定中心

    private fun bindingState(loggedIn: Boolean = false) =
        accountState(entries()).copy(loggedIn = loggedIn, username = "8207260917", schoolName = "中南大学")

    @Test
    fun `绑定中心未绑校园卡时给出绑定入口并说明可以不绑`() {
        compose.setContent {
            EcardLedgerTheme { BindingCenterScreen(state = bindingState(loggedIn = false)) }
        }
        compose.onNodeWithText("每一项都可以不绑定", substring = true).assertExists()
        compose.onNodeWithText("添加高校").assertIsDisplayed()
        compose.onNodeWithText("解除绑定").assertDoesNotExist()
        compose.onNodeWithText("中南大学", substring = true).assertExists()
    }

    @Test
    fun `绑定中心已绑校园卡时显示学号与解绑入口`() {
        compose.setContent {
            EcardLedgerTheme { BindingCenterScreen(state = bindingState(loggedIn = true)) }
        }
        compose.onNodeWithText("已绑定", substring = true).assertExists()
        compose.onNodeWithText("8207260917", substring = true).assertExists()
        compose.onNodeWithText("解除绑定").assertIsDisplayed()
    }

    @Test
    fun `绑定中心解绑需要二次确认`() {
        compose.setContent {
            EcardLedgerTheme { BindingCenterScreen(state = bindingState(loggedIn = true)) }
        }
        compose.onNodeWithText("确认解除").assertDoesNotExist()
        compose.onNodeWithText("解除绑定").performClick()
        compose.onNodeWithText("确认解除").assertExists()
        compose.onNodeWithText("取消").assertExists()
    }

    @Test
    fun `绑定中心已开启的渠道才显示余额输入`() {
        compose.setContent {
            EcardLedgerTheme { BindingCenterScreen(state = bindingState()) }
        }
        // accountState 里微信是 enabled=true、支付宝 enabled=false
        compose.onNodeWithText("微信 余额（元）").assertExists()
        compose.onNodeWithText("支付宝 余额（元）").assertDoesNotExist()
    }

    // ------------------------------------------------ 首次权限引导

    private fun permissionState() = accountState(emptyList()).copy(
        notificationAccessGranted = false,
        batteryExempt = false,
        permissionsOnboarded = false,
    )

    @Test
    fun `权限引导页列出通知使用权并说明每一项为什么需要`() {
        compose.setContent {
            EcardLedgerTheme { PermissionsScreen(state = permissionState()) }
        }
        compose.onNodeWithText("开启必要的权限").assertIsDisplayed()
        compose.onNodeWithText("通知使用权").assertIsDisplayed()
        compose.onNodeWithText("忽略电池优化").assertIsDisplayed()
        compose.onNodeWithText("需要开启").assertExists()
        // 两项权限都没开，所以「去开启」出现两次
        compose.onAllNodesWithText("去开启").assertCountEquals(2)
        // 可跳过：手动记账不需要权限
        compose.onNodeWithText("先跳过").assertIsDisplayed()
        compose.onNodeWithText("不想用自动记账就直接跳过", substring = true).assertExists()
    }

    @Test
    fun `权限引导页已授权时显示完成而不是跳过`() {
        compose.setContent {
            EcardLedgerTheme {
                PermissionsScreen(
                    state = permissionState().copy(
                        notificationAccessGranted = true,
                        batteryExempt = true,
                    ),
                )
            }
        }
        compose.onNodeWithText("完成，开始记账").assertIsDisplayed()
        compose.onNodeWithText("先跳过").assertDoesNotExist()
    }


    // ------------------------------------------------ 通知来源白名单与诊断

    @Test
    fun `通知设置页显示发送方白名单与诊断开关`() {
        compose.setContent {
            EcardLedgerTheme {
                NotificationSetupScreen(
                    state = accountState(entries()).copy(
                        notifSenders = listOf("微信支付", "支付宝"),
                        notifDiagnostics = false,
                    ),
                    onBack = {},
                    onRefresh = {},
                    onOpenSystemSettings = {},
                    onSelfCheck = { _, _, _ -> },
                    onRebuildPairs = {},
                    onDismissNotice = {},
                )
            }
        }
        compose.onNodeWithText("支付通知的来源白名单").assertExists()
        compose.onNodeWithText("先按发送方筛一道", substring = true).assertExists()
        compose.onNodeWithText("微信支付").assertExists()
        compose.onNodeWithText("支付宝").assertExists()
        compose.onNodeWithText("通知诊断").assertExists()
        compose.onNodeWithText("记录收到的通知与判定结果").assertExists()
    }

    @Test
    fun `诊断记录里可以把没认出来的发送方一键加入白名单`() {
        var added: String? = null
        compose.setContent {
            EcardLedgerTheme {
                NotificationSetupScreen(
                    state = accountState(entries()).copy(
                        notifSenders = listOf("微信支付"),
                        notifDiagnostics = true,
                        notifLog = listOf(
                            NotificationLogEntry(
                                timeMillis = 1_790_000_000_000L,
                                packageName = "com.tencent.mm",
                                title = "XX收款",
                                text = "收款 ¥66.00",
                                verdict = "已忽略",
                                reason = "发送方不是支付来源，正文也没有支付措辞",
                            ),
                        ),
                    ),
                    onBack = {},
                    onRefresh = {},
                    onOpenSystemSettings = {},
                    onSelfCheck = { _, _, _ -> },
                    onRebuildPairs = {},
                    onAddSender = { added = it },
                    onDismissNotice = {},
                )
            }
        }
        compose.onNodeWithText("标题：XX收款").assertExists()
        compose.onNodeWithText("微信 · 已忽略").assertExists()
        compose.onNodeWithText("加入白名单", substring = true).performScrollTo().performClick()
        assertEquals("XX收款", added)
    }

    // ------------------------------------------------ 批量管理与待确认筛选

    @Test
    fun `有待确认记录时出现筛选芯片`() {
        val review = entries().first().copy(
            orderId = "REVIEW-1",
            kind = LedgerRepository.KIND_NEEDS_REVIEW,
            amountCents = 0,
        )
        compose.setContent {
            EcardLedgerTheme { TestHome(accountState(listOf(review) + entries())) }
        }
        compose.onNodeWithText("待确认 1").assertExists()
        compose.onNodeWithText("批量").assertExists()
    }

    @Test
    fun `批量模式可以全选并确认删除`() {
        var deleted: List<String>? = null
        compose.setContent {
            EcardLedgerTheme {
                HomeScreen(
                    state = accountState(entries()),
                    actions = HomeActions(onDeleteMany = { deleted = it }),
                )
            }
        }
        compose.onNodeWithText("批量").performClick()
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("删除 3 条").assertExists()
        compose.onNodeWithText("删除 3 条").performClick()

        // 二次确认：一次删几十条，误触代价太大
        compose.onNodeWithText("删除 3 条记录？").assertExists()
        compose.onNodeWithText("删除").performClick()

        assertEquals(3, deleted?.size)
    }

    @Test
    fun `批量删除可以取消`() {
        var deleted: List<String>? = null
        compose.setContent {
            EcardLedgerTheme {
                HomeScreen(
                    state = accountState(entries()),
                    actions = HomeActions(onDeleteMany = { deleted = it }),
                )
            }
        }
        compose.onNodeWithText("批量").performClick()
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("删除 3 条").performClick()
        compose.onNodeWithText("取消").performClick()

        assertEquals("取消之后不该删任何东西", null, deleted)
    }
}
